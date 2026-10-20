package de.schmodas.multimedia;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleSupplier;

/**
 * Zustand einer laufenden Disc: welche Titel, in welcher Reihenfolge, welcher gerade läuft.
 * Bleibt beim Skippen erhalten (die Playlist muss nicht neu geladen werden).
 * Das Mischen nutzt einen festen Startwert, damit alle Spieler mit Mod dieselbe Reihenfolge hören.
 *
 * Im Radio-Modus (Schild an der Jukebox) ergibt sich die Position aus der Spielzeit der Welt,
 * die für alle Spieler gleich ist: Wer dazukommt, hört genau dieselbe Stelle wie alle anderen.
 */
public final class Playback {
	/** duration in Sekunden, 0 = unbekannt. */
	public record Track(String url, String title, double duration) {
	}

	/** Position im Radio: Durchlauf, Titel darin und Sekunden im Titel. */
	public record Position(int pass, int index, double offset) {
	}

	public final Source source;
	private final long seed;
	/** Liefert die Radio-Uhr in Sekunden (null = kein Radio-Modus). */
	private final DoubleSupplier radioClock;
	private volatile List<Track> tracks;

	/** Was gerade zu hören ist (Durchlauf + Position darin). */
	private volatile int playingPass;
	private volatile int playingIndex;

	/** Wo der nächste Stream anfangen soll. */
	private volatile int startPass;
	private volatile int startIndex;

	public Playback(Source source, long seed) {
		this(source, seed, null);
	}

	private Playback(Source source, long seed, DoubleSupplier radioClock) {
		this.source = source;
		this.seed = seed;
		this.radioClock = radioClock;
	}

	/**
	 * Radio-Modus: Startwert hängt nur vom Link ab, damit alle gleich mischen.
	 * Hat das Schild einen Startzeitpunkt (@...), zählt die Uhr ab diesem Moment – dann fängt alles von vorne an.
	 */
	public static Playback radio(Source source, DoubleSupplier clockSeconds) {
		DoubleSupplier clock = source.isPaused()
				? source::paused
				: source.start() >= 0
				? () -> Math.max(0, clockSeconds.getAsDouble() - source.start())
				: clockSeconds;
		return new Playback(source, source.spec().hashCode(), clock);
	}

	/** Wie radio(), aber die Uhr wird nicht nach dem Startzeitpunkt verschoben (sie ist es schon). */
	public static Playback radioRaw(Source source, DoubleSupplier positionClock) {
		return new Playback(source, source.spec().hashCode(), positionClock);
	}

	public boolean isRadio() {
		return radioClock != null;
	}

	public double radioNow() {
		return radioClock.getAsDouble();
	}

	public List<Track> tracks() {
		return tracks;
	}

	public void setTracks(List<Track> tracks) {
		this.tracks = List.copyOf(tracks);
	}

	/** Reihenfolge für einen Durchlauf; bei "mix" pro Durchlauf neu, aber für alle gleich gemischt. */
	public List<Track> order(int pass) {
		List<Track> order = new ArrayList<>(tracks);
		if (source.shuffle()) {
			Collections.shuffle(order, new Random(seed * 31 + pass));
		}
		return order;
	}

	/**
	 * Wo das Radio zur Zeit t (Sekunden) gerade steht.
	 * Gibt null zurück, wenn Längen fehlen (z.B. Livestream) – dann wird von vorne gespielt.
	 */
	public Position radioPosition(double t) {
		t = Math.max(0, t);
		double total = 0;
		for (Track tr : tracks) {
			if (tr.duration() <= 0) {
				return null;
			}
			total += tr.duration();
		}
		// Skip-Knopf: ab Titel "first" (über Durchläufe gezählt) – die Uhr läuft ab dessen Anfang
		int first = source.first();
		if (first > 0 && !tracks.isEmpty()) {
			int fPass = first / tracks.size();
			List<Track> fOrder = order(fPass);
			double before = fPass * total;
			for (int i = 0; i < first % tracks.size(); i++) {
				before += fOrder.get(i).duration();
			}
			t += before;
		}
		int pass = (int) Math.floor(t / total);
		double inPass = t - pass * total;
		List<Track> order = order(pass);
		for (int i = 0; i < order.size(); i++) {
			double d = order.get(i).duration();
			if (inPass < d) {
				return new Position(pass, i, inPass);
			}
			inPass -= d;
		}
		return new Position(pass + 1, 0, 0);
	}

	public int startPass() {
		return tracks == null || tracks.isEmpty() ? startPass : source.first() / tracks.size();
	}

	public int startIndex() {
		return tracks == null || tracks.isEmpty() ? startIndex : source.first() % tracks.size();
	}

	public void markPlaying(int pass, int index) {
		playingPass = pass;
		playingIndex = index;
	}

}
