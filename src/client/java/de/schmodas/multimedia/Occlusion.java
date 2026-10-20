package de.schmodas.multimedia;

import de.schmodas.multimedia.mixin.ChannelAccessor;
import de.schmodas.multimedia.mixin.SoundEngineAccessor;
import de.schmodas.multimedia.mixin.SoundManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.EXTEfx;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Wände dämpfen die Musik: Zwischen Spieler und Jukebox werden die Blöcke gezählt.
 * Je mehr Wand dazwischen ist, desto leiser und dumpfer (Tiefpass-Filter von OpenAL)
 * klingt der Stream. Gilt nur für die Streams dieser Mod.
 */
public final class Occlusion {
	private static final int INTERVAL = 4;
	private static final double STEP = 0.2;
	private static final double MAX_OCCLUSION = 6;
	/** Pro Tick höchstens ~0,4 dB lauter/leiser bzw. ~1 dB dumpfer/heller. */
	private static final float MAX_GAIN_STEP = 1.047F;
	private static final float MAX_HF_STEP = 1.12F;

	private static final Set<StreamSoundInstance> ACTIVE = new HashSet<>();
	private static int tick;
	/** Wenn OpenAL keine Filter kann, nicht ständig neu probieren. */
	private static volatile boolean efxBroken;
	private static volatile boolean reverbBroken;

	private Occlusion() {
	}

	public static void track(StreamSoundInstance instance) {
		ACTIVE.add(instance);
	}

	public static void tick(Minecraft mc) {
		if (ACTIVE.isEmpty()) {
			return;
		}
		SoundEngineAccessor engine = (SoundEngineAccessor) ((SoundManagerAccessor) mc.getSoundManager()).multimedia$getSoundEngine();
		var channels = engine.multimedia$getInstanceToChannel();
		ClientLevel level = mc.level;
		boolean measure = ++tick % INTERVAL == 0;
		Vec3 ear = mc.gameRenderer.mainCamera().position();

		Iterator<StreamSoundInstance> it = ACTIVE.iterator();
		while (it.hasNext()) {
			StreamSoundInstance inst = it.next();
			inst.occlusionAge++;
			ChannelAccess.ChannelHandle handle = channels.get(inst);
			// Fertig, sobald Minecraft den Kanal (und damit die OpenAL-Quelle) gelöscht hat.
			// Erst dann dürfen Filter und Hall weg – vorher hängen sie noch an der Quelle.
			if (handle == null && (inst.isCancelled() || inst.occlusionAge > 200 || level == null)) {
				it.remove();
				release(engine, inst);
				continue;
			}
			if (handle == null || efxBroken || level == null || inst.isCancelled()) {
				continue;
			}
			// Wände und Raum nur alle paar Ticks messen (Strahlen kosten Zeit) ...
			if (measure || inst.targetReverb == null) {
				double occ = occlusion(level, ear, new Vec3(inst.getX(), inst.getY(), inst.getZ()));
				inst.occlusion = inst.targetReverb == null ? occ : inst.occlusion + (occ - inst.occlusion) * 0.5;
				float o = (float) inst.occlusion;
				// Stärke aus /disc wand: 0 = aus, 5 = Standard, 10 = sehr stark
				double k = Settings.wallStrength() / 5.0;
				inst.targetGain = (float) Math.max(0.12 / Math.max(1, k * k), Math.pow(0.6, o * k)); // leiser pro Wand
				inst.targetGainHf = (float) Math.max(0.03 / Math.max(1, k), 1.0 / (1.0 + o * 4.0 * k)); // dumpfer pro Wand
				inst.targetReverb = Settings.reverb()
						? RoomAcoustics.at(level, BlockPos.containing(inst.getX(), inst.getY(), inst.getZ()), tick)
						: RoomAcoustics.Reverb.NONE;
			}
			// ... aber jeden Tick ein kleines Stück dorthin gleiten: Sprünge in der Lautstärke knacken hörbar.
			float wantGain = Math.max(0.001F, inst.targetGain * inst.userVolume); // inkl. Schild-Lautstärke
			float wantHf = Math.max(0.001F, inst.targetGainHf);
			boolean first = inst.appliedSettings == -1;
			float gain = first ? wantGain : glide(inst.appliedGain, wantGain, MAX_GAIN_STEP);
			float gainHf = first ? wantHf : glide(inst.appliedGainHf, wantHf, MAX_HF_STEP);
			RoomAcoustics.Reverb reverb = inst.targetReverb;
			if (!first && gain == inst.appliedGain && gainHf == inst.appliedGainHf
					&& reverb.equals(inst.appliedReverb) && inst.appliedSettings == Settings.version) {
				continue;
			}
			inst.appliedGain = gain;
			inst.appliedGainHf = gainHf;
			inst.appliedReverb = reverb;
			inst.appliedSettings = Settings.version;
			handle.execute(channel -> apply(inst, ((ChannelAccessor) channel).multimedia$getSource(), gain, gainHf, reverb));
		}
	}

	/**
	 * Filter, Hall-Effekt und Effekt-Slot einer beendeten Jukebox freigeben (im Sound-Thread, nach allen
	 * noch ausstehenden Änderungen). Wichtig: OpenAL rechnet jeden Hall-Slot ständig weiter, auch ohne Quelle –
	 * ohne Aufräumen sammeln sie sich an, bis der ganze Spiel-Sound knackt.
	 */
	private static void release(SoundEngineAccessor engine, StreamSoundInstance inst) {
		if (inst.alFilter == 0 && inst.alSlot == 0 && inst.alEffect == 0) {
			return;
		}
		engine.multimedia$getExecutor().execute(() -> {
			AL10.alGetError();
			if (inst.alSlot != 0) {
				EXTEfx.alAuxiliaryEffectSloti(inst.alSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, EXTEfx.AL_EFFECT_NULL);
				EXTEfx.alDeleteAuxiliaryEffectSlots(inst.alSlot);
				inst.alSlot = 0;
			}
			if (inst.alEffect != 0) {
				EXTEfx.alDeleteEffects(inst.alEffect);
				inst.alEffect = 0;
			}
			if (inst.alFilter != 0) {
				EXTEfx.alDeleteFilters(inst.alFilter);
				inst.alFilter = 0;
			}
			int err = AL10.alGetError();
			if (err != AL10.AL_NO_ERROR) {
				MultiMediaClient.LOGGER.warn("OpenAL-Aufräumen meldet Fehler {}", err);
			}
			RELEASED.incrementAndGet();
		});
	}

	/** Zählt freigegebene Effekte (fürs Log/Tests). */
	static final java.util.concurrent.atomic.AtomicInteger RELEASED = new java.util.concurrent.atomic.AtomicInteger();

	/** Höchstens maxFactor pro Tick lauter/leiser; ganz nah am Ziel direkt das Ziel. */
	private static float glide(float current, float target, float maxFactor) {
		if (current <= 0) {
			return target;
		}
		float ratio = target / current;
		if (ratio > maxFactor) {
			return current * maxFactor;
		}
		if (ratio < 1 / maxFactor) {
			return current / maxFactor;
		}
		return target;
	}

	/** Läuft im Sound-Thread (dort ist der OpenAL-Kontext aktiv). */
	private static void apply(StreamSoundInstance inst, int source, float gain, float gainHf, RoomAcoustics.Reverb reverb) {
		if (efxBroken) {
			return;
		}
		AL10.alGetError();
		if (inst.alFilter == 0) {
			int filter = EXTEfx.alGenFilters();
			if (AL10.alGetError() != AL10.AL_NO_ERROR || filter == 0) {
				efxBroken = true;
				MultiMediaClient.LOGGER.warn("OpenAL-Filter nicht verfügbar – Wände dämpfen die Musik nicht");
				return;
			}
			EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
			inst.alFilter = filter;
		}
		EXTEfx.alFilterf(inst.alFilter, EXTEfx.AL_LOWPASS_GAIN, gain);
		EXTEfx.alFilterf(inst.alFilter, EXTEfx.AL_LOWPASS_GAINHF, gainHf);
		AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, inst.alFilter);
		if (AL10.alGetError() != AL10.AL_NO_ERROR) {
			efxBroken = true;
			MultiMediaClient.LOGGER.warn("OpenAL-Filter ließ sich nicht setzen – Wände dämpfen die Musik nicht");
			return;
		}
		applyReverb(inst, source, reverb);
	}

	/** Hall über einen "Effekt-Slot" von OpenAL; der Hall-Anteil geht durch denselben Wand-Filter. */
	private static void applyReverb(StreamSoundInstance inst, int source, RoomAcoustics.Reverb r) {
		if (reverbBroken) {
			return;
		}
		AL10.alGetError();
		if (inst.alSlot == 0) {
			int effect = EXTEfx.alGenEffects();
			int slot = EXTEfx.alGenAuxiliaryEffectSlots();
			if (AL10.alGetError() != AL10.AL_NO_ERROR || effect == 0 || slot == 0) {
				reverbBroken = true;
				MultiMediaClient.LOGGER.warn("OpenAL-Hall nicht verfügbar");
				return;
			}
			EXTEfx.alEffecti(effect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_REVERB);
			inst.alEffect = effect;
			inst.alSlot = slot;
		}
		int e = inst.alEffect;
		if (r.equals(inst.loadedReverb)) {
			// Hall unverändert: nur den Filter für den Hall-Anteil aktualisieren.
			// (Den Effekt neu in den Slot zu laden setzt den Hall zurück – das knackt.)
			AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, inst.alSlot, 0, inst.alFilter);
			return;
		}
		inst.loadedReverb = r;
		EXTEfx.alEffectf(e, EXTEfx.AL_REVERB_DENSITY, 1.0F);
		EXTEfx.alEffectf(e, EXTEfx.AL_REVERB_DIFFUSION, 1.0F);
		EXTEfx.alEffectf(e, EXTEfx.AL_REVERB_GAIN, r.gain());
		EXTEfx.alEffectf(e, EXTEfx.AL_REVERB_GAINHF, r.gainHf());
		EXTEfx.alEffectf(e, EXTEfx.AL_REVERB_DECAY_TIME, Math.max(0.1F, Math.min(20F, r.decay())));
		EXTEfx.alEffectf(e, EXTEfx.AL_REVERB_DECAY_HFRATIO, 0.8F);
		EXTEfx.alEffectf(e, EXTEfx.AL_REVERB_REFLECTIONS_GAIN, Math.min(3.16F, r.gain() * 1.2F));
		EXTEfx.alEffectf(e, EXTEfx.AL_REVERB_REFLECTIONS_DELAY, Math.min(0.3F, r.reflectionsDelay()));
		EXTEfx.alEffectf(e, EXTEfx.AL_REVERB_LATE_REVERB_GAIN, Math.min(10F, r.gain() * 2.0F));
		EXTEfx.alEffectf(e, EXTEfx.AL_REVERB_LATE_REVERB_DELAY, Math.min(0.1F, r.reflectionsDelay() / 2));
		// Effekt (neu) in den Slot laden, damit die Werte gelten
		EXTEfx.alAuxiliaryEffectSloti(inst.alSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, e);
		AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, inst.alSlot, 0, inst.alFilter);
		if (AL10.alGetError() != AL10.AL_NO_ERROR) {
			reverbBroken = true;
			MultiMediaClient.LOGGER.warn("OpenAL-Hall ließ sich nicht setzen");
		}
	}

	/** Zählt "Wandstärke" zwischen Ohr und Jukebox. Volle Blöcke zählen 1, Glas, Türen usw. weniger. */
	static double occlusion(ClientLevel level, Vec3 from, Vec3 to) {
		BlockPos source = BlockPos.containing(to);
		BlockPos ear = BlockPos.containing(from);
		Vec3 dir = to.subtract(from);
		double len = dir.length();
		if (len < 0.5) {
			return 0;
		}
		Vec3 step = dir.scale(STEP / len);
		Set<BlockPos> seen = new HashSet<>();
		double total = 0;
		Vec3 p = from;
		for (double d = 0; d < len && total < MAX_OCCLUSION; d += STEP) {
			BlockPos bp = BlockPos.containing(p);
			p = p.add(step);
			if (bp.equals(source) || bp.equals(ear) || !seen.add(bp)) {
				continue;
			}
			total += weight(level, bp);
		}
		return Math.min(total, MAX_OCCLUSION);
	}

	private static double weight(ClientLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			return 0;
		}
		// eigene Einstellung per /disc block hat Vorrang
		Integer custom = Settings.blockDamping(
				net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		if (custom != null) {
			return custom / 10.0;
		}
		if (state.isSolidRender()) {
			return 1.0; // Stein, Holz, Wolle, ...
		}
		if (!state.getCollisionShape(level, pos).isEmpty()) {
			return 0.35; // Glas, Türen, Stufen, Zäune ...
		}
		return 0; // Pflanzen, Fackeln, Schilder ...
	}
}
