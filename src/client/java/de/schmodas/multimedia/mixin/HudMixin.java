package de.schmodas.multimedia.mixin;

import de.schmodas.multimedia.LeanView;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In der Jukebox-Ansicht keine Leiste, kein Fadenkreuz (die Hände bleiben – die stützen sich ab). */
@Mixin(Hud.class)
public abstract class HudMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void multimedia$hide(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (LeanView.active()) {
			ci.cancel();
		}
	}
}
