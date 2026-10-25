package de.schmodas.multimedia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Ein Jukebox-Sound wie bei Vanilla (Lautstärke-Regler "Musikblöcke", 64 Blöcke Reichweite,
 * leiser mit Abstand), nur dass das Audio aus dem Stream kommt.
 */
public class StreamSoundInstance extends AbstractSoundInstance {
	public static final Identifier SOUND_ID = Identifier.fromNamespaceAndPath(MultiMediaClient.MOD_ID, "stream");

	private final Playback playback;
	private final CompletableFuture<Void> cancelled = new CompletableFuture<>();
	private volatile Consumer<String> onTrack;
	private volatile Consumer<String> onError;
	/** Welcher Stereo-Kanal (StreamHub.MIX/LEFT/RIGHT). */
	private final int stereo;

	/** range: Reichweite in Vielfachen von 64 Blöcken (Schild-Lautstärke über 100 %). */
	public StreamSoundInstance(Playback playback, Vec3 pos, float range, int stereo) {
		super(SOUND_ID, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
		this.playback = playback;
		this.stereo = stereo;
		this.volume = 4.0F * Math.max(1F, range); // wie Vanilla-Jukebox: 4 x 16 = 64 Blöcke
		this.pitch = 1.0F;
		this.attenuation = SoundInstance.Attenuation.LINEAR;
		this.looping = false;
		this.x = pos.x;
		this.y = pos.y;
		this.z = pos.z;
		Occlusion.track(this);
	}

	@Override
	public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader, Identifier id, boolean repeatInstantly) {
		java.util.function.BiConsumer<YtAudioStream.TrackStart, Long> trackHandler = (track, delayMs) -> Minecraft.getInstance().execute(() -> StreamWatcher.later(delayMs, () -> {
					if (isCancelled()) {
						return;
					}
					playback.markPlaying(track.pass(), track.index());
					Consumer<String> c = onTrack;
					if (c != null && track.title() != null) {
						c.accept(track.title());
					}
				}));
		// gemeinsamer Stream für alle Jukeboxen mit demselben Link
		return StreamHub.openTap(playback, stereo, MultiMediaClient::info,
				msg -> {
					MultiMediaClient.error(T.t("Stream fehlgeschlagen: ") + YtAudioStream.friendly(msg));
					Consumer<String> c = onError;
					if (c != null) {
						Minecraft.getInstance().execute(() -> c.accept(msg));
					}
				}, trackHandler, cancelled);
	}

	public Playback playback() {
		return playback;
	}

	/** Wird (im Spiel-Thread) aufgerufen, wenn bei einer Playlist ein neuer Titel beginnt. */
	/** Wird (im Spiel-Thread) aufgerufen, wenn der Stream nicht startet. */
	public void setOnError(Consumer<String> onError) {
		this.onError = onError;
	}

	public void setOnTrack(Consumer<String> onTrack) {
		this.onTrack = onTrack;
	}


	// Wand-Dämpfung (siehe Occlusion); alFilter nur im Sound-Thread benutzen
	double occlusion;
	int occlusionAge;
	float appliedGain = 1F;
	float appliedGainHf = 1F;
	volatile int alFilter;
	volatile int alEffect;
	volatile int alSlot;
	RoomAcoustics.Reverb appliedReverb;
	/** Zielwerte aus der letzten Messung (Spiel-Thread). */
	float targetGain = 1F;
	float targetGainHf = 1F;
	RoomAcoustics.Reverb targetReverb;
	/** Hall, der gerade im Effekt-Slot geladen ist (nur Sound-Thread). */
	RoomAcoustics.Reverb loadedReverb;
	int appliedSettings = -1;
	/** Lautstärke vom Schild (0..1), wird über den Filter angewendet. */
	volatile float userVolume = 1F;

	/** Beendet den Stream (auch wenn er noch lädt). */
	public void cancel() {
		cancelled.complete(null);
	}

	public boolean isCancelled() {
		return cancelled.isDone();
	}

}
