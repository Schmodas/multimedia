package de.schmodas.multimedia;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Wo eine Leinwand ist: Das Bildschirm-Schild hängt an einem Block; direkt darüber beginnt die
 * Leinwand. Sie ist das Rechteck aus gleichen Blöcken (z.B. schwarzer Beton), das dort anfängt –
 * so breit, wie die unterste Reihe reicht, und so hoch, wie ganze Reihen darüber passen.
 *
 * @param center  Mitte der Bildfläche (knapp vor der Wand)
 * @param facing  in welche Richtung die Bildfläche zeigt (wie das Schild)
 * @param right   "rechts" aus Sicht des Zuschauers
 */
public record ScreenGeometry(Vec3 center, Direction facing, Direction right, int width, int height) {
	private static final int MAX_WIDTH = 48;
	private static final int MAX_HEIGHT = 32;

	/** Null, wenn das Schild kein Wandschild ist oder über ihm keine passende Wand ist. */
	public static ScreenGeometry detect(ClientLevel level, BlockPos signPos) {
		BlockState sign = level.getBlockState(signPos);
		if (!(sign.getBlock() instanceof WallSignBlock)) {
			return null;
		}
		Direction facing = sign.getValue(WallSignBlock.FACING);
		BlockPos seed = signPos.relative(facing.getOpposite()).above();
		BlockState mat = level.getBlockState(seed);
		if (mat.isAir() || !mat.isSolidRender()) {
			return null;
		}
		Block block = mat.getBlock();
		Direction right = facing.getCounterClockWise();
		// unterste Reihe: so weit nach links und rechts, wie derselbe Block geht
		int left = 0;
		while (left < MAX_WIDTH && level.getBlockState(seed.relative(right, -(left + 1))).is(block)) {
			left++;
		}
		int rightCount = 0;
		while (left + rightCount + 1 < MAX_WIDTH && level.getBlockState(seed.relative(right, rightCount + 1)).is(block)) {
			rightCount++;
		}
		int width = left + rightCount + 1;
		BlockPos bottomLeft = seed.relative(right, -left);
		// nach oben, solange die ganze Reihe aus demselben Block ist
		int height = 1;
		outer:
		while (height < MAX_HEIGHT) {
			BlockPos rowStart = bottomLeft.above(height);
			for (int i = 0; i < width; i++) {
				if (!level.getBlockState(rowStart.relative(right, i)).is(block)) {
					break outer;
				}
			}
			height++;
		}
		Vec3 blCenter = Vec3.atCenterOf(bottomLeft);
		Vec3 r = Vec3.atLowerCornerOf(right.getUnitVec3i());
		Vec3 f = Vec3.atLowerCornerOf(facing.getUnitVec3i());
		Vec3 center = blCenter
				.add(r.scale((width - 1) / 2.0))
				.add(0, (height - 1) / 2.0, 0)
				.add(f.scale(0.5 + 0.004)); // knapp vor der Wand, damit nichts flackert
		return new ScreenGeometry(center, facing, right, width, height);
	}
}
