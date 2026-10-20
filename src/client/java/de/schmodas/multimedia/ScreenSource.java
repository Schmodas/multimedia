package de.schmodas.multimedia;

import net.minecraft.resources.Identifier;

/** Etwas, das auf einer Leinwand läuft: ein YouTube-Video oder ein Live-Bildschirm. */
public interface ScreenSource {
	Identifier textureId();

	boolean hasFrame();

	/** Neues Bild in die Textur (nur Render-Thread). */
	void upload();

	void close();

	/** Hinweis auf der Leinwand (z.B. "Kein Signal"), oder null. */
	default String status() {
		return null;
	}
}
