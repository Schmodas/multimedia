package de.schmodas.multimedia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;

import java.util.ArrayList;
import java.util.List;

/**
 * Bedienfeld, während man über der Jukebox "lehnt" (siehe LeanView). Die Welt bleibt sichtbar,
 * links die Platten aus dem Inventar, rechts Wiedergabe und Schild.
 */
public class LeanScreen extends Screen {
	private static final int PANEL_W = 150;
	private static final int ROW = 22;

	private final BlockPos jukebox;
	/** Platz im Inventar + Position des Knopfs, für die Platten-Symbole. */
	private final List<int[]> discButtons = new ArrayList<>();
	private String state = "";
	private int leftBottom = 60;
	private int rightBottom = 60;
	private int ticks;

	public LeanScreen(BlockPos jukebox) {
		super(Component.literal(T.t("Jukebox")));
		this.jukebox = jukebox;
	}

	@Override
	protected void init() {
		Minecraft mc = Minecraft.getInstance();
		discButtons.clear();
		state = stateKey(mc);
		if (mc.player == null || mc.level == null) {
			return;
		}
		// ---- links: Platten
		int x = 10;
		int y = 36;
		List<int[]> slots = LeanView.discSlots(mc.player);
		int max = Math.max(1, (this.height - 36 - 40) / ROW);
		for (int i = 0; i < slots.size() && i < max; i++) {
			int slot = slots.get(i)[0];
			ItemStack stack = mc.player.getInventory().getItem(slot);
			String name = discName(stack);
			Button b = Button.builder(Component.literal(shorten(name, PANEL_W - 30)), btn -> LeanView.choose(mc, slot))
					.bounds(x, y, PANEL_W, 20).tooltip(Tooltip.create(Component.literal(name + " – " + T.t("Klicken: einlegen")))).build();
			this.addRenderableWidget(b);
			discButtons.add(new int[]{slot, x + 2, y + 2});
			y += ROW;
		}
		leftBottom = Math.max(y, 36 + ROW) + 4;
		// ---- rechts: Jukebox
		int rx = this.width - PANEL_W - 10;
		int ry = 36;
		boolean hasRecord = LeanView.hasRecord(mc.level.getBlockState(jukebox));
		BlockPos sign = LeanView.signOf(mc.level, jukebox);
		RadioManager.SignInfo info = RadioManager.infoAt(mc.level, jukebox);
		Source src = info == null ? null : info.source();
		if (src != null && hasRecord) { // ohne Platte spielt nichts – dann keine Wiedergabe-Knöpfe
			boolean paused = src.isPaused();
			this.addRenderableWidget(Button.builder(Component.literal(paused ? T.t("▶ Weiter") : T.t("⏸ Pause")),
					b -> LeanView.signAction(mc, paused ? "resume" : "pause"))
					.bounds(rx, ry, PANEL_W / 2 - 1, 20).tooltip(Tooltip.create(Component.literal(paused
							? T.t("Spielt für alle mit Mod an der angehaltenen Stelle weiter.")
							: T.t("Hält Bild und Ton für alle mit Mod an dieser Stelle an.")))).build());
			this.addRenderableWidget(Button.builder(Component.literal(T.t("⟲ Neustart")), b -> LeanView.signAction(mc, "restart"))
					.bounds(rx + PANEL_W / 2 + 1, ry, PANEL_W / 2 - 1, 20).tooltip(Tooltip.create(Component.literal(
							T.t("Startet für alle mit Mod von vorne.")))).build());
			ry += ROW;
			if (src.playlist()) {
				this.addRenderableWidget(Button.builder(Component.literal(T.t("◀◀ Zurück")), b -> LeanView.signAction(mc, "prev"))
						.bounds(rx, ry, PANEL_W / 2 - 1, 20).tooltip(Tooltip.create(Component.literal(
								T.t("Vorheriger Titel (läuft der aktuelle schon länger: an dessen Anfang) – für alle mit Mod.")))).build());
				this.addRenderableWidget(Button.builder(Component.literal(T.t("Weiter ▶▶")), b -> LeanView.signAction(mc, "next"))
						.bounds(rx + PANEL_W / 2 + 1, ry, PANEL_W / 2 - 1, 20).tooltip(Tooltip.create(Component.literal(
								T.t("Nächster Titel – für alle mit Mod.")))).build());
				ry += ROW;
			}
			ry += 6;
		}
		Button edit = Button.builder(Component.literal(T.t("✎ Schild bearbeiten")), b -> LeanView.signAction(mc, null))
				.bounds(rx, ry, PANEL_W, 20).tooltip(Tooltip.create(Component.literal(sign == null
						? T.t("An dieser Jukebox hängt kein Schild.")
						: T.t("Link, Kanal, Lautstärke … auf dem Schild ändern.")))).build();
		edit.active = sign != null;
		this.addRenderableWidget(edit);
		ry += ROW;
		Button eject = Button.builder(Component.literal(T.t("⏏ Platte rausnehmen")), b -> LeanView.eject(mc))
				.bounds(rx, ry, PANEL_W, 20).build();
		eject.active = hasRecord;
		this.addRenderableWidget(eject);
		rightBottom = ry + ROW + 2;
	}

	@Override
	public void tick() {
		if (++ticks % 5 == 0) {
			String now = stateKey(Minecraft.getInstance());
			if (!now.equals(state)) {
				rebuildWidgets();
			}
		}
	}

	/** Alles, was die Knöpfe beeinflusst – ändert es sich (z.B. nach Pause), werden sie neu gebaut. */
	private String stateKey(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		sb.append(LeanView.hasRecord(mc.level.getBlockState(jukebox)));
		sb.append('|').append(RadioManager.rawTextAt(mc.level, jukebox));
		for (int[] s : LeanView.discSlots(mc.player)) {
			sb.append('|').append(s[0]).append(':').append(mc.player.getInventory().getItem(s[0]).getItem());
		}
		return sb.toString();
	}

	private static String discName(ItemStack stack) {
		return JukeboxSong.fromStack(stack).map(h -> h.value().description().getString())
				.filter(s -> !s.isBlank()).orElse(stack.getHoverName().getString());
	}

	private String shorten(String s, int width) {
		if (this.font.width(s) <= width) {
			return s;
		}
		while (!s.isEmpty() && this.font.width(s + "…") > width) {
			s = s.substring(0, s.length() - 1);
		}
		return s + "…";
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// kein Abdunkeln/Weichzeichnen: man soll die Jukebox sehen
		int top = 24;
		g.fill(4, top, 10 + PANEL_W + 6, leftBottom, 0x88000000);
		g.fill(this.width - PANEL_W - 16, top, this.width - 4, rightBottom, 0x88000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		Minecraft mc = Minecraft.getInstance();
		g.centeredText(this.font, Component.literal(nowPlaying(mc)), this.width / 2, 10, 0xFFFFFFFF);
		g.text(this.font, Component.literal(T.t("Platten")), 12, 28 - 0, 0xFFAAAAAA);
		g.text(this.font, Component.literal(T.t("Jukebox")), this.width - PANEL_W - 8, 28, 0xFFAAAAAA);
		if (discButtons.isEmpty()) {
			g.text(this.font, Component.literal(T.t("Keine Platten im Inventar")), 12, 42, 0xFF777777);
		}
		if (mc.player != null) {
			for (int[] d : discButtons) {
				g.item(mc.player.getInventory().getItem(d[0]), d[1], d[2]);
			}
		}
		g.centeredText(this.font, Component.literal(T.t("Esc – zurück")), this.width / 2, this.height - 16, 0xFFAAAAAA);
	}

	/** Oben: was läuft, mit Kanal bei Lautsprechern. */
	private String nowPlaying(Minecraft mc) {
		if (mc.level == null) {
			return "";
		}
		for (RadioManager.Playing p : RadioManager.playing(mc)) {
			if (p.pos().equals(jukebox)) {
				if (p.error() != null) {
					return "⚠ " + p.error();
				}
				return p.title() != null ? "♪ " + p.title() : T.t("lädt …");
			}
		}
		RadioManager.SignInfo info = RadioManager.infoAt(mc.level, jukebox);
		if (!LeanView.hasRecord(mc.level.getBlockState(jukebox))) {
			return T.t("Keine Platte drin");
		}
		if (info != null && info.source() != null && info.source().isPaused()) {
			return T.t("⏸ Angehalten");
		}
		if (info != null && info.source() == null && info.channel() != null) {
			return T.t("Lautsprecher für #") + info.channel();
		}
		return "♪";
	}

	@Override
	public void onClose() {
		LeanView.close();
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
