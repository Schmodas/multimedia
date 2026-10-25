package de.schmodas.multimedia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Was man an einer spielenden Radio-Jukebox sieht (nur Spieler mit Mod):
 * - der aktuelle Titel schwebt über der Jukebox (bzw. über dem Schild darauf)
 * - Notenpartikel kommen im Takt der Musik statt einmal pro Sekunde; die Farbe
 *   hängt davon ab, ob gerade eher Bass oder eher Höhen zu hören sind.
 * Die Titel sind nur bei uns erzeugte Text-Displays, der Server weiß nichts davon.
 */
public final class JukeboxVisuals {
	/** Bis zu dieser Entfernung kommen Noten. */
	private static final double NOTE_DISTANCE = 48;
	private static final int MAX_TITLE = 48;
	/** Eigene IDs weit weg von denen des Servers. */
	private static int nextId = -1_000_000;

	private static final class Label {
		final Display.TextDisplay entity;
		String text;

		Label(Display.TextDisplay entity) {
			this.entity = entity;
		}
	}

	private static final Map<BlockPos, Label> LABELS = new HashMap<>();
	private static ClientLevel lastLevel;

	private JukeboxVisuals() {
	}

	public static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level != lastLevel) {
			LABELS.clear(); // alte Welt ist weg, ihre Entities auch
			lastLevel = level;
		}
		if (level == null || mc.player == null) {
			return;
		}
		Set<BlockPos> seen = new HashSet<>();
		Vec3 me = mc.player.position();
		for (RadioManager.Playing p : RadioManager.playing(mc)) {
			if (!p.audible()) {
				continue;
			}
			seen.add(p.pos());
			double top = topOf(level, p.pos());
			if (!Settings.hideTitles() && !p.pos().equals(LeanView.jukebox())) { // in der Jukebox-Ansicht steht der Titel oben
				updateLabel(level, p.pos(), labelText(p), top);
			}
			if (!mc.isPaused() && Settings.notes() > 0 && Vec3.atCenterOf(p.pos()).distanceToSqr(me) < NOTE_DISTANCE * NOTE_DISTANCE) {
				notes(level, p, top);
			}
		}
		// Lautsprecher, zu deren Kanal es keine Haupt-Quelle in der Nähe gibt; Jukeboxen mit Fehler
		if (!Settings.hideTitles()) {
			double max = Settings.titleDistance();
			RadioManager.failed().forEach((pos, error) -> {
				if (!seen.contains(pos) && Vec3.atCenterOf(pos).distanceToSqr(me) < max * max) {
					seen.add(pos);
					updateLabel(level, pos, "§c⚠ " + shorten(error), topOf(level, pos));
				}
			});
			RadioManager.speakersWithoutSender().forEach((pos, channel) -> {
				if (!seen.contains(pos) && level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.JUKEBOX)
						&& Vec3.atCenterOf(pos).distanceToSqr(me) < max * max) {
					seen.add(pos);
					updateLabel(level, pos, "§7#" + channel + T.t(": kein Sender"), topOf(level, pos));
				}
			});
		}
		// Titel von Jukeboxen entfernen, die nicht mehr spielen
		Iterator<Map.Entry<BlockPos, Label>> it = LABELS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<BlockPos, Label> e = it.next();
			if (!seen.contains(e.getKey()) || Settings.hideTitles() || e.getKey().equals(LeanView.jukebox())) {
				remove(level, e.getValue());
				it.remove();
			}
		}
	}

	public static void clear(Minecraft mc) {
		if (mc.level != null) {
			LABELS.values().forEach(l -> remove(mc.level, l));
		}
		LABELS.clear();
	}

	/** Höhe über der Jukebox, ab der frei ist (Schild oder anderer Block obendrauf wird übersprungen). */
	private static double topOf(ClientLevel level, BlockPos pos) {
		BlockPos above = pos.above();
		BlockState state = level.getBlockState(above);
		return state.isAir() ? above.getY() : above.getY() + 1;
	}

	/** Was über einer spielenden Jukebox steht: Titel, Fehler oder "lädt …". */
	private static String labelText(RadioManager.Playing p) {
		if (p.error() != null) {
			return "§c⚠ " + shorten(p.error());
		}
		if (p.title() != null) {
			return "♪ " + shorten(p.title());
		}
		if (p.spec().startsWith("live:")) {
			return "♪ Live: " + p.spec().substring(5);
		}
		return "§7" + T.t("lädt …");
	}

	private static void updateLabel(ClientLevel level, BlockPos pos, String text, double top) {
		Label label = LABELS.get(pos);
		if (label == null) {
			Display.TextDisplay e = new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level);
			e.setId(nextId--);
			e.setBillboardConstraints(Display.BillboardConstraints.CENTER);
			e.setFlags(Display.TextDisplay.FLAG_SHADOW);
			e.setPos(pos.getX() + 0.5, top + 0.35, pos.getZ() + 0.5);
			level.addEntity(e);
			label = new Label(e);
			LABELS.put(pos, label);
		}
		label.entity.setPos(pos.getX() + 0.5, top + 0.35, pos.getZ() + 0.5);
		// Einstellungen (ändern sich nur, wenn sie wirklich anders sind)
		label.entity.setViewRange(Settings.titleDistance() / 64F);
		label.entity.setBackgroundColor(Settings.titleBackground() ? 0x60000000 : 0);
		if (!text.equals(label.text)) {
			label.text = text;
			label.entity.setText(Component.literal(text));
		}
	}

	private static void remove(ClientLevel level, Label label) {
		level.removeEntity(label.entity.getId(), Entity.RemovalReason.DISCARDED);
	}

	private static String shorten(String title) {
		return title.length() <= MAX_TITLE ? title : title.substring(0, MAX_TITLE - 1).trim() + "…";
	}

	private static void notes(ClientLevel level, RadioManager.Playing p, double top) {
		StreamHub.Beat beat = StreamHub.beat(p.spec());
		if (beat.strength() <= 0) {
			return;
		}
		// Farbe: Bass = grün/gelb, Höhen = blau/lila (wie die Farben beim Notenblock)
		double color = 0.15 + beat.brightness() * 0.6 + (level.getRandom().nextDouble() - 0.5) * 0.08;
		int amount = Settings.notes();
		if (amount == 1 && beat.strength() < 1.25F) {
			return; // wenig: nur deutliche Beats
		}
		int count = (beat.strength() >= 1.6 ? 2 : 1) + (amount == 3 ? 1 : 0);
		for (int i = 0; i < count; i++) {
			double x = p.pos().getX() + 0.5 + (level.getRandom().nextDouble() - 0.5) * 0.5;
			double z = p.pos().getZ() + 0.5 + (level.getRandom().nextDouble() - 0.5) * 0.5;
			level.addParticle(ParticleTypes.NOTE, x, top + 0.2, z, color, 0, 0);
		}
	}
}
