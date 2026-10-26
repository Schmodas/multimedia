package de.schmodas.multimedia;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Jukebox-Ansicht: die eigenen Arme als Modell in der Welt, fest an der Jukebox verankert
 * (bewegen sich nicht mit, wenn man sich umschaut).
 * Die Unterarme kommen aus den unteren Bildecken über die Vorderkante.
 * Rechts liegt der Unterarm weit auf der Platte, links stützt sich die Hand an der vorderen Ecke ab.
 */
public final class LeanHands {
	/** Länge des Arm-Modells in Blöcken (12 Pixel). */
	private static final double ARM = 12 / 16.0;
	/** Der Arm ist 4 Pixel dick: seine Mitte liegt 2 Pixel über der Fläche. */
	private static final double REST = 0.85 * 2 / 16.0 + 0.004;

	private LeanHands() {
	}

	public static void render(LevelRenderContext ctx) {
		BlockPos jb = LeanView.jukebox();
		Vec3 eye = LeanView.cameraPos();
		float lean = LeanView.lean();
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (jb == null || eye == null || lean <= 0.05f || mc.level == null || player == null) {
			return;
		}
		Vec3 center = Vec3.atCenterOf(jb);
		if (eye.y < center.y) {
			return; // Jukebox an der Decke: da stützt man sich nicht ab
		}
		if (!(mc.getEntityRenderDispatcher().getRenderer(player) instanceof AvatarRenderer<?> renderer)) {
			return;
		}
		// Richtungen fest an der Jukebox (aus der Kopfposition beim Vorbeugen, ändert sich nicht beim Umschauen):
		// n zeigt von der Jukebox zum Spieler, a nach rechts
		Vec3 flat = new Vec3(eye.x - center.x, 0, eye.z - center.z);
		Vec3 n = flat.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : flat.normalize();
		Vec3 a = new Vec3(-n.x, 0, -n.z).cross(new Vec3(0, 1, 0));
		double top = jb.getY() + 1 + REST;
		Vec3 topCenter = new Vec3(center.x, top, center.z);

		// beim Vorbeugen gleiten die Arme von vorne heran
		float t = smooth(Math.min(1f, Math.max(0f, (lean - 0.2f) / 0.7f)));
		Vec3 slide = n.scale((1 - t) * 0.8);

		// Schultern: unterhalb des Kopfes, knapp über der Platte, etwas hinter dem Kopf
		Vec3 shoulderR = eye.add(0, -0.55, 0).add(n.scale(0.15)).add(a.scale(0.6));
		Vec3 shoulderL = eye.add(0, -0.55, 0).add(n.scale(0.15)).add(a.scale(-0.6));
		// rechts: Unterarm liegt weit auf der Platte, Hand nahe der Mitte
		Vec3 handR = onTop(jb, topCenter.add(n.scale(0.15)).add(a.scale(0.24))).add(slide);
		// links: Hand stützt sich an der vorderen linken Ecke ab
		Vec3 handL = onTop(jb, topCenter.add(n.scale(0.3)).add(a.scale(-0.33))).add(slide);

		PlayerModel model = renderer.getModel();
		PlayerSkin skin = player.getSkin();
		boolean slim = skin.model() == PlayerModelType.SLIM;
		RenderType type = RenderTypes.entityTranslucent(skin.body().texturePath());
		int light = LightCoordsUtil.getLightCoords(mc.level, jb.above());
		Vec3 cam = ctx.gameRenderer().mainCamera().position();

		ModelPart right = prepare(model.rightArm, model.rightSleeve, player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE));
		ModelPart left = prepare(model.leftArm, model.leftSleeve, player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE));
		segment(ctx, cam, right, true, slim, shoulderR, handR, type, light);
		segment(ctx, cam, left, false, slim, shoulderL, handL, type, light);
	}

	/** Punkt auf der Oberseite halten (nicht über den Rand hinaus). */
	private static Vec3 onTop(BlockPos jb, Vec3 p) {
		return new Vec3(Math.max(jb.getX() + 0.14, Math.min(jb.getX() + 0.86, p.x)), p.y,
				Math.max(jb.getZ() + 0.14, Math.min(jb.getZ() + 0.86, p.z)));
	}

	private static ModelPart prepare(ModelPart arm, ModelPart sleeve, boolean sleeveShown) {
		arm.resetPose();
		arm.setPos(0, 0, 0);
		arm.setRotation(0, 0, 0);
		sleeve.visible = sleeveShown;
		return arm;
	}

	/** Zeichnet den Arm als geraden Klotz von 'from' (Schulterende) bis 'to' (Handende), Handfläche nach unten. */
	private static void segment(LevelRenderContext ctx, Vec3 cam, ModelPart arm, boolean isRight, boolean slim,
			Vec3 from, Vec3 to, RenderType type, int light) {
		Vec3 along = to.subtract(from);
		double len = along.length();
		if (len < 1e-3) {
			return;
		}
		Vec3 y = along.scale(1 / len);
		Vec3 down = new Vec3(0, -1, 0);
		Vec3 palm = down.subtract(y.scale(down.dot(y)));
		palm = palm.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : palm.normalize();
		// Handfläche zeigt beim rechten Arm Richtung Modell +x, beim linken Richtung -x
		Vec3 x = isRight ? palm : palm.scale(-1);
		Vec3 z = x.cross(y);
		// Mitte des Handendes im Modell (Pixel): rechter Arm x -3..1 (schmal -2..1), linker -1..3 (schmal -1..2)
		float hx = isRight ? (slim ? -0.5f : -1f) : (slim ? 0.5f : 1f);
		Vec3 o = to.subtract(cam);
		PoseStack ps = ctx.poseStack();
		ps.pushPose();
		ps.mulPose(new Matrix4f(
				(float) x.x, (float) x.y, (float) x.z, 0,
				(float) y.x, (float) y.y, (float) y.z, 0,
				(float) z.x, (float) z.y, (float) z.z, 0,
				(float) o.x, (float) o.y, (float) o.z, 1));
		ps.scale(0.85f, (float) (len / ARM), 0.85f);
		ps.translate(-hx / 16f, -10 / 16f, 0);
		ctx.submitNodeCollector().submitModelPart(arm, ps, type, light, OverlayTexture.NO_OVERLAY, null);
		ps.popPose();
	}

	private static float smooth(float t) {
		return t * t * (3 - 2 * t);
	}
}
