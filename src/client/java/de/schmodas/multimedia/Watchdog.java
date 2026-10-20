package de.schmodas.multimedia;

import net.minecraft.client.Minecraft;

/**
 * Zur Fehlersuche: Hängt Minecraft länger als 1,5 Sekunden, steht im Log, wo es hängt (welcher Code gerade läuft).
 * Kostet nichts, solange alles flüssig ist.
 */
final class Watchdog {
	private static volatile long lastTick = System.nanoTime();
	private static Thread renderThread;

	private Watchdog() {
	}

	static void tick(Minecraft mc) {
		if (renderThread == null) {
			renderThread = Thread.currentThread();
			Thread t = new Thread(Watchdog::watch, "MultiMedia-Watchdog");
			t.setDaemon(true);
			t.start();
		}
		lastTick = System.nanoTime();
	}

	private static void watch() {
		long reportedFor = 0;
		while (true) {
			P2p.sleep(250);
			long since = lastTick;
			long stalled = (System.nanoTime() - since) / 1_000_000;
			if (stalled > 1500 && reportedFor != since && Minecraft.getInstance().level != null) {
				reportedFor = since;
				StringBuilder sb = new StringBuilder();
				StackTraceElement[] st = renderThread.getStackTrace();
				for (int i = 0; i < Math.min(st.length, 30); i++) {
					sb.append("\n    at ").append(st[i]);
				}
				MultiMediaClient.LOGGER.warn("Ruckler: Minecraft hängt seit {} ms, gerade hier:{}", stalled, sb);
				// auch den Ton-Thread zeigen (dort wartet Minecraft oft)
				for (var e : Thread.getAllStackTraces().entrySet()) {
					if (e.getKey().getName().contains("Sound")) {
						StringBuilder s2 = new StringBuilder();
						StackTraceElement[] st2 = e.getValue();
						for (int i = 0; i < Math.min(st2.length, 15); i++) {
							s2.append("\n    at ").append(st2[i]);
						}
						MultiMediaClient.LOGGER.warn("Ruckler: Thread {}:{}", e.getKey().getName(), s2);
					}
				}
			}
		}
	}
}
