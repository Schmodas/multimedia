package de.schmodas.multimedia.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import de.schmodas.multimedia.LeanView;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Jukebox-Ansicht: normale Hand ausblenden, die Arme zeichnet LeanHands in der Welt. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class HandsMixin {
	@Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
	private void multimedia$leanHands(float partialTicks, PoseStack poseStack, SubmitNodeCollector collector, PlayerRenderState playerState,
			FirstPersonHandsAndItemsRenderState state, CallbackInfo ci) {
		if (!LeanView.active()) {
			return;
		}
		// die Arme zeichnet LeanHands als Modell in der Welt (liegen auf der Jukebox)
		ci.cancel();
	}
}
