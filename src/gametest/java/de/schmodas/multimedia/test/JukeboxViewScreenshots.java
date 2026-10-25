package de.schmodas.multimedia.test;

import de.schmodas.multimedia.LeanView;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;

/**
 * Kein echter Test, sondern ein Fotograf: baut eine Jukebox auf, öffnet die Jukebox-Ansicht
 * und speichert Screenshots (run/clientGameTest/screenshots), damit man Änderungen sieht, ohne selbst zu spielen.
 */
public class JukeboxViewScreenshots implements FabricClientGameTest {
	private static final BlockPos JUKEBOX = new BlockPos(0, 100, 0);

	@Override
	public void runTest(ClientGameTestContext ctx) {
		ctx.getInput().resizeWindow(1280, 720);
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			var server = sp.getServer();
			server.runCommand("gamerule advance_time false");
			server.runCommand("time set noon");
			server.runCommand("fill -6 99 -6 6 99 6 minecraft:spruce_planks");
			server.runCommand("fill -6 100 -6 6 106 6 minecraft:air");
			server.runCommand("setblock 0 100 0 minecraft:jukebox");
			server.runCommand("gamemode creative @a");
			ctx.waitTicks(40);

			// ohne Platten
			shoot(ctx, server, "0.5 100 -1.6 0 35", "1_vorne_leer");
			// mit Platten
			server.runCommand("give @a minecraft:music_disc_cat");
			server.runCommand("give @a minecraft:music_disc_pigstep");
			server.runCommand("give @a minecraft:music_disc_13");
			shoot(ctx, server, "0.5 100 -1.6 0 35", "2_vorne_platten");
			shoot(ctx, server, "-0.9 100 -0.9 -45 35", "3_schraeg_platten");
		}
	}

	private static void shoot(ClientGameTestContext ctx, net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext server,
			String tp, String name) {
		ctx.runOnClient(mc -> {
			if (LeanView.active()) {
				LeanView.close();
			}
		});
		ctx.waitTicks(30);
		ctx.runOnClient(mc -> mc.gui.setScreen(null));
		server.runCommand("tp @a " + tp);
		ctx.waitTicks(40);
		ctx.runOnClient(mc -> LeanView.open(JUKEBOX));
		ctx.waitTicks(40);
		ctx.takeScreenshot(name);
	}
}
