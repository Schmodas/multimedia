package de.schmodas.multimedia;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Zeichnet die Hotbars der Jukebox-Ansicht als gebaute Gegenstände in der Welt: ein durchgehendes Stück
 * mit der Hotbar-Grafik vorne, die Felder als Vertiefungen, echte Seiten- und Rückflächen. Die Farben kommen
 * aus der Hotbar-Grafik selbst, damit es auch mit Ressourcenpaketen passt.
 *
 * Koordinaten in "Hotbar-Pixeln": x nach rechts (0..W), y nach unten (0..22), z zur Kamera hin.
 * Feld i liegt bei x = 20*i .. 20*i+22 wie in der normalen Hotbar.
 */
public final class LeanRender {
	private static final Identifier HOTBAR = Identifier.withDefaultNamespace("textures/gui/sprites/hud/hotbar.png");
	private static final Identifier SELECTION = Identifier.withDefaultNamespace("textures/gui/sprites/hud/hotbar_selection.png");
	/** Breite der Hotbar-Grafik von Minecraft (9 Felder). */
	private static final float TEX_W = 182f;
	private static final float TEX_H = 22f;
	/** Breite unserer Hotbar mit 6 Feldern. */
	public static final float W = 20f * LeanPanels.VISIBLE + 2f;
	private static final float H = 22f;
	/** Dicke der Hotbar und Tiefe der Felder, in Pixeln. */
	private static final float THICK = 3f;
	private static final float DEPTH = 1.5f;
	/** Öffnung eines Feldes (wo in der normalen Hotbar der Gegenstand sitzt). */
	private static final float HOLE0 = 3f;
	private static final float HOLE1 = 19f;
	private static final ItemStackRenderState ITEM = new ItemStackRenderState();

	private LeanRender() {
	}

	public static void render(LevelRenderContext ctx) {
		BlockPos jb = LeanView.jukebox();
		float lean = LeanView.lean();
		Minecraft mc = Minecraft.getInstance();
		if (jb == null || lean < 0.55f || mc.level == null || mc.player == null) {
			return;
		}
		float unfold = Math.min(1f, (lean - 0.55f) / 0.4f);
		Camera cam = ctx.gameRenderer().mainCamera();
		LeanPanels.Strip[] strips = LeanPanels.strips(cam, jb, unfold);
		Vec3 camPos = cam.position();
		boolean below = camPos.y < jb.getY() + 0.5;
		int light = LightCoordsUtil.getLightCoords(mc.level, below ? jb.below() : jb.above());
		// nicht ganz dunkel werden lassen, sonst sieht man im Keller nichts
		light = LightCoordsUtil.pack(Math.max(LightCoordsUtil.block(light), 9), LightCoordsUtil.sky(light));
		PoseStack ps = ctx.poseStack();

		List<Integer> discs = LeanPanels.discs;
		List<LeanPanels.Control> controls = LeanPanels.controls;
		int scroll = LeanPanels.scroll;

		body(ctx, ps, camPos, strips[0], light);
		body(ctx, ps, camPos, strips[1], light);

		for (int i = 0; i < LeanPanels.VISIBLE; i++) {
			// ---- Platten
			ItemStack stack = scroll + i < discs.size() ? mc.player.getInventory().getItem(discs.get(scroll + i)) : ItemStack.EMPTY;
			if (LeanPanels.hoverDisc == i && !stack.isEmpty()) {
				selection(ctx, ps, camPos, strips[0], i);
			}
			if (!stack.isEmpty()) {
				item(ctx, ps, camPos, strips[0], i, stack, light);
			}
			// ---- Knöpfe
			LeanPanels.Control c = i < controls.size() ? controls.get(i) : null;
			if (c != null && c.active() && LeanPanels.hoverControl == i) {
				selection(ctx, ps, camPos, strips[1], i);
			}
			if (c != null && unfold > 0.6f) {
				icon(ctx, ps, camPos, strips[1], slotCenter(i), -DEPTH + 0.1f, c.icon(), c.active() ? 0xFFFFFFFF : 0xFF5A5A5A);
			}
		}
		if (unfold < 1) {
			return;
		}
		// Name über dem Feld, auf das man zeigt
		int hd = LeanPanels.hoverDisc;
		if (hd >= 0 && scroll + hd < discs.size()) {
			label(ctx, ps, camPos, strips[0], hd, LeanPanels.discName(mc.player.getInventory().getItem(discs.get(scroll + hd))));
		} else if (discs.isEmpty()) {
			label(ctx, ps, camPos, strips[0], 0, T.t("Keine Platten im Inventar"));
		}
		int hc = LeanPanels.hoverControl;
		if (hc >= 0 && hc < controls.size()) {
			label(ctx, ps, camPos, strips[1], hc, controls.get(hc).label());
		}
		if (scroll > 0) {
			icon(ctx, ps, camPos, strips[0], -8, 0.1f, "◀", 0xFFFFFFFF);
		}
		if (scroll + LeanPanels.VISIBLE < discs.size()) {
			icon(ctx, ps, camPos, strips[0], W + 8, 0.1f, "▶", 0xFFFFFFFF);
		}
	}

	static float slotCenter(int i) {
		return 20f * i + 11f;
	}

	// ------------------------------------------------------------------ Lage

	/** Die Fläche der Hotbar, zur Kamera hin gedreht. */
	private static Vec3 normal(LeanPanels.Strip s) {
		Vec3 n = s.right().cross(s.down()).normalize();
		return n.dot(s.front()) < 0 ? n.scale(-1) : n;
	}

	/** Rahmen in Hotbar-Pixeln (siehe Klassenbeschreibung). */
	private static Matrix4f frame(Vec3 camPos, LeanPanels.Strip s) {
		Vec3 o = s.topLeft().subtract(camPos);
		Vec3 x = s.right().scale(1 / W);
		Vec3 y = s.down().scale(1 / H);
		// Dicke bleibt gleich, auch während die Hotbar noch ausklappt
		Vec3 z = normal(s).scale(LeanPanels.CELL / H);
		return new Matrix4f(
				(float) x.x, (float) x.y, (float) x.z, 0,
				(float) y.x, (float) y.y, (float) y.z, 0,
				(float) z.x, (float) z.y, (float) z.z, 0,
				(float) o.x, (float) o.y, (float) o.z, 1);
	}

	// ------------------------------------------------------------------ Körper

	/** Die ganze Hotbar: Vorderseite mit Löchern, vertiefte Felder, Rückseite und Kanten. */
	private static void body(LevelRenderContext ctx, PoseStack ps, Vec3 camPos, LeanPanels.Strip s, int light) {
		ps.pushPose();
		ps.mulPose(frame(camPos, s));
		RenderType type = RenderTypes.entityTranslucent(HOTBAR);
		ctx.submitNodeCollector().submitCustomGeometry(ps, type, (pose, buf) -> {
			Geo g = new Geo(pose, buf, light);
			int n = LeanPanels.VISIBLE;
			// ---- Vorderseite: Streifen oben und unten, Stege zwischen den Feldern
			g.front(0, 0, W, HOLE0);
			g.front(0, HOLE1, W, H);
			g.front(0, HOLE0, 20f * 0 + HOLE0, HOLE1);
			for (int i = 0; i < n - 1; i++) {
				g.front(20f * i + HOLE1, HOLE0, 20f * (i + 1) + HOLE0, HOLE1);
			}
			g.front(20f * (n - 1) + HOLE1, HOLE0, W, HOLE1);

			// ---- Felder: Boden und Innenwände
			for (int i = 0; i < n; i++) {
				float x0 = 20f * i + HOLE0;
				float x1 = 20f * i + HOLE1;
				g.floor(x0, HOLE0, x1, HOLE1);
				int dark = 0xFF8C8C8C;
				int darker = 0xFF6E6E6E;
				// Licht kommt von oben: obere Innenwand und linke im Schatten
				g.wallX(x0, HOLE0, HOLE1, -DEPTH, 0, 1, darker);
				g.wallX(x1, HOLE0, HOLE1, -DEPTH, 0, -1, dark);
				g.wallY(HOLE0, x0, x1, -DEPTH, 0, 1, darker);
				g.wallY(HOLE1, x0, x1, -DEPTH, 0, -1, dark);
			}

			// ---- Rückseite und Außenkanten in der Rahmenfarbe der Grafik
			int side = 0xFFC8C8C8;
			g.back(0, 0, W, H, -THICK);
			g.wallY(0, 0, W, -THICK, 0, -1, side);
			g.wallY(H, 0, W, -THICK, 0, 1, side);
			g.wallX(0, 0, H, -THICK, 0, -1, side);
			g.wallX(W, 0, H, -THICK, 0, 1, side);
		});
		ps.popPose();
	}

	/** Hilfe zum Zeichnen in Hotbar-Pixeln mit passenden Texturkoordinaten. */
	private record Geo(PoseStack.Pose pose, VertexConsumer buf, int light) {
		/** Textur-x für einen Hotbar-Pixel: das letzte Pixel ist der rechte Rand der Grafik. */
		private static float u(float x) {
			return (x <= W - 1 ? x : TEX_W - (W - x)) / TEX_W;
		}

		private static float v(float y) {
			return y / TEX_H;
		}

		/** Ein Texel aus dem Rahmen der Grafik, für einfarbige Flächen. */
		private static final float FRAME_U = 10.5f / TEX_W;
		private static final float FRAME_V = 1.5f / TEX_H;
		/** Ein Texel aus dem Inneren eines Feldes. */
		private static final float SLOT_U = 11f / TEX_W;
		private static final float SLOT_V = 11f / TEX_H;

		void front(float x0, float y0, float x1, float y1) {
			// am rechten Rand teilen, damit dort die echte Abschlusskante der Grafik liegt
			if (x0 < W - 1 && x1 > W - 1) {
				front(x0, y0, W - 1, y1);
				front(W - 1, y0, x1, y1);
				return;
			}
			rect(x0, y0, x1, y1, 0, u(x0), v(y0), u(x1), v(y1), 0xFFFFFFFF, 0, 0, 1);
		}

		void floor(float x0, float y0, float x1, float y1) {
			rect(x0, y0, x1, y1, -DEPTH, u(x0), v(y0), u(x1), v(y1), 0xFFB4B4B4, 0, 0, 1);
		}

		void back(float x0, float y0, float x1, float y1, float z) {
			rect(x0, y0, x1, y1, z, FRAME_U, FRAME_V, FRAME_U, FRAME_V, 0xFF9A9A9A, 0, 0, -1);
		}

		/** Senkrechte Wand bei x, von y0 bis y1, zwischen z0 und z1. */
		void wallX(float x, float y0, float y1, float z0, float z1, float nx, int color) {
			boolean inner = z0 == -DEPTH;
			float tu = inner ? SLOT_U : FRAME_U;
			float tv = inner ? SLOT_V : FRAME_V;
			Matrix4f m = pose.pose();
			float[][] p = {{y0, z1}, {y1, z1}, {y1, z0}, {y0, z0}};
			emit(m, new float[][]{{x, p[0][0], p[0][1]}, {x, p[1][0], p[1][1]}, {x, p[2][0], p[2][1]}, {x, p[3][0], p[3][1]}},
					tu, tv, color, nx, 0, 0);
		}

		/** Waagerechte Wand bei y, von x0 bis x1, zwischen z0 und z1. */
		void wallY(float y, float x0, float x1, float z0, float z1, float ny, int color) {
			boolean inner = z0 == -DEPTH;
			float tu = inner ? SLOT_U : FRAME_U;
			float tv = inner ? SLOT_V : FRAME_V;
			Matrix4f m = pose.pose();
			emit(m, new float[][]{{x0, y, z1}, {x1, y, z1}, {x1, y, z0}, {x0, y, z0}}, tu, tv, color, 0, ny, 0);
		}

		private void emit(Matrix4f m, float[][] v, float tu, float tv, int color, float nx, float ny, float nz) {
			for (int k = 0; k < 4; k++) {
				vertex(m, v[k][0], v[k][1], v[k][2], tu, tv, color, nx, ny, nz);
			}
			for (int k = 3; k >= 0; k--) {
				vertex(m, v[k][0], v[k][1], v[k][2], tu, tv, color, -nx, -ny, -nz);
			}
		}

		/** Rechteck in der Ebene z (beidseitig). */
		void rect(float x0, float y0, float x1, float y1, float z, float u0, float v0, float u1, float v1, int color,
				float nx, float ny, float nz) {
			Matrix4f m = pose.pose();
			float[][] v = {{x0, y0, u0, v0}, {x0, y1, u0, v1}, {x1, y1, u1, v1}, {x1, y0, u1, v0}};
			for (int k = 0; k < 4; k++) {
				vertex(m, v[k][0], v[k][1], z, v[k][2], v[k][3], color, nx, ny, nz);
			}
			for (int k = 3; k >= 0; k--) {
				vertex(m, v[k][0], v[k][1], z, v[k][2], v[k][3], color, -nx, -ny, -nz);
			}
		}

		private void vertex(Matrix4f m, float x, float y, float z, float tu, float tv, int color, float nx, float ny, float nz) {
			// y zeigt in der Hotbar nach unten, für das Licht muss es nach oben zeigen
			buf.addVertex(m, x, y, z).setColor(color).setUv(tu, tv).setOverlay(OverlayTexture.NO_OVERLAY)
					.setLight(light).setNormal(pose, nx, -ny, nz);
		}
	}

	// ------------------------------------------------------------------ Inhalt

	/** Auswahlrahmen wie in der normalen Hotbar, knapp vor der Vorderseite. */
	private static void selection(LevelRenderContext ctx, PoseStack ps, Vec3 camPos, LeanPanels.Strip s, int i) {
		ps.pushPose();
		ps.mulPose(frame(camPos, s));
		float x = 20f * i;
		ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.entityTranslucent(SELECTION), (pose, buf) ->
				new Geo(pose, buf, LightCoordsUtil.FULL_BRIGHT).rect(x - 1, -1, x + 23, 22, 0.15f, 0, 0, 1, 1, 0xFFFFFFFF, 0, 0, 1));
		ps.popPose();
	}

	/** Platte als kleiner flacher Gegenstand im vertieften Feld. */
	private static void item(LevelRenderContext ctx, PoseStack ps, Vec3 camPos, LeanPanels.Strip s, int i, ItemStack stack, int light) {
		Minecraft mc = Minecraft.getInstance();
		ps.pushPose();
		ps.mulPose(frame(camPos, s));
		ps.translate(slotCenter(i), 11, -DEPTH + 0.3f);
		// Gegenstände sind 1 groß mit y nach oben; die Hotbar hat y nach unten
		ps.scale(16, -16, 0.6f);
		mc.getItemModelResolver().updateForTopItem(ITEM, stack, ItemDisplayContext.GUI, mc.level, null, 0);
		ITEM.submit(ps, ctx.submitNodeCollector(), light, OverlayTexture.NO_OVERLAY, 0);
		ps.popPose();
	}

	/** Symbol (Text) mittig bei x, in der Tiefe z. */
	private static void icon(LevelRenderContext ctx, PoseStack ps, Vec3 camPos, LeanPanels.Strip s, float cx, float z, String icon, int color) {
		Font font = Minecraft.getInstance().font;
		ps.pushPose();
		ps.mulPose(frame(camPos, s));
		ps.translate(0, 0, z);
		int w = font.width(icon);
		ctx.submitNodeCollector().submitText(ps, cx - w / 2f, 7, Component.literal(icon).getVisualOrderText(), false,
				Font.DisplayMode.NORMAL, LightCoordsUtil.FULL_BRIGHT, color, 0, 0);
		ps.popPose();
	}

	/** Name über einem Feld, in der Ebene der Hotbar (wie der Gegenstandsname über der normalen Hotbar). */
	private static void label(LevelRenderContext ctx, PoseStack ps, Vec3 camPos, LeanPanels.Strip s, int i, String text) {
		Font font = Minecraft.getInstance().font;
		ps.pushPose();
		ps.mulPose(frame(camPos, s));
		ps.translate(0, 0, 0.1f);
		ctx.submitNodeCollector().submitText(ps, 20f * i + 1, -11, Component.literal(text).getVisualOrderText(), false,
				Font.DisplayMode.NORMAL, LightCoordsUtil.FULL_BRIGHT, 0xFFFFFFFF, 0xAA000000, 0);
		ps.popPose();
	}
}
