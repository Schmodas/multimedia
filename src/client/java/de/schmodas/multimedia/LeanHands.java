package de.schmodas.multimedia;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Jukebox-Ansicht: die eigenen Arme als echtes Modell in der Welt, die Unterarme kommen schräg von unten
 * und die Hände liegen flach auf der Oberseite der Jukebox, als würde man sich darauf abstützen.
 */
public final class LeanHands {
	/** Länge des Arm-Modells in Blöcken (12 Pixel). */
	private static final double ARM = 12 / 16.0;
	/** Abstand der Hände von der Mitte (zur Seite) und von der Mitte zur Kamera hin. */
	private static final double SIDE = 0.37;
	private static final double NEAR = 0.33;

	private LeanHands() {
	}

	public static void render(LevelRenderContext ctx) {
		BlockPos jb = LeanView.jukebox();
		float lean = LeanView.lean();
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (jb == null || lean <= 0.02f || mc.level == null || player == null) {
			return;
		}
		Camera cam = ctx.gameRenderer().mainCamera();
		Vec3 camPos = cam.position();
		if (camPos.y < jb.getY() + 0.5) {
			return; // Jukebox an der Decke: da stützt man sich nicht ab
		}
		if (!(mc.getEntityRenderDispatcher().getRenderer(player) instanceof AvatarRenderer<?> renderer)) {
			return;
		}
		PlayerModel model = renderer.getModel();
		PlayerSkin skin = player.getSkin();
		boolean slim = skin.model() == PlayerModelType.SLIM;
		Identifier texture = skin.body().texturePath();
		int light = LightCoordsUtil.getLightCoords(mc.level, jb.above());

		// Richtungen waagerecht: von der Kamera zur Jukebox und nach rechts
		Vec3 center = Vec3.atCenterOf(jb);
		Vec3 fwd = new Vec3(center.x - camPos.x, 0, center.z - camPos.z);
		fwd = fwd.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : fwd.normalize();
		Vector3f r = new Vector3f(1, 0, 0).rotate(cam.rotation());
		Vec3 right = new Vec3(r.x, 0, r.z);
		right = right.lengthSqr() < 1e-4 ? new Vec3(-fwd.z, 0, fwd.x) : right.normalize();
		Vec3 camDown = new Vec3(new Vector3f(0, -1, 0).rotate(cam.rotation()).x, new Vector3f(0, -1, 0).rotate(cam.rotation()).y,
				new Vector3f(0, -1, 0).rotate(cam.rotation()).z);

		// Hände kommen beim Vorbeugen von unten
		float t = smooth(Math.min(1f, Math.max(0f, (lean - 0.15f) / 0.75f)));
		double top = jb.getY() + 1 + 2 / 16.0 + 0.003; // Arm ist 4 Pixel dick, Mitte 2 Pixel über der Platte

		for (int side = -1; side <= 1; side += 2) {
			boolean isRight = side > 0;
			// Ziel: flach auf der Oberseite, links/rechts und etwas zur Kamera hin, nie über den Rand hinaus
			Vec3 hand = center.add(right.scale(side * SIDE)).subtract(fwd.scale(NEAR));
			hand = new Vec3(clamp(hand.x, jb.getX() + 0.16, jb.getX() + 0.84), top, clamp(hand.z, jb.getZ() + 0.16, jb.getZ() + 0.84));
			// Schulter: unterhalb und seitlich der Kamera
			Vec3 shoulder = camPos.add(right.scale(side * 0.55)).add(camDown.scale(0.75)).subtract(fwd.scale(0.15));
			// beim Ausklappen: Hand liegt noch tiefer, kommt hoch
			hand = hand.add(0, -(1 - t) * 0.6, 0).subtract(fwd.scale((1 - t) * 0.2));

			Vec3 along = hand.subtract(shoulder);
			double dist = along.length();
			Vec3 y = along.normalize(); // Modell +y: von der Schulter zur Hand
			// Handfläche nach unten: Modell +x (rechter Arm) bzw. -x (linker Arm) zeigt nach unten
			Vec3 down = new Vec3(0, -1, 0);
			Vec3 palm = down.subtract(y.scale(down.dot(y))).normalize();
			Vec3 x = isRight ? palm : palm.scale(-1);
			Vec3 z = x.cross(y);
			// Arm so lang machen, dass er bis zur Schulter reicht (höchstens etwas gestreckt)
			float stretch = (float) Math.max(1.0, Math.min(1.6, dist / ARM));

			ModelPart arm = isRight ? model.rightArm : model.leftArm;
			ModelPart sleeve = isRight ? model.rightSleeve : model.leftSleeve;
			arm.resetPose();
			arm.setPos(0, 0, 0);
			arm.setRotation(0, 0, 0);
			sleeve.visible = player.isModelPartShown(isRight ? PlayerModelPart.RIGHT_SLEEVE : PlayerModelPart.LEFT_SLEEVE);
			// Mitte des Handendes im Modell (Pixel): rechter Arm x -3..1 (schmal -2..1), linker -1..3 (schmal -1..2)
			float hx = isRight ? (slim ? -0.5f : -1f) : (slim ? 0.5f : 1f);

			PoseStack ps = ctx.poseStack();
			ps.pushPose();
			Vec3 o = hand.subtract(camPos);
			ps.mulPose(new Matrix4f(
					(float) x.x, (float) x.y, (float) x.z, 0,
					(float) y.x, (float) y.y, (float) y.z, 0,
					(float) z.x, (float) z.y, (float) z.z, 0,
					(float) o.x, (float) o.y, (float) o.z, 1));
			ps.scale(1, stretch, 1);
			ps.translate(-hx / 16f, -10 / 16f, 0);
			ctx.submitNodeCollector().submitModelPart(arm, ps, RenderTypes.entityTranslucent(texture), light, OverlayTexture.NO_OVERLAY, null);
			ps.popPose();
		}
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static float smooth(float t) {
		return t * t * (3 - 2 * t);
	}
}
