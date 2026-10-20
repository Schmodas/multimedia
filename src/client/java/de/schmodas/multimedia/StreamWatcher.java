package de.schmodas.multimedia;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/** Verzögerte Aktionen im Spiel-Thread (z.B. Titelanzeige); zählen nur, solange das Spiel nicht pausiert ist. */
public final class StreamWatcher {
	private static final class Delayed {
		int ticksLeft;
		final Runnable action;

		Delayed(int ticksLeft, Runnable action) {
			this.ticksLeft = ticksLeft;
			this.action = action;
		}
	}

	private static final List<Delayed> DELAYED = new ArrayList<>();

	private StreamWatcher() {
	}

	/** Nur im Spiel-Thread aufrufen. */
	public static void later(long delayMs, Runnable action) {
		DELAYED.add(new Delayed((int) Math.max(0, delayMs / 50), action));
	}

	public static void tick(Minecraft mc) {
		if (DELAYED.isEmpty()) {
			return;
		}
		if (mc.level == null) {
			DELAYED.clear();
			return;
		}
		if (mc.isPaused()) {
			return;
		}
		List<Delayed> due = new ArrayList<>();
		DELAYED.removeIf(d -> {
			if (--d.ticksLeft <= 0) {
				due.add(d);
				return true;
			}
			return false;
		});
		due.forEach(d -> d.action.run());
	}
}
