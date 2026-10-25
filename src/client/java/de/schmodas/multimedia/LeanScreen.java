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
 * Gezeichnet wird mit der echten Hotbar-Grafik, Feld für Feld perspektivisch auf die Blockkante gesetzt.
 */
public class LeanScreen extends Screen {
	private static final net.minecraft.resources.Identifier HOTBAR = net.minecraft.resources.Identifier.withDefaultNamespace("hud/hotbar");
	private static final net.minecraft.resources.Identifier SELECTION = net.minecraft.resources.Identifier.withDefaultNamespace("hud/hotbar_selection");
	/** Ein Feld in Bildpunkten, wie in der normalen Hotbar (20 + Rand). */
	private static final int CELL = 22;
	private static final int VISIBLE = 6;
	/** Eine Leiste ist genau eine Blockkante lang, die Felder sind quadratisch. */
	private static final double SLOT_W = 1.0 / VISIBLE;

	private final BlockPos jukebox;
	private int scroll;
	/** Beim letzten Zeichnen: pro Feld die Abbildung Feld-Pixel -> Bildschirm (null = nicht sichtbar). */
	private Matrix3x2f[] discCells = new Matrix3x2f[0];
	private Matrix3x2f[] controlCells = new Matrix3x2f[0];
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

	/** Eine Leiste: linkes und rechtes Ende der Unterkante (auf der Blockkante) und "hoch" (ganze Feldhöhe). */
	private record Strip(Vec3 left, Vec3 right, Vec3 up) {
		/** Feld i: obere linke Ecke, nach rechts, nach unten. */
		Vec3[] cell(int i) {
			Vec3 along = right.subtract(left).scale(1.0 / VISIBLE);
			Vec3 bottomLeft = left.add(along.scale(i));
			boolean hanging = up.y < 0;
			Vec3 topLeft = hanging ? bottomLeft : bottomLeft.add(up);
			Vec3 down = hanging ? up : up.scale(-1);
			return new Vec3[]{topLeft, along, down};
		}
	}

	/**
	 * Die hinteren Kanten der Oberseite (bzw. Unterseite bei Decken-Jukeboxen), von der Kamera aus gesehen.
	 * [0] = Platten, [1] = Knöpfe. Schräg: Platten rechts hinten, Knöpfe links hinten (wie in der Zeichnung).
	 * Gerade davor: beide an der Hinterkante, Knöpfe über den Platten.
	 */
	private Strip[] strips(Camera cam) {
		Vec3 camPos = cam.position();
		Vec3 c = Vec3.atCenterOf(jukebox);
		boolean below = camPos.y < c.y - 0.5;
		double faceY = below ? jukebox.getY() - 0.001 : jukebox.getY() + 1.001;
		Vec3 away = new Vec3(c.x - camPos.x, 0, c.z - camPos.z);
		away = away.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : away.normalize();
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
		double h = SLOT_W;
		if (normals[best].dot(away) > 0.9) {
			Strip discs = strip(cam, c, faceY, normals[best], 0, h, below);
			Strip buttons = strip(cam, c, faceY, normals[best], h * 1.12, h, below);
			return new Strip[]{discs, buttons};
		}
		Strip a = strip(cam, c, faceY, normals[best], 0, h, below);
		Strip b = strip(cam, c, faceY, normals[second], 0, h, below);
		// Platten auf die Kante, die auf dem Bildschirm weiter rechts liegt
		Vector2f pa = project(cam, a.left.add(a.right).scale(0.5));
		Vector2f pb = project(cam, b.left.add(b.right).scale(0.5));
		boolean aRight = pa == null || pb == null || pa.x >= pb.x;
		return aRight ? new Strip[]{a, b} : new Strip[]{b, a};
	}

	/** Leiste auf einer Kante (steht drauf bzw. hängt bei Decken-Jukeboxen darunter), links/rechts wie man es sieht. */
	private Strip strip(Camera cam, Vec3 c, double faceY, Vec3 normal, double lift, double h, boolean below) {
		Vec3 along = new Vec3(-normal.z, 0, normal.x);
		Vec3 mid = new Vec3(c.x + normal.x * 0.5, faceY + (below ? -lift : lift), c.z + normal.z * 0.5);
		Vec3 l = mid.subtract(along.scale(0.5));
		Vec3 r = mid.add(along.scale(0.5));
		Vector2f pl = project(cam, l);
		Vector2f pr = project(cam, r);
		if (pl != null && pr != null && pl.x > pr.x) {
			Vec3 t = l;
			l = r;
			r = t;
		}
		// leicht nach hinten geneigt, damit man gut draufschaut
		Vec3 up = new Vec3(normal.x * 0.15, 1, normal.z * 0.15).normalize().scale(below ? -h : h);
		return new Strip(l, r, up);
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

	/** Abbildung Feld-Pixel (0..CELL) -> Bildschirm für ein Feld. Jedes Feld einzeln: so stimmt die Perspektive. */
	private Matrix3x2f[] cells(Camera cam, Strip strip) {
		Matrix3x2f[] out = new Matrix3x2f[VISIBLE];
		for (int i = 0; i < VISIBLE; i++) {
			Vec3[] c = strip.cell(i);
			Vector2f o = project(cam, c[0]);
			Vector2f r = project(cam, c[0].add(c[1]));
			Vector2f d = project(cam, c[0].add(c[2]));
			if (o == null || r == null || d == null) {
				return null;
			}
			out[i] = new Matrix3x2f((r.x - o.x) / CELL, (r.y - o.y) / CELL, (d.x - o.x) / CELL, (d.y - o.y) / CELL, o.x, o.y);
		}
		return out;
	}

	/** Welches Feld unter der Maus liegt, oder -1. */
	private static int cellAt(Matrix3x2f[] cells, double mx, double my) {
		for (int i = 0; i < cells.length; i++) {
			Vector2f v = new Vector2f((float) mx, (float) my);
			new Matrix3x2f(cells[i]).invert().transformPosition(v);
			if (v.x >= 0 && v.x < CELL && v.y >= 0 && v.y < CELL) {
				return i;
			}
		}
		return -1;
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
		discCells = new Matrix3x2f[0];
		controlCells = new Matrix3x2f[0];
		if (mc.player == null || mc.level == null) {
			return;
		}
		g.centeredText(this.font, Component.literal(nowPlaying(mc)), this.width / 2, 10, 0xFFFFFFFF);
		g.centeredText(this.font, Component.literal(T.t("Esc – zurück")), this.width / 2, this.height - 14, 0x99FFFFFF);
		if (LeanView.lean() < 0.9f) {
			return; // erst zeigen, wenn man sich vorgebeugt hat
		}
		Camera cam = mc.gameRenderer.mainCamera();
		Strip[] s = strips(cam);
		Matrix3x2f[] dc = cells(cam, s[0]);
		Matrix3x2f[] cc = cells(cam, s[1]);
		controls.clear();
		controls.addAll(controls(mc));

		// ---- Platten
		List<Integer> discs = discs(mc);
		scroll = Math.max(0, Math.min(scroll, Math.max(0, discs.size() - VISIBLE)));
		if (dc != null) {
			discCells = dc;
			int hover = cellAt(dc, mouseX, mouseY);
			String label = discs.isEmpty() ? T.t("Keine Platten im Inventar") : null;
			int labelCell = 0;
			for (int i = 0; i < VISIBLE; i++) {
				int idx = scroll + i;
				ItemStack stack = idx < discs.size() ? mc.player.getInventory().getItem(discs.get(idx)) : ItemStack.EMPTY;
				cell(g, dc[i], i, i == hover && !stack.isEmpty(), null, true);
				if (!stack.isEmpty()) {
					g.pose().pushMatrix();
					g.pose().mul(dc[i]);
					g.item(stack, 3, 3);
					g.pose().popMatrix();
					if (i == hover) {
						label = discName(stack);
						labelCell = i;
					}
				}
			}
			if (scroll > 0) {
				arrow(g, dc[0], "◀", -9);
			}
			if (scroll + VISIBLE < discs.size()) {
				arrow(g, dc[VISIBLE - 1], "▶", CELL + 2);
			}
			if (label != null) {
				label(g, dc[labelCell], label);
			}
		}

		// ---- Knöpfe
		if (cc != null) {
			controlCells = cc;
			int hover = cellAt(cc, mouseX, mouseY);
			for (int i = 0; i < VISIBLE && i < controls.size(); i++) {
				Control c = controls.get(i);
				cell(g, cc[i], i, c.active && i == hover, c, c.active);
				if (i == hover) {
					label(g, cc[i], c.label);
				}
			}
		}
	}

	/** Ein Hotbar-Feld (aus der echten Hotbar-Grafik), mit Auswahl-Rahmen, wenn die Maus drauf ist. */
	private void cell(GuiGraphicsExtractor g, Matrix3x2f m, int i, boolean hover, Control control, boolean active) {
		g.pose().pushMatrix();
		g.pose().mul(m);
		g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, HOTBAR, 182, 22, 20 * i, 0, 0, 0, CELL, CELL);
		if (control != null) {
			int w = this.font.width(control.icon);
			g.text(this.font, control.icon, (CELL - w) / 2, 7, active ? 0xFFFFFFFF : 0xFF5A5A5A);
		}
		if (hover) {
			g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, SELECTION, -1, -1, 24, 23);
		}
		g.pose().popMatrix();
	}

	/** Text über einem Feld, wie der Gegenstandsname über der Hotbar. */
	private void label(GuiGraphicsExtractor g, Matrix3x2f m, String text) {
		g.pose().pushMatrix();
		g.pose().mul(m);
		g.text(this.font, text, 0, -11, 0xFFFFFFFF, true);
		g.pose().popMatrix();
	}

	private void arrow(GuiGraphicsExtractor g, Matrix3x2f m, String s, int x) {
		g.pose().pushMatrix();
		g.pose().mul(m);
		g.text(this.font, s, x, 7, 0xFFFFFFFF, true);
		g.pose().popMatrix();
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
		int d = cellAt(discCells, event.x(), event.y());
		if (d >= 0) {
			List<Integer> discs = discs(mc);
			if (scroll + d < discs.size()) {
				LeanView.choose(mc, discs.get(scroll + d));
				return true;
			}
		}
		int c = cellAt(controlCells, event.x(), event.y());
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
