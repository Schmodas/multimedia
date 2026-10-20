package de.schmodas.multimedia;

import net.minecraft.client.Minecraft;

/**
 * Uhr, die stehen bleibt, solange das Spiel pausiert ist (Einzelspieler-Menü).
 * Dann bleiben auch die Jukeboxen synchron, die erst nach einer Pause dazukommen.
 */
public final class PauseClock {
	private static volatile long pausedTotal;
	private static volatile long pauseStart;

	private PauseClock() {
	}

	public static void tick(Minecraft mc) {
		long now = System.nanoTime();
		if (mc.isPaused()) {
			if (pauseStart == 0) {
				pauseStart = now;
			}
		} else if (pauseStart != 0) {
			pausedTotal += now - pauseStart;
			pauseStart = 0;
		}
	}

	public static long now() {
		long now = System.nanoTime();
		long ps = pauseStart;
		return now - pausedTotal - (ps != 0 ? now - ps : 0);
	}
}
