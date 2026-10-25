package de.schmodas.multimedia.mixin;

import de.schmodas.multimedia.LeanView;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Kamera gleitet zur Jukebox, solange man "drüberlehnt" (LeanView). */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow private Vec3 position;
	@Shadow private float xRot;
	@Shadow private float yRot;

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void multimedia$lean(float partialTicks, CallbackInfo ci) {
		float[] pos = new float[3];
		float[] rot = LeanView.apply(this.position, this.yRot, this.xRot, pos);
		if (rot == null) {
			return;
		}
		setRotation(rot[0], rot[1]);
		setPosition(new Vec3(pos[0], pos[1], pos[2]));
	}
}
