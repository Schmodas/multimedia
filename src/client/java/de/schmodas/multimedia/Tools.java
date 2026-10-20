package de.schmodas.multimedia;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Kümmert sich um die externen Programme:
 * yt-dlp (holt den Audio-Stream), ffmpeg (wandelt ihn in rohes Audio um)
 * und deno (braucht yt-dlp, um YouTube zu entschlüsseln).
 * Unter Windows werden sie beim ersten Bedarf nach .minecraft/multimedia/bin geladen
 * (ein vorhandener Ordner .minecraft/discstream/bin von früher wird übernommen).
 */
public final class Tools {
	public record Paths(String ytDlp, String ffmpeg, String deno) {
	}

	private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
	private static final Path BIN = binDir();

	/** Programme-Ordner; den alten von Disc Stream umziehen, damit nichts neu geladen werden muss. */
	private static Path binDir() {
		Path game = FabricLoader.getInstance().getGameDir();
		Path dir = game.resolve("multimedia").resolve("bin");
		Path old = game.resolve("discstream").resolve("bin");
		try {
			if (!java.nio.file.Files.exists(dir) && java.nio.file.Files.isDirectory(old)) {
				java.nio.file.Files.createDirectories(dir.getParent());
				java.nio.file.Files.move(old, dir);
			}
		} catch (Exception e) {
			return old; // Umziehen ging nicht (z.B. Datei in Benutzung) -> alten Ordner weiter nutzen
		}
		return dir;
	}

	private static final String YTDLP_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";
	private static final String FFMPEG_URL = "https://github.com/yt-dlp/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip";
	private static final String DENO_URL = "https://github.com/denoland/deno/releases/latest/download/deno-x86_64-pc-windows-msvc.zip";

	private static CompletableFuture<Paths> pending;

	private Tools() {
	}

	/** Liefert die Programmpfade; lädt fehlende Programme vorher herunter. */
	public static synchronized CompletableFuture<Paths> ensure(Consumer<String> status) {
		Paths ready = readyPaths();
		if (ready != null) {
			maybeUpdateYtDlp(ready);
			return CompletableFuture.completedFuture(ready);
		}
		if (pending == null || pending.isDone()) {
			pending = CompletableFuture.supplyAsync(() -> {
				try {
					return install(status);
				} catch (Exception e) {
					throw new RuntimeException(e.getMessage(), e);
				}
			}, r -> {
				Thread t = new Thread(r, "MultiMedia-Download");
				t.setDaemon(true);
				t.start();
			});
		}
		return pending;
	}

	private static volatile boolean updateChecked;

	/**
	 * yt-dlp höchstens einmal am Tag still aktualisieren (YouTube ändert öfter etwas, dann gehen alte Versionen
	 * nicht mehr). Läuft im Hintergrund; klappt es nicht, bleibt einfach die alte Version.
	 */
	private static void maybeUpdateYtDlp(Paths paths) {
		if (updateChecked || !WINDOWS) {
			return; // Linux/macOS: yt-dlp kommt vom Paketmanager
		}
		updateChecked = true;
		Path stamp = BIN.resolve("yt-dlp.updated");
		try {
			if (Files.exists(stamp) && System.currentTimeMillis() - Files.getLastModifiedTime(stamp).toMillis() < 20 * 3600_000L) {
				return;
			}
		} catch (IOException ignored) {
		}
		Thread t = new Thread(() -> {
			try {
				Process p = new ProcessBuilder(paths.ytDlp(), "-U").redirectErrorStream(true).start();
				String out = readQuiet(p.getInputStream());
				boolean done = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
				if (!done) {
					p.destroyForcibly();
				}
				Files.writeString(stamp, out);
				String last = lastLine(out.trim(), "");
				MultiMediaClient.LOGGER.info("yt-dlp-Update: {}", last);
				if (out.contains("Updated yt-dlp")) {
					MultiMediaClient.info(T.t("YouTube-Werkzeug (yt-dlp) wurde aktualisiert."));
				}
			} catch (Exception e) {
				MultiMediaClient.LOGGER.info("yt-dlp-Update ging nicht: {}", e.toString());
			}
		}, "MultiMedia-YtDlpUpdate");
		t.setDaemon(true);
		t.start();
	}

	/** Ton-Helfer fürs Senden (nur Windows, steckt in der Mod): bei Bedarf entpacken. Null, wenn nicht verfügbar. */
	static synchronized Path audioHelper() {
		if (!WINDOWS) {
			return null;
		}
		try (InputStream in = Tools.class.getResourceAsStream("/multimedia/mmaudio.exe")) {
			if (in == null) {
				return null;
			}
			byte[] data = in.readAllBytes();
			Path target = BIN.resolve("mmaudio.exe");
			if (!Files.exists(target) || Files.size(target) != data.length
					|| !java.util.Arrays.equals(Files.readAllBytes(target), data)) {
				Files.createDirectories(BIN);
				Files.write(target, data);
			}
			return target;
		} catch (IOException e) {
			MultiMediaClient.LOGGER.warn("Ton-Helfer konnte nicht entpackt werden: {}", e.toString());
			return null;
		}
	}

	private static Paths readyPaths() {
		if (!WINDOWS) {
			// Linux/macOS: Programme müssen installiert sein (Paketmanager).
			return new Paths("yt-dlp", "ffmpeg", "deno");
		}
		Path y = BIN.resolve("yt-dlp.exe");
		Path f = BIN.resolve("ffmpeg.exe");
		Path d = BIN.resolve("deno.exe");
		if (Files.exists(y) && Files.exists(f) && Files.exists(d)) {
			return new Paths(y.toString(), f.toString(), d.toString());
		}
		return null;
	}

	private static Paths install(Consumer<String> status) throws IOException, InterruptedException {
		Files.createDirectories(BIN);
		HttpClient http = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.ALWAYS)
				.connectTimeout(Duration.ofSeconds(20))
				.build();

		Path ytDlp = BIN.resolve("yt-dlp.exe");
		if (!Files.exists(ytDlp)) {
			status.accept(T.t("Lade yt-dlp herunter (ca. 18 MB) …"));
			download(http, YTDLP_URL, ytDlp, null);
		}
		Path deno = BIN.resolve("deno.exe");
		if (!Files.exists(deno)) {
			status.accept(T.t("Lade deno herunter (ca. 45 MB) …"));
			download(http, DENO_URL, deno, "deno.exe");
		}
		Path ffmpeg = BIN.resolve("ffmpeg.exe");
		if (!Files.exists(ffmpeg)) {
			status.accept(T.t("Lade ffmpeg herunter (ca. 200 MB, dauert etwas) …"));
			download(http, FFMPEG_URL, ffmpeg, "ffmpeg.exe");
		}
		status.accept(T.t("Alles bereit."));
		return new Paths(ytDlp.toString(), ffmpeg.toString(), deno.toString());
	}

	/**
	 * Lädt eine Datei. Ist zipEntryName gesetzt, wird aus dem ZIP nur die Datei
	 * mit diesem Namen (egal in welchem Unterordner) entpackt.
	 */
	private static void download(HttpClient http, String url, Path target, String zipEntryName)
			throws IOException, InterruptedException {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", "MultiMedia-Minecraft-Mod")
				.GET().build();
		HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
		if (res.statusCode() != 200) {
			res.body().close();
			throw new IOException(T.t("Download fehlgeschlagen (") + res.statusCode() + "): " + url);
		}
		Path tmp = target.resolveSibling(target.getFileName() + ".part");
		try (InputStream in = res.body()) {
			if (zipEntryName == null) {
				Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
			} else {
				boolean found = false;
				try (ZipInputStream zip = new ZipInputStream(in)) {
					ZipEntry entry;
					while ((entry = zip.getNextEntry()) != null) {
						String name = entry.getName();
						if (!entry.isDirectory() && (name.equals(zipEntryName) || name.endsWith("/" + zipEntryName))) {
							Files.copy(zip, tmp, StandardCopyOption.REPLACE_EXISTING);
							found = true;
							break;
						}
					}
				}
				if (!found) {
					throw new IOException(zipEntryName + T.t(" nicht im Download gefunden"));
				}
			}
		}
		Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
	}

	/** Aktualisiert yt-dlp (hilft, wenn YouTube etwas geändert hat). */
	public static CompletableFuture<String> updateYtDlp(Consumer<String> status) {
		return ensure(status).thenApply(p -> {
			try {
				Process proc = new ProcessBuilder(p.ytDlp(), "-U").redirectErrorStream(true).start();
				String out = new String(proc.getInputStream().readAllBytes()).trim();
				proc.waitFor();
				String[] lines = out.split("\\R");
				return lines[lines.length - 1];
			} catch (Exception e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		});
	}

	/** Holt den Titel eines Videos oder einer Playlist (prüft nebenbei, ob der Link funktioniert). */
	public static CompletableFuture<String> fetchTitle(String spec, Consumer<String> status) {
		Source src = Source.parse(spec);
		if (src == null || !Source.isSafe(src.url())) {
			return CompletableFuture.failedFuture(new IllegalArgumentException(T.t("Kein YouTube-Link")));
		}
		return ensure(status).thenApplyAsync(p -> {
			try {
				ProcessBuilder pb = src.playlist()
						? new ProcessBuilder(p.ytDlp(), "--flat-playlist", "--no-warnings", "-I", "1",
								"--print", "playlist_title", "--js-runtimes", "deno:" + p.deno(), src.url())
						: new ProcessBuilder(p.ytDlp(), "--no-playlist", "--no-warnings", "--skip-download",
								"--print", "title", "--js-runtimes", "deno:" + p.deno(), src.url());
				Process proc = pb.start();
				CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> readQuiet(proc.getErrorStream()));
				String out = readQuiet(proc.getInputStream()).trim();
				int code = proc.waitFor();
				if (code != 0 || out.isEmpty()) {
					throw new RuntimeException(lastLine(err.join(), T.t("Link konnte nicht gelesen werden")));
				}
				String title = out.split("\\R")[0];
				return src.playlist() ? src.describe() + ": " + title : title;
			} catch (InterruptedException | IOException e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		});
	}

	private record CachedTracks(CompletableFuture<List<Playback.Track>> future, long time) {
	}

	private static final Map<String, CachedTracks> TRACKS = new java.util.concurrent.ConcurrentHashMap<>();

	/** Titel einer Playlist, wenn sie schon geladen wurden (sonst null) – für die Skip-Knöpfe. */
	public static List<Playback.Track> cachedTracks(String url) {
		CachedTracks c = TRACKS.get(url);
		if (c == null || !c.future().isDone() || c.future().isCompletedExceptionally()) {
			return null;
		}
		return c.future().getNow(null);
	}
	private static final long TRACKS_TTL_MS = 10 * 60 * 1000;

	/**
	 * Titel, Namen und Längen eines Links (Playlist oder einzelnes Video). Ton und Bild fragen gleichzeitig –
	 * es läuft aber nur EINE Abfrage, die zweite wartet auf deren Ergebnis. Ergebnis wird 10 Minuten gemerkt.
	 */
	static List<Playback.Track> tracks(Paths p, String url, Consumer<String> errors) throws IOException, InterruptedException {
		if (!Source.isSafe(url)) {
			throw new IOException(T.t("Nur YouTube-Links werden abgespielt"));
		}
		long now = System.currentTimeMillis();
		CompletableFuture<List<Playback.Track>> mine = new CompletableFuture<>();
		CachedTracks entry = TRACKS.compute(url, (k, old) ->
				old != null && now - old.time() < TRACKS_TTL_MS && !old.future().isCompletedExceptionally() ? old : new CachedTracks(mine, now));
		if (entry.future() != mine) {
			try {
				return entry.future().get();
			} catch (java.util.concurrent.ExecutionException e) {
				throw new IOException(e.getCause());
			}
		}
		try {
			Process proc = new ProcessBuilder(p.ytDlp(), "--flat-playlist", "--no-warnings",
					"--print", "%(id)s\t%(duration)s\t%(title)s", "--js-runtimes", "deno:" + p.deno(), url).start();
			CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> readQuiet(proc.getErrorStream()));
			String out = readQuiet(proc.getInputStream());
			proc.waitFor();
			String errText = err.join().trim();
			if (!errText.isEmpty()) {
				errors.accept(lastLine(errText, errText));
			}
			List<Playback.Track> tracks = new ArrayList<>();
			for (String line : out.split("\\R")) {
				String[] parts = line.split("\t", 3);
				if (parts.length == 3 && parts[0].matches("[A-Za-z0-9_-]{11}")) {
					double duration;
					try {
						duration = Double.parseDouble(parts[1]);
					} catch (NumberFormatException e) {
						duration = 0; // z.B. "NA" bei Livestreams
					}
					tracks.add(new Playback.Track("https://www.youtube.com/watch?v=" + parts[0], parts[2], duration));
				}
			}
			List<Playback.Track> result = List.copyOf(tracks);
			if (result.isEmpty()) {
				TRACKS.remove(url); // nicht merken, beim nächsten Mal neu versuchen
			}
			mine.complete(result);
			return result;
		} catch (IOException | RuntimeException | InterruptedException e) {
			TRACKS.remove(url);
			mine.completeExceptionally(e);
			throw e;
		}
	}

	/** Direkte Adresse eines Formats und die ffmpeg-Optionen, damit YouTube die Anfrage akzeptiert. */
	public record Direct(String url, List<String> inputArgs) {
	}

	/**
	 * Fragt yt-dlp nach der direkten Adresse UND den Kopfzeilen (User-Agent usw.), mit denen sie
	 * abgerufen werden muss. Ohne die passenden Kopfzeilen liefert YouTube manche Formate nicht aus.
	 */
	static Direct resolveDirect(Paths p, String url, String format, Consumer<String> errors) throws IOException, InterruptedException {
		if (!Source.isSafe(url)) {
			throw new IOException(T.t("Nur YouTube-Links werden abgespielt"));
		}
		Process proc = new ProcessBuilder(p.ytDlp(), "-f", format, "--no-playlist", "--no-warnings",
				"--print", "%(url)s", "--print", "%(http_headers)j", "--js-runtimes", "deno:" + p.deno(), url).start();
		CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> readQuiet(proc.getErrorStream()));
		String out = readQuiet(proc.getInputStream());
		proc.waitFor();
		String errText = err.join().trim();
		if (!errText.isEmpty()) {
			errors.accept(lastLine(errText, errText));
		}
		String direct = null;
		Map<String, String> headers = Map.of();
		for (String line : out.split("\\R")) {
			line = line.trim();
			if (line.startsWith("{")) {
				try {
					headers = new com.google.gson.Gson().fromJson(line,
							new com.google.gson.reflect.TypeToken<Map<String, String>>() { }.getType());
				} catch (Exception ignored) {
				}
			} else if (!line.isEmpty() && direct == null && !line.equals("NA")) {
				direct = line;
			}
		}
		if (direct == null) {
			return null;
		}
		List<String> args = new ArrayList<>();
		if (direct.startsWith("http")) {
			// über den lokalen Vermittler: holt in Stücken (wie yt-dlp), damit YouTube nicht bremst,
			// und schickt die Kopfzeilen mit, die YouTube erwartet
			direct = ChunkProxy.register(direct, headers);
			args.addAll(List.of("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5"));
		}
		return new Direct(direct, args);
	}

	static String readQuiet(InputStream in) {
		try {
			return new String(in.readAllBytes());
		} catch (IOException e) {
			return "";
		}
	}

	static String lastLine(String text, String fallback) {
		String[] lines = text.trim().split("\\R");
		String last = lines[lines.length - 1].trim();
		return last.isEmpty() ? fallback : last;
	}
}
