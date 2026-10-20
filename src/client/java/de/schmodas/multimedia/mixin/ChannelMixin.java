package de.schmodas.multimedia.mixin;

import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.sounds.AudioStream;
import org.jspecify.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Läuft der Puffer einer MultiMedia-Jukebox kurz leer (Ruckler im Spiel), stoppt OpenAL die Quelle und Minecraft
 * würde sie wegwerfen. Dann einfach weiterspielen, sobald wieder Daten in der Warteschlange sind.
 */
@Mixin(Channel.class)
public abstract class ChannelMixin {
	@Shadow
	@Final
	private int source;

	@Shadow
	private @Nullable AudioStream stream;

	@Unique
	private boolean multimedia$stopRequested;

	@Inject(method = "stop", at = @At("HEAD"))
	private void multimedia$onStop(CallbackInfo ci) {
		multimedia$stopRequested = true;
	}

	@Inject(method = "updateStream", at = @At("TAIL"))
	private void multimedia$resumeAfterUnderrun(CallbackInfo ci) {
		AudioStream s = this.stream;
		if (s == null || multimedia$stopRequested || !s.getClass().getName().startsWith("de.schmodas.multimedia")) {
			return;
		}
		if (AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED
				&& AL10.alGetSourcei(this.source, AL10.AL_BUFFERS_QUEUED) > 0) {
			AL10.alSourcePlay(this.source);
		}
	}
}
