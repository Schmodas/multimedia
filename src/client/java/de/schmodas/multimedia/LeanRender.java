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
 * Zeichnet die Hotbars der Jukebox-Ansicht als echte Gegenstände in der Welt: Hotbar-Grafik von Minecraft,
 * mit etwas Dicke, im Licht der Welt, die Platten als kleine Gegenstände in den Feldern.
 */
public final class LeanRender {
	private static final Identifier HOTBAR = Identifier.withDefaultNamespace("textures/gui/sprites/hud/hotbar.png");
	private static final Identifier SELECTION = Identifier.withDefaultNamespace("textures/gui/sprites/hud/hotbar_selection.png");
	/** Ein Feld in Pixeln der Hotbar-Grafik (20 + Rand). */
	private static final float PX = 22f;
	/** Dicke der Hotbar in Pixeln. */
	private static final float THICK = 2f;
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
		for (int i = 0; i < LeanPanels.VISIBLE; i++) {
			// ---- Platten
			ItemStack stack = scroll + i < discs.size() ? mc.player.getInventory().getItem(discs.get(scroll + i)) : ItemStack.EMPTY;
			boolean hoverD = LeanPanels.hoverDisc == i && !stack.isEmpty();
			cell(ctx, ps, camPos, strips[0], i, light, hoverD);
			if (!stack.isEmpty()) {
				item(ctx, ps, camPos, strips[0], i, stack, light, unfold);
			}
			// ---- Knöpfe
			LeanPanels.Control c = i < controls.size() ? controls.get(i) : null;
			boolean hoverC = c != null && c.active() && LeanPanels.hoverControl == i;
			cell(ctx, ps, camPos, strips[1], i, light, hoverC);
			if (c != null && unfold > 0.6f) {
				icon(ctx, ps, camPos, strips[1], i, c.icon(), c.active() ? 0xFFFFFFFF : 0xFF5A5A5A, light);
			}
		}
		if (unfold < 1) {
			return;
		}
		// Name über dem Feld, auf das man zeigt
		int hd = LeanPanels.hoverDisc;
		if (hd >= 0 && scroll + hd < discs.size()) {
			label(ctx, ps, camPos, strips[0], hd, LeanPanels.discName(mc.player.getInventory().getItem(discs.get(scroll + hd))), light);
		} else if (discs.isEmpty()) {
			label(ctx, ps, camPos, strips[0], 0, T.t("Keine Platten im Inventar"), light);
		}
		int hc = LeanPanels.hoverControl;
		if (hc >= 0 && hc < controls.size()) {
			label(ctx, ps, camPos, strips[1], hc, controls.get(hc).label(), light);
		}
		if (scroll > 0) {
			icon(ctx, ps, camPos, strips[0], -1, "◀", 0xFFFFFFFF, light);
		}
		if (scroll + LeanPanels.VISIBLE < discs.size()) {
			icon(ctx, ps, camPos, strips[0], LeanPanels.VISIBLE, "▶", 0xFFFFFFFF, light);
		}
	}

	/** Feld-Koordinaten: x nach rechts, y nach unten (je 0..22 Pixel), z zur Kamera hin. */
	private static Matrix4f cellFrame(Vec3 camPos, LeanPanels.Strip s, int i) {
		Vec3 o = s.cellOrigin(i).subtract(camPos);
		Vec3 x = s.cellRight().scale(1 / PX);
		Vec3 y = s.down().scale(1 / PX);
		Vec3 z = s.front().scale(LeanPanels.CELL / PX);
		return new Matrix4f(
				(float) x.x, (float) x.y, (float) x.z, 0,
				(float) y.x, (float) y.y, (float) y.z, 0,
				(float) z.x, (float) z.y, (float) z.z, 0,
				(float) o.x, (float) o.y, (float) o.z, 1);
	}

	private static void cell(LevelRenderContext ctx, PoseStack ps, Vec3 camPos, LeanPanels.Strip s, int i, int light, boolean hover) {
		ps.pushPose();
		ps.mulPose(cellFrame(camPos, s, i));
		Vec3 n = s.front();
		float u0 = 20f * i / 182f;
		float u1 = (20f * i + 22f) / 182f;
		RenderType hotbar = RenderTypes.entityTranslucent(HOTBAR);
		ctx.submitNodeCollector().submitCustomGeometry(ps, hotbar, (pose, buf) -> {
			// Vorderseite mit der Hotbar-Grafik
			quad(pose, buf, 0, 0, PX, PX, 0, u0, 0, u1, 1, 0xFFFFFFFF, light, n);
			// Rückseite und Kanten: dunkler Rand der Grafik
			float e0 = 0.2f / 182f;
			float e1 = 0.8f / 182f;
			quad(pose, buf, 0, 0, PX, PX, -THICK, e0, 0.3f, e1, 0.7f, 0xFF3A3A3A, light, n.scale(-1));
			edgeH(pose, buf, 0, PX, 0, e0, e1, light, n);
			edgeH(pose, buf, 0, PX, PX, e0, e1, light, n);
			if (i == 0) {
				edgeV(pose, buf, 0, e0, e1, light, n);
			}
			if (i == LeanPanels.VISIBLE - 1) {
				edgeV(pose, buf, PX, e0, e1, light, n);
			}
		});
		if (hover) {
			ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.entityTranslucent(SELECTION),
					(pose, buf) -> quad(pose, buf, -1, -1, 23, 22, 0.15f, 0, 0, 1, 1, 0xFFFFFFFF, LightCoordsUtil.FULL_BRIGHT, n));
		}
		ps.popPose();
	}

	/** Rechteck (beidseitig), x/y in Feld-Pixeln, z = Abstand zur Vorderseite. */
	private static void quad(PoseStack.Pose pose, VertexConsumer buf, float x0, float y0, float x1, float y1, float z,
			float u0, float v0, float u1, float v1, int color, int light, Vec3 normal) {
		float nx = (float) normal.x;
		float ny = (float) normal.y;
		float nz = (float) normal.z;
		Matrix4f m = pose.pose();
		float[][] v = {{x0, y0, u0, v0}, {x0, y1, u0, v1}, {x1, y1, u1, v1}, {x1, y0, u1, v0}};
		for (int k = 0; k < 4; k++) {
			buf.addVertex(m, v[k][0], v[k][1], z).setColor(color).setUv(v[k][2], v[k][3]).setOverlay(OverlayTexture.NO_OVERLAY)
					.setLight(light).setNormal(pose, nx, ny, nz);
		}
		for (int k = 3; k >= 0; k--) {
			buf.addVertex(m, v[k][0], v[k][1], z).setColor(color).setUv(v[k][2], v[k][3]).setOverlay(OverlayTexture.NO_OVERLAY)
					.setLight(light).setNormal(pose, -nx, -ny, -nz);
		}
	}

	/** Waagerechte Kante (oben/unten) zwischen Vorder- und Rückseite. */
	private static void edgeH(PoseStack.Pose pose, VertexConsumer buf, float x0, float x1, float y, float e0, float e1, int light, Vec3 n) {
		Matrix4f m = pose.pose();
		float[][] v = {{x0, 0}, {x1, 0}, {x1, -THICK}, {x0, -THICK}};
		for (int pass = 0; pass < 2; pass++) {
			for (int k = 0; k < 4; k++) {
				float[] p = v[pass == 0 ? k : 3 - k];
				buf.addVertex(m, p[0], y, p[1]).setColor(0xFF2A2A2A).setUv(e0, 0.5f).setOverlay(OverlayTexture.NO_OVERLAY)
						.setLight(light).setNormal(pose, 0, 1, 0);
			}
		}
	}

	/** Senkrechte Kante (links/rechts) zwischen Vorder- und Rückseite. */
	private static void edgeV(PoseStack.Pose pose, VertexConsumer buf, float x, float e0, float e1, int light, Vec3 n) {
		Matrix4f m = pose.pose();
		float[][] v = {{0, 0}, {PX, 0}, {PX, -THICK}, {0, -THICK}};
		for (int pass = 0; pass < 2; pass++) {
			for (int k = 0; k < 4; k++) {
				float[] p = v[pass == 0 ? k : 3 - k];
				buf.addVertex(m, x, p[0], p[1]).setColor(0xFF2A2A2A).setUv(e0, 0.5f).setOverlay(OverlayTexture.NO_OVERLAY)
						.setLight(light).setNormal(pose, 1, 0, 0);
			}
		}
	}

	/** Platte als kleiner flacher Gegenstand im Feld. */
	private static void item(LevelRenderContext ctx, PoseStack ps, Vec3 camPos, LeanPanels.Strip s, int i, ItemStack stack, int light, float unfold) {
		Minecraft mc = Minecraft.getInstance();
		Vec3 r = s.cellRight();
		Vec3 center = s.cellOrigin(i).add(r.scale(0.5)).add(s.down().scale(0.5)).add(s.front().scale(0.004)).subtract(camPos);
		Vec3 xr = r.normalize();
		Vec3 up = s.down().normalize().scale(-1);
		Vec3 out = xr.cross(up);
		if (out.dot(s.front()) < 0) {
			out = out.scale(-1);
		}
		float size = (float) (LeanPanels.CELL * 0.72);
		ps.pushPose();
		ps.mulPose(new Matrix4f(
				(float) xr.x * size, (float) xr.y * size, (float) xr.z * size, 0,
				(float) up.x * size * unfold, (float) up.y * size * unfold, (float) up.z * size * unfold, 0,
				(float) out.x * size * 0.05f, (float) out.y * size * 0.05f, (float) out.z * size * 0.05f, 0,
				(float) center.x, (float) center.y, (float) center.z, 1));
		mc.getItemModelResolver().updateForTopItem(ITEM, stack, ItemDisplayContext.GUI, mc.level, null, 0);
		ITEM.submit(ps, ctx.submitNodeCollector(), light, OverlayTexture.NO_OVERLAY, 0);
		ps.popPose();
	}

	/** Symbol (Text) mittig im Feld i; i = -1 / VISIBLE: links/rechts neben der Hotbar. */
	private static void icon(LevelRenderContext ctx, PoseStack ps, Vec3 camPos, LeanPanels.Strip s, int i, String icon, int color, int light) {
		Font font = Minecraft.getInstance().font;
		ps.pushPose();
		ps.mulPose(cellFrame(camPos, s, i));
		ps.translate(0, 0, 0.1f);
		int w = font.width(icon);
		ctx.submitNodeCollector().submitText(ps, (PX - w) / 2f, 7, Component.literal(icon).getVisualOrderText(), false,
				Font.DisplayMode.NORMAL, LightCoordsUtil.FULL_BRIGHT, color, 0, 0);
		ps.popPose();
	}

	/** Name über einem Feld, in der Ebene der Hotbar (wie der Gegenstandsname über der normalen Hotbar). */
	private static void label(LevelRenderContext ctx, PoseStack ps, Vec3 camPos, LeanPanels.Strip s, int i, String text, int light) {
		Font font = Minecraft.getInstance().font;
		ps.pushPose();
		ps.mulPose(cellFrame(camPos, s, i));
		ps.translate(0, 0, 0.1f);
		ctx.submitNodeCollector().submitText(ps, 1, -11, Component.literal(text).getVisualOrderText(), false,
				Font.DisplayMode.NORMAL, LightCoordsUtil.FULL_BRIGHT, 0xFFFFFFFF, 0xAA000000, 0);
		ps.popPose();
	}
}
