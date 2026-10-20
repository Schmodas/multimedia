package de.schmodas.multimedia;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Übersetzung der Texte im Spiel: Die Mod ist auf Deutsch geschrieben; ist Minecraft nicht auf Deutsch
 * eingestellt, kommt der englische Text aus assets/multimedia/texts_en.json (deutscher Text -> englischer Text).
 */
public final class T {
	private static volatile Map<String, String> english;
	private static final Set<String> MISSING = ConcurrentHashMap.newKeySet();

	private T() {
	}

	/** Ist Minecraft auf Deutsch (de_de, de_at, de_ch …) eingestellt? */
	public static boolean german() {
		try {
			Minecraft mc = Minecraft.getInstance();
			return mc == null || mc.getLanguageManager() == null || mc.getLanguageManager().getSelected().startsWith("de");
		} catch (Exception e) {
			return true;
		}
	}

	/** Text in der Sprache des Spielers. */
	public static String t(String german) {
		if (german == null || german()) {
			return german;
		}
		String en = english().get(german);
		if (en == null) {
			if (MISSING.add(german)) {
				MultiMediaClient.LOGGER.debug("Keine Übersetzung für: {}", german);
			}
			return german;
		}
		return en;
	}

	private static Map<String, String> english() {
		Map<String, String> m = english;
		if (m == null) {
			try (InputStream in = T.class.getResourceAsStream("/assets/multimedia/texts_en.json")) {
				m = in == null ? Map.of() : new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
						new TypeToken<Map<String, String>>() {
						}.getType());
			} catch (Exception e) {
				m = Map.of();
			}
			english = m;
		}
		return m;
	}
}
