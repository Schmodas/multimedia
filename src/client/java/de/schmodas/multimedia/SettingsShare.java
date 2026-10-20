package de.schmodas.multimedia;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Einstellungen (Wand-Stärke + Block-Dämpfung) als kurzer Code im Chat teilen:
 *   ds1:w5;bookshelf=0;glass=3
 * Spieler mit der Mod bekommen darunter einen [Übernehmen]-Knopf.
 */
public final class SettingsShare {
	/** "<Name> Text" oder "Name: Text" am Anfang einer Chatzeile. */
	private static final Pattern SENDER = Pattern.compile("^\\s*(?:<([^>]{1,32})>|([A-Za-z0-9_]{3,16}):)");
	private static final Pattern CODE = Pattern.compile("ds1:[A-Za-z0-9_:.;=\\-]+");
	/** Chat erlaubt 256 Zeichen; etwas Platz für "/msg Name " lassen. */
	private static final int MAX_LENGTH = 220;

	private SettingsShare() {
	}

	public static void register() {
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> {
			Minecraft mc = Minecraft.getInstance();
			boolean own = sender != null && mc.player != null && sender.id().equals(mc.player.getUUID());
			if (!own) {
				onMessage(message.getString(), sender != null ? sender.name() : "Jemand");
			}
		});
		// manche Server schicken Chat als Systemnachricht
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				String text = message.getString();
				Matcher name = SENDER.matcher(text);
				onMessage(text, name.find() ? (name.group(1) != null ? name.group(1) : name.group(2)) : "Jemand");
			}
		});
	}

	/** Baut den Code aus den eigenen Einstellungen (null, wenn er zu lang für den Chat ist). */
	public static String encode() {
		StringBuilder sb = new StringBuilder("ds1:w").append(Settings.wallStrength());
		Settings.blockDampings().forEach((id, v) ->
				sb.append(';').append(id.startsWith("minecraft:") ? id.substring(10) : id).append('=').append(v));
		return sb.length() > MAX_LENGTH ? null : sb.toString();
	}

	public record Decoded(Integer wall, Map<String, Integer> blocks) {
	}

	public static Decoded decode(String code) {
		if (!code.startsWith("ds1:")) {
			return null;
		}
		Integer wall = null;
		Map<String, Integer> blocks = new LinkedHashMap<>();
		for (String part : code.substring(4).split(";")) {
			try {
				if (part.startsWith("w") && !part.contains("=")) {
					wall = Integer.parseInt(part.substring(1));
				} else if (part.contains("=")) {
					String[] kv = part.split("=", 2);
					String id = kv[0].contains(":") ? kv[0] : "minecraft:" + kv[0];
					blocks.put(id, Integer.parseInt(kv[1]));
				}
			} catch (NumberFormatException ignored) {
				// kaputten Teil überspringen
			}
		}
		return new Decoded(wall, blocks);
	}

	public static void apply(Decoded d) {
		if (d.wall() != null) {
			Settings.setWallStrength(d.wall());
		}
		d.blocks().forEach(Settings::setBlockDamping);
	}

	private static void onMessage(String text, String from) {
		Matcher m = CODE.matcher(text);
		if (!m.find()) {
			return;
		}
		String code = m.group();
		Decoded d = decode(code);
		if (d == null) {
			return;
		}
		String summary = (d.wall() != null ? T.t("Wände ") + d.wall() : "") +
				(d.blocks().isEmpty() ? "" : (d.wall() != null ? ", " : "") + d.blocks().size() + T.t(" Block-Einstellung(en)"));
		Component button = Component.literal(T.t("[Übernehmen]")).withStyle(s -> s
				.withColor(ChatFormatting.GREEN)
				.withClickEvent(new ClickEvent.SuggestCommand("/disc import " + code))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(T.t("Klicken, dann Enter drücken")))));
		Minecraft mc = Minecraft.getInstance();
		// einen Tick später, damit der Knopf unter der eigentlichen Chatnachricht steht
		mc.execute(() -> StreamWatcher.later(50, () -> {
			if (mc.player != null) {
				mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[MM] ").withStyle(ChatFormatting.LIGHT_PURPLE)
						.append(Component.literal(from + T.t(" teilt Disc-Stream-Einstellungen (") + summary + ") ").withStyle(ChatFormatting.GRAY))
						.append(button));
			}
		}));
	}
}
