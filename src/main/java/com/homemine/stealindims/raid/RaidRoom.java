package com.homemine.stealindims.raid;

import net.minecraft.core.BlockPos;

/**
 * One room of a raid map's subdivision, on one walkable layer (design §3).
 *
 * <p>Bounds are inclusive and <em>include the room's own wall ring</em>: the walls stand on
 * {@code minX}, {@code maxX}, {@code minZ} and {@code maxZ}, and the walkable interior is
 * {@code [minX + 1, maxX - 1] × [minZ + 1, maxZ - 1]}. Two rooms of the same layer therefore meet
 * along exactly one shared wall column when {@code a.maxX() == b.minX()} (or the Z mirror), which is
 * also the adjacency test {@link RaidLayoutBuilder} uses to punch doorways.
 *
 * <p>{@link #layer} is the y a player's feet stand on inside the room; the floor blocks themselves
 * are one below it, matching {@link RaidArena#MAIN_LAYER_Y}.
 */
public record RaidRoom(int minX, int minZ, int maxX, int maxZ, int layer) {

    /** Width along X, walls included. */
    public int width() {
        return this.maxX - this.minX + 1;
    }

    /** Depth along Z, walls included. */
    public int depth() {
        return this.maxZ - this.minZ + 1;
    }

    public int area() {
        return width() * depth();
    }

    public boolean contains(int x, int z) {
        return x >= this.minX && x <= this.maxX && z >= this.minZ && z <= this.maxZ;
    }

    /** Whether {@code (x, z)} is walkable floor rather than part of the wall ring. */
    public boolean containsInterior(int x, int z) {
        return x > this.minX && x < this.maxX && z > this.minZ && z < this.maxZ;
    }

    public BlockPos center() {
        return new BlockPos((this.minX + this.maxX) / 2, this.layer, (this.minZ + this.maxZ) / 2);
    }

    /** Whether the two rooms' interiors overlap. A shared wall column does not count. */
    public boolean overlaps(RaidRoom other) {
        return this.minX < other.maxX && other.minX < this.maxX
                && this.minZ < other.maxZ && other.minZ < this.maxZ;
    }

    public RaidRoom withLayer(int newLayer) {
        return new RaidRoom(this.minX, this.minZ, this.maxX, this.maxZ, newLayer);
    }
}
