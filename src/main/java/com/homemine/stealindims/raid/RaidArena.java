package com.homemine.stealindims.raid;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;

/**
 * Fixed geometry of a raid arena inside a raid dimension.
 *
 * <p>Only one raid can run per dimension at a time (design §5), so the arena always uses the same
 * coordinates instead of a random origin: the extraction room is centred on the origin, and the map
 * sits {@value #MAP_OFFSET_X} blocks to the east. Everything in between is void, which is what makes
 * the extraction room physically separate from the map (design §9).
 *
 * <p>From P0 of the mansion rework on, the <em>map</em> half of the geometry lives in
 * {@link RaidGeometry} implementations — {@link RegionGeometry} holds the four-region answer this
 * class used to compute itself, and the mansion generator will add a second strategy without any of
 * the call sites changing. This class keeps two things:
 *
 * <ul>
 *     <li>the extraction room, which never moves and never changes shape, as ordinary statics;</li>
 *     <li>the old static map accessors, {@linkplain Deprecated kept as a facade} that forwards to
 *     {@link RegionGeometry}. Both spellings therefore run <em>the same code</em>, which is what makes
 *     the extraction a behavioural no-op for the six legacy maps (risk R-a). New code should take a
 *     {@link RaidGeometry} from the session or the map instead.</li>
 * </ul>
 */
public final class RaidArena {

    public static final int SAFE_ROOM_Y = 64;

    /** Walkable layer of every region, and the layer the extraction room floor sits on. */
    public static final int MAIN_LAYER_Y = 64;
    /** Underground layer of every region, reached through that region's stairwell (design §3). */
    public static final int UNDER_LAYER_Y = 48;

    /** Half width of the extraction room's outer wall. */
    public static final int ROOM_HALF = 5;
    public static final int ROOM_FLOOR_Y = SAFE_ROOM_Y - 1;
    public static final int ROOM_CEILING_Y = SAFE_ROOM_Y + 4;

    /** The map platform is offset along +X so the two rooms never touch. */
    public static final int MAP_OFFSET_X = RegionGeometry.MAP_OFFSET_X;

    /** How many regions the map is split into, laid out 2×2 (design §3). */
    public static final int REGIONS = RegionGeometry.REGIONS;

    /** Half width of a vault, the sealed submission room at each region's centre. */
    public static final int VAULT_HALF = RegionGeometry.VAULT_HALF;

    private RaidArena() {
    }

    // ---------------------------------------------------------------- geometry strategy

    /**
     * The geometry {@code map} declares, defaulting to the four-region layout that every map used
     * before the mansion rework.
     */
    public static RaidGeometry geometryOf(@Nullable RaidTheme.RaidMap map,
                                          RaidDifficulty difficulty) {
        return RaidGeometry.of(map, difficulty);
    }

    /**
     * The geometry {@code map} declares, with the run's seed so a mansion plan can be grown from it.
     * The region layouts answer the same thing either way.
     */
    public static RaidGeometry geometryOf(@Nullable RaidTheme.RaidMap map,
                                          RaidDifficulty difficulty, long seed) {
        return RaidGeometry.of(map, difficulty, seed);
    }

    // ---------------------------------------------------------------- extraction room

    public static BlockPos safeRoomAnchor() {
        return new BlockPos(-3, SAFE_ROOM_Y, 0);
    }

    public static BlockPos safeRoomCrate() {
        return new BlockPos(3, SAFE_ROOM_Y, 0);
    }

    /** Where the player lands when arriving from the map, next to the anchor. */
    public static BlockPos safeRoomSpawn() {
        return new BlockPos(0, SAFE_ROOM_Y, 0);
    }

    /** The prep pad the lobby is opened from: every member has to press it (design §4.2). */
    public static BlockPos safeRoomReadyPad() {
        return new BlockPos(0, SAFE_ROOM_Y, -3);
    }

    /** Lobby ender chest: where a banned item has to go before the owner may ready up (decision A5). */
    public static BlockPos safeRoomEnderChest() {
        return new BlockPos(0, SAFE_ROOM_Y, 3);
    }

    /** The lobby's exit portal: the way out before the run, the way to pull out during it (§3.2). */
    public static BlockPos safeRoomExitPortal() {
        return new BlockPos(-3, SAFE_ROOM_Y, 3);
    }

    public static boolean isInSafeRoom(BlockPos pos) {
        return Math.abs(pos.getX()) <= ROOM_HALF
                && Math.abs(pos.getZ()) <= ROOM_HALF
                && pos.getY() >= ROOM_FLOOR_Y
                && pos.getY() <= ROOM_CEILING_Y;
    }

    // ---------------------------------------------------------------- legacy facade

    /**
     * Half extent of one region's floor plan, driven by the map's {@code size} field.
     *
     * @deprecated take the {@link RaidGeometry} of the session's map instead.
     */
    @Deprecated
    public static int regionHalf(String size) {
        return new RegionGeometry(size, null).regionHalf();
    }

    /**
     * The two walkable layers of a region, top first.
     *
     * @deprecated take the {@link RaidGeometry} of the session's map instead.
     */
    @Deprecated
    public static int[] layerYs() {
        return new RegionGeometry(null, null).layerYs();
    }

    /**
     * Centre of region {@code index} on {@link #MAIN_LAYER_Y}.
     *
     * @deprecated take the {@link RaidGeometry} of the session's map instead.
     */
    @Deprecated
    public static BlockPos regionCenter(int index, String size) {
        return new RegionGeometry(size, null).regionCenter(index);
    }

    /**
     * Floor plan of region {@code index} on the main layer, both corners inclusive.
     *
     * @deprecated take the {@link RaidGeometry} of the session's map instead.
     */
    @Deprecated
    public static Bounds regionBounds(int index, String size) {
        return new RegionGeometry(size, null).regionBounds(index);
    }

    /** One region's floor plan, both corners inclusive. */
    public record Bounds(int minX, int maxX, int minZ, int maxZ) {

        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    /**
     * @deprecated take the {@link RaidGeometry} of the session's map instead.
     */
    @Deprecated
    public static BlockPos vaultCenter(int region, String size) {
        return new RegionGeometry(size, null).vaultCenter(region);
    }

    /**
     * Anchor inside the vault that takes the player back to the extraction room.
     *
     * @deprecated take the {@link RaidGeometry} of the session's map instead.
     */
    @Deprecated
    public static BlockPos vaultAnchor(int region, String size) {
        return new RegionGeometry(size, null).vaultAnchor(region);
    }

    /**
     * Transit crate inside the vault: the map's submission point.
     *
     * @deprecated take the {@link RaidGeometry} of the session's map instead.
     */
    @Deprecated
    public static BlockPos vaultCrate(int region, String size) {
        return new RegionGeometry(size, null).vaultCrate(region);
    }

    /**
     * Whether {@code pos} lies inside any region's vault, which is a safe zone (design §3.1).
     *
     * @deprecated take the {@link RaidGeometry} of the session's map instead.
     */
    @Deprecated
    public static boolean isInVault(BlockPos pos, String size) {
        return new RegionGeometry(size, null).isInSafeZone(pos);
    }

    /**
     * Outer radius of the whole map, measured from {@link #mapCenterX()}.
     *
     * @deprecated take the {@link RaidGeometry} of the session's map instead.
     */
    @Deprecated
    public static int mapRadius(String size) {
        return new RegionGeometry(size, null).mapRadius();
    }

    public static int mapCenterX() {
        return MAP_OFFSET_X;
    }

    /**
     * Centre of the vestibule west of region 0, the only walkable way onto the map.
     *
     * @deprecated take the {@link RaidGeometry} of the session's map instead.
     */
    @Deprecated
    public static BlockPos mapEntryPoint(String size) {
        return new RegionGeometry(size, null).mapEntryPoint();
    }

    /**
     * Anchor that takes the player back to the extraction room.
     *
     * @deprecated take the {@link RaidGeometry} of the session's map instead.
     */
    @Deprecated
    public static BlockPos mapAnchor(String size) {
        return new RegionGeometry(size, null).mapAnchor();
    }

    /**
     * Where the player lands when entering the map.
     *
     * @deprecated take the {@link RaidGeometry} of the session's map instead.
     */
    @Deprecated
    public static BlockPos mapSpawn(String size) {
        return new RegionGeometry(size, null).mapSpawn();
    }

    /**
     * @deprecated take the {@link RaidGeometry} of the session's map instead.
     */
    @Deprecated
    public static boolean isInMap(BlockPos pos, String size) {
        return new RegionGeometry(size, null).isInMap(pos);
    }

    /**
     * Whether {@code pos} is anywhere a raid player could legitimately stand.
     *
     * @deprecated take the {@link RaidGeometry} of the session's map instead.
     */
    @Deprecated
    public static boolean isInsideArena(BlockPos pos, String size) {
        return new RegionGeometry(size, null).isInsideArena(pos);
    }
}
