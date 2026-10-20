package de.schmodas.multimedia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Radio-Modus: Hängt an einer Jukebox (oder direkt daneben) ein Schild mit einem Link und
 * steckt eine Disc drin, spielt die Jukebox diesen Link – für jeden mit Mod, der in Hörweite ist,
 * auch wenn er erst später dazukommt. Die Position folgt der Spielzeit der Welt, deshalb hören
 * alle immer dieselbe Stelle.
 */
public final class RadioManager {
	private static final double START_DISTANCE = 64;
	private static final double STOP_DISTANCE = 72;
	/** So weit wird nach Haupt-Jukeboxen eines Kanals gesucht. */
	public static final double CHANNEL_DISTANCE = 128;
	private static final int SCAN_INTERVAL = 10;
	private static final int GRACE_TICKS = 100;

	/** Was eine Jukebox spielen soll: Link, Lautstärke (1.0 = 100 %) und Stereo-Kanal. */
	record Wanted(Source source, float volume, int stereo) {
		/** Über 100 % wird es nicht lauter, aber weiter hörbar (Reichweite in Vielfachen von 64 Blöcken). */
		float range() {
			return Math.max(1F, volume);
		}
	}

	private static final class Radio {
		final String spec;
		float range = 1F;
		int stereo;
		/** Titel, der gerade zu hören ist (null = noch unbekannt). */
		String title;
		/** Fehler beim Starten (für die Anzeige über der Jukebox). */
		String error;
		final StreamSoundInstance instance;
		int age;

		Radio(String spec, StreamSoundInstance instance) {
			this.spec = spec;
			this.instance = instance;
		}
	}

	private static final Map<BlockPos, Radio> RADIOS = new HashMap<>();
	/** Jukeboxen, die nicht starten konnten: erst ab diesem Tick wieder versuchen. */
	private static final Map<BlockPos, Integer> RETRY_AFTER = new HashMap<>();
	private static final Map<BlockPos, String> FAILED = new HashMap<>();

	/** Jukeboxen, deren Stream zuletzt nicht ging (mit Fehlertext), solange sie auf den nächsten Versuch warten. */
	public static Map<BlockPos, String> failed() {
		return FAILED;
	}
	private static int tick;
	private static ClientLevel lastLevel;

	private RadioManager() {
	}

	public static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level != lastLevel) {
			stopAll(mc);
			lastLevel = level;
		}
		if (level == null || mc.player == null) {
			return;
		}
		if (Settings.muted()) {
			if (!RADIOS.isEmpty()) {
				stopAll(mc);
			}
			return;
		}
		RADIOS.values().forEach(r -> r.age++);
		if (++tick % SCAN_INTERVAL != 0) {
			return;
		}

		Vec3 me = mc.player.position();
		Map<BlockPos, Wanted> wanted = findRadios(level, me);

		// Laufende Radios beenden, die nicht mehr gelten (oder die Reichweite ändert sich)
		Iterator<Map.Entry<BlockPos, Radio>> it = RADIOS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<BlockPos, Radio> e = it.next();
			Wanted w = wanted.get(e.getKey());
			Radio radio = e.getValue();
			double dist = Math.sqrt(Vec3.atCenterOf(e.getKey()).distanceToSqr(me));
			boolean finished = radio.age > GRACE_TICKS && !mc.getSoundManager().isActive(radio.instance);
			if (w == null || w.volume() <= 0 || w.source().isPaused() || !w.source().spec().equals(radio.spec) || w.range() != radio.range
					|| w.stereo() != radio.stereo || dist > STOP_DISTANCE * radio.range || finished) {
				if (finished && radio.error != null && w != null && w.source().spec().equals(radio.spec)) {
					// Fehler (Video gesperrt o. Ä.): nicht sofort wieder versuchen, Fehler über der Jukebox lassen
					RETRY_AFTER.put(e.getKey(), tick + 30 * 20);
					FAILED.put(e.getKey(), radio.error);
				}
				stop(mc, radio);
				it.remove();
			} else {
				radio.instance.userVolume = Math.min(1F, w.volume()); // Lautstärke live anpassen
			}
		}

		// Neue Radios in Hörweite starten
		for (Map.Entry<BlockPos, Wanted> e : wanted.entrySet()) {
			BlockPos pos = e.getKey();
			Wanted w = e.getValue();
			Integer retry = RETRY_AFTER.get(pos);
			if (retry != null && tick < retry) {
				continue;
			}
			RETRY_AFTER.remove(pos);
			FAILED.remove(pos);
			if (RADIOS.containsKey(pos) || w.volume() <= 0 || w.source().isPaused()
					|| Math.sqrt(Vec3.atCenterOf(pos).distanceToSqr(me)) > START_DISTANCE * w.range()) {
				continue;
			}
			start(mc, level, pos, w);
		}
	}

	private static void start(Minecraft mc, ClientLevel level, BlockPos pos, Wanted wanted) {
		Source source = wanted.source();
		Playback playback = Playback.radio(source, () -> level.getGameTime() / 20.0);
		StreamSoundInstance instance = new StreamSoundInstance(playback, Vec3.atCenterOf(pos), wanted.range(), wanted.stereo());
		instance.userVolume = Math.min(1F, wanted.volume());
		Radio radio = new Radio(source.spec(), instance);
		radio.range = wanted.range();
		radio.stereo = wanted.stereo();
		instance.setOnError(msg -> radio.error = msg);
		instance.setOnTrack(title -> {
			radio.title = title;
			if (RADIOS.get(pos) == radio && Settings.nowPlaying()) {
				mc.gui.hud.setNowPlaying(Component.literal(title));
			}
		});
		RADIOS.put(pos.immutable(), radio);
		MultiMediaClient.LOGGER.info("Radio an bei {}: {}", pos, source.spec());
		if (mc.getSoundManager().play(instance) == net.minecraft.client.sounds.SoundEngine.PlayResult.NOT_STARTED) {
			// kein Platz für einen weiteren Stream o. Ä.: nicht ständig neu versuchen
			RADIOS.remove(pos);
			RETRY_AFTER.put(pos.immutable(), tick + 30 * 20);
			MultiMediaClient.LOGGER.info("Radio bei {} konnte nicht starten (zu viele gleichzeitig?) – neuer Versuch in 30 s", pos);
		}
	}

	private static void stop(Minecraft mc, Radio radio) {
		radio.instance.cancel();
		mc.getSoundManager().stop(radio.instance);
	}

	public static void stopAll(Minecraft mc) {
		RADIOS.values().forEach(r -> stop(mc, r));
		RADIOS.clear();
		RETRY_AFTER.clear();
		FAILED.clear();
		lastScreens = List.of();
		lastMasters = List.of();
		lastNoSender = Map.of();
	}

	/** Ein laufendes Radio, wie es für Titel-Anzeige und Noten gebraucht wird. */
	public record Playing(BlockPos pos, String spec, String title, String error, boolean audible) {
	}

	/** Alle Radios, die gerade laufen. */
	public static List<Playing> playing(Minecraft mc) {
		List<Playing> out = new ArrayList<>();
		RADIOS.forEach((pos, r) -> out.add(new Playing(pos, r.spec, r.title, r.error,
				!r.instance.isCancelled() && mc.getSoundManager().isActive(r.instance))));
		return out;
	}

	/** Spielt an dieser Jukebox gerade ein Radio? */
	public static boolean isPlayingAt(BlockPos pos) {
		return RADIOS.containsKey(pos);
	}

	/** Läuft in der Nähe (Hörweite) ein Radio? */
	public static boolean isNear(Vec3 me) {
		return RADIOS.keySet().stream().anyMatch(p -> Vec3.atCenterOf(p).distanceToSqr(me) <= START_DISTANCE * START_DISTANCE);
	}

	/** Was auf einem Schild steht: Link (Haupt-Jukebox) und/oder Kanal ("#haus"). */
	public record SignInfo(Source source, String channel, float volume, int stereo, boolean screen, String tv) {
	}

	/** Schild-Info für einen Block: direkt ein Schild, oder eine Jukebox mit Schild daneben. */
	public static SignInfo infoAt(ClientLevel level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
			return parseSign(sign);
		}
		for (Direction d : Direction.values()) {
			if (level.getBlockEntity(pos.relative(d)) instanceof SignBlockEntity sign) {
				SignInfo info = parseSign(sign);
				if (info != null) {
					return info;
				}
			}
		}
		return null;
	}

	/** Text eines Schilds (oder des Schilds an einer Jukebox), Vorder- und Rückseite zusammen – für Hinweise. */
	public static String rawTextAt(ClientLevel level, BlockPos pos) {
		StringBuilder sb = new StringBuilder();
		List<SignBlockEntity> signs = new ArrayList<>();
		if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
			signs.add(sign);
		} else {
			for (Direction d : Direction.values()) {
				if (level.getBlockEntity(pos.relative(d)) instanceof SignBlockEntity sign) {
					signs.add(sign);
				}
			}
		}
		for (SignBlockEntity sign : signs) {
			lines(sign, SignTextSlot.FRONT).forEach(l -> sb.append(l).append(' '));
			lines(sign, SignTextSlot.BACK).forEach(l -> sb.append(l).append(' '));
		}
		return sb.toString();
	}

	/** Hängt an dieser Jukebox ein Schild mit Link oder Kanal? (Dann spielt sie im Radio-Modus.) */
	public static boolean isRadioJukebox(ClientLevel level, BlockPos jukebox) {
		for (Direction d : Direction.values()) {
			if (level.getBlockEntity(jukebox.relative(d)) instanceof SignBlockEntity sign) {
				SignInfo info = parseSign(sign);
				if (info != null && !info.screen()) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Alle Jukeboxen mit Disc und Radio-Schild in der Umgebung.
	 * Haupt-Jukeboxen (Link) können einen Kanal setzen; Lautsprecher (nur Kanal) spielen dessen Link.
	 */
	private static Map<BlockPos, Wanted> findRadios(ClientLevel level, Vec3 me) {
		Map<BlockPos, Wanted> found = new HashMap<>();
		List<Master> masters = new ArrayList<>();
		List<ScreenSign> screens = new ArrayList<>();
		Map<BlockPos, SignInfo> speakers = new HashMap<>();
		String myName = Minecraft.getInstance().getUser().getName();
		int cx = BlockPos.containing(me).getX() >> 4;
		int cz = BlockPos.containing(me).getZ() >> 4;
		// so weit suchen, dass jede Haupt-Quelle eines hörbaren Lautsprechers gefunden wird (für alle Spieler gleich)
		int r = (int) Math.ceil((CHANNEL_DISTANCE + START_DISTANCE) / 16) + 1;
		for (int x = cx - r; x <= cx + r; x++) {
			for (int z = cz - r; z <= cz + r; z++) {
				if (!level.hasChunk(x, z)) {
					continue;
				}
				LevelChunk chunk = level.getChunk(x, z);
				for (BlockEntity be : List.copyOf(chunk.getBlockEntities().values())) {
					if (!(be instanceof SignBlockEntity sign)) {
						continue;
					}
					SignInfo info = parseSign(sign);
					if (info == null) {
						continue;
					}
					BlockPos sp = sign.getBlockPos().immutable();
					if (info.screen()) {
						// Leinwand: kein Jukebox-Schild, kann aber Haupt-Quelle eines Kanals sein (Ton über die Lautsprecher)
						screens.add(new ScreenSign(sp, info));
						if (info.tv() != null && info.channel() != null) {
							// Live-Bildschirm: Ton über die Lautsprecher – außer beim Sender selbst (der hört seinen PC ja schon)
							Source live = Source.live(info.tv());
							masters.add(new Master(sp, info.channel(), new Wanted(live, info.volume(), 0),
									info.tv().equalsIgnoreCase(myName) || Settings.tvSource(info.tv()) == null));
						} else if (info.source() != null && info.channel() != null) {
							masters.add(new Master(sp, info.channel(), new Wanted(info.source(), info.volume(), 0), false));
						}
						continue;
					}
					for (Direction d : Direction.values()) {
						BlockPos jp = sp.relative(d);
						if (!hasRecord(level.getBlockState(jp))) {
							continue; // jede Jukebox braucht eine Disc
						}
						if (info.source() != null) {
							Wanted w = new Wanted(info.source(), info.volume(), info.stereo());
							found.putIfAbsent(jp.immutable(), w);
							if (info.channel() != null) {
								masters.add(new Master(jp.immutable(), info.channel(), w, false)); // Lautstärke des Haupt-Schilds gilt fürs ganze System
							}
						} else {
							speakers.putIfAbsent(jp.immutable(), info);
						}
					}
				}
			}
		}
		lastMasters = masters;
		// Lautsprecher: die nächste Haupt-Quelle mit ihrem Kanal (ohne Quelle in Reichweite: still)
		Map<BlockPos, String> noSender = new HashMap<>();
		speakers.forEach((pos, info) -> {
			Master main = nearestMaster(masters, info.channel(), pos);
			if (main == null) {
				noSender.put(pos, info.channel());
			} else if (!main.silent()) {
				// Gesamtlautstärke (Haupt-Schild) x eigene Lautstärke (Lautsprecher-Schild)
				found.putIfAbsent(pos, new Wanted(main.wanted().source(), main.wanted().volume() * info.volume(), info.stereo()));
			}
		});
		lastNoSender = noSender;
		lastScreens = screens;
		return found;
	}

	/** Haupt-Quelle eines Kanals: Jukebox mit Link oder Leinwand; silent = ihr Ton kommt hier nicht aus den Lautsprechern. */
	public record Master(BlockPos pos, String channel, Wanted wanted, boolean silent) {
	}

	private static volatile List<Master> lastMasters = List.of();
	private static volatile Map<BlockPos, String> lastNoSender = Map.of();

	/** Nächste Haupt-Quelle mit diesem Kanal (bis CHANNEL_DISTANCE); bei Gleichstand entscheidet die Position – für alle gleich. */
	static Master nearestMaster(List<Master> masters, String channel, BlockPos to) {
		Master best = null;
		double bestDist = CHANNEL_DISTANCE * CHANNEL_DISTANCE;
		for (Master m : masters) {
			if (!m.channel().equals(channel)) {
				continue;
			}
			double d = m.pos().distSqr(to);
			if (d < bestDist || (d == bestDist && best != null && m.pos().compareTo(best.pos()) < 0)) {
				best = m;
				bestDist = d;
			}
		}
		return best;
	}

	/** Alle Haupt-Quellen aus dem letzten Scan. */
	public static List<Master> masters() {
		return lastMasters;
	}

	/** Lautsprecher (Jukeboxen nur mit Kanal), zu deren Kanal es keine Haupt-Quelle in der Nähe gibt. */
	public static Map<BlockPos, String> speakersWithoutSender() {
		return lastNoSender;
	}

	/** Ein Schild mit "bildschirm" (Leinwand), Stand des letzten Scans. */
	public record ScreenSign(BlockPos pos, SignInfo info) {
	}

	private static volatile List<ScreenSign> lastScreens = List.of();

	/** Bildschirm-Schilder in der Nähe (letzter Scan). */
	public static List<ScreenSign> screens() {
		return lastScreens;
	}

	/** Was auf einem Kanal an dieser Stelle läuft (nächste Haupt-Quelle) oder null. */
	public static Source channelSource(String channel, BlockPos at) {
		if (channel == null) {
			return null;
		}
		Master m = nearestMaster(lastMasters, channel, at);
		return m == null ? null : m.wanted().source();
	}

	private static boolean hasRecord(BlockState state) {
		return state.is(Blocks.JUKEBOX) && state.getValue(JukeboxBlock.HAS_RECORD);
	}


	/**
	 * Liest ein Schild (Vorder- oder Rückseite): Link (darf über mehrere Zeilen gehen),
	 * Zeilen mit nur "mix"/"loop" als Optionen, "#name" bzw. "#1.1" als Kanal (wie Funkkanäle). Null, wenn nichts davon draufsteht.
	 */
	static SignInfo parseSign(SignBlockEntity sign) {
		SignParser.Info info = SignParser.parse(lines(sign, SignTextSlot.FRONT), lines(sign, SignTextSlot.BACK));
		return info == null ? null : new SignInfo(info.source(), info.channel(), info.volume(), info.stereo(), info.screen(), info.tv());
	}

	private static List<String> lines(SignBlockEntity sign, SignTextSlot slot) {
		List<String> out = new ArrayList<>();
		for (Component line : sign.getText(slot).getMessages(false)) {
			out.add(line.getString());
		}
		return out;
	}
}
