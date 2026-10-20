package de.schmodas.multimedia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Gemeinsamer Stream für alle Radio-Jukeboxen mit demselben Link: Das Audio wird nur einmal
 * geladen und dekodiert und an alle Jukeboxen verteilt. Jede Jukebox liest an der Stelle, die
 * gerade "dran" ist (gemeinsame Uhr) – dadurch laufen alle auf Millisekunden genau gleich.
 */
public final class StreamHub {
	private static final int SAMPLE_RATE = 48000;
	/** Was jede Jukebox bekommt: Mono (für räumlichen Klang). */
	private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
	/** Gespeichert wird Stereo: Bytes pro Sekunde und pro Frame. */
	private static final int FRAME = YtAudioStream.FRAME;
	private static final int BPS = SAMPLE_RATE * FRAME;
	/** Bytes pro Sekunde, die eine Jukebox liest (Mono). */
	private static final int MONO_BPS = SAMPLE_RATE * 2;
	private static final int READ_BYTES = BPS / 4;
	/** So weit darf der Hub der Uhr vorauslaufen. */
	private static final long AHEAD_BYTES = 20L * BPS;
	/** So viel muss da sein, bevor eine neue Jukebox startet (Minecraft holt sich gleich 4 s). */
	private static final long START_BUFFER_BYTES = (long) (4.5 * BPS);
	/** So viel Vergangenheit wird aufgehoben. */
	private static final long KEEP_BYTES = 30L * BPS;
	/** Stille bei Hängern: 0,25 s, damit Minecrafts Warteschlange (4 Puffer) nicht zu kurz wird. */
	private static final int SILENCE_FRAMES = SAMPLE_RATE / 4;
	/** Einblenden beim Start und nach einem Hänger (10 ms), sonst knackt der Einsatz. */
	private static final int FADE_FRAMES = SAMPLE_RATE / 100;
	private static final long CLOSE_DELAY_MS = 3000;
	/** Live (Bildschirm eines Spielers): Ton wird so viel nach Ankunft gespielt (siehe LiveFeed.AUDIO_DELAY). */
	private static final long LIVE_LEAD_BYTES = (long) (LiveFeed.AUDIO_DELAY * SAMPLE_RATE) * FRAME;
	/** Weniger Vorlauf als das: Lücke einfügen (sonst knackt es); mehr als das: Daten verwerfen (sonst wächst die Verzögerung). */
	private static final long LIVE_MIN_BYTES = (long) (0.95 * SAMPLE_RATE) * FRAME;
	private static final long LIVE_MAX_BYTES = (long) (1.6 * SAMPLE_RATE) * FRAME;
	/** Live: kleine Häppchen an Minecraft (4 davon liegen in der Warteschlange = 0,8 s). */
	private static final int LIVE_READ_FRAMES = SAMPLE_RATE / 5;

	private static final Map<String, StreamHub> HUBS = new HashMap<>();

	public static final int MIX = 0;
	public static final int LEFT = 1;
	public static final int RIGHT = 2;

	// ---- Analyse für die Noten: Lautstärke in 10-ms-Fenstern (gesamt und nur Bass)
	private static final int WINDOW_FRAMES = SAMPLE_RATE / 100;
	private static final int HISTORY = 4096;
	/** Bass-Filter (ca. 150 Hz) für die Beat-Erkennung. */
	private static final double BASS_ALPHA = 1 - Math.exp(-2 * Math.PI * 150 / SAMPLE_RATE);

	/** Was in diesem Tick zu hören war: Beat (Stärke, 0 = keiner) und wie hell der Klang ist (0 = Bass, 1 = Höhen). */
	public record Beat(float strength, float brightness, float loudness) {
		static final Beat NONE = new Beat(0, 0, 0);
	}

	private record Chunk(long start, byte[] data) {
	}

	private record Marker(long pos, YtAudioStream.TrackStart track) {
	}

	private final String key;
	private final boolean live;
	private final List<Chunk> chunks = new ArrayList<>();
	private final List<Marker> markers = new ArrayList<>();
	private final List<BiConsumer<YtAudioStream.TrackStart, Long>> listeners = new ArrayList<>();
	private final CompletableFuture<Void> innerCancel = new CompletableFuture<>();
	private final CompletableFuture<Boolean> ready = new CompletableFuture<>();
	private volatile String error;
	private volatile long produced;
	private volatile boolean ended;
	private volatile boolean closed;
	/** Uhrzeit (PauseClock), zu der Byte 0 zu hören war. */
	private volatile long originNanos;
	/** Radio-Uhr (Sekunden Spielzeit), die zu Byte 0 gehört – damit Leinwände sich nach dem Ton richten können. */
	private volatile double originClock = Double.NaN;
	private int taps;
	private long emptySince;
	/** Für das Log: wie viel Stille bei Hängern eingefügt wurde. */
	private long stallFrames;
	private long lastStallLog;

	private final float[] fullRms = new float[HISTORY];
	private final float[] bassRms = new float[HISTORY];
	/** Anzahl fertiger Fenster. */
	private volatile long windows;
	private double bassState;
	private double sumFull;
	private double sumBass;
	private int windowFill;
	private long lastWindowChecked = -1;
	private long lastBeatWindow = -1000;
	private Beat beat = Beat.NONE;

	private StreamHub(String key) {
		this.key = key;
		this.live = key.startsWith("live:");
	}

	// ------------------------------------------------------------------ Verwaltung

	/** Öffnet einen Zugang ("Tap") für eine Jukebox. Gleicher Link = gleicher Hub. */
	public static synchronized CompletableFuture<AudioStream> openTap(Playback playback, int stereo, Consumer<String> status,
			Consumer<String> onError, BiConsumer<YtAudioStream.TrackStart, Long> onTrack, CompletableFuture<Void> cancelled) {
		String key = playback.source.spec();
		StreamHub hub = HUBS.get(key);
		if (hub == null || hub.closed) {
			hub = new StreamHub(key);
			HUBS.put(key, hub);
			hub.start(playback, status);
		}
		StreamHub h = hub;
		h.taps++;
		Tap tap = h.new Tap(onTrack, stereo);
		cancelled.thenRun(tap::close);
		return h.ready.thenApplyAsync(ok -> {
			if (!ok || h.closed) {
				onError.accept(h.error != null ? h.error : T.t("Kein Audio erhalten"));
				tap.close();
				return (AudioStream) YtAudioStream.EmptyStream.INSTANCE;
			}
			try {
				tap.awaitStartBuffer();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return (AudioStream) tap;
		}, StreamHub::runDaemon);
	}

	/** Regelmäßig aufrufen: schließt Hubs, die keiner mehr braucht. */
	public static synchronized void tick() {
		long now = System.currentTimeMillis();
		HUBS.values().forEach(StreamHub::detectBeat);
		HUBS.values().removeIf(hub -> {
			if (hub.closed) {
				return true;
			}
			if (hub.taps > 0) {
				hub.emptySince = 0;
				return false;
			}
			if (hub.emptySince == 0) {
				hub.emptySince = now;
			}
			if (now - hub.emptySince > CLOSE_DELAY_MS) {
				hub.close();
				return true;
			}
			return false;
		});
	}

	/** Wo der Ton für diesen Link gerade ist (Radio-Uhr in Sekunden), oder NaN, wenn kein Ton läuft. */
	public static synchronized double clock(String spec) {
		StreamHub hub = HUBS.get(spec);
		if (hub == null || hub.closed || hub.originNanos == 0 || Double.isNaN(hub.originClock)) {
			return Double.NaN;
		}
		return hub.originClock + (PauseClock.now() - hub.originNanos) / 1e9;
	}

	/** Welcher Titel gerade zu hören ist und seit wie vielen Sekunden. */
	public record TrackAt(int pass, int index, double seconds) {
	}

	/** Titel, der für diesen Link gerade zu hören ist (für Leinwände: Bild folgt dem Ton), oder null. */
	public static synchronized TrackAt track(String spec) {
		StreamHub hub = HUBS.get(spec);
		if (hub == null || hub.closed || hub.originNanos == 0) {
			return null;
		}
		long now = hub.nowBytes();
		Marker cur = null;
		synchronized (hub) {
			for (Marker m : hub.markers) {
				if (m.pos <= now) {
					cur = m;
				}
			}
		}
		return cur == null ? null : new TrackAt(cur.track.pass(), cur.track.index(), (now - cur.pos) / (double) BPS);
	}

	/** Beat dieses Ticks für einen Link (NONE, wenn nichts läuft). */
	public static synchronized Beat beat(String spec) {
		StreamHub hub = HUBS.get(spec);
		return hub == null || hub.closed ? Beat.NONE : hub.beat;
	}

	/**
	 * Schaut sich die Fenster an, die seit dem letzten Tick hörbar geworden sind:
	 * Ein Beat ist, wenn der Bass (oder bei Musik ohne Bass die Gesamtlautstärke) plötzlich
	 * deutlich über dem Schnitt der letzten Sekunde liegt.
	 */
	private void detectBeat() {
		beat = Beat.NONE;
		long done = windows;
		long nowWin = nowBytes() / FRAME / WINDOW_FRAMES;
		long upTo = Math.min(done, nowWin);
		if (lastWindowChecked < 0) {
			lastWindowChecked = upTo;
			return;
		}
		float best = 0;
		float loud = 0;
		float bright = 0;
		for (long w = Math.max(lastWindowChecked + 1, upTo - 30); w <= upTo - 1; w++) {
			if (w < 100 || done - w >= HISTORY - 1) {
				continue;
			}
			double meanFull = 0;
			double meanBass = 0;
			for (long k = w - 100; k < w; k++) {
				meanFull += fullRms[(int) (k % HISTORY)];
				meanBass += bassRms[(int) (k % HISTORY)];
			}
			meanFull /= 100;
			meanBass /= 100;
			float f = fullRms[(int) (w % HISTORY)];
			float b = bassRms[(int) (w % HISTORY)];
			loud = Math.max(loud, f);
			if (f < 600 || w - lastBeatWindow < 22) {
				continue; // zu leise oder gerade erst ein Beat
			}
			double score = Math.max(b / Math.max(meanBass, 150) / 1.45, f / Math.max(meanFull, 300) / 1.6);
			if (score >= 1 && score > best) {
				best = (float) score;
				bright = (float) Math.max(0, Math.min(1, 1 - b / Math.max(1, f)));
				lastBeatWindow = w;
			}
		}
		// ruhige Musik ohne klare Beats: trotzdem ab und zu eine Note
		if (best == 0 && loud >= 600 && upTo - lastBeatWindow > 120) {
			float f = fullRms[(int) ((upTo - 1) % HISTORY)];
			float b = bassRms[(int) ((upTo - 1) % HISTORY)];
			best = 0.5F;
			bright = (float) Math.max(0, Math.min(1, 1 - b / Math.max(1, f)));
			lastBeatWindow = upTo;
		}
		lastWindowChecked = upTo - 1;
		beat = new Beat(best, bright, loud / 32768F);
	}

	public static synchronized void closeAll() {
		HUBS.values().forEach(StreamHub::close);
		HUBS.clear();
	}

	private static synchronized void release(StreamHub hub) {
		hub.taps--;
	}

	// ------------------------------------------------------------------ Erzeuger

	private void start(Playback playback, Consumer<String> status) {
		if (live) {
			startLive(playback.source.liveName());
			return;
		}
		YtAudioStream.open(playback, status, msg -> error = msg,
				(track, delay) -> {
					synchronized (this) {
						markers.add(new Marker(produced, track));
					}
				}, innerCancel).thenAccept(inner -> {
			if (inner instanceof YtAudioStream.EmptyStream || closed) {
				ready.complete(false);
				return;
			}
			runDaemon(() -> pull(inner));
		});
	}

	/** Live-Ton eines Spielers: kommt aus der gemeinsamen Verbindung (LiveFeed). */
	private void startLive(String player) {
		Settings.TvSource target = LiveFeed.targetFor(player, net.minecraft.client.Minecraft.getInstance().getUser().getName());
		if (target == null) {
			error = T.t("Keine Einladung von ") + player;
			closed = true;
			ready.complete(false);
			return;
		}
		LiveFeed feed = LiveFeed.acquire(player, target);
		liveFeed = feed;
		java.util.concurrent.BlockingQueue<byte[]> queue = feed.listenAudio();
		originNanos = PauseClock.now();
		ready.complete(true);
		runDaemon(() -> {
			try {
				while (!closed) {
					byte[] data = queue.poll(200, TimeUnit.MILLISECONDS);
					if (data != null) {
						appendLive(data);
					}
				}
			} catch (InterruptedException ignored) {
			} finally {
				feed.stopListening(queue);
				feed.release();
				ended = true;
			}
		});
	}

	/** Live: an der richtigen Stelle einsortieren – Vorlauf zwischen LIVE_MIN und LIVE_MAX halten. */
	private synchronized void appendLive(byte[] data) {
		long now = nowBytes();
		long lead = produced - now;
		if (lead < LIVE_MIN_BYTES) {
			liveGaps++;
			produced = now + LIVE_LEAD_BYTES; // Lücke (Stille), damit wieder genug Vorlauf da ist
			produced -= produced % FRAME;
		} else if (lead > LIVE_MAX_BYTES) {
			liveDropped++;
			liveReport();
			return; // zu viel Vorlauf (Daten kamen geballt) – Stück weglassen
		}
		LiveFeed feed = liveFeed;
		if (feed != null) {
			feed.audioLead((produced - now) / (double) BPS); // so viel später ist dieser Ton zu hören -> Bild genauso verzögern
		}
		liveReport();
		append(ByteBuffer.wrap(data));
	}

	private volatile LiveFeed liveFeed;
	private int liveGaps;
	private int liveDropped;
	private long liveLastReport;

	/** Für Tests mit Freunden: Lücken/verworfene Stücke höchstens alle 30 s ins Log. */
	private void liveReport() {
		long t = System.currentTimeMillis();
		if ((liveGaps > 1 || liveDropped > 0) && t - liveLastReport > 30_000) {
			MultiMediaClient.LOGGER.info("Live-Ton {}: {} Lücke(n), {} Stück(e) verworfen (Internet ruckelt?)", key, liveGaps - 1, liveDropped);
			liveLastReport = t;
			liveGaps = 1;
			liveDropped = 0;
		}
	}

	/** Liest den inneren Stream und hängt das Audio an den Verlauf an – so schnell wie nötig, nie zu weit voraus. */
	private void pull(AudioStream inner) {
		if (inner instanceof YtAudioStream yt) {
			yt.waitForData = true;
		}
		try {
			ByteBuffer first = inner.read(READ_BYTES); // beim ersten Lesen gleicht der innere Stream die Ladezeit aus
			originNanos = PauseClock.now();
			if (inner instanceof YtAudioStream yt && yt.syncNanos() != 0) {
				originClock = yt.radioSyncClock + (System.nanoTime() - yt.syncNanos()) / 1e9;
			}
			append(first);
			ready.complete(true);
			while (!closed) {
				if (produced - nowBytes() > AHEAD_BYTES) {
					Thread.sleep(50);
					continue;
				}
				ByteBuffer buf = inner.read(READ_BYTES);
				if (buf == null) {
					break;
				}
				append(buf);
			}
		} catch (Exception e) {
			error = e.getMessage();
		} finally {
			ended = true;
			ready.complete(false);
			try {
				inner.close();
			} catch (Exception ignored) {
			}
		}
	}

	private synchronized void append(ByteBuffer buf) {
		if (buf == null || !buf.hasRemaining()) {
			return;
		}
		byte[] data = new byte[buf.remaining()];
		buf.get(data);
		analyse(data);
		chunks.add(new Chunk(produced, data));
		produced += data.length;
		// alte Daten wegwerfen
		long keepFrom = nowBytes() - KEEP_BYTES;
		while (chunks.size() > 1 && chunks.get(0).start + chunks.get(0).data.length < keepFrom) {
			chunks.remove(0);
		}
		// alte Titelmarken weg – bis auf die letzte davor (sonst weiß man bei langen Titeln nicht mehr, welcher läuft)
		Marker keep = null;
		for (Marker m : markers) {
			if (m.pos < keepFrom) {
				keep = m;
			}
		}
		Marker last = keep;
		markers.removeIf(m -> m.pos < keepFrom && m != last);
	}

	/** Lautstärke (gesamt und Bass) in 10-ms-Fenstern merken. */
	private void analyse(byte[] data) {
		for (int i = 0; i + 3 < data.length; i += FRAME) {
			int l = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
			int r = (short) ((data[i + 2] & 0xFF) | (data[i + 3] << 8));
			double m = (l + r) * 0.5;
			bassState += BASS_ALPHA * (m - bassState);
			sumFull += m * m;
			sumBass += bassState * bassState;
			if (++windowFill == WINDOW_FRAMES) {
				int idx = (int) (windows % HISTORY);
				fullRms[idx] = (float) Math.sqrt(sumFull / WINDOW_FRAMES);
				bassRms[idx] = (float) Math.sqrt(sumBass / WINDOW_FRAMES);
				sumFull = 0;
				sumBass = 0;
				windowFill = 0;
				windows++;
			}
		}
	}

	/** Welches Byte gerade "dran" ist (für alle Jukeboxen gleich). */
	private long nowBytes() {
		long origin = originNanos;
		if (origin == 0) {
			return 0;
		}
		long b = (long) ((PauseClock.now() - origin) / 1e9 * BPS);
		return b - (b % FRAME);
	}

	/** Ein Hänger (Daten kommen nicht schnell genug): ins Log, aber höchstens alle 10 Sekunden. */
	private void stalled() {
		stallFrames += SILENCE_FRAMES;
		long now = System.currentTimeMillis();
		if (now - lastStallLog > 10_000) {
			MultiMediaClient.LOGGER.warn("Stream kommt nicht hinterher ({} s Lücke in den letzten Sekunden) – Internet zu langsam? {}",
					String.format(java.util.Locale.ROOT, "%.2f", stallFrames / (double) SAMPLE_RATE), key);
			lastStallLog = now;
			stallFrames = 0;
		}
	}

	private void close() {
		closed = true;
		innerCancel.complete(null);
		ready.complete(false);
	}

	private static void runDaemon(Runnable r) {
		Thread t = new Thread(r, "MultiMedia-Hub");
		t.setDaemon(true);
		t.start();
	}

	// ------------------------------------------------------------------ Zugang pro Jukebox

	private final class Tap implements AudioStream {
		private final BiConsumer<YtAudioStream.TrackStart, Long> onTrack;
		private final int[] lastReads = new int[3];
		private int lastReadIdx;
		private final int stereo;
		private long cursor = -1;
		/** Wie viele Frames noch eingeblendet werden (beim Start und nach Stille). */
		private int fadeLeft = FADE_FRAMES;
		private volatile boolean tapClosed;

		Tap(BiConsumer<YtAudioStream.TrackStart, Long> onTrack, int stereo) {
			this.onTrack = onTrack;
			this.stereo = stereo;
		}

		void awaitStartBuffer() throws InterruptedException {
			if (live) {
				return;
			}
			long deadline = System.currentTimeMillis() + 30_000;
			while (!tapClosed && !ended && !closed && produced < nowBytes() + START_BUFFER_BYTES
					&& System.currentTimeMillis() < deadline) {
				Thread.sleep(50);
			}
		}

		@Override
		public AudioFormat getFormat() {
			return FORMAT;
		}

		@Override
		public ByteBuffer read(int expectedSize) {
			if (tapClosed) {
				return null;
			}
			if (cursor < 0) {
				cursor = nowBytes(); // gemeinsame Uhr -> alle Jukeboxen an derselben Stelle
				// Titel, der gerade läuft, sofort anzeigen
				synchronized (StreamHub.this) {
					Marker current = null;
					for (Marker m : markers) {
						if (m.pos <= cursor) {
							current = m;
						}
					}
					if (current != null && onTrack != null) {
						onTrack.accept(current.track, 0L);
					}
				}
			}
			// Minecraft fragt in Mono-Bytes; gelesen wird Stereo (doppelt so viel)
			int frames = live ? LIVE_READ_FRAMES : Math.max(expectedSize / 2, SILENCE_FRAMES);
			byte[] stereoBuf = new byte[frames * FRAME];
			int got = 0;
			// Live nie warten: das hier läuft im Ton-Thread von Minecraft – wartet er, hängt irgendwann das ganze Spiel
			long deadline = System.nanoTime() + (live ? 0 : TimeUnit.MILLISECONDS.toNanos(60));
			while (produced <= cursor && !ended && !closed && System.nanoTime() < deadline) {
				try {
					Thread.sleep(5);
				} catch (InterruptedException e) {
					break;
				}
			}
			long start = cursor;
			synchronized (StreamHub.this) {
				if (produced <= cursor) {
					if (ended || closed) {
						return null;
					}
					// Netz zu langsam: kurz Stille, aber im Takt der Uhr bleiben
					got = live ? frames * FRAME : SILENCE_FRAMES * FRAME;
					cursor += got;
					fadeLeft = FADE_FRAMES;
					if (!live) {
						stalled();
					}
				} else {
					for (Chunk c : chunks) {
						long end = c.start + c.data.length;
						if (end <= cursor || got >= stereoBuf.length) {
							continue;
						}
						if (c.start > cursor) {
							if (live) {
								// Lücke im Live-Ton: Stille bis zum nächsten Stück (im Takt bleiben)
								int gap = (int) Math.min(stereoBuf.length - got, c.start - cursor);
								got += gap;
								cursor += gap;
								fadeLeft = FADE_FRAMES;
								if (got >= stereoBuf.length) {
									break;
								}
							} else {
								if (got > 0) {
									break;
								}
								cursor = c.start; // zu weit zurück (Verlauf schon weg) -> aufholen
							}
						}
						int from = (int) (cursor - c.start);
						int n = Math.min(stereoBuf.length - got, c.data.length - from);
						System.arraycopy(c.data, from, stereoBuf, got, n);
						got += n;
						cursor += n;
					}
				}
				// Titelwechsel in diesem Abschnitt melden (mit der Zeit, bis er zu hören ist)
				if (onTrack != null) {
					long ahead = 0;
					for (int r : lastReads) {
						ahead += r;
					}
					for (Marker m : markers) {
						if (m.pos >= start && m.pos < cursor && m.pos > 0) {
							onTrack.accept(m.track, ahead * 1000L / MONO_BPS + (m.pos - start) * 1000L / BPS);
						}
					}
				}
			}
			int outFrames = got / FRAME;
			ByteBuffer out = ByteBuffer.allocateDirect(outFrames * 2).order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < outFrames; i++) {
				int p = i * FRAME;
				short l = (short) ((stereoBuf[p] & 0xFF) | (stereoBuf[p + 1] << 8));
				short r = (short) ((stereoBuf[p + 2] & 0xFF) | (stereoBuf[p + 3] << 8));
				int v = switch (stereo) {
					case LEFT -> l;
					case RIGHT -> r;
					default -> (l + r) / 2;
				};
				if (fadeLeft > 0 && (l != 0 || r != 0 || fadeLeft < FADE_FRAMES)) {
					v = v * (FADE_FRAMES - fadeLeft) / FADE_FRAMES;
					fadeLeft--;
				}
				out.putShort((short) v);
			}
			out.flip();
			lastReads[lastReadIdx] = out.limit();
			lastReadIdx = (lastReadIdx + 1) % lastReads.length;
			return out;
		}

		@Override
		public void close() {
			if (!tapClosed) {
				tapClosed = true;
				release(StreamHub.this);
			}
		}
	}
}
