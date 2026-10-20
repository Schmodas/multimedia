package de.schmodas.multimedia.mixin;

import de.schmodas.multimedia.RadioManager;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Die Noten, die der Server einmal pro Sekunde schickt, an Radio-Jukeboxen weglassen – dort kommen sie im Takt. */
@Mixin(ClientPacketListener.class)
public abstract class ParticlePacketMixin {
	@Inject(method = "handleParticleEvent", cancellable = true, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
			shift = At.Shift.AFTER))
	private void multimedia$skipJukeboxNotes(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
		if (packet.particle().getType() == ParticleTypes.NOTE
				&& RadioManager.isPlayingAt(BlockPos.containing(packet.x(), packet.y() - 1.2, packet.z()))) {
			ci.cancel();
		}
	}
}
