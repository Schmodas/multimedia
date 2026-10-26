package de.schmodas.multimedia;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Die zwei Hotbars der Jukebox-Ansicht als echte Teile der Spielwelt: Lage an den hinteren Kanten,
 * Inhalt (Platten, Knöpfe), was die Maus gerade trifft. LeanRender zeichnet sie, LeanScreen nimmt die Klicks.
 */
public final class LeanPanels {
	public static final int VISIBLE = 6;
	/** Eine Hotbar ist genau eine Blockkante lang, die Felder sind quadratisch. */
	public static final double CELL = 1.0 / VISIBLE;
	/** Abstand zwischen Jukebox und Hotbar, in Blöcken. */
	static final double FLOAT = 0.15;

	public record Control(String icon, String label, boolean active, Runnable run) {
	}

	/**
	 * Eine Hotbar in der Welt: linke obere Ecke, nach rechts (ganze Länge), nach unten (Feldhöhe),
	 * und die Seite, auf der die Kamera ist (Vorderseite).
	 */
	public record Strip(Vec3 topLeft, Vec3 right, Vec3 down, Vec3 front) {
		public Vec3 cellOrigin(int i) {
			return topLeft.add(right.scale(i / (double) VISIBLE));
		}

		public Vec3 cellRight() {
			return right.scale(1.0 / VISIBLE);
		}
	}

	/** Was gerade angezeigt wird (vom Bildschirm jedes Bild aktualisiert). */
	static volatile List<Integer> discs = List.of();
	static volatile List<Control> controls = List.of();
	static volatile int scroll;
	static volatile int hoverDisc = -1;
	static volatile int hoverControl = -1;

	private LeanPanels() {
	}

	// ------------------------------------------------------------------ Inhalt

	static void refresh(Minecraft mc, BlockPos jukebox) {
		List<Integer> d = new ArrayList<>();
		if (mc.player != null) {
			for (int[] s : LeanView.discSlots(mc.player)) {
				d.add(s[0]);
			}
		}
		discs = d;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, d.size() - VISIBLE)));
		controls = controls(mc, jukebox);
	}

	private static List<Control> controls(Minecraft mc, BlockPos jukebox) {
		List<Control> out = new ArrayList<>();
		if (mc.level == null) {
			return out;
		}
		boolean hasRecord = LeanView.hasRecord(mc.level.getBlockState(jukebox));
		BlockPos sign = LeanView.signOf(mc.level, jukebox);
		RadioManager.SignInfo info = RadioManager.infoAt(mc.level, jukebox);
		Source src = info == null ? null : info.source();
		boolean play = src != null && hasRecord;
		boolean paused = src != null && src.isPaused();
		out.add(new Control(paused ? "▶" : "⏸", paused ? T.t("Weiter") : T.t("Pause"), play,
				() -> LeanView.signAction(mc, paused ? "resume" : "pause")));
		out.add(new Control("⟲", T.t("Neustart"), play, () -> LeanView.signAction(mc, "restart")));
		boolean list = play && src.playlist();
		out.add(new Control("◀◀", T.t("Vorheriger Titel"), list, () -> LeanView.signAction(mc, "prev")));
		out.add(new Control("▶▶", T.t("Nächster Titel"), list, () -> LeanView.signAction(mc, "next")));
		out.add(new Control("⏏", T.t("Platte rausnehmen"), hasRecord, () -> LeanView.eject(mc)));
		out.add(new Control("✎", sign == null ? T.t("Kein Schild an der Jukebox") : T.t("Schild bearbeiten"), sign != null,
				() -> LeanView.signAction(mc, null)));
		return out;
	}

	static String discName(ItemStack stack) {
		return JukeboxSong.fromStack(stack).map(h -> h.value().description().getString())
				.filter(s -> !s.isBlank()).orElse(stack.getHoverName().getString());
	}

	// ------------------------------------------------------------------ Lage

	/**
	 * Die hinteren Kanten der Oberseite (bzw. Unterseite bei Decken-Jukeboxen), von der Kamera aus gesehen.
	 * [0] = Platten, [1] = Knöpfe. Schräg: Platten rechts hinten, Knöpfe links hinten.
	 * Gerade davor: beide an der Hinterkante, Knöpfe über den Platten.
	 * height: 0..1, wie weit die Hotbars schon "ausgeklappt" sind.
	 */
	public static Strip[] strips(Camera cam, BlockPos jukebox, float height) {
		Vec3 camPos = cam.position();
		Vector3f r = new Vector3f(1, 0, 0).rotate(cam.rotation());
		Vec3 camRight = new Vec3(r.x, r.y, r.z);
		Vec3 c = Vec3.atCenterOf(jukebox);
		boolean below = camPos.y < c.y; // Kamera unter der Mitte: Hotbars hängen an der Unterkante
		double faceY = below ? jukebox.getY() - 0.002 : jukebox.getY() + 1.002;
		Vec3 away = new Vec3(c.x - camPos.x, 0, c.z - camPos.z);
		away = away.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : away.normalize();
		if (below) {
			// Decken-Jukebox: an die vordere Unterkante (zwischen Spieler und Jukebox), dort sind sie groß und lesbar
			away = away.scale(-1);
		}
		Vec3[] normals = {new Vec3(0, 0, -1), new Vec3(0, 0, 1), new Vec3(-1, 0, 0), new Vec3(1, 0, 0)};
		int best = 0;
		for (int i = 1; i < 4; i++) {
			if (normals[i].dot(away) > normals[best].dot(away)) {
				best = i;
			}
		}
		int second = -1;
		for (int i = 0; i < 4; i++) {
			if (i != best && (second < 0 || normals[i].dot(away) > normals[second].dot(away))) {
				second = i;
			}
		}
		double h = CELL * Math.max(0.02, height);
		if (normals[best].dot(away) > 0.9) {
			Strip discs = strip(camPos, camRight, c, faceY, normals[best], 0, h, below);
			Strip buttons = strip(camPos, camRight, c, faceY, normals[best], CELL * 1.1 * height, h, below);
			return reachable(camPos, new Strip[]{discs, buttons}, below);
		}
		Strip a = strip(camPos, camRight, c, faceY, normals[best], 0, h, below);
		Strip b = strip(camPos, camRight, c, faceY, normals[second], 0, h, below);
		double ma = a.topLeft.add(a.right.scale(0.5)).subtract(camPos).dot(camRight);
		double mb = b.topLeft.add(b.right.scale(0.5)).subtract(camPos).dot(camRight);
		return reachable(camPos, ma >= mb ? new Strip[]{a, b} : new Strip[]{b, a}, below);
	}

	/** So weit weg dürfen die Hotbars höchstens sein (Blöcke), sonst kommen sie näher. */
	private static final double MAX_REACH = 1.8;

	/**
	 * Hängt die Jukebox weit über einem (z. B. 5 Blöcke), wären die Hotbars winzig und kaum zu treffen:
	 * dann schweben sie auf bequemer Entfernung zwischen Spieler und Jukebox (gleiche Lage, nur näher).
	 */
	private static Strip[] reachable(Vec3 cam, Strip[] strips, boolean below) {
		if (!below) {
			return strips;
		}
		Vec3 mid = strips[0].topLeft.add(strips[0].right.scale(0.5)).add(strips[0].down.scale(0.5));
		double d = mid.distanceTo(cam);
		if (d <= MAX_REACH) {
			return strips;
		}
		Vec3 toCam = cam.subtract(mid).normalize();
		Vec3 newMid = mid.add(toCam.scale(d - MAX_REACH));
		// zur Kamera drehen (sonst sieht man sie von unten nur als Strich): links/rechts bleibt waagerecht
		Vec3 rightU = strips[0].right.normalize();
		Vec3 oldDownU = strips[0].down.normalize();
		Vec3 newDownU = rightU.cross(toCam).normalize();
		if (newDownU.y > 0) {
			newDownU = newDownU.scale(-1);
		}
		Strip[] out = new Strip[strips.length];
		for (int i = 0; i < strips.length; i++) {
			Strip s = strips[i];
			Vec3 c = s.topLeft.add(s.right.scale(0.5)).add(s.down.scale(0.5));
			Vec3 rel = c.subtract(mid);
			Vec3 nc = newMid.add(rightU.scale(rel.dot(rightU))).add(newDownU.scale(rel.dot(oldDownU)));
			Vec3 down = newDownU.scale(s.down.length());
			out[i] = new Strip(nc.subtract(s.right.scale(0.5)).subtract(down.scale(0.5)), s.right, down, toCam);
		}
		return out;
	}

	/** Hotbar, die auf einer Kante steht (bzw. bei Decken-Jukeboxen darunter hängt), links/rechts wie man es sieht. */
	private static Strip strip(Vec3 cam, Vec3 camRight, Vec3 c, double faceY, Vec3 normal, double lift, double h, boolean below) {
		Vec3 along = new Vec3(-normal.z, 0, normal.x);
		if (along.dot(camRight) < 0) {
			along = along.scale(-1);
		}
		// schwebt etwas über der Kante statt direkt draufzustehen
		double floatUp = FLOAT * Math.min(1, h / (CELL * 0.5));
		Vec3 mid = new Vec3(c.x + normal.x * 0.5, faceY + (below ? -(lift + floatUp) : lift + floatUp), c.z + normal.z * 0.5);
		Vec3 left = mid.subtract(along.scale(0.5));
		// leicht nach hinten geneigt, damit man gut draufschaut
		Vec3 up = new Vec3(normal.x * 0.12, 1, normal.z * 0.12).normalize().scale(h);
		Vec3 topLeft = below ? left : left.add(up);
		Vec3 down = below ? up.scale(-1) : up.scale(-1);
		if (below) {
			topLeft = left; // hängt: oben ist die Kante
			down = new Vec3(normal.x * 0.12, -1, normal.z * 0.12).normalize().scale(h);
		}
		Vec3 front = cam.subtract(mid).normalize();
		return new Strip(topLeft, along, down, front);
	}

	// ------------------------------------------------------------------ Maus

	/** Strahl von der Kamera durch einen Bildschirmpunkt (GUI-Koordinaten). */
	static Vec3 ray(Camera cam, double mx, double my, int width, int height) {
		float f = 1f / (float) Math.tan(Math.toRadians(cam.getFov()) / 2);
		float aspect = (float) width / height;
		float xn = (float) (mx / width * 2 - 1);
		float yn = (float) (1 - my / height * 2);
		Vector3f v = new Vector3f(xn * aspect / f, yn / f, -1).rotate(cam.rotation());
		return new Vec3(v.x, v.y, v.z).normalize();
	}

	/** Welches Feld der Hotbar der Strahl trifft (0..5), oder -1. */
	static int hit(Strip s, Vec3 from, Vec3 dir) {
		Vec3 r = s.cellRight();
		Vec3 n = r.cross(s.down);
		double denom = dir.dot(n);
		if (Math.abs(denom) < 1e-6) {
			return -1;
		}
		double t = s.topLeft.subtract(from).dot(n) / denom;
		if (t <= 0) {
			return -1;
		}
		Vec3 p = from.add(dir.scale(t)).subtract(s.topLeft);
		double u = p.dot(s.right) / s.right.lengthSqr();
		double v = p.dot(s.down) / s.down.lengthSqr();
		if (u < 0 || u >= 1 || v < 0 || v > 1) {
			return -1;
		}
		// Felder liegen wie in der normalen Hotbar alle 20 Pixel, mit 1 Pixel Rand links
		double px = u * LeanRender.W;
		return (int) Math.max(0, Math.min(VISIBLE - 1, Math.floor((px - 1) / 20)));
	}
}
