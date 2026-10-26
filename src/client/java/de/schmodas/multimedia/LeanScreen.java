package de.schmodas.multimedia;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Bildschirm, während man über der Jukebox lehnt (siehe LeanView). Er zeichnet selbst fast nichts – die Hotbars
 * stehen als Teil der Welt auf der Jukebox (LeanRender). Hier kommen nur Maus und Tastatur an.
 */
public class LeanScreen extends Screen {
	private final BlockPos jukebox;

	public LeanScreen(BlockPos jukebox) {
		super(Component.literal(T.t("Jukebox")));
		this.jukebox = jukebox;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// nichts abdunkeln: man soll die Jukebox sehen
	}

	/** Welche Hotbar und welches Feld die Maus trifft: {0=Platten/1=Knöpfe, Feld} oder null. */
	private int[] pick(double mx, double my) {
		Minecraft mc = Minecraft.getInstance();
		if (LeanView.lean() < 0.9f) {
			return null;
		}
		Camera cam = mc.gameRenderer.mainCamera();
		LeanPanels.Strip[] s = LeanPanels.strips(cam, jukebox, 1);
		Vec3 dir = LeanPanels.ray(cam, mx, my, this.width, this.height);
		for (int k = 0; k < 2; k++) {
			int i = LeanPanels.hit(s[k], cam.position(), dir);
			if (i >= 0) {
				return new int[]{k, i};
			}
		}
		return null;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		LeanPanels.refresh(mc, jukebox);
		int[] p = pick(mouseX, mouseY);
		LeanPanels.hoverDisc = p != null && p[0] == 0 ? p[1] : -1;
		LeanPanels.hoverControl = p != null && p[0] == 1 ? p[1] : -1;
		g.centeredText(this.font, Component.literal(nowPlaying(mc)), this.width / 2, 10, 0xFFFFFFFF);
		g.centeredText(this.font, Component.literal(T.t("Esc – zurück")), this.width / 2, this.height - 14, 0x99FFFFFF);
	}

	/** Oben: was läuft. */
	private String nowPlaying(Minecraft mc) {
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
			return T.t("Lautsprecher für #") + String.join(" #", info.channels());
		}
		return "♪";
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		Minecraft mc = Minecraft.getInstance();
		int[] p = pick(event.x(), event.y());
		if (p != null) {
			if (p[0] == 0) {
				List<Integer> discs = LeanPanels.discs;
				int idx = LeanPanels.scroll + p[1];
				if (idx < discs.size()) {
					LeanView.choose(mc, discs.get(idx));
				}
			} else {
				List<LeanPanels.Control> controls = LeanPanels.controls;
				if (p[1] < controls.size() && controls.get(p[1]).active()) {
					controls.get(p[1]).run().run();
				}
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		LeanPanels.scroll = Math.max(0, LeanPanels.scroll - (int) Math.signum(scrollY));
		return true;
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
