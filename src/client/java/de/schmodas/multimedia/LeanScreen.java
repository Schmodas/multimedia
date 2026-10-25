package de.schmodas.multimedia;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Bedienfeld, während man über der Jukebox lehnt (siehe LeanView): zwei Leisten, die an den hinteren Kanten
 * der Jukebox schweben – eine mit den Platten aus dem Inventar, eine mit kleinen Knöpfen.
 * Gezeichnet wird in der Bildschirm-Ebene, aber so verzerrt, dass es auf der Jukebox zu stehen scheint.
 */
public class LeanScreen extends Screen {
	private static final int SLOT = 20;
	private static final int GAP = 2;
	private static final int VISIBLE = 6;
	/** Größe einer Leiste in Bildpunkten (für die Zeichnung) bzw. in Blöcken (in der Welt). */
	private static final int PANEL_W = VISIBLE * (SLOT + GAP) + GAP;
	private static final int PANEL_H = SLOT + 2 * GAP;
	private static final double WORLD_W = 0.92;
	private static final double WORLD_H = WORLD_W * PANEL_H / PANEL_W;

	private final BlockPos jukebox;
	private int scroll;
	/** Beim letzten Zeichnen: Abbildung Leiste -> Bildschirm (null = gerade nicht sichtbar). */
	private Matrix3x2f discMap;
	private Matrix3x2f controlMap;
	private final List<Control> controls = new ArrayList<>();

	private record Control(String icon, String label, boolean active, Runnable run) {
	}

	public LeanScreen(BlockPos jukebox) {
		super(Component.literal(T.t("Jukebox")));
		this.jukebox = jukebox;
	}

	// ------------------------------------------------------------------ Inhalt

	private List<Integer> discs(Minecraft mc) {
		List<Integer> out = new ArrayList<>();
		if (mc.player != null) {
			for (int[] s : LeanView.discSlots(mc.player)) {
				out.add(s[0]);
			}
		}
		return out;
	}

	private List<Control> controls(Minecraft mc) {
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

	private static String discName(ItemStack stack) {
		return JukeboxSong.fromStack(stack).map(h -> h.value().description().getString())
				.filter(s -> !s.isBlank()).orElse(stack.getHoverName().getString());
	}

	// ------------------------------------------------------------------ Lage der Leisten in der Welt

	/** Eine Leiste in der Welt: obere linke Ecke, Richtung nach rechts (ganze Breite), nach unten (ganze Höhe). */
	private record Panel(Vec3 origin, Vec3 right, Vec3 down) {
	}

	/**
	 * Die beiden hinteren Kanten der Oberseite (bzw. Unterseite bei Decken-Jukeboxen), von der Kamera aus gesehen.
	 * [0] = Platten (rechte Kante), [1] = Knöpfe (linke Kante). Schaut man gerade auf eine Seite, stehen beide
	 * an derselben Kante übereinander.
	 */
	private Panel[] panels(Vec3 cam) {
		Vec3 c = Vec3.atCenterOf(jukebox);
		boolean below = cam.y < c.y - 0.5;
		double faceY = below ? jukebox.getY() - 0.02 : jukebox.getY() + 1.02;
		Vec3 away = new Vec3(c.x - cam.x, 0, c.z - cam.z);
		if (away.lengthSqr() < 1e-4) {
			away = new Vec3(0, 0, 1);
		}
		away = away.normalize();
		Vec3[] normals = {new Vec3(0, 0, -1), new Vec3(0, 0, 1), new Vec3(-1, 0, 0), new Vec3(1, 0, 0)};
		int best = 0;
		int second = -1;
		for (int i = 1; i < 4; i++) {
			if (normals[i].dot(away) > normals[best].dot(away)) {
				best = i;
			}
		}
		for (int i = 0; i < 4; i++) {
			if (i != best && (second < 0 || normals[i].dot(away) > normals[second].dot(away))) {
				second = i;
			}
		}
		// Neigung: oben etwas nach hinten
		double tilt = 0.25;
		boolean straight = normals[best].dot(away) > 0.9;
		if (straight) {
			Panel lower = panelOnEdge(c, faceY, normals[best], cam, 0, tilt, below);
			Panel upper = panelOnEdge(c, faceY, normals[best], cam, WORLD_H * 1.25, tilt, below);
			return new Panel[]{upper, lower};
		}
		Panel a = panelOnEdge(c, faceY, normals[best], cam, 0, tilt, below);
		Panel b = panelOnEdge(c, faceY, normals[second], cam, 0, tilt, below);
		// Platten auf die rechte Kante (vom Betrachter aus)
		Vec3 rightOfView = new Vec3(-away.z, 0, away.x); // rechts, wenn man in "away" schaut
		Vec3 midA = a.origin.add(a.right.scale(0.5));
		Vec3 midB = b.origin.add(b.right.scale(0.5));
		return midA.subtract(c).dot(rightOfView) >= midB.subtract(c).dot(rightOfView) ? new Panel[]{a, b} : new Panel[]{b, a};
	}

	/** Leiste, die auf einer Kante steht (bzw. bei Decken-Jukeboxen von ihr hängt). */
	private static Panel panelOnEdge(Vec3 c, double faceY, Vec3 normal, Vec3 cam, double lift, double tilt, boolean below) {
		Vec3 along = new Vec3(-normal.z, 0, normal.x);
		Vec3 edgeMid = new Vec3(c.x + normal.x * 0.5, faceY, c.z + normal.z * 0.5);
		Vec3 a = edgeMid.subtract(along.scale(WORLD_W / 2));
		// links nach rechts so, wie die Kamera es sieht
		Vec3 toCam = cam.subtract(edgeMid);
		Vec3 right = along.scale(WORLD_W);
		if (new Vec3(-toCam.z, 0, toCam.x).dot(along) > 0) { // along zeigt nach links -> umdrehen
			a = edgeMid.add(along.scale(WORLD_W / 2));
			right = along.scale(-WORLD_W);
		}
		double sign = below ? -1 : 1;
		Vec3 up = new Vec3(normal.x * tilt, 1, normal.z * tilt).normalize().scale(sign);
		Vec3 bottomLeft = a.add(new Vec3(0, sign * lift, 0));
		Vec3 topLeft = bottomLeft.add(up.scale(WORLD_H));
		if (below) {
			// hängt nach unten: "oben" der Leiste ist an der Kante
			return new Panel(bottomLeft, right, up.scale(WORLD_H));
		}
		return new Panel(topLeft, right, up.scale(-WORLD_H));
	}

	/** Welt -> Bildschirm (GUI-Pixel); null, wenn hinter der Kamera. */
	private Vector2f project(Camera cam, Vec3 p) {
		Vec3 cp = cam.position();
		Vector3f v = new Vector3f((float) (p.x - cp.x), (float) (p.y - cp.y), (float) (p.z - cp.z));
		new Quaternionf(cam.rotation()).conjugate().transform(v);
		if (v.z > -0.05f) {
			return null;
		}
		float f = 1f / (float) Math.tan(Math.toRadians(cam.getFov()) / 2);
		float aspect = (float) this.width / this.height;
		float xn = (v.x / -v.z) * f / aspect;
		float yn = (v.y / -v.z) * f;
		return new Vector2f((xn + 1) / 2 * this.width, (1 - yn) / 2 * this.height);
	}

	/** Abbildung Leisten-Pixel -> Bildschirm für eine Leiste (affin genähert), oder null. */
	private Matrix3x2f map(Camera cam, Panel panel) {
		Vector2f o = project(cam, panel.origin);
		Vector2f r = project(cam, panel.origin.add(panel.right));
		Vector2f d = project(cam, panel.origin.add(panel.down));
		if (o == null || r == null || d == null) {
			return null;
		}
		return new Matrix3x2f((r.x - o.x) / PANEL_W, (r.y - o.y) / PANEL_W, (d.x - o.x) / PANEL_H, (d.y - o.y) / PANEL_H, o.x, o.y);
	}

	/** Bildschirm -> Leisten-Pixel. */
	private static Vector2f local(Matrix3x2f m, double x, double y) {
		if (m == null) {
			return null;
		}
		Matrix3x2f inv = new Matrix3x2f(m).invert();
		Vector2f v = new Vector2f((float) x, (float) y);
		inv.transformPosition(v);
		return v;
	}

	/** Welches Feld (0..5) unter der Maus liegt, oder -1. */
	private static int slotAt(Matrix3x2f m, double mx, double my) {
		Vector2f l = local(m, mx, my);
		if (l == null || l.y < GAP || l.y > GAP + SLOT || l.x < GAP) {
			return -1;
		}
		int i = (int) ((l.x - GAP) / (SLOT + GAP));
		float inSlot = (l.x - GAP) - i * (SLOT + GAP);
		return i < VISIBLE && inSlot <= SLOT ? i : -1;
	}

	// ------------------------------------------------------------------ Zeichnen

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// nichts abdunkeln: man soll die Jukebox sehen
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		Minecraft mc = Minecraft.getInstance();
		discMap = null;
		controlMap = null;
		if (mc.player == null || mc.level == null) {
			return;
		}
		g.centeredText(this.font, Component.literal(nowPlaying(mc)), this.width / 2, 10, 0xFFFFFFFF);
		g.centeredText(this.font, Component.literal(T.t("Esc – zurück")), this.width / 2, this.height - 14, 0x99FFFFFF);
		if (LeanView.lean() < 0.9f) {
			return; // erst zeigen, wenn man sich vorgebeugt hat
		}
		Camera cam = mc.gameRenderer.mainCamera();
		Panel[] p = panels(cam.position());
		discMap = map(cam, p[0]);
		controlMap = map(cam, p[1]);
		controls.clear();
		controls.addAll(controls(mc));

		// ---- Platten
		List<Integer> discs = discs(mc);
		scroll = Math.max(0, Math.min(scroll, Math.max(0, discs.size() - VISIBLE)));
		if (discMap != null) {
			int hover = slotAt(discMap, mouseX, mouseY);
			g.pose().pushMatrix();
			g.pose().mul(discMap);
			frame(g);
			String label = discs.isEmpty() ? T.t("Keine Platten im Inventar") : null;
			for (int i = 0; i < VISIBLE; i++) {
				int x = GAP + i * (SLOT + GAP);
				int idx = scroll + i;
				boolean has = idx < discs.size();
				slot(g, x, has && i == hover);
				if (has) {
					ItemStack stack = mc.player.getInventory().getItem(discs.get(idx));
					g.item(stack, x + 2, GAP + 2);
					if (i == hover) {
						label = discName(stack);
					}
				}
			}
			if (scroll > 0) {
				g.text(this.font, "◀", -8, GAP + 6, 0xFFFFFFFF);
			}
			if (scroll + VISIBLE < discs.size()) {
				g.text(this.font, "▶", PANEL_W + 2, GAP + 6, 0xFFFFFFFF);
			}
			if (label != null) {
				labelAbove(g, label);
			}
			g.pose().popMatrix();
		}

		// ---- Knöpfe
		if (controlMap != null) {
			int hover = slotAt(controlMap, mouseX, mouseY);
			g.pose().pushMatrix();
			g.pose().mul(controlMap);
			frame(g);
			for (int i = 0; i < controls.size() && i < VISIBLE; i++) {
				Control c = controls.get(i);
				int x = GAP + i * (SLOT + GAP);
				slot(g, x, c.active && i == hover);
				int w = this.font.width(c.icon);
				g.text(this.font, c.icon, x + (SLOT - w) / 2 + 1, GAP + 6, c.active ? 0xFFFFFFFF : 0xFF666666);
				if (i == hover) {
					labelAbove(g, c.label);
				}
			}
			g.pose().popMatrix();
		}
	}

	private void frame(GuiGraphicsExtractor g) {
		g.fill(0, 0, PANEL_W, PANEL_H, 0xAA101010);
		g.outline(0, 0, PANEL_W, PANEL_H, 0xFFE0E0E0);
	}

	private void slot(GuiGraphicsExtractor g, int x, boolean hover) {
		g.fill(x, GAP, x + SLOT, GAP + SLOT, hover ? 0xCC5A5A5A : 0xAA2A2A2A);
		g.outline(x, GAP, SLOT, SLOT, hover ? 0xFFFFFFFF : 0xFFAAAAAA);
	}

	/** Schild über der Leiste, wie in der Zeichnung ("Music disc"). */
	private void labelAbove(GuiGraphicsExtractor g, String text) {
		int w = Math.min(this.font.width(text) + 8, PANEL_W + 40);
		int x = 2;
		g.fill(x, -14, x + w, -2, 0xCC101010);
		g.outline(x, -14, w, 12, 0xFFE0E0E0);
		g.text(this.font, text, x + 4, -12, 0xFFFFFFFF);
	}

	/** Oben: was läuft. */
	private String nowPlaying(Minecraft mc) {
		for (RadioManager.Playing p : RadioManager.playing(mc)) {
			if (p.pos().equals(jukebox)) {
				if (p.error() != null) {
					return "⚠ " + p.error();
				}
				return p.title() != null ? "♪ " + p.title() : T.t("lädt …");
			}
		}
		RadioManager.SignInfo info = RadioManager.infoAt(mc.level, jukebox);
		if (!LeanView.hasRecord(mc.level.getBlockState(jukebox))) {
			return T.t("Keine Platte drin");
		}
		if (info != null && info.source() != null && info.source().isPaused()) {
			return T.t("⏸ Angehalten");
		}
		if (info != null && info.source() == null && info.channel() != null) {
			return T.t("Lautsprecher für #") + info.channel();
		}
		return "♪";
	}

	// ------------------------------------------------------------------ Eingabe

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		Minecraft mc = Minecraft.getInstance();
		int d = slotAt(discMap, event.x(), event.y());
		if (d >= 0) {
			List<Integer> discs = discs(mc);
			if (scroll + d < discs.size()) {
				LeanView.choose(mc, discs.get(scroll + d));
				return true;
			}
		}
		int c = slotAt(controlMap, event.x(), event.y());
		if (c >= 0 && c < controls.size() && controls.get(c).active) {
			controls.get(c).run.run();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		scroll -= (int) Math.signum(scrollY);
		return true;
	}

	@Override
	public void onClose() {
		LeanView.close();
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
