package de.schmodas.multimedia.mixin;

import de.schmodas.multimedia.RadioManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelEventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.item.JukeboxSong;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Jukeboxen mit Disc-Stream-Schild spielen nicht den normalen Disc-Song –
 * die Musik kommt dann vom RadioManager.
 */
@Mixin(LevelEventHandler.class)
public abstract class LevelEventHandlerMixin {
	@Shadow @Final private ClientLevel level;

	@Shadow
	private void stopJukeboxSong(BlockPos pos) {
		throw new AssertionError();
	}

	@Inject(method = "playJukeboxSong", at = @At("HEAD"), cancellable = true)
	private void multimedia$play(Holder<JukeboxSong> songHolder, BlockPos pos, CallbackInfo ci) {
		if (RadioManager.isRadioJukebox(this.level, pos)) {
			this.stopJukeboxSong(pos);
			ci.cancel();
		}
	}
}
