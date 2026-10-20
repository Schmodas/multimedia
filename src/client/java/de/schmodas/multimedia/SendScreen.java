package de.schmodas.multimedia;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Menü nach Klick auf "Senden" im Schild-Editor: welcher Monitor (mit Vorschaubild), wen einladen, starten.
 */
public class SendScreen extends Screen {
	private static final int PREVIEW_W = 128;
	private static final int PREVIEW_H = 72;
	private static final int GAP = 8;

	private final List<Identifier> previews = new ArrayList<>();
	private volatile boolean loading = true;
	private volatile String loadError;
	private int selected = Settings.tvMonitor();
	private final Set<String> invite = new LinkedHashSet<>(Settings.tvInvited());
	private final List<String> players = new ArrayList<>();
	/** Ton: all, except, only, off – und das Programm für except/only. */
	private String soundMode;
	private String program;
	private final List<String> programs = new ArrayList<>();

	/** true: es wird schon gesendet, hier nur ändern (Zuschauer bleiben verbunden). */
	private final boolean change;

	public SendScreen() {
		this(false);
	}

	public SendScreen(boolean change) {
		super(Component.literal(change ? T.t("Senden ändern") : T.t("Bildschirm senden")));
		this.change = change && TvSender.isSending();
		Minecraft mc = Minecraft.getInstance();
		var conn = mc.getConnection();
		if (conn != null) {
			for (PlayerInfo p : conn.getOnlinePlayers()) {
				String name = p.getProfile().name();
				if (!name.equalsIgnoreCase(mc.getUser().getName())) {
					players.add(name);
				}
			}
		}
		players.sort(String.CASE_INSENSITIVE_ORDER);
		invite.retainAll(players); // nur, wer gerade online ist
		String sound = Settings.tvSound();
		int colon = sound.indexOf(':');
		soundMode = colon < 0 ? sound : sound.substring(0, colon);
		program = colon < 0 ? null : sound.substring(colon + 1);
		if (program != null) {
			programs.add(program);
		}
		loadPreviews();
		Thread t = new Thread(() -> {
			List<String> found = TvSender.audioPrograms();
			Minecraft.getInstance().execute(() -> {
				for (String f : found) {
					if (programs.stream().noneMatch(f::equalsIgnoreCase)) {
						programs.add(f);
					}
				}
				if (program == null && !programs.isEmpty()) {
					program = programs.contains("Discord.exe") ? "Discord.exe" : programs.get(0);
				}
				rebuildWidgets();
			});
		}, "MultiMedia-Programs");
		t.setDaemon(true);
		t.start();
	}

	private void loadPreviews() {
		Tools.ensure(MultiMediaClient::info).thenAcceptAsync(paths -> {
			List<byte[]> pngs = TvSender.monitorPreviews(paths);
			Minecraft.getInstance().execute(() -> {
				for (int i = 0; i < pngs.size(); i++) {
					try {
						NativeImage img = NativeImage.read(pngs.get(i));
						Identifier id = Identifier.fromNamespaceAndPath(MultiMediaClient.MOD_ID, "preview/" + i);
						Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, img));
						previews.add(id);
					} catch (Exception e) {
						MultiMediaClient.LOGGER.warn("Vorschau {} kaputt: {}", i, e.toString());
					}
				}
				if (previews.isEmpty()) {
					loadError = T.t("Keine Monitore gefunden – Aufnahme geht hier nicht.");
				}
				if (selected >= previews.size()) {
					selected = 0;
				}
				loading = false;
				rebuildWidgets();
			});
		}).exceptionally(e -> {
			loadError = T.t("Programme fehlen: ") + e.getMessage();
			loading = false;
			return null;
		});
	}

	private int previewTop() {
		return 46;
	}

	private int previewLeft(int i) {
		int n = Math.max(1, previews.size());
		int total = n * PREVIEW_W + (n - 1) * GAP;
		return this.width / 2 - total / 2 + i * (PREVIEW_W + GAP);
	}

	@Override
	protected void init() {
		int y = previewTop() + PREVIEW_H + 30;
		// Ton
		boolean withProgram = soundMode.equals("except") || soundMode.equals("only");
		this.addRenderableWidget(Button.builder(Component.literal(soundLabel()), b -> {
			soundMode = switch (soundMode) {
				case "all" -> "except";
				case "except" -> "only";
				case "only" -> "off";
				default -> "all";
			};
			rebuildWidgets();
		}).bounds(withProgram ? this.width / 2 - 154 : this.width / 2 - 100, y, withProgram ? 150 : 200, 20)
				.tooltip(Tooltip.create(Component.literal(T.t("Der Ton deines PCs geht mit (Minecraft selbst nicht). Freunde hören ihn aus ")
						+ T.t("Jukeboxen mit demselben #Kanal wie die Leinwand.")))).build());
		if (withProgram) {
			Button prog = Button.builder(Component.literal(program == null ? T.t("Programm: (keins gefunden)") : T.t("Programm: ") + program), b -> {
				if (!programs.isEmpty()) {
					int i = program == null ? -1 : programs.indexOf(program);
					program = programs.get((i + 1) % programs.size());
					b.setMessage(Component.literal(T.t("Programm: ") + program));
				}
			}).bounds(this.width / 2 + 4, y, 150, 20).tooltip(Tooltip.create(Component.literal(
					T.t("Klicken zum Wechseln. Zur Auswahl stehen Programme, die gerade Ton machen (z. B. Discord).")))).build();
			prog.active = !programs.isEmpty();
			this.addRenderableWidget(prog);
		}
		y += 42; // Platz für die Überschrift "Einladen"
		// Einladen
		int col = 0;
		int x0 = this.width / 2 - 154;
		for (String p : players.subList(0, Math.min(players.size(), 12))) {
			String name = p;
			Button b = Button.builder(label(name), btn -> {
				if (!invite.remove(name)) {
					invite.add(name);
				}
				btn.setMessage(label(name));
			}).bounds(x0 + col * 104, y, 100, 20).tooltip(Tooltip.create(Component.literal(
					T.t("Einladen: ") + name + T.t(" bekommt einen Code per Flüstern und kann dann zuschauen.")))).build();
			this.addRenderableWidget(b);
			col++;
			if (col == 3) {
				col = 0;
				y += 24;
			}
		}
		if (col != 0) {
			y += 24;
		}
		y = Math.max(y + 6, this.height - 30);
		Button start = Button.builder(Component.literal(change ? T.t("✔ Übernehmen") : T.t("▶ Senden starten")), b -> start())
				.bounds(this.width / 2 - 154, this.height - 30, 150, 20).build();
		start.active = !loading && loadError == null;
		this.addRenderableWidget(start);
		this.addRenderableWidget(Button.builder(Component.literal(T.t("Abbrechen")), b -> onClose())
				.bounds(this.width / 2 + 4, this.height - 30, 150, 20).build());
	}

	private String soundLabel() {
		return switch (soundMode) {
			case "except" -> T.t("Ton: Alles außer …");
			case "only" -> T.t("Ton: Nur …");
			case "off" -> T.t("Ton: Aus");
			default -> T.t("Ton: Alles");
		};
	}

	private String soundSetting() {
		if ((soundMode.equals("except") || soundMode.equals("only")) && program != null) {
			return soundMode + ":" + program;
		}
		return soundMode.equals("off") ? "off" : "all";
	}

	private Component label(String name) {
		return Component.literal((invite.contains(name) ? "✔ " : "") + name);
	}

	private void start() {
		int monitor = selected;
		Settings.setTvMonitor(monitor);
		Settings.setTvInvited(invite);
		String sound = soundSetting();
		Settings.setTvSound(sound);
		Set<String> toInvite = new LinkedHashSet<>(invite);
		if (change && TvSender.change(monitor, sound, MultiMediaClient::status)) {
			if (!toInvite.isEmpty()) {
				TvInvite.send(toInvite);
			}
			onClose();
			return;
		}
		MultiMediaClient.status(T.t("Starte Senden von Monitor ") + (monitor + 1) + " …");
		TvSender.start(monitor, sound, MultiMediaClient::status, () -> {
			TvSender s = TvSender.current();
			MultiMediaClient.status(T.t("Du sendest Monitor ") + (monitor + 1) + " (" + (s != null ? s.encoderName() + ", " + s.soundInfo() : "?") + ").");
			if (s != null && !s.networkInfo().isEmpty()) {
				MultiMediaClient.error(s.networkInfo());
			}
			if (!toInvite.isEmpty()) {
				Minecraft.getInstance().execute(() -> TvInvite.send(toInvite));
			}
		});
		onClose();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int i = 0; i < previews.size(); i++) {
			int x = previewLeft(i);
			int y = previewTop();
			if (event.x() >= x && event.x() < x + PREVIEW_W && event.y() >= y && event.y() < y + PREVIEW_H) {
				selected = i;
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
		g.centeredText(this.font, Component.literal(T.t("Welcher Monitor? (Alles, was darauf zu sehen ist, wird gesendet)")),
				this.width / 2, 30, 0xFFAAAAAA);
		if (loading) {
			g.centeredText(this.font, Component.literal(T.t("Suche Monitore …")), this.width / 2, previewTop() + PREVIEW_H / 2, 0xFFFFFFFF);
		} else if (loadError != null) {
			g.centeredText(this.font, Component.literal(loadError), this.width / 2, previewTop() + PREVIEW_H / 2, 0xFFFF5555);
		}
		for (int i = 0; i < previews.size(); i++) {
			int x = previewLeft(i);
			int y = previewTop();
			int border = i == selected ? 0xFF55FF55 : 0xFF555555;
			g.fill(x - 2, y - 2, x + PREVIEW_W + 2, y + PREVIEW_H + 2, border);
			g.blit(RenderPipelines.GUI_TEXTURED, previews.get(i), x, y, 0F, 0F, PREVIEW_W, PREVIEW_H, 256, 144, 256, 144);
			g.centeredText(this.font, Component.literal(T.t("Monitor ") + (i + 1) + (i == selected ? " ✔" : "")), x + PREVIEW_W / 2,
					y + PREVIEW_H + 6, i == selected ? 0xFF55FF55 : 0xFFAAAAAA);
		}
		int inviteTitle = previewTop() + PREVIEW_H + 30 + 42 - 13;
		g.centeredText(this.font, Component.literal(players.isEmpty() ? T.t("Niemand sonst online zum Einladen")
				: T.t("Wen einladen? (Wer schon eingeladen war, braucht keine neue Einladung)")), this.width / 2, inviteTitle, 0xFFAAAAAA);
	}

	@Override
	public void removed() {
		for (Identifier id : previews) {
			Minecraft.getInstance().getTextureManager().release(id);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
