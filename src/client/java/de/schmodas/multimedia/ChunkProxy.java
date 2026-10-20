package de.schmodas.multimedia;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kleiner Vermittler nur auf diesem PC (127.0.0.1): ffmpeg fragt hier an, und die Daten werden bei
 * YouTube in Stücken von 4 MB geholt – genau wie yt-dlp es macht. Holt man ein Video in einer einzigen
 * großen Anfrage, bremst YouTube auf etwa Abspielgeschwindigkeit, und das Bild bleibt immer wieder hängen.
 * Springen (ffmpeg -ss) funktioniert weiterhin, weil ffmpeg hier beliebige Bereiche anfragen kann.
 */
final class ChunkProxy {
	private static final long CHUNK = 4L * 1024 * 1024;
	private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d*)");
	private static final Pattern TOTAL = Pattern.compile("/(\\d+)$");

	private record Target(String url, Map<String, String> headers) {
	}

	private static final Map<String, Target> TARGETS = new ConcurrentHashMap<>();
	private static final Map<String, Long> SIZES = new ConcurrentHashMap<>();
	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(15))
			.version(HttpClient.Version.HTTP_1_1)
			.build();
	private static final SecureRandom RANDOM = new SecureRandom();
	private static HttpServer server;

	private ChunkProxy() {
	}

	/** Lokale Adresse für ffmpeg, die die Daten von url (mit diesen Kopfzeilen) in Stücken holt. */
	static synchronized String register(String url, Map<String, String> headers) throws IOException {
		if (server == null) {
			server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
			server.createContext("/s/", ChunkProxy::handle);
			server.setExecutor(Executors.newCachedThreadPool(r -> {
				Thread t = new Thread(r, "MultiMedia-Proxy");
				t.setDaemon(true);
				return t;
			}));
			server.start();
		}
		byte[] b = new byte[16];
		RANDOM.nextBytes(b);
		String token = HexFormat.of().formatHex(b); // nicht erratbar
		TARGETS.put(token, new Target(url, headers));
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/s/" + token;
	}

	static void unregister(String localUrl) {
		String token = localUrl.substring(localUrl.lastIndexOf('/') + 1);
		TARGETS.remove(token);
		SIZES.remove(token);
	}

	private static void handle(HttpExchange ex) throws IOException {
		try (ex) {
			String token = ex.getRequestURI().getPath().substring(3);
			Target t = TARGETS.get(token);
			if (t == null) {
				ex.sendResponseHeaders(404, -1);
				return;
			}
			long start = 0;
			long reqEnd = -1;
			boolean partial = false;
			String range = ex.getRequestHeaders().getFirst("Range");
			if (range != null) {
				Matcher m = RANGE.matcher(range);
				if (m.matches()) {
					start = Long.parseLong(m.group(1));
					if (!m.group(2).isEmpty()) {
						reqEnd = Long.parseLong(m.group(2));
					}
					partial = true;
				}
			}
			// Erstes Stück gleich anfragen – aus seiner Antwort kennen wir auch die Gesamtgröße (spart eine Anfrage)
			Long known = SIZES.get(token);
			long firstEnd = start + CHUNK - 1;
			if (reqEnd >= 0) {
				firstEnd = Math.min(firstEnd, reqEnd);
			}
			if (known != null) {
				firstEnd = Math.min(firstEnd, known - 1);
			}
			HttpResponse<InputStream> first = known != null && start >= known ? null : open(t, start, firstEnd);
			long size = known != null ? known : sizeFrom(first);
			SIZES.put(token, size);
			long end = reqEnd >= 0 ? Math.min(reqEnd, size - 1) : size - 1;
			if (start > end) {
				if (first != null) {
					first.body().close();
				}
				ex.getResponseHeaders().add("Content-Range", "bytes */" + size);
				ex.sendResponseHeaders(416, -1);
				return;
			}
			ex.getResponseHeaders().add("Accept-Ranges", "bytes");
			ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
			if (partial) {
				ex.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + end + "/" + size);
			}
			ex.sendResponseHeaders(partial ? 206 : 200, end - start + 1);
			if ("HEAD".equals(ex.getRequestMethod())) {
				first.body().close();
				return;
			}
			OutputStream out = ex.getResponseBody();
			byte[] buf = new byte[64 * 1024];
			long pos = start;
			// erstes Stück direkt weiterreichen
			try (InputStream in = first.body()) {
				int n;
				while ((n = in.read(buf)) > 0 && pos <= end) {
					int k = (int) Math.min(n, end - pos + 1);
					out.write(buf, 0, k);
					pos += k;
				}
			}
			while (pos <= end) {
				long chunkEnd = Math.min(end, pos + CHUNK - 1);
				long got = fetch(t, pos, chunkEnd, out, buf);
				if (got <= 0) {
					break;
				}
				pos += got;
			}
		} catch (IOException e) {
			// ffmpeg hat die Verbindung geschlossen (Sprung/Ende) – normal
		}
	}

	private static HttpResponse<InputStream> open(Target t, long from, long to) throws IOException {
		IOException last = null;
		for (int attempt = 0; attempt < 3; attempt++) {
			try {
				HttpResponse<InputStream> resp = CLIENT.send(request(t, from, to), HttpResponse.BodyHandlers.ofInputStream());
				if (resp.statusCode() < 400) {
					return resp;
				}
				resp.body().close();
				last = new IOException("HTTP " + resp.statusCode());
				if (resp.statusCode() == 403 || resp.statusCode() == 404) {
					break; // hat keinen Sinn, nochmal zu fragen
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException(e);
			} catch (IOException e) {
				last = e;
			}
		}
		throw last != null ? last : new IOException(T.t("keine Antwort"));
	}

	private static long sizeFrom(HttpResponse<InputStream> resp) throws IOException {
		String cr = resp.headers().firstValue("Content-Range").orElse("");
		Matcher m = TOTAL.matcher(cr);
		if (m.find()) {
			return Long.parseLong(m.group(1));
		}
		long len = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
		if (len > 0 && resp.statusCode() == 200) {
			return len;
		}
		resp.body().close();
		throw new IOException(T.t("Größe unbekannt (HTTP ") + resp.statusCode() + ")");
	}

	/** Holt [from, to] in einem Stück (mit bis zu 3 Versuchen) und schreibt es nach out. */
	private static long fetch(Target t, long from, long to, OutputStream out, byte[] buf) throws IOException {
		IOException last = null;
		long written = 0;
		for (int attempt = 0; attempt < 3; attempt++) {
			try {
				HttpResponse<InputStream> resp = CLIENT.send(request(t, from + written, to), HttpResponse.BodyHandlers.ofInputStream());
				if (resp.statusCode() >= 400) {
					resp.body().close();
					throw new IOException("HTTP " + resp.statusCode());
				}
				try (InputStream in = resp.body()) {
					int n;
					while ((n = in.read(buf)) > 0) {
						out.write(buf, 0, n);
						written += n;
					}
				}
				if (from + written > to) {
					return written;
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException(e);
			} catch (IOException e) {
				if (isClientGone(e)) {
					throw e;
				}
				last = e;
			}
		}
		if (written > 0) {
			return written;
		}
		throw last != null ? last : new IOException(T.t("keine Daten"));
	}

	private static boolean isClientGone(IOException e) {
		String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
		return m.contains("broken pipe") || m.contains("connection reset") || m.contains("stream is closed")
				|| m.contains("abgebrochen") || m.contains("aborted");
	}

	private static HttpRequest request(Target t, long from, long to) {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(t.url()))
				.timeout(Duration.ofSeconds(30))
				.header("Range", "bytes=" + from + "-" + to);
		t.headers().forEach((k, v) -> {
			String key = k.toLowerCase(java.util.Locale.ROOT);
			// Diese setzt der HttpClient selbst bzw. dürfen nicht gesetzt werden
			if (!key.equals("host") && !key.equals("connection") && !key.equals("content-length")
					&& !key.equals("range") && !key.equals("accept-encoding") && !key.equals("upgrade") && !key.equals("expect")) {
				try {
					b.header(k, v);
				} catch (IllegalArgumentException ignored) {
				}
			}
		});
		return b.GET().build();
	}
}
