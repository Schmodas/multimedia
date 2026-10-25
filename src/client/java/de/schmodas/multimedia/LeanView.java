package de.schmodas.multimedia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SignApplicator;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * "Über die Jukebox lehnen": Rechtsklick auf eine Jukebox lässt die Kamera zur Jukebox gleiten,
 * dort gibt es ein Bedienfeld (Platte wählen/rausnehmen, Schild bearbeiten, Pause/Neustart/Skip).
 * Alles, was die Welt verändert, läuft über normale Klicks (wie von Hand) – für den Server sieht es aus wie immer.
 */
public final class LeanView {
	private static final long IN_NANOS = 550_000_000L;
	private static final long OUT_NANOS = 450_000_000L;
	/** Weiter weg als das (Blöcke) = Ansicht schließen. */
	private static final double MAX_DISTANCE = 6.0;

	private static BlockPos jukebox;
	private static Vec3 camPos;
	private static float camYaw;
	private static float camPitch;
	private static long startNanos;
	private static boolean closing;
	private static long closeNanos;
	private static float closeFrom;
	private static boolean savedHideGui;
	/** Unsere eigenen Klicks dürfen durch (sonst würde die Ansicht sich selbst wieder öffnen). */
	private static boolean bypass;
	/** Aktion, die der Schild-Editor gleich unsichtbar ausführen soll ("pause", "restart", …); null = normal bearbeiten. */
	private static String pendingSignAction;
	private static BlockPos pendingSignPos;
	/** Platte, die eingelegt werden soll, sobald die alte draußen ist (Inventar-Platz), sonst -1. */
	private static int pendingInsertSlot = -1;
	private static int pendingInsertTicks;

	private LeanView() {
	}

	public static boolean active() {
		return jukebox != null;
	}

	public static BlockPos jukebox() {
		return jukebox;
	}

	public static boolean bypass() {
		return bypass;
	}

	// ------------------------------------------------------------------ öffnen / schließen

	/** Rechtsklick auf eine Jukebox: Ansicht öffnen. */
	public static void open(BlockPos pos) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			return;
		}
		if (jukebox == null) {
			savedHideGui = mc.gui.hud.isHidden();
		}
		jukebox = pos.immutable();
		computeTarget(mc.level, player.getEyePosition(), player.getYRot());
		startNanos = System.nanoTime();
		closing = false;
		setHudHidden(mc, true);
		mc.gui.setScreen(new LeanScreen(jukebox));
	}

	/** Bedienfeld zu (Escape): Kamera gleitet zurück. */
	public static void close() {
		if (jukebox == null || closing) {
			return;
		}
		closeFrom = progressIn();
		closing = true;
		closeNanos = System.nanoTime();
		pendingInsertSlot = -1;
	}

	private static void finish() {
		Minecraft mc = Minecraft.getInstance();
		jukebox = null;
		closing = false;
		setHudHidden(mc, savedHideGui);
		if (mc.gui.screen() instanceof LeanScreen) {
			mc.gui.setScreen(null);
		}
	}

	private static void setHudHidden(Minecraft mc, boolean hidden) {
		if (mc.gui.hud.isHidden() != hidden) {
			mc.gui.hud.toggle();
		}
	}

	public static void tick(Minecraft mc) {
		if (jukebox == null) {
			return;
		}
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		if (level == null || player == null || !level.getBlockState(jukebox).is(Blocks.JUKEBOX)
				|| player.getEyePosition().distanceTo(Vec3.atCenterOf(jukebox)) > MAX_DISTANCE) {
			if (!closing) {
				close();
				if (mc.gui.screen() instanceof LeanScreen) {
					mc.gui.setScreen(null);
				}
			}
		}
		if (closing) {
			if (progressOut() >= 1) {
				finish();
			}
			return;
		}
		// Platte wechseln: erst wenn die alte draußen ist, die neue einlegen
		if (pendingInsertSlot >= 0) {
			if (!hasRecord(level.getBlockState(jukebox))) {
				int slot = pendingInsertSlot;
				pendingInsertSlot = -1;
				insert(mc, slot);
			} else if (++pendingInsertTicks > 40) {
				pendingInsertSlot = -1; // Server hat nicht reagiert
			}
		}
		// nach Schild-Editor usw. wieder zum Bedienfeld
		if (mc.gui.screen() == null) {
			mc.gui.setScreen(new LeanScreen(jukebox));
		}
	}

	// ------------------------------------------------------------------ Kamera

	/** Wie weit die Kamera schon bei der Jukebox ist (0 = beim Spieler, 1 = bei der Jukebox), schon geglättet. */
	public static float blend() {
		if (jukebox == null) {
			return 0;
		}
		float t = closing ? closeFrom * (1 - progressOut()) : progressIn();
		return t * t * (3 - 2 * t);
	}

	private static float progressIn() {
		return Mth.clamp((System.nanoTime() - startNanos) / (float) IN_NANOS, 0, 1);
	}

	private static float progressOut() {
		return Mth.clamp((System.nanoTime() - closeNanos) / (float) OUT_NANOS, 0, 1);
	}

	public static Vec3 cameraPos() {
		return camPos;
	}

	public static float cameraYaw() {
		return camYaw;
	}

	public static float cameraPitch() {
		return camPitch;
	}

	/** Wohin die Kamera gleitet: schräg über (oder unter, bei Decken-Jukeboxen) die Jukebox, zur Jukebox hin schauend. */
	private static void computeTarget(ClientLevel level, Vec3 eye, float playerYaw) {
		Vec3 center = Vec3.atCenterOf(jukebox);
		double dx = eye.x - center.x;
		double dz = eye.z - center.z;
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 0.2) { // direkt drüber/drunter: von der Blickrichtung her
			double rad = Math.toRadians(playerYaw);
			dx = Math.sin(rad);
			dz = -Math.cos(rad);
			len = 1;
		}
		dx /= len;
		dz /= len;
		boolean below = eye.y < center.y - 1.0; // Jukebox an der Decke
		double up = below ? -1 : 1;
		Vec3 look = center.add(0, up * 0.15, 0);
		Vec3 best = null;
		double[][] tries = {{1.35, 1.4}, {1.1, 1.25}, {0.85, 1.1}, {0.6, 1.0}, {0.3, 1.1}, {0.9, 0.7}};
		for (double[] t : tries) {
			Vec3 cand = center.add(dx * t[0], up * t[1], dz * t[0]);
			if (free(level, cand)) {
				best = cand;
				break;
			}
		}
		if (best == null) { // eng: auf halbem Weg zwischen Kopf und Jukebox bleiben
			best = eye.lerp(center, 0.4);
		}
		camPos = best;
		Vec3 d = look.subtract(best);
		double h = Math.sqrt(d.x * d.x + d.z * d.z);
		camYaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
		camPitch = (float) -Math.toDegrees(Math.atan2(d.y, h));
	}

	private static boolean free(ClientLevel level, Vec3 p) {
		BlockPos bp = BlockPos.containing(p);
		return level.getBlockState(bp).getCollisionShape(level, bp).isEmpty();
	}

	// ------------------------------------------------------------------ Aktionen (wie Klicks von Hand)

	public static boolean hasRecord(BlockState state) {
		return state.is(Blocks.JUKEBOX) && state.getValue(JukeboxBlock.HAS_RECORD);
	}

	/** Schild an dieser Jukebox (das mit Link/Kanal zuerst), oder null. */
	public static BlockPos signOf(ClientLevel level, BlockPos jb) {
		BlockPos any = null;
		for (Direction d : Direction.values()) {
			BlockPos p = jb.relative(d);
			if (level.getBlockEntity(p) instanceof SignBlockEntity sign) {
				if (RadioManager.parseSign(sign) != null) {
					return p;
				}
				if (any == null) {
					any = p;
				}
			}
		}
		return any;
	}

	/** Platte aus dem Inventar einlegen (eine alte vorher rausnehmen). */
	public static void choose(Minecraft mc, int slot) {
		if (jukebox == null || mc.level == null) {
			return;
		}
		if (hasRecord(mc.level.getBlockState(jukebox))) {
			eject(mc);
			pendingInsertSlot = slot;
			pendingInsertTicks = 0;
		} else {
			insert(mc, slot);
		}
	}

	/** Platte rausnehmen: ein Klick auf die Jukebox (wie mit der Hand). */
	public static void eject(Minecraft mc) {
		click(mc, jukebox, InteractionHand.MAIN_HAND);
	}

	private static void insert(Minecraft mc, int slot) {
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null) {
			return;
		}
		Inventory inv = player.getInventory();
		if (slot == Inventory.SLOT_OFFHAND) {
			click(mc, jukebox, InteractionHand.OFF_HAND);
			return;
		}
		int selected = inv.getSelectedSlot();
		if (Inventory.isHotbarSlot(slot)) {
			inv.setSelectedSlot(slot);
			click(mc, jukebox, InteractionHand.MAIN_HAND);
			inv.setSelectedSlot(selected);
		} else {
			// aus dem Rucksack: kurz in die Hand tauschen, einlegen, zurücktauschen
			int menuSlot = slot; // Inventar-Plätze 9–35 haben im Inventar-Menü dieselbe Nummer
			int id = player.inventoryMenu.containerId;
			mc.gameMode.handleContainerInput(id, menuSlot, selected, net.minecraft.world.inventory.ContainerInput.SWAP, player);
			click(mc, jukebox, InteractionHand.MAIN_HAND);
			mc.gameMode.handleContainerInput(id, menuSlot, selected, net.minecraft.world.inventory.ContainerInput.SWAP, player);
		}
	}

	/** Schild bearbeiten (action null) oder eine Schild-Aktion unsichtbar ausführen ("pause", "resume", "restart", "next", "prev"). */
	public static void signAction(Minecraft mc, String action) {
		if (jukebox == null || mc.level == null || mc.player == null) {
			return;
		}
		BlockPos sign = signOf(mc.level, jukebox);
		if (sign == null) {
			return;
		}
		pendingSignAction = action;
		pendingSignPos = sign;
		// Farbstoff/Leuchttinte/Honigwabe in der Hand würden das Schild färben bzw. wachsen – kurz eine andere Hand nehmen
		Inventory inv = mc.player.getInventory();
		int selected = inv.getSelectedSlot();
		int use = selected;
		if (inv.getItem(selected).getItem() instanceof SignApplicator) {
			for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
				if (!(inv.getItem(i).getItem() instanceof SignApplicator)) {
					use = i;
					break;
				}
			}
		}
		inv.setSelectedSlot(use);
		click(mc, sign, InteractionHand.MAIN_HAND);
		inv.setSelectedSlot(selected);
	}

	/** Für den Schild-Editor: soll er gleich eine Aktion ausführen? (Einmal abholen.) */
	public static String takeSignAction(BlockPos signPos) {
		if (pendingSignPos == null || !pendingSignPos.equals(signPos)) {
			return null;
		}
		String a = pendingSignAction;
		pendingSignAction = null;
		pendingSignPos = null;
		return a;
	}

	/** Gibt es gleich eine unsichtbare Schild-Aktion für dieses Schild? (Dann den Editor nicht zeigen.) */
	public static boolean silentFor(BlockPos signPos) {
		return pendingSignPos != null && pendingSignPos.equals(signPos) && pendingSignAction != null;
	}

	private static void click(Minecraft mc, BlockPos pos, InteractionHand hand) {
		if (mc.gameMode == null || mc.player == null) {
			return;
		}
		Vec3 eye = mc.player.getEyePosition();
		Vec3 c = Vec3.atCenterOf(pos);
		Direction face = Direction.getApproximateNearest(eye.x - c.x, eye.y - c.y, eye.z - c.z);
		Vec3 hit = c.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
		bypass = true;
		try {
			mc.gameMode.useItemOn(mc.player, hand, new BlockHitResult(hit, face, pos, false));
		} finally {
			bypass = false;
		}
	}

	/** Platten im Inventar (Platz-Nummer und Stapel), Hand zuerst. */
	public static java.util.List<int[]> discSlots(LocalPlayer player) {
		java.util.List<int[]> out = new java.util.ArrayList<>();
		Inventory inv = player.getInventory();
		int sel = inv.getSelectedSlot();
		if (isDisc(inv.getItem(sel))) {
			out.add(new int[]{sel});
		}
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			if (i != sel && isDisc(inv.getItem(i))) {
				out.add(new int[]{i});
			}
		}
		if (isDisc(inv.getItem(Inventory.SLOT_OFFHAND))) {
			out.add(new int[]{Inventory.SLOT_OFFHAND});
		}
		return out;
	}

	public static boolean isDisc(ItemStack stack) {
		return !stack.isEmpty() && stack.has(DataComponents.JUKEBOX_PLAYABLE);
	}
}
