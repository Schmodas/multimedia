package de.schmodas.multimedia.test;

import de.schmodas.multimedia.LeanView;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import de.schmodas.multimedia.RadioManager;
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

			// das Jukebox-Modell ohne Ansicht, schräg von oben und nah von der Seite
			server.runCommand("tp @a -1.3 100 -1.3 -45 38");
			ctx.waitTicks(40);
			ctx.takeScreenshot("0_modell_schraeg");
			server.runCommand("tp @a 1.9 99.6 -0.4 70 12");
			ctx.waitTicks(30);
			ctx.takeScreenshot("0_modell_seite");
			// ohne Platten
			shoot(ctx, server, "0.5 100 -1.6 0 35", "1_vorne_leer");
			// mit Platten
			server.runCommand("give @a minecraft:music_disc_cat");
			server.runCommand("give @a minecraft:music_disc_pigstep");
			server.runCommand("give @a minecraft:music_disc_13");
			shoot(ctx, server, "0.5 100 -1.6 0 35", "2_vorne_platten");
			shoot(ctx, server, "-0.9 100 -0.9 -45 35", "3_schraeg_platten");
			// weites Sichtfeld wie bei vielen Spielern
			ctx.runOnClient(mc -> mc.options.fov().set(110));
			shoot(ctx, server, "-0.9 100 -0.9 -45 35", "4_fov110_schraeg");
			shoot(ctx, server, "0.5 100 -1.6 0 35", "5_fov110_vorne");
			// enger Raum: Decke 3 Blöcke hoch, Wände knapp
			server.runCommand("fill -2 103 -2 2 103 2 minecraft:stone");
			server.runCommand("fill -2 100 -2 2 102 -2 minecraft:stone_bricks");
			server.runCommand("fill -2 100 -2 -2 102 2 minecraft:stone_bricks");
			shoot(ctx, server, "-0.9 100 -0.9 -45 35", "6_raum_schraeg");
			// Jukebox unter der Decke (über Augenhöhe): stehen bleiben, hochschauen
			server.runCommand("setblock 1 102 1 minecraft:jukebox");
			ctx.waitTicks(10);
			shoot(ctx, server, "1.5 100 -0.6 0 -40", "7_decke", new BlockPos(1, 102, 1));
			ctx.runOnClient(mc -> mc.options.fov().set(70));
			// Jukebox 5 Blöcke über dem Kopf: Hotbars sollen in Reichweite schweben
			server.runCommand("setblock 4 106 0 minecraft:jukebox");
			ctx.waitTicks(10);
			shoot(ctx, server, "4.5 100 -1.5 0 -60", "7b_hoch", new BlockPos(4, 106, 0));
			channelLogic();
			// Schild-Editor einer Haupt-Jukebox mit Kanal: Knopf "Bei Pause: Kanal halten/freigeben"
			server.runCommand("fill -2 103 -2 2 103 2 minecraft:air");
			server.runCommand("setblock 0 100 -1 minecraft:oak_wall_sign[facing=north]{front_text:{messages:[\"youtu.be/dQw4w9WgXcQ\",\"#kino\",\"\",\"\"]}}");
			ctx.waitTicks(10);
			shoot(ctx, server, "0.5 100 -2.2 0 35", "8_mit_schild");
			ctx.runOnClient(mc -> LeanView.signAction(mc, null));
			ctx.waitTicks(30);
			ctx.takeScreenshot("9_schild_editor");
			// Jukebox in der Ecke: Wände direkt hinter den beiden hinteren Kanten
			server.runCommand("fill 1 100 -1 1 102 1 minecraft:stone_bricks");
			server.runCommand("fill -1 100 1 1 102 1 minecraft:stone_bricks");
			ctx.waitTicks(10);
			shoot(ctx, server, "-0.9 100 -0.9 -45 35", "10_ecke");
		}
	}

	/** Prüft, welchen Kanal ein Lautsprecher "#kino #1.2" spielt. */
	private static void channelLogic() {
		BlockPos at = new BlockPos(0, 0, 0);
		de.schmodas.multimedia.Source film = de.schmodas.multimedia.Source.parse("youtu.be/aaaaaaaaaaa");
		de.schmodas.multimedia.Source filmPaused = de.schmodas.multimedia.Source.parse("youtu.be/aaaaaaaaaaa ⏸12.0");
		de.schmodas.multimedia.Source house = de.schmodas.multimedia.Source.parse("youtu.be/bbbbbbbbbbb");
		java.util.function.BiFunction<de.schmodas.multimedia.Source, Boolean, RadioManager.Master> kino = (src, rel) ->
				new RadioManager.Master(new BlockPos(5, 0, 0), "kino", new RadioManager.Wanted(src, 1F, 0), false, rel);
		RadioManager.Master haus = new RadioManager.Master(new BlockPos(9, 0, 0), "1.2", new RadioManager.Wanted(house, 1F, 0), false, false);
		java.util.List<String> chans = java.util.List.of("kino", "1.2");
		check("Kino läuft -> Kino", RadioManager.choose(java.util.List.of(kino.apply(film, false), haus), chans, at, 100), "kino");
		check("kein Kino -> Haus", RadioManager.choose(java.util.List.of(haus), chans, at, 100), "1.2");
		check("Kino Pause, halten -> Kino (still)", RadioManager.choose(java.util.List.of(kino.apply(filmPaused, false), haus), chans, at, 100), "kino");
		check("Kino Pause, freigeben -> Haus", RadioManager.choose(java.util.List.of(kino.apply(filmPaused, true), haus), chans, at, 100), "1.2");
		check("nichts -> null", RadioManager.choose(java.util.List.of(), chans, at, 100), null);
		check("Parser", de.schmodas.multimedia.SignParser.parse(java.util.List.of("#kino #1.2 ⇆"), java.util.List.of()).channels().toString()
				+ de.schmodas.multimedia.SignParser.parse(java.util.List.of("#kino #1.2 ⇆"), java.util.List.of()).release(), "[kino, 1.2]true");
	}

	private static void check(String what, Object got, String want) {
		String g = got instanceof RadioManager.Master m ? m.channel() : got == null ? null : got.toString();
		boolean ok = java.util.Objects.equals(g, want);
		de.schmodas.multimedia.MultiMediaClient.LOGGER.info("KANAL-TEST {}: {} ({})", ok ? "OK" : "FEHLER", what, g);
	}

	private static void shoot(ClientGameTestContext ctx, net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext server,
			String tp, String name) {
		shoot(ctx, server, tp, name, JUKEBOX);
	}

	private static void shoot(ClientGameTestContext ctx, net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext server,
			String tp, String name, BlockPos jukebox) {
		ctx.runOnClient(mc -> {
			if (LeanView.active()) {
				LeanView.close();
			}
		});
		ctx.waitTicks(30);
		ctx.runOnClient(mc -> mc.gui.setScreen(null));
		server.runCommand("tp @a " + tp);
		ctx.waitTicks(40);
		ctx.runOnClient(mc -> LeanView.open(jukebox));
		ctx.waitTicks(40);
		ctx.takeScreenshot(name);
	}
}
