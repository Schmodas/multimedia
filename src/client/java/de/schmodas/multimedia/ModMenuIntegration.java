package de.schmodas.multimedia;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Zahnrad-Knopf in Mod Menu öffnet die Einstellungen (wird nur geladen, wenn Mod Menu da ist). */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return SettingsScreen::new;
	}
}
