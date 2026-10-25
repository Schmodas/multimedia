package de.schmodas.multimedia;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.item.ItemStackRenderState;
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
 * Zeichnet die Hotbars der Jukebox-Ansicht flach in die Welt, so wie die echte Hotbar aussieht,
 * nur mit 6 statt 9 Feldern. Die Grafik kommt aus dem Spiel bzw. dem Ressourcenpaket.
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
				icon(ctx, ps, camPos, strips[1], slotCenter(i), 0.1f, c.icon(), c.active() ? 0xFFFFFFFF : 0xFF5A5A5A);
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
		Vec3 z = normal(s).scale(LeanPanels.CELL / H);
		return new Matrix4f(
				(float) x.x, (float) x.y, (float) x.z, 0,
				(float) y.x, (float) y.y, (float) y.z, 0,
				(float) z.x, (float) z.y, (float) z.z, 0,
				(float) o.x, (float) o.y, (float) o.z, 1);
	}

	// ------------------------------------------------------------------ Körper

	/**
	 * Die Hotbar flach wie die echte: links und die Felder 1-6 aus der Grafik, rechts der echte Abschluss
	 * der Originalgrafik. Geschnitten wird genau am inneren Rand eines Feldes (x = 19 + 20*i), weil die Grafik
	 * dort bei Feld 6 und Feld 9 gleich aussieht - so gibt es keine sichtbare Naht.
	 */
	private static void body(LevelRenderContext ctx, PoseStack ps, Vec3 camPos, LeanPanels.Strip s, int light) {
		ps.pushPose();
		ps.mulPose(frame(camPos, s));
		ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.entityTranslucent(HOTBAR), (pose, buf) -> {
			Geo g = new Geo(pose, buf, light);
			float cut = 20f * (LeanPanels.VISIBLE - 1) + 19f;
			float texCut = 20f * 8 + 19f;
			g.rect(0, 0, cut, H, 0, 0, 0, cut / TEX_W, 1, 0xFFFFFFFF);
			g.rect(cut, 0, W, H, 0, texCut / TEX_W, 0, 1, 1, 0xFFFFFFFF);
		});
		ps.popPose();
	}

	/** Zeichnet beidseitige Rechtecke in Hotbar-Pixeln. */
	private record Geo(PoseStack.Pose pose, VertexConsumer buf, int light) {
		void rect(float x0, float y0, float x1, float y1, float z, float u0, float v0, float u1, float v1, int color) {
			Matrix4f m = pose.pose();
			float[][] v = {{x0, y0, u0, v0}, {x0, y1, u0, v1}, {x1, y1, u1, v1}, {x1, y0, u1, v0}};
			for (int k = 0; k < 4; k++) {
				vertex(m, v[k], z, color, 1);
			}
			for (int k = 3; k >= 0; k--) {
				vertex(m, v[k], z, color, -1);
			}
		}

		private void vertex(Matrix4f m, float[] p, float z, int color, float nz) {
			buf.addVertex(m, p[0], p[1], z).setColor(color).setUv(p[2], p[3]).setOverlay(OverlayTexture.NO_OVERLAY)
					.setLight(light).setNormal(pose, 0, 0, nz);
		}
	}

	// ------------------------------------------------------------------ Inhalt

	/** Auswahlrahmen wie in der normalen Hotbar, knapp vor der Vorderseite. */
	private static void selection(LevelRenderContext ctx, PoseStack ps, Vec3 camPos, LeanPanels.Strip s, int i) {
		ps.pushPose();
		ps.mulPose(frame(camPos, s));
		float x = 20f * i;
		ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.entityTranslucent(SELECTION), (pose, buf) ->
				new Geo(pose, buf, LightCoordsUtil.FULL_BRIGHT).rect(x - 1, -1, x + 23, 22, 0.15f, 0, 0, 1, 1, 0xFFFFFFFF));
		ps.popPose();
	}

	/** Platte als kleiner flacher Gegenstand im vertieften Feld. */
	private static void item(LevelRenderContext ctx, PoseStack ps, Vec3 camPos, LeanPanels.Strip s, int i, ItemStack stack, int light) {
		Minecraft mc = Minecraft.getInstance();
		ps.pushPose();
		ps.mulPose(frame(camPos, s));
		ps.translate(slotCenter(i), 11, 0.05f);
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
