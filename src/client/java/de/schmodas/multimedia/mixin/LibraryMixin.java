package de.schmodas.multimedia.mixin;

import com.mojang.blaze3d.audio.Library;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Minecraft erlaubt nur 8 gleichzeitige Musik-Streams (jede Radio-Jukebox braucht einen). Bei mehr Jukeboxen
 * starten die übrigen gar nicht und werden ständig neu versucht. OpenAL schafft locker mehr – also 32.
 */
@Mixin(Library.class)
public abstract class LibraryMixin {
	@Redirect(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(III)I", ordinal = 0))
	private int multimedia$moreStreams(int value, int min, int max) {
		// value = Wurzel der Kanalzahl (meist 15), Vanilla: höchstens 8
		return Mth.clamp(value * 2, min, 32);
	}
}
