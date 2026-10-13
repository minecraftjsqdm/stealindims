package com.homemine.stealindims.raid;

import com.homemine.stealindims.StealInDimsMod;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;

/**
 * Everything about a raid map that answers "where is it and what shape is it" (design §3.1).
 *
 * <p>Up to v1.0.0 those answers all came from the static methods of {@link RaidArena}, which made the
 * map's geometry a pure function of its {@code size} field. The mansion rework turns layer count,
 * submission point count and footprint into functions of the <em>difficulty</em> as well, so the
 * geometry has to become an object a session can hand around: {@link RegionGeometry} keeps the old
 * four-region answer verbatim, and the mansion generator will add a second implementation without
 * touching any of the call sites below.
 *
 * <p>The extraction room is deliberately <em>not</em> part of this interface. It never moves and
 * never changes shape, so it stays where it has always been: the static methods of {@link RaidArena}.
 *
 * @see RegionGeometry
 */
public interface RaidGeometry {

    /**
     * Outer half extent of the whole map, measured from {@link #mapCenterX()}. Used to wipe the arena
     * and to judge whether a position belongs to the map at all.
     */
    int mapRadius();

    /** The X the map platform is centred on; the extraction room always sits at X = 0. */
    int mapCenterX();

    /** Where the player lands when entering the map. */
    BlockPos mapSpawn();

    /** Anchor that takes the player back to the extraction room. */
    BlockPos mapAnchor();

    boolean isInMap(BlockPos pos);

    /** Whether {@code pos} is anywhere a raid player could legitimately stand, lobby included. */
    boolean isInsideArena(BlockPos pos);

    /** Centre of the vestibule west of the map, the only walkable way onto it. */
    BlockPos mapEntryPoint();

    /** Where the player lands in the extraction room, next to the anchor. */
    BlockPos safeRoomSpawn();

    /**
     * Whether {@code pos} is a submission point's safe zone.
     *
     * <p>For the four-region geometry this is exactly the old {@code RaidArena.isInVault}: the sealed
     * vault at each region's centre is the place a run is submitted from, so no trap and no patrol
     * may be placed there.
     */
    boolean isInSafeZone(BlockPos pos);

    /** How many buildable areas the map is split into (the old {@code RaidArena.REGIONS}). */
    int regionCount();

    /** Floor plan of buildable area {@code index}, both corners inclusive (the old {@code regionBounds}). */
    RaidArena.Bounds regionBounds(int index);

    /** Centre of buildable area {@code index} on the map's main layer (the old {@code regionCenter}). */
    BlockPos regionCenter(int index);

    /** Centre of submission point {@code index} (the old vault at each region's centre). */
    BlockPos vaultCenter(int index);

    /** Anchor at submission point {@code index}, taking the player back to the extraction room. */
    BlockPos vaultAnchor(int index);

    /** Transit crate at submission point {@code index}: what {@link #submissionPoints()} lists. */
    BlockPos vaultCrate(int index);

    /** Floor plans of the map, one rectangle per buildable area (the old {@code regionBounds}). */
    List<RaidArena.Bounds> buildAreas();

    /** The walkable layers, top first. */
    int[] layerYs();

    /** Every place a run can be submitted from: the transit crate of each submission point. */
    List<BlockPos> submissionPoints();

    /** Every anchor that takes a raider back to the extraction room. */
    List<BlockPos> anchorPoints();

    /**
     * Where a raider lands after walking through the boss chamber's portal, or {@code null} when this
     * strategy has no boss chamber at all.
     *
     * <p>The four-region layouts have none — their boss is the device's own dimension-driven one — so
     * the default answer is {@code null}. The mansion grows a chamber south of the mansion in P3
     * (plan §P3.4) and overrides this.
     */
    @Nullable
    default BlockPos bossChamberSpawn() {
        return null;
    }

    /**
     * Resolves the geometry a map declares.
     *
     * <p>The map's datapack field {@code geometry} names the strategy; it defaults to
     * {@code "region"}, so every theme written before the mansion rework keeps the old four-region
     * layout. An unknown name falls back to {@code "region"} with a warning rather than failing the
     * map, since a missing map is a much worse failure than an old-looking one.
     *
     * @param map        the map whose {@code geometry} field is read; {@code null} means the region
     *                   default, which is what the call sites outside a session pass
     * @param difficulty the difficulty, ignored by {@link RegionGeometry} but driving the mansion's
     *                   layer count, submission count and room target (decisions R3/R4/R5)
     * @param seed       the run's seed; the mansion plan is grown from it, the region layout ignores
     *                   it because {@code RaidLayoutBuilder} derives its own per-region seeds
     */
    static RaidGeometry of(RaidTheme.RaidMap map, RaidDifficulty difficulty, long seed) {
        if (map == null) {
            return new RegionGeometry(null, difficulty);
        }
        if ("region".equals(map.geometry())) {
            return new RegionGeometry(map.size(), difficulty);
        }
        if ("mansion".equals(map.geometry())) {
            return MansionGeometry.of(map, difficulty, seed);
        }
        StealInDimsMod.LOGGER.warn("Raid map '{}' asks for unknown geometry '{}', falling back to region",
                map.id(), map.geometry());
        return new RegionGeometry(map.size(), difficulty);
    }

    /**
     * The map's geometry without a run seed.
     *
     * <p>Only safe for the region layouts, whose shape does not depend on the seed of the run; a
     * mansion resolved this way is grown from seed {@code 0} and will not match the one a session
     * built. Call sites that have a seed should use {@link #of(RaidTheme.RaidMap, RaidDifficulty, long)}.
     */
    static RaidGeometry of(RaidTheme.RaidMap map, RaidDifficulty difficulty) {
        return of(map, difficulty, 0L);
    }
}
