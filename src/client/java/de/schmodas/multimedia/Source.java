package de.schmodas.multimedia;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Was eine Jukebox abspielen soll, gelesen vom Schild.
 * Sicherheit: Es werden NUR YouTube-Video- bzw. Playlist-IDs übernommen und daraus selbst ein
 * youtube.com-Link gebaut. Beliebige andere Links werden ignoriert – sonst könnte jemand per Schild
 * alle Spieler in der Nähe unbemerkt seinen eigenen Server aufrufen lassen (und ihre IP-Adressen sehen).
 * Beispiele:
 *   youtu.be/dQw4w9WgXcQ            -> ein Video
 *   list=PLxxxxxxxx                 -> Playlist in Reihenfolge, einmal
 *   list=PLxxxxxxxx mix loop        -> Playlist gemischt, endlos
 */
/**
 * start: Startzeitpunkt in Sekunden Spielzeit ("@84213" auf dem Schild), -1 = keiner (läuft wie ein Radio).
 * paused: angehalten an dieser Stelle in Sekunden ("⏸84.5" auf dem Schild), NaN = läuft.
 */
public record Source(String url, boolean playlist, boolean shuffle, boolean loop, long start, double paused, int first) {
	/** Ohne Titel-Angabe (fängt mit dem ersten Titel an). */
	public Source(String url, boolean playlist, boolean shuffle, boolean loop, long start, double paused) {
		this(url, playlist, shuffle, loop, start, paused, 0);
	}

	private static final Pattern LIST = Pattern.compile("[?&]?\\blist=([A-Za-z0-9_-]{10,})");
	private static final Pattern VIDEO = Pattern.compile(
			"(?:youtu\\.be/|[?&]v=|/shorts/|/live/|\\byt:)([A-Za-z0-9_-]{11})");
	/** So sehen alle Links aus, die an yt-dlp gehen dürfen. */
	private static final Pattern SAFE = Pattern.compile(
			"https://www\\.youtube\\.com/(watch\\?v=[A-Za-z0-9_-]{11}|playlist\\?list=[A-Za-z0-9_-]{10,})");
	/** Sieht nach einem (fremden) Link aus – für eine verständliche Fehlermeldung. */
	private static final Pattern LOOKS_LIKE_URL = Pattern.compile("(?i)(https?://|www\\.|\\.[a-z]{2,6}/)");
	/** Startzeitpunkt: wird beim Einfügen mit Strg+V aufs Schild geschrieben, damit alles von vorne anfängt. */
	private static final Pattern START = Pattern.compile("(?<![\\w@])@(\\d{1,12})\\b");
	/** Pause: Stelle in Sekunden, an der angehalten wurde (Knopf im Schild-Editor). */
	private static final Pattern PAUSE = Pattern.compile("⏸(\\d{1,9}(?:\\.\\d)?)");
	/** Playlist: ab welchem Titel (1 = erster) – kommt von den Skip-Knöpfen im Schild-Editor. */
	private static final Pattern FIRST = Pattern.compile("▶(\\d{1,6})");
	private static final Pattern MIX = Pattern.compile("\\b(mix|shuffle|zufall|gemischt)\\b");
	private static final Pattern LOOP = Pattern.compile("\\b(loop|repeat|endlos|wiederholen)\\b");

	/** Liest Text vom Schild. Gibt null zurück, wenn kein YouTube-Link (bzw. yt:/list=) drin ist. */
	public static Source parse(String text) {
		if (text == null) {
			return null;
		}
		Matcher st = START.matcher(text);
		long start = st.find() ? Long.parseLong(st.group(1)) : -1;
		Matcher pz = PAUSE.matcher(text);
		double paused = pz.find() ? Double.parseDouble(pz.group(1)) : Double.NaN;
		Matcher fm = FIRST.matcher(text);
		int first = fm.find() ? Math.max(0, Integer.parseInt(fm.group(1)) - 1) : 0;
		Matcher packed = PACKED.matcher(text);
		if (packed.find()) {
			String id = unpack(packed.group(1).charAt(0), packed.group(2));
			if (id != null) {
				String rest = (text.substring(0, packed.start()) + " " + text.substring(packed.end())).toLowerCase(Locale.ROOT);
				return new Source("https://www.youtube.com/playlist?list=" + id, true,
						MIX.matcher(rest).find(), LOOP.matcher(rest).find(), start, paused, first);
			}
		}
		Matcher list = LIST.matcher(text);
		if (list.find()) {
			String rest = (text.substring(0, list.start()) + " " + text.substring(list.end()))
					.replaceAll("\\S*(youtube\\.com|youtu\\.be)\\S*", " ")
					.toLowerCase(Locale.ROOT);
			return new Source("https://www.youtube.com/playlist?list=" + list.group(1), true,
					MIX.matcher(rest).find(), LOOP.matcher(rest).find(), start, paused, first);
		}
		Matcher video = VIDEO.matcher(text);
		if (video.find()) {
			return new Source("https://www.youtube.com/watch?v=" + video.group(1), false, false, false, start, paused);
		}
		return null; // andere Links werden absichtlich nicht abgespielt
	}

	// ------------------------------------------------------------------ Playlist-ID kurz (2 Schildzeilen)

	/**
	 * Playlist-IDs sind bis zu 41 Zeichen lang – zu lang fürs Schild. Deshalb dicht verpackt: ein Kopfzeichen
	 * (Hangul, 9 Bit: bekannter Anfang + Länge) und danach chinesische Schriftzeichen mit je 14 Bit.
	 * Nicht zum Lesen gedacht – eingefügt wird mit Strg+V.
	 */
	private static final Pattern PACKED = Pattern.compile("([\uAC00-\uADFF])([\u4E00-\u8DFF]{1,40})");
	private static final String[] PREFIXES = {"", "PL", "OLAK5uy_", "UU", "FL", "LL", "RD", "OL"};
	private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

	static String pack(String id) {
		if (id == null || !id.matches("[A-Za-z0-9_-]{2,80}")) {
			return null;
		}
		int prefix = 0;
		for (int i = 1; i < PREFIXES.length; i++) {
			if (id.startsWith(PREFIXES[i]) && PREFIXES[i].length() > PREFIXES[prefix].length()) {
				prefix = i;
			}
		}
		String rest = id.substring(PREFIXES[prefix].length());
		if (rest.length() > 63) {
			return null;
		}
		StringBuilder out = new StringBuilder();
		out.append((char) (0xAC00 + (prefix << 6 | rest.length())));
		long acc = 0;
		int bits = 0;
		for (char c : rest.toCharArray()) {
			acc = acc << 6 | ALPHABET.indexOf(c);
			bits += 6;
			while (bits >= 14) {
				bits -= 14;
				out.append((char) (0x4E00 + ((acc >> bits) & 0x3FFF)));
			}
		}
		if (bits > 0) {
			out.append((char) (0x4E00 + ((acc << (14 - bits)) & 0x3FFF)));
		}
		return out.toString();
	}

	static String unpack(char header, String data) {
		int h = header - 0xAC00;
		int prefix = h >> 6;
		int len = h & 63;
		if (prefix >= PREFIXES.length || data.length() < (len * 6 + 13) / 14) {
			return null;
		}
		StringBuilder id = new StringBuilder(PREFIXES[prefix]);
		long acc = 0;
		int bits = 0;
		int i = 0;
		while (id.length() < PREFIXES[prefix].length() + len) {
			while (bits < 6) {
				acc = acc << 14 | (data.charAt(i++) - 0x4E00);
				bits += 14;
			}
			bits -= 6;
			id.append(ALPHABET.charAt((int) ((acc >> bits) & 63)));
		}
		String s = id.toString();
		return s.matches("[A-Za-z0-9_-]{10,80}") ? s : null;
	}

	/** Darf diese Adresse an yt-dlp gegeben werden? (Zweite Absicherung vor jedem Programmaufruf.) */
	public static boolean isSafe(String url) {
		return url != null && SAFE.matcher(url).matches();
	}

	/** Steht ein Link drin, der aber kein YouTube-Link ist? */
	public static boolean looksLikeOtherLink(String text) {
		return text != null && parse(text) == null && LOOKS_LIKE_URL.matcher(text).find();
	}

	/** Text-Form zum Speichern/Weitergeben; parse(spec()) ergibt wieder dasselbe. */
	public String spec() {
		return key() + (first > 0 ? " ▶" + (first + 1) : "") + (start >= 0 ? " @" + start : "")
				+ (isPaused() ? " ⏸" + String.format(Locale.ROOT, "%.1f", paused) : "");
	}

	/** Was abgespielt wird, ohne Start/Pause – gleich bleibend, wenn man pausiert oder weiterspielt. */
	public String key() {
		return url + (shuffle ? " mix" : "") + (loop ? " loop" : "");
	}

	/** Live-Bildschirm eines Spielers als Ton-Quelle für Kanäle ("live:name"). */
	public static Source live(String player) {
		return new Source("live:" + player.toLowerCase(Locale.ROOT), false, false, false, -1, Double.NaN);
	}

	public boolean isLive() {
		return url.startsWith("live:");
	}

	public String liveName() {
		return url.substring(5);
	}

	public boolean isPaused() {
		return !Double.isNaN(paused);
	}

	/** Wo es gerade steht (Sekunden), bei gegebener Spielzeit in Sekunden. */
	public double positionAt(double gameSeconds) {
		if (isPaused()) {
			return paused;
		}
		return start >= 0 ? Math.max(0, gameSeconds - start) : gameSeconds;
	}

	/** Kurze Form für Schilder: "yt:VIDEOID" bzw. "list=PLAYLISTID". */
	public String compact() {
		if (playlist) {
			String id = url.substring(url.indexOf("list=") + 5);
			String packed = pack(id);
			return packed != null ? packed : "list=" + id;
		}
		int v = url.indexOf("youtube.com/watch?v=");
		return v >= 0 ? "yt:" + url.substring(v + "youtube.com/watch?v=".length()) : url;
	}

	public String describe() {
		if (!playlist) {
			return url;
		}
		return "Playlist" + (shuffle ? ", gemischt" : "") + (loop ? ", endlos" : "");
	}
}
