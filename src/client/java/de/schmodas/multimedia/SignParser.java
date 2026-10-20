package de.schmodas.multimedia;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest den Text eines Schilds (Vorder- und Rückseite).
 * - Link: darf über mehrere Zeilen gehen (Teile werden ohne Leerzeichen zusammengesetzt)
 * - Kanal "#1.1", Lautstärke "80%", Stereo "links"/"rechts", Optionen "mix"/"loop": in eigenen Zeilen oder zusammen
 *   in einer Zeile ("#1.1 80% mix"), auf der Vorder- oder Rückseite.
 */
public final class SignParser {
	/** stereo: 0 = beide Kanäle gemischt, 1 = nur links, 2 = nur rechts. */
	/** tv: Name des Spielers, der gerade auf diese Leinwand sendet ("tv:Schmodas"), sonst null. */
	public record Info(Source source, String channel, float volume, int stereo, boolean screen, String tv) {
	}

	private static final Pattern VOLUME = Pattern.compile("(\\d{1,3})%");
	private static final Pattern CHANNEL = Pattern.compile("#([\\p{L}0-9_.,-]+)");
	private static final Pattern START = Pattern.compile("@\\d{1,12}|⏸\\d{1,9}(\\.\\d)?|▶\\d{1,6}");
	private static final Pattern TV = Pattern.compile("tv:([A-Za-z0-9_]{3,16})", Pattern.CASE_INSENSITIVE);
	private static final Pattern SCREEN = Pattern.compile("bildschirm|leinwand|screen");
	private static final Pattern LEFT = Pattern.compile("links|left");
	private static final Pattern RIGHT = Pattern.compile("rechts|right");
	private static final Pattern FLAG = Pattern.compile("mix|shuffle|zufall|gemischt|loop|repeat|endlos");

	private SignParser() {
	}

	private static final class Side {
		final StringBuilder link = new StringBuilder();
		final StringBuilder flags = new StringBuilder();
		String channel;
		Float volume;
		Integer stereo;
		boolean screen;
		String tv;
	}

	private static Side read(List<String> lines) {
		Side side = new Side();
		for (String raw : lines) {
			if (raw == null) {
				continue;
			}
			// "# 1.1" -> "#1.1", "80 %" -> "80%"
			String line = raw.trim().replaceAll("#\\s+", "#").replaceAll("(\\d)\\s+%", "$1%");
			if (line.isEmpty()) {
				continue;
			}
			for (String token : line.split("\\s+")) {
				String lower = token.toLowerCase(Locale.ROOT);
				Matcher vol = VOLUME.matcher(token);
				Matcher ch = CHANNEL.matcher(token);
				if (vol.matches()) {
					side.volume = Math.min(200, Integer.parseInt(vol.group(1))) / 100F;
				} else if (ch.matches()) {
					side.channel = ch.group(1).toLowerCase(Locale.ROOT);
				} else if (TV.matcher(token).matches()) {
					Matcher tv = TV.matcher(token);
					tv.matches();
					side.tv = tv.group(1);
				} else if (START.matcher(token).matches()) {
					side.flags.append(' ').append(token); // Startzeitpunkt geht mit an Source.parse
				} else if (SCREEN.matcher(lower).matches()) {
					side.screen = true;
				} else if (LEFT.matcher(lower).matches()) {
					side.stereo = 1;
				} else if (RIGHT.matcher(lower).matches()) {
					side.stereo = 2;
				} else if (FLAG.matcher(lower).matches()) {
					side.flags.append(' ').append(lower);
				} else {
					side.link.append(token); // Link-Teile ohne Leerzeichen zusammensetzen
				}
			}
		}
		return side;
	}

	/** Null, wenn weder Link noch Kanal auf dem Schild steht. */
	public static Info parse(List<String> front, List<String> back) {
		Side f = read(front);
		Side b = read(back);
		String flags = f.flags.toString() + b.flags;
		Source src = Source.parse(f.link + flags);
		if (src == null) {
			src = Source.parse(b.link + flags);
		}
		String channel = f.channel != null ? f.channel : b.channel;
		Float volume = f.volume != null ? f.volume : b.volume;
		String tv = f.tv != null ? f.tv : b.tv;
		boolean screen = f.screen || b.screen;
		if (src == null && channel == null && !(screen && tv != null)) {
			return null;
		}
		Integer stereo = f.stereo != null ? f.stereo : b.stereo;
		return new Info(src, channel, volume != null ? volume : 1F, stereo != null ? stereo : 0, screen, tv);
	}
}
