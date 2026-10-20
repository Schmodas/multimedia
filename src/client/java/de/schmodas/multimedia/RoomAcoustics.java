package de.schmodas.multimedia;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Schätzt den Raum um eine Jukebox: Von der Jukebox aus werden Strahlen in alle Richtungen
 * geschickt. Wie weit sie kommen, wie viele auf Wände treffen und woraus die Wände sind,
 * ergibt den Hall (große leere Steinhalle = langer Hall, Wollzimmer = trocken, draußen = kaum Hall).
 */
public final class RoomAcoustics {
	/** Ergebnis: Halldauer in Sekunden, Lautstärke des Halls, Vorverzögerung, Höhen im Hall. */
	public record Reverb(float decay, float gain, float reflectionsDelay, float gainHf) {
		public static final Reverb NONE = new Reverb(0.1F, 0F, 0.005F, 1F);
	}

	private static final int RAYS = 48;
	private static final double MAX_DIST = 32;
	private static final double STEP = 0.5;
	private static final int CACHE_TICKS = 40;

	private static final Vec3[] DIRECTIONS = fibonacciSphere(RAYS);
	private static final Map<BlockPos, Reverb> CACHE = new HashMap<>();
	private static final Map<BlockPos, Long> CACHE_TIME = new HashMap<>();

	private RoomAcoustics() {
	}

	/** Hall für eine Jukebox (zwischengespeichert, damit nicht jeden Tick neu gerechnet wird). */
	public static Reverb at(ClientLevel level, BlockPos jukebox, long now) {
		Long t = CACHE_TIME.get(jukebox);
		if (t != null && now - t < CACHE_TICKS) {
			return CACHE.get(jukebox);
		}
		Reverb r = analyse(level, Vec3.atCenterOf(jukebox));
		CACHE.put(jukebox, r);
		CACHE_TIME.put(jukebox, now);
		if (CACHE.size() > 64) {
			CACHE.clear();
			CACHE_TIME.clear();
		}
		return r;
	}

	static Reverb analyse(ClientLevel level, Vec3 center) {
		int hits = 0;
		double distSum = 0;
		double absorbSum = 0;
		for (Vec3 dir : DIRECTIONS) {
			Vec3 p = center;
			BlockPos last = BlockPos.containing(center);
			for (double d = STEP; d <= MAX_DIST; d += STEP) {
				p = center.add(dir.scale(d));
				BlockPos bp = BlockPos.containing(p);
				if (bp.equals(last)) {
					continue;
				}
				last = bp;
				BlockState state = level.getBlockState(bp);
				if (state.isSolidRender() || (!state.getCollisionShape(level, bp).isEmpty() && !state.isAir())) {
					hits++;
					distSum += d;
					absorbSum += absorption(state);
					break;
				}
			}
		}
		if (hits == 0) {
			return Reverb.NONE;
		}
		double enclosure = hits / (double) RAYS;     // 1 = rundum Wände, 0 = freies Feld
		double avgDist = distSum / hits;             // typische Raumgröße
		double absorb = absorbSum / hits;            // 0 = Stein, 1 = Wolle

		// Halldauer: wächst mit der Raumgröße, schrumpft mit weichen Wänden und offenen Seiten
		double decay = (0.25 + avgDist * 0.17) * (1 - absorb * 0.8) * (0.3 + 0.7 * enclosure * enclosure);
		decay = Math.max(0.1, Math.min(6.0, decay));
		// Hall-Lautstärke: nur in geschlossenen, harten Räumen deutlich
		double gain = Math.pow(enclosure, 1.5) * (1 - absorb * 0.85) * 0.55;
		double delay = Math.min(0.3, avgDist / 343.0 * 2);
		double gainHf = 0.9 - absorb * 0.7;
		return new Reverb((float) decay, (float) Math.max(0, Math.min(1, gain)), (float) delay,
				(float) Math.max(0.05, Math.min(1, gainHf)));
	}

	/** Wie stark ein Block Schall schluckt (0 = hart wie Stein, 1 = weich wie Wolle). */
	private static double absorption(BlockState state) {
		if (state.is(BlockTags.WOOL) || state.is(BlockTags.WOOL_CARPETS)) {
			return 1.0;
		}
		if (state.is(BlockTags.LEAVES) || state.is(Blocks.HAY_BLOCK) || state.is(Blocks.MOSS_BLOCK)
				|| state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.SPONGE) || state.is(Blocks.WET_SPONGE)) {
			return 0.8;
		}
		if (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)) {
			return 0.5;
		}
		if (state.is(BlockTags.PLANKS) || state.is(BlockTags.LOGS) || state.is(BlockTags.WOODEN_SLABS)
				|| state.is(BlockTags.WOODEN_STAIRS)) {
			return 0.3;
		}
		return 0.05; // Stein, Beton, Glas, Metall, ...
	}

	private static Vec3[] fibonacciSphere(int n) {
		Vec3[] out = new Vec3[n];
		double golden = Math.PI * (3 - Math.sqrt(5));
		for (int i = 0; i < n; i++) {
			double y = 1 - (i + 0.5) * 2 / n;
			double r = Math.sqrt(1 - y * y);
			double theta = golden * i;
			out[i] = new Vec3(Math.cos(theta) * r, y, Math.sin(theta) * r);
		}
		return out;
	}
}
