package de.schmodas.multimedia;

import com.mojang.serialization.Codec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Einstellungsseite (über Mod Menu). Alles hier gilt nur für einen selbst –
 * was auf den Schildern steht, ist für alle gleich.
 */
public class SettingsScreen extends OptionsSubScreen {
	private static final String KEY = "multimedia.options.";

	public SettingsScreen(Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.translatable(KEY + "title"));
	}

	@Override
	protected void addOptions() {
		// ---------------------------------------------------------------- Anzeige
		this.list.addHeader(Component.translatable(KEY + "header.display"));
		this.list.addSmall(
				bool("titles", !Settings.hideTitles(), v -> Settings.setHideTitles(!v)),
				bool("title_background", Settings.titleBackground(), Settings::setTitleBackground),
				intSlider("title_distance", 1, 8, Settings.titleDistance() / 8, v -> Settings.setTitleDistance(v * 8),
						v -> Component.translatable(KEY + "blocks", v * 8)),
				notes(),
				bool("now_playing", Settings.nowPlaying(), Settings::setNowPlaying),
				bool("lean_view", Settings.leanView(), Settings::setLeanView),
				bool("chat", Settings.chatMessages(), Settings::setChatMessages));

		// ---------------------------------------------------------------- Klang
		this.list.addHeader(Component.translatable(KEY + "header.sound"));
		this.list.addSmall(
				bool("enabled", !Settings.muted(), v -> {
					Settings.setMuted(!v);
					if (!v) {
						RadioManager.stopAll(Minecraft.getInstance());
					}
				}),
				bool("reverb", Settings.reverb(), Settings::setReverb),
				bool("screens", Settings.screens(), Settings::setScreens),
				intSlider("screen_brightness", 2, 20, Settings.screenBrightness() / 5, v -> Settings.setScreenBrightness(v * 5),
						v -> Component.literal(v * 5 + " %")),
				intSlider("walls", 0, 10, Settings.wallStrength(), Settings::setWallStrength, SettingsScreen::wallLabel));

		// ---------------------------------------------------------------- Blöcke
		this.list.addHeader(Component.translatable(KEY + "header.blocks"));
		Map<String, Integer> blocks = Settings.blockDampings();
		if (blocks.isEmpty()) {
			// Hinweis statt leerer Liste
			Button hint = Button.builder(Component.translatable(KEY + "blocks_hint"), b -> {
			}).width(310).tooltip(Tooltip.create(Component.translatable(KEY + "blocks_hint.tooltip"))).build();
			hint.active = false;
			this.list.addBig(hint);
		} else {
			List<OptionInstance<?>> sliders = new ArrayList<>();
			blocks.forEach((id, value) -> sliders.add(blockSlider(id, value)));
			this.list.addSmall(sliders.toArray(OptionInstance[]::new));
		}
	}

	// ---------------------------------------------------------------- Bausteine

	private static OptionInstance<Boolean> bool(String key, boolean value, java.util.function.Consumer<Boolean> onChange) {
		return OptionInstance.createBoolean(KEY + key, OptionInstance.cachedConstantTooltip(
				Component.translatable(KEY + key + ".tooltip")), value, onChange::accept);
	}

	private static OptionInstance<Integer> intSlider(String key, int min, int max, int value,
			java.util.function.IntConsumer onChange, java.util.function.IntFunction<Component> label) {
		return new OptionInstance<>(KEY + key,
				OptionInstance.cachedConstantTooltip(Component.translatable(KEY + key + ".tooltip")),
				(caption, v) -> Options.genericValueLabel(caption, label.apply(v)),
				new OptionInstance.IntRange(min, max, false), Math.max(min, Math.min(max, value)), onChange::accept);
	}

	private static OptionInstance<Integer> notes() {
		return new OptionInstance<>(KEY + "notes",
				OptionInstance.cachedConstantTooltip(Component.translatable(KEY + "notes.tooltip")),
				(caption, v) -> Component.translatable(KEY + "notes." + v), // Knopf setzt "Noten:" selbst davor
				new OptionInstance.Enum<>(List.of(0, 1, 2, 3), Codec.INT), Settings.notes(), Settings::setNotes);
	}

	private static Component wallLabel(int v) {
		return v == 0 ? Component.translatable(KEY + "off") : v == 5
				? Component.translatable(KEY + "walls.default", v) : Component.literal(String.valueOf(v));
	}

	/** Regler für einen Block: -1 = Standard (wird entfernt), 0..10 = eigene Dämpfung. */
	private static OptionInstance<Integer> blockSlider(String id, int value) {
		Identifier ident = Identifier.tryParse(id);
		Component name = ident != null && BuiltInRegistries.BLOCK.containsKey(ident)
				? BuiltInRegistries.BLOCK.getValue(ident).getName()
				: Component.literal(id.replace("minecraft:", ""));
		return new OptionInstance<>(KEY + "block",
				OptionInstance.cachedConstantTooltip(Component.translatable(KEY + "block.tooltip")),
				(caption, v) -> Options.genericValueLabel(name, v < 0
						? Component.translatable(KEY + "block.default") : Component.literal(String.valueOf(v))),
				new OptionInstance.IntRange(-1, 10, false), value, v -> Settings.setBlockDamping(id, v < 0 ? null : v));
	}
}
