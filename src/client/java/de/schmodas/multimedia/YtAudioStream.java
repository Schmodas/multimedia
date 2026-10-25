package de.schmodas.multimedia;

import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Spielt ein Video oder eine Playlist als Audio-Stream ab:
 * yt-dlp lädt das Audio, ffmpeg macht daraus rohes Stereo-PCM. Der StreamHub macht daraus pro
 * Jukebox Mono (links, rechts oder gemischt), damit OpenAL den Ton räumlich platzieren kann.
 * Bei Playlists laufen die Videos nahtlos hintereinander in denselben Stream.
 */
public final class YtAudioStream implements AudioStream {
	private static final int SAMPLE_RATE = 48000;
	private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
	/** Bytes pro Stereo-Frame (2 Kanäle x 16 Bit). */
	static final int FRAME = 4;
	private static final AudioFormat MONO = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
	/** 0,25 Sekunden pro Block. */
	private static final int CHUNK_BYTES = SAMPLE_RATE * FRAME / 4;
	/** Maximal ~20 Sekunden im Voraus puffern. */
	private static final int QUEUE_CHUNKS = 80;
	private static final int BYTES_PER_SECOND = SAMPLE_RATE * FRAME;
	/** Ab diesem Sprung (Sekunden) wird direkt im Video gesprungen statt bis dahin herunterzuladen. */
	private static final double DIRECT_SEEK_FROM = 90;
	/** Vor dem Start ~5 Sekunden puffern (Minecraft holt sich beim Start gleich 4 Sekunden). */
	private static final int PREBUFFER_CHUNKS = 20;
	/** 0,1 Sekunden Stille, falls das Netz kurz hängt. */
	private static final int SILENCE_BYTES = SAMPLE_RATE * FRAME / 10;
	private static final Object EOF = new Object();

	/** Wird in die Warteschlange gelegt, wenn ein neuer Titel beginnt. */
	public record TrackStart(int pass, int index, String title) {
	}

	private static final Set<YtAudioStream> OPEN = ConcurrentHashMap.newKeySet();

	private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE_CHUNKS);
	private final List<Process> processes = new CopyOnWriteArrayList<>();
	private final StringBuilder errors = new StringBuilder();
	private final BiConsumer<TrackStart, Long> onTrack;
	/** Größen der letzten gelesenen Puffer (Minecraft hält bis zu 4 davon in der Warteschlange). */
	private final int[] lastReads = new int[3];
	private int lastReadIdx;
	private volatile boolean closed;
	private volatile boolean ended;
	private volatile long bytesProduced;
	private byte[] current;
	private int currentPos;
	/** Radio: Zeitpunkt, für den die Startposition berechnet wurde (0 = noch nicht). */
	private volatile long radioSyncNanos;
	/** Radio: Uhrzeit (Sekunden), für die die Startposition berechnet wurde. */
	volatile double radioSyncClock;
	private boolean driftHandled;
	/** Für den StreamHub: Wenn nichts da ist, leeren Puffer statt Stille liefern (der Hub wartet dann). */
	volatile boolean waitForData;

	private YtAudioStream(BiConsumer<TrackStart, Long> onTrack) {
		this.onTrack = onTrack;
	}

	/**
	 * Startet den Stream im Hintergrund. Das Future ist fertig, sobald genug vorgepuffert ist.
	 * Bei Fehlern wird ein leerer Stream geliefert (die Disc bleibt dann still) und onError aufgerufen.
	 * onTrack bekommt bei Playlists den neuen Titel und nach wie vielen ms er zu hören ist
	 * (Minecraft puffert ein paar Sekunden im Voraus).
	 */
	public static CompletableFuture<AudioStream> open(Playback playback, Consumer<String> status, Consumer<String> onError,
			BiConsumer<TrackStart, Long> onTrack, CompletableFuture<Void> cancelled) {
		Source source = playback.source;
		YtAudioStream stream = new YtAudioStream(onTrack);
		OPEN.add(stream);
		cancelled.thenRun(stream::closeQuietly);

		return Tools.ensure(status).thenApplyAsync(paths -> {
			try {
				runDaemon(() -> stream.produce(paths, playback));
				stream.awaitPrebuffer();
				if (stream.closed) {
					return (AudioStream) EmptyStream.INSTANCE;
				}
				if (stream.ended && stream.bytesProduced < CHUNK_BYTES) {
					onError.accept(stream.errorText());
					stream.closeQuietly();
					return (AudioStream) EmptyStream.INSTANCE;
				}
				MultiMediaClient.LOGGER.info("Stream gestartet: {}", source.spec());
				return (AudioStream) stream;
			} catch (Exception e) {
				onError.accept(e.getMessage() != null ? e.getMessage() : e.toString());
				stream.closeQuietly();
				return (AudioStream) EmptyStream.INSTANCE;
			}
		}, YtAudioStream::runDaemon).exceptionally(e -> {
			onError.accept(e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
			stream.closeQuietly();
			return EmptyStream.INSTANCE;
		});
	}

	public static void closeAll() {
		for (YtAudioStream s : OPEN) {
			s.closeQuietly();
		}
	}

	// ---------------------------------------------------------------- Erzeuger

	private void produce(Tools.Paths paths, Playback playback) {
		Source source = playback.source;
		try {
			if (playback.tracks() == null) {
				playback.setTracks(source.playlist() || playback.isRadio()
						? resolveTracks(paths, source.url())
						: List.of(new Playback.Track(source.url(), null, 0)));
			}
			if (playback.tracks().isEmpty()) {
				if (errors.length() == 0) {
					addError(source.playlist() ? T.t("Playlist ist leer oder privat") : T.t("Video nicht gefunden"));
				}
				return;
			}
			int pass = playback.startPass();
			int index = playback.startIndex();
			double offset = 0;
			if (playback.isRadio()) {
				double clockNow = playback.radioNow();
				Playback.Position pos = playback.radioPosition(clockNow);
				radioSyncClock = clockNow;
				radioSyncNanos = System.nanoTime();
				if (pos != null) {
					pass = pos.pass();
					index = pos.index();
					offset = pos.offset();
				} else if (source.playlist()) {
					offset = Math.max(0, clockNow); // Längen unbekannt: so lange läuft dieser Titel schon (gilt für den ersten)
				}
				MultiMediaClient.LOGGER.info("Radio: Titel {} ab {} s", index + 1, (int) offset);
			}
			boolean loop = source.loop() || playback.isRadio();
			while (!closed) {
				List<Playback.Track> order = playback.order(pass);
				long before = bytesProduced;
				for (int i = index; i < order.size() && !closed; i++) {
					Playback.Track t = order.get(i);
					put(new TrackStart(pass, i, t.title()));
					playOne(paths, t.url(), offset);
					offset = 0;
				}
				if (!loop || bytesProduced == before) {
					return; // fertig, oder ganzer Durchlauf ohne Ton -> nicht endlos weiterprobieren
				}
				pass++;
				index = 0;
			}
		} catch (Exception e) {
			addError(e.getMessage() != null ? e.getMessage() : e.toString());
		} finally {
			ended = true;
			queue.offer(EOF);
		}
	}

	/** Lädt Titel, Namen und Längen (Playlist oder einzelnes Video) – geteilt mit dem Bild, siehe Tools.tracks. */
	private List<Playback.Track> resolveTracks(Tools.Paths paths, String url) throws IOException, InterruptedException {
		requireSafe(url);
		List<Playback.Track> tracks = Tools.tracks(paths, url, this::addError);
		MultiMediaClient.LOGGER.info("{} Titel geladen", tracks.size());
		return tracks;
	}

	/**
	 * Spielt ein einzelnes Video ab Sekunde offset in die Warteschlange und kehrt zurück, wenn es zu Ende ist.
	 * Große Sprünge versucht es direkt (ffmpeg springt im Video), sonst wird bis zur Stelle mitgelesen.
	 */
	private void playOne(Tools.Paths paths, String url, double offset) throws IOException, InterruptedException {
		requireSafe(url);
		if (offset >= DIRECT_SEEK_FROM) {
			long before = bytesProduced;
			try {
				playDirect(paths, url, offset);
			} catch (IOException e) {
				MultiMediaClient.LOGGER.warn("Direkter Sprung fehlgeschlagen: {}", e.getMessage());
			}
			if (bytesProduced > before || closed) {
				return;
			}
			MultiMediaClient.LOGGER.info("Direkter Sprung ging nicht, lese bis zur Stelle mit");
		}
		Process ytDlp = start(new ProcessBuilder(paths.ytDlp(),
				"-f", "bestaudio/best", "--no-playlist", "--quiet", "--no-warnings", "--no-progress",
				"--js-runtimes", "deno:" + paths.deno(),
				"-o", "-", url));
		List<String> ff = new ArrayList<>(List.of(paths.ffmpeg(), "-hide_banner", "-loglevel", "error", "-i", "pipe:0"));
		if (offset > 0) {
			ff.addAll(List.of("-ss", String.format(java.util.Locale.ROOT, "%.2f", offset)));
		}
		ff.addAll(List.of("-vn", "-ac", "2", "-ar", String.valueOf(SAMPLE_RATE), "-f", "s16le", "pipe:1"));
		Process ffmpeg = start(new ProcessBuilder(ff));
		collectErrors(ytDlp.getErrorStream(), "yt-dlp");
		collectErrors(ffmpeg.getErrorStream(), "ffmpeg");

		// yt-dlp -> ffmpeg
		runDaemon(() -> {
			try (InputStream in = ytDlp.getInputStream(); OutputStream out = ffmpeg.getOutputStream()) {
				in.transferTo(out);
			} catch (IOException ignored) {
				// passiert beim Stoppen
			}
		});
		try {
			pump(ffmpeg);
		} finally {
			ytDlp.destroy();
			processes.remove(ytDlp);
		}
	}

	/** Holt die direkte Audio-Adresse und lässt ffmpeg dort an die Stelle springen. */
	private void playDirect(Tools.Paths paths, String url, double offset) throws IOException, InterruptedException {
		Tools.Direct direct = Tools.resolveDirect(paths, url, "bestaudio/best", this::addError);
		if (direct == null) {
			throw new IOException(T.t("keine direkte Adresse"));
		}
		List<String> cmd = new ArrayList<>(List.of(paths.ffmpeg(), "-hide_banner", "-loglevel", "error"));
		cmd.addAll(direct.inputArgs()); // Kopfzeilen, die YouTube erwartet
		cmd.addAll(List.of("-ss", String.format(java.util.Locale.ROOT, "%.2f", offset), "-i", direct.url(),
				"-vn", "-ac", "2", "-ar", String.valueOf(SAMPLE_RATE), "-f", "s16le", "pipe:1"));
		Process ffmpeg = start(new ProcessBuilder(cmd));
		collectErrors(ffmpeg.getErrorStream(), "ffmpeg");
		pump(ffmpeg);
	}

	/** ffmpeg-Ausgabe -> Warteschlange, bis das Video zu Ende ist. */
	private void pump(Process ffmpeg) throws InterruptedException {
		try (InputStream in = ffmpeg.getInputStream()) {
			while (!closed) {
				byte[] chunk = in.readNBytes(CHUNK_BYTES);
				if (chunk.length == 0) {
					break;
				}
				put(chunk);
				bytesProduced += chunk.length;
				if (chunk.length < CHUNK_BYTES) {
					break;
				}
			}
		} catch (IOException ignored) {
		} finally {
			ffmpeg.destroy();
			processes.remove(ffmpeg);
		}
	}

	/** Nur echte YouTube-Adressen an yt-dlp geben (siehe Source). */
	private static void requireSafe(String url) throws IOException {
		if (!Source.isSafe(url)) {
			throw new IOException(T.t("Nur YouTube-Links werden abgespielt"));
		}
	}

	private Process start(ProcessBuilder pb) throws IOException {
		if (closed) {
			throw new IOException("gestoppt");
		}
		Process p = pb.start();
		processes.add(p);
		if (closed) {
			p.destroyForcibly();
		}
		return p;
	}

	long syncNanos() {
		return radioSyncNanos;
	}

	private void put(Object item) throws InterruptedException {
		while (!closed && !queue.offer(item, 200, TimeUnit.MILLISECONDS)) {
			// Puffer voll (oder Spiel pausiert) -> warten
		}
	}

	private void awaitPrebuffer() throws InterruptedException {
		long deadline = System.currentTimeMillis() + 60_000;
		while (!closed && !ended && queue.size() < prebufferTarget()) {
			if (System.currentTimeMillis() > deadline) {
				throw new IllegalStateException(T.t("Zeitüberschreitung beim Laden des Streams"));
			}
			Thread.sleep(50);
		}
	}

	/** Im Radio-Modus muss zusätzlich die Ladezeit vorgepuffert werden, die beim Start übersprungen wird. */
	private int prebufferTarget() {
		long sync = radioSyncNanos;
		if (sync == 0) {
			return PREBUFFER_CHUNKS;
		}
		double waited = (System.nanoTime() - sync) / 1e9;
		return Math.min(QUEUE_CHUNKS - 4, PREBUFFER_CHUNKS + (int) Math.ceil(waited * 4) + 2);
	}

	private void collectErrors(InputStream in, String name) {
		runDaemon(() -> {
			String text = Tools.readQuiet(in).trim();
			if (!text.isEmpty()) {
				MultiMediaClient.LOGGER.warn("[{}] {}", name, text);
				addError(text);
			}
		});
	}

	private void addError(String text) {
		synchronized (errors) {
			errors.append(text).append('\n');
		}
	}

	private String errorText() {
		synchronized (errors) {
			return friendly(errors.toString());
		}
	}

	/** yt-dlp-Fehler in kurze, verständliche Sätze (für Chat und Anzeige über der Jukebox). */
	static String friendly(String raw) {
		String all = raw.toLowerCase(java.util.Locale.ROOT);
		if (all.contains("confirm you") && all.contains("bot")) {
			return T.t("YouTube blockt gerade (Bot-Schutz) – später nochmal");
		}
		if (all.contains("confirm your age") || all.contains("age-restricted")) {
			return T.t("Video ab 18 – geht leider nicht");
		}
		if (all.contains("not available in your country") || all.contains("geo restrict") || all.contains("geo-restrict")) {
			return T.t("Video ist in Deutschland gesperrt");
		}
		if (all.contains("private video") || all.contains("is private")) {
			return T.t("Video ist privat");
		}
		if (all.contains("video unavailable") || all.contains("has been removed") || all.contains("does not exist")) {
			return T.t("Video gibt es nicht (mehr)");
		}
		if (all.contains("no space left") || all.contains("failed to extract entry") || all.contains("[pyi-")
				|| all.contains("not enough space") || all.contains("disk full")) {
			return T.t("Festplatte voll – yt-dlp kann nicht starten (Platz auf C: freimachen)");
		}
		if (all.contains("live event will begin") || all.contains("premieres in")) {
			return T.t("Livestream hat noch nicht angefangen");
		}
		return Tools.lastLine(raw.trim(), T.t("Kein Audio erhalten"));
	}

	// ---------------------------------------------------------------- Verbraucher (Minecraft)

	@Override
	public AudioFormat getFormat() {
		return FORMAT;
	}

	/**
	 * Wird von Minecraft (Sound-Thread) aufgerufen, bis zu 1 Sekunde Audio pro Aufruf.
	 * Kommen die Daten zu langsam, wird kurz Stille geliefert – sonst würde OpenAL
	 * den Song bei einer Lücke einfach beenden.
	 */
	@Override
	public ByteBuffer read(int expectedSize) {
		int size = Math.max(expectedSize - (expectedSize % FRAME), SILENCE_BYTES);
		ByteBuffer out = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(60);
		long skip = 0;
		if (!driftHandled) {
			driftHandled = true;
			long sync = radioSyncNanos;
			if (sync != 0) {
				// Radio: die Zeit, die das Laden gedauert hat, überspringen -> alle hören dieselbe Stelle
				skip = (long) ((System.nanoTime() - sync) / 1e9 * BYTES_PER_SECOND);
				skip -= skip % FRAME;
				MultiMediaClient.LOGGER.info("Radio: überspringe {} s Ladezeit", String.format(java.util.Locale.ROOT, "%.1f", skip / (double) BYTES_PER_SECOND));
			}
		}
		while (skip > 0 && !closed) {
			if (current == null || currentPos >= current.length) {
				Object next = queue.poll();
				if (next == null || next == EOF) {
					if (next == EOF) {
						queue.offer(EOF);
					}
					break; // nicht genug da -> Rest nicht überspringen
				}
				if (next instanceof TrackStart t) {
					if (onTrack != null) {
						onTrack.accept(t, 0L);
					}
					continue;
				}
				current = (byte[]) next;
				currentPos = 0;
			}
			int n = (int) Math.min(skip, current.length - currentPos);
			currentPos += n;
			skip -= n;
		}

		while (out.hasRemaining() && !closed) {
			if (current == null || currentPos >= current.length) {
				Object next;
				try {
					long wait = deadline - System.nanoTime();
					next = wait > 0 ? queue.poll(wait, TimeUnit.NANOSECONDS) : queue.poll();
				} catch (InterruptedException e) {
					break;
				}
				if (next == null) {
					if (ended && queue.isEmpty()) {
						return finish(out, true);
					}
					break; // gerade nichts da
				}
				if (next == EOF) {
					queue.offer(EOF);
					current = null;
					return finish(out, true);
				}
				if (next instanceof TrackStart t) {
					if (onTrack != null) {
						long aheadBytes = out.position();
						for (int r : lastReads) {
							aheadBytes += r;
						}
						long delayMs = aheadBytes * 1000L / BYTES_PER_SECOND;
						MultiMediaClient.LOGGER.debug("Nächster Titel in {} ms: {}", delayMs, t.title());
						onTrack.accept(t, delayMs);
					}
					continue;
				}
				current = (byte[]) next;
				currentPos = 0;
			}
			int n = Math.min(out.remaining(), current.length - currentPos);
			out.put(current, currentPos, n);
			currentPos += n;
		}
		return finish(out, closed);
	}

	private ByteBuffer finish(ByteBuffer out, boolean atEnd) {
		if (waitForData && out.position() == 0 && !atEnd) {
			out.limit(0);
			return out; // nichts da – keine Stille einschieben, sonst rutscht der Stream aus dem Takt
		}
		ByteBuffer result = finishBuffer(out, atEnd);
		lastReads[lastReadIdx] = result == null ? 0 : result.limit();
		lastReadIdx = (lastReadIdx + 1) % lastReads.length;
		return result;
	}

	private static ByteBuffer finishBuffer(ByteBuffer out, boolean atEnd) {
		if (out.position() == 0) {
			if (atEnd) {
				return null; // nichts mehr -> Song endet
			}
			// Netz zu langsam: kurze Stille, damit der Song weiterläuft
			out.limit(Math.min(out.capacity(), SILENCE_BYTES));
			out.position(out.limit());
		}
		// Nur so viel abgeben wie da ist (kein langes Auffüllen mit Stille)
		out.limit(out.position() - (out.position() % FRAME));
		out.position(0);
		return out;
	}

	@Override
	public void close() {
		closeQuietly();
	}

	public void closeQuietly() {
		if (closed) {
			return;
		}
		closed = true;
		OPEN.remove(this);
		for (Process p : processes) {
			p.descendants().forEach(ProcessHandle::destroyForcibly);
			p.destroyForcibly();
		}
		queue.clear();
		queue.offer(EOF);
	}

	private static void runDaemon(Runnable r) {
		Thread t = new Thread(r, "MultiMedia-Worker");
		t.setDaemon(true);
		t.start();
	}

	/** Stream ohne Inhalt: die Disc bleibt still und endet sofort. */
	static final class EmptyStream implements AudioStream {
		static final EmptyStream INSTANCE = new EmptyStream();

		@Override
		public AudioFormat getFormat() {
			return MONO;
		}

		@Override
		public ByteBuffer read(int expectedSize) {
			return null;
		}

		@Override
		public void close() {
		}
	}
}
