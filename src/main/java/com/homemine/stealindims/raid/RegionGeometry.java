package com.homemine.stealindims.raid;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * The geometry every raid map used before the mansion rework: four regions in a 2×2 grid with two
 * walkable layers each, a sealed vault at every region's centre, and one entry vestibule west of
 * region 0.
 *
 * <p>This class is a <em>verbatim</em> move of the bodies that used to live in {@link RaidArena} —
 * no formula, threshold, constant or loop bound was touched, only the receiver changed from a
 * {@code size} parameter to this instance's {@link #size} field. That is what keeps the six legacy
 * maps byte-for-byte identical (design §7, risk R-a): {@link RaidArena} now forwards its deprecated
 * statics here, so both spellings run the same code.
 *
 * <p>{@link #difficulty} is stored but deliberately unused: layer count, submission point count and
 * size are all fixed for the region layouts (decision R10 — the other six maps are only locked, never
 * reshaped). The mansion geometry of P1 is the implementation that reads it.
 */
public final class RegionGeometry implements RaidGeometry {

    /** Half width of a vault, the sealed submission room at each region's centre. */
    public static final int VAULT_HALF = 4;

    /** How many regions the map is split into, laid out 2×2 (design §3). */
    public static final int REGIONS = 4;

    /** The map platform is offset along +X so the two rooms never touch. */
    public static final int MAP_OFFSET_X = 80;

    /** Walkable layer of every region, and the layer the extraction room floor sits on. */
    public static final int MAIN_LAYER_Y = 64;
    /** Underground layer of every region, reached through that region's stairwell (design §3). */
    public static final int UNDER_LAYER_Y = 48;

    /** Corridor width separating the two region columns (along X). */
    private static final int COLUMN_GAP = 16;
    /** Bridge width separating the two region rows (along Z). */
    private static final int ROW_GAP = 4;

    /** Gap between region 0's outer wall and the centre of the entry vestibule. */
    private static final int VESTIBULE_OFFSET = 8;
    /** Half width of the entry vestibule; also the margin {@link #mapRadius} adds on top of it. */
    private static final int VESTIBULE_HALF = 3;

    private final String size;
    private final RaidDifficulty difficulty;

    public RegionGeometry(String size, RaidDifficulty difficulty) {
        this.size = size;
        this.difficulty = difficulty;
    }

    /** The map's {@code size} field, kept so the old facades can still echo it. */
    public String size() {
        return this.size;
    }

    /** Part of the {@link RaidGeometry} contract from P1 on; unused by the region layouts. */
    public RaidDifficulty difficulty() {
        return this.difficulty;
    }

    // ---------------------------------------------------------------- regions

    /** Half extent of one region's floor plan, driven by the map's {@code size} field. */
    public int regionHalf() {
        return switch (this.size == null ? "medium" : this.size) {
            case "small" -> 15;
            case "large" -> 22;
            default -> 18;
        };
    }

    /** How many regions the map is split into. */
    @Override
    public int regionCount() {
        return REGIONS;
    }

    /** The two walkable layers of a region, top first. */
    @Override
    public int[] layerYs() {
        return new int[]{MAIN_LAYER_Y, UNDER_LAYER_Y};
    }

    /**
     * Centre of region {@code index} on {@link #MAIN_LAYER_Y}.
     *
     * <p>Regions are indexed 0..3 as {@code (-X,-Z), (+X,-Z), (-X,+Z), (+X,+Z)}: 0 is the region the
     * map entry vestibule opens into, so walking east from the vestibule leads into a region rather
     * than into the gap between two of them.
     */
    @Override
    public BlockPos regionCenter(int index) {
        int half = regionHalf();
        int offset = half + COLUMN_GAP / 2;
        int zOffset = half + ROW_GAP / 2;
        int x = MAP_OFFSET_X + ((index % 2 == 1) ? offset : -offset);
        int z = (index >= 2) ? zOffset : -zOffset;
        return new BlockPos(x, MAIN_LAYER_Y, z);
    }

    /** Floor plan of region {@code index} on the main layer, both corners inclusive. */
    @Override
    public RaidArena.Bounds regionBounds(int index) {
        int half = regionHalf();
        BlockPos center = regionCenter(index);
        return new RaidArena.Bounds(center.getX() - half, center.getX() + half,
                center.getZ() - half, center.getZ() + half);
    }

    /** Floor plans of all four regions, in index order. */
    @Override
    public List<RaidArena.Bounds> buildAreas() {
        List<RaidArena.Bounds> areas = new ArrayList<>(REGIONS);
        for (int region = 0; region < REGIONS; region++) {
            areas.add(regionBounds(region));
        }
        return List.copyOf(areas);
    }

    // ---------------------------------------------------------------- vaults

    @Override
    public BlockPos vaultCenter(int region) {
        return regionCenter(region);
    }

    /** Anchor inside the vault that takes the player back to the extraction room. */
    @Override
    public BlockPos vaultAnchor(int region) {
        return vaultCenter(region).offset(-2, 0, 0);
    }

    /** Transit crate inside the vault: the map's submission point. */
    @Override
    public BlockPos vaultCrate(int region) {
        return vaultCenter(region).offset(2, 0, 0);
    }

    /** Whether {@code pos} lies inside any region's vault, which is a safe zone (design §3.1). */
    @Override
    public boolean isInSafeZone(BlockPos pos) {
        int half = VAULT_HALF;
        for (int region = 0; region < REGIONS; region++) {
            BlockPos center = vaultCenter(region);
            if (Math.abs(pos.getX() - center.getX()) <= half
                    && Math.abs(pos.getZ() - center.getZ()) <= half
                    && pos.getY() >= MAIN_LAYER_Y
                    && pos.getY() <= MAIN_LAYER_Y + 4) {
                return true;
            }
        }
        return false;
    }

    /** One transit crate per region's vault, the map's four submission points. */
    @Override
    public List<BlockPos> submissionPoints() {
        List<BlockPos> points = new ArrayList<>(REGIONS);
        for (int region = 0; region < REGIONS; region++) {
            points.add(vaultCrate(region));
        }
        return List.copyOf(points);
    }

    /** One return anchor per region's vault. */
    @Override
    public List<BlockPos> anchorPoints() {
        List<BlockPos> points = new ArrayList<>(REGIONS);
        for (int region = 0; region < REGIONS; region++) {
            points.add(vaultAnchor(region));
        }
        return List.copyOf(points);
    }

    // ---------------------------------------------------------------- map platform

    /**
     * Outer radius of the whole map, measured from {@link #mapCenterX()}: covers all four regions,
     * the corridors between them and the entry vestibule, in one square. Used to wipe the arena and
     * to judge whether a position belongs to the map at all.
     */
    @Override
    public int mapRadius() {
        return 2 * regionHalf() + COLUMN_GAP / 2 + VESTIBULE_OFFSET + VESTIBULE_HALF;
    }

    @Override
    public int mapCenterX() {
        return MAP_OFFSET_X;
    }

    /** Centre of the vestibule west of region 0, the only walkable way onto the map. */
    @Override
    public BlockPos mapEntryPoint() {
        return new BlockPos(MAP_OFFSET_X - mapRadius() + VESTIBULE_HALF, MAIN_LAYER_Y,
                regionCenter(0).getZ());
    }

    /** Anchor that takes the player back to the extraction room. */
    @Override
    public BlockPos mapAnchor() {
        return mapEntryPoint();
    }

    /** Where the player lands when entering the map. */
    @Override
    public BlockPos mapSpawn() {
        return mapEntryPoint().offset(-2, 0, 0);
    }

    /** Where the player lands in the extraction room, next to the anchor. */
    @Override
    public BlockPos safeRoomSpawn() {
        return RaidArena.safeRoomSpawn();
    }

    @Override
    public boolean isInMap(BlockPos pos) {
        int radius = mapRadius();
        return Math.abs(pos.getX() - MAP_OFFSET_X) <= radius + 1 && Math.abs(pos.getZ()) <= radius + 1;
    }

    /** Whether {@code pos} is anywhere a raid player could legitimately stand. */
    @Override
    public boolean isInsideArena(BlockPos pos) {
        return RaidArena.isInSafeRoom(pos) || isInMap(pos);
    }
}
