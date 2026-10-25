package de.schmodas.multimedia.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import de.schmodas.multimedia.LeanView;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Jukebox-Ansicht: statt der Hand mit Gegenstand beide leeren Arme, die sich auf der Jukebox abstützen. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class HandsMixin {
	@Shadow
	private void renderPlayerArm(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords,
			float inverseArmHeight, float attackValue, HumanoidArm arm, PlayerRenderState playerState) {
		throw new AssertionError();
	}

	@Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
	private void multimedia$leanHands(float partialTicks, PoseStack poseStack, SubmitNodeCollector collector, PlayerRenderState playerState,
			FirstPersonHandsAndItemsRenderState state, CallbackInfo ci) {
		if (!LeanView.active()) {
			return;
		}
		ci.cancel();
		float lean = LeanView.lean();
		if (lean <= 0 || playerState.avatarRenderState == null) {
			return; // beim Gehen: Hände weg (sie kommen beim Vorbeugen von unten)
		}
		int light = playerState.avatarRenderState.lightCoords;
		for (HumanoidArm arm : HumanoidArm.values()) {
			float invert = arm == HumanoidArm.RIGHT ? 1 : -1;
			poseStack.pushPose();
			// etwas nach innen und nach vorne: Hände liegen links und rechts auf der Vorderkante
			poseStack.translate(invert * -0.1f, -0.05f, -0.12f);
			renderPlayerArm(poseStack, collector, light, 1 - lean, 0, arm, playerState);
			poseStack.popPose();
		}
	}
}
