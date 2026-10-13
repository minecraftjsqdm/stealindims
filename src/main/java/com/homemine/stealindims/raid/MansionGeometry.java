package com.homemine.stealindims.raid;

import com.homemine.stealindims.StealInDimsMod;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;

/**
 * The mansion answer to {@link RaidGeometry} (design §3.1, plan §P1.2).
 *
 * <p>Everything about the map is answered from a {@link MansionLayout}: the layers the difficulty
 * grants (C 1 / B, A 2 / S 3), how many submission points each of them carries (2 / 2 / 2 / 3), where
 * the player lands, which rectangles the spawner and the trap pass may use, and how big the map is.
 * The radius is not a constant here — it is what the generated plan actually used, plus a margin, so
 * a 60 room S mansion and a 24 room C mansion can coexist behind the same map entry.
 *
 * <p>The plan is a pure function of {@code (difficulty, seed, room archetypes)}, but it is expensive
 * enough that re-deriving it on every {@code geometry()} call would be wasteful, so the factory
 * caches the last few. {@link MansionRoomRegistry#revision()} is part of the key, which is what stops
 * a data pack reload from being answered with a plan grown from the previous mix.
 *
 * <p>The mansion sits at {@link #MANSION_OFFSET_X}, further east than the four-region layouts, so
 * even the widest S footprint stays clear of the extraction room at the origin.
 */
public final class MansionGeometry implements RaidGeometry {

    /**
     * The X the mansion is centred on. Well east of the extraction room, which straddles X = 0.
     *
     * <p>R25-E moved it out from 120. The organic growth is not a rectangle any more, so
     * {@link MansionLayout#mapRadius()} is measured from the cells it really used and can reach
     * {@code 24 cells / 2 * 8 + EDGE_MARGIN = 108}; {@link #mapRadius()} then has to add the boss
     * chamber, which hangs {@code BOSS_CHAMBER_GAP + 2 * BOSS_CHAMBER_HALF + 4 = 34} blocks further
     * south, so the arena's own radius can be 142. At the old 120 the wipe and
     * {@link #isInMap(BlockPos)} would both have reached across X = 0 into the extraction room;
     * {@code MansionLayoutBuilderTest#theMapNeverReachesTheExtractionRoom} pins the margin.
     */
    public static final int MANSION_OFFSET_X = 200;

    /** Half extent of the boss chamber's floor plan; the room is {@code 2 * this + 1} blocks square. */
    public static final int BOSS_CHAMBER_HALF = 12;

    /** Blocks between the mansion's outer wall and the near wall of the boss chamber. */
    public static final int BOSS_CHAMBER_GAP = 6;

    /** How many recently built plans are kept; a session only ever needs one. */
    private static final int CACHE_LIMIT = 6;

    private static final Map<String, MansionGeometry> CACHE = new LinkedHashMap<>();

    private final RaidTheme.RaidMap map;
    private final RaidDifficulty difficulty;
    private final long seed;
    private final MansionLayout layout;

    private MansionGeometry(RaidTheme.RaidMap map, RaidDifficulty difficulty, long seed,
                            MansionLayout layout) {
        this.map = map;
        this.difficulty = difficulty;
        this.seed = seed;
        this.layout = layout;
    }

    /** Builds (or reuses) the geometry of {@code map} at {@code difficulty} for {@code seed}. */
    public static MansionGeometry of(RaidTheme.RaidMap map, RaidDifficulty difficulty, long seed) {
        String key = (map == null ? "?" : map.id()) + '|' + difficulty.name() + '|' + seed
                + '|' + MansionRoomRegistry.revision();
        synchronized (CACHE) {
            MansionGeometry cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }

        MansionLayout plan = MansionLayoutBuilder.build(difficulty, seed, MANSION_OFFSET_X);
        MansionGeometry geometry = new MansionGeometry(map, difficulty, seed, plan);
        StealInDimsMod.LOGGER.info(
                "Mansion layout: grid={}x{} templates={} rooms={} storeys={} cells={}x{} radius={} "
                        + "deadends={} lots={} submissions={} seed={}",
                plan.gridSize(), plan.gridSize(), plan.pieces().size(), plan.rooms().size(),
                plan.floors().size(), plan.bounds().width(), plan.bounds().depth(), plan.mapRadius(),
                plan.deadEnds(), plan.lots(), plan.submissions().size(), seed);
        // The landmarks are logged because they are the only way to check a live run by hand: the
        // in-game seed is random, so the plan cannot be reproduced outside this log line.
        StealInDimsMod.LOGGER.info("Mansion landmarks: spawn={} anchor={} submissions={} templates={}",
                format(plan.spawn()), format(plan.anchor()), format(plan.submissionPoints()),
                plan.pieces().size());
        MansionLayout.Statue statue = plan.statue();
        if (statue == null) {
            StealInDimsMod.LOGGER.warn("Mansion has no statue room; the boss portal will be unreachable");
        } else {
            // R24: the wall line, the vault and the room rectangle are the evidence for "the statue
            // backs onto a wall and the sealed room is behind it"; the in-game seed is random, so this
            // line is the only way to check a live run by hand.
            StealInDimsMod.LOGGER.info(
                    "Mansion statue: layer={} centreX={} eyeY={} faceZ={} backPlaneZ={} wallZ={} "
                            + "room={}..{} vault={}..{} chamber={}..{} crank={} treasure={} portal={}",
                    statue.layerY(), statue.centerX(), statue.eyeY(), statue.faceZ(),
                    statue.templateMinZ(), statue.wallZ(),
                    format(new BlockPos(plan.blockMinX(statue.room()), statue.layerY(),
                            plan.blockMinZ(statue.room()))),
                    format(new BlockPos(plan.blockMaxX(statue.room()), statue.layerY(),
                            plan.blockMaxZ(statue.room()))),
                    format(statue.chamberShellMin()), format(statue.chamberShellMax()),
                    format(new BlockPos(statue.chamberMinX(), statue.chamberMinY(), statue.chamberMinZ())),
                    format(new BlockPos(statue.chamberMaxX(), statue.chamberMaxY(), statue.chamberMaxZ())),
                    format(statue.crankPos()), format(statue.treasure()), format(statue.bossPortal()));
        }

        synchronized (CACHE) {
            while (CACHE.size() >= CACHE_LIMIT) {
                Iterator<String> oldest = CACHE.keySet().iterator();
                if (!oldest.hasNext()) {
                    break;
                }
                oldest.next();
                oldest.remove();
            }
            CACHE.put(key, geometry);
        }
        return geometry;
    }

    // ---------------------------------------------------------------- the plan

    public MansionLayout layout() {
        return this.layout;
    }

    public RaidTheme.RaidMap map() {
        return this.map;
    }

    public RaidDifficulty difficulty() {
        return this.difficulty;
    }

    public long seed() {
        return this.seed;
    }

    // ---------------------------------------------------------------- RaidGeometry

    @Override
    public int mapRadius() {
        // The boss chamber hangs off the mansion's south side, so the map platform is as big as the
        // further of the two: everything that wipes or sweeps the arena reads this.
        return Math.max(this.layout.mapRadius(), bossChamberCenter().getZ() + BOSS_CHAMBER_HALF + 4);
    }

    @Override
    public int mapCenterX() {
        return MANSION_OFFSET_X;
    }

    @Override
    public BlockPos mapSpawn() {
        return this.layout.spawn();
    }

    @Override
    public BlockPos mapAnchor() {
        return this.layout.anchor();
    }

    @Override
    public boolean isInMap(BlockPos pos) {
        int radius = mapRadius();
        return Math.abs(pos.getX() - MANSION_OFFSET_X) <= radius + 1
                && Math.abs(pos.getZ()) <= radius + 1;
    }

    @Override
    public boolean isInsideArena(BlockPos pos) {
        return RaidArena.isInSafeRoom(pos) || isInMap(pos);
    }

    @Override
    public BlockPos mapEntryPoint() {
        return this.layout.spawn();
    }

    @Override
    public BlockPos safeRoomSpawn() {
        return RaidArena.safeRoomSpawn();
    }

    @Override
    public boolean isInSafeZone(BlockPos pos) {
        return this.layout.isInSafeZone(pos);
    }

    /** The submission points double as this strategy's "regions". */
    @Override
    public int regionCount() {
        return this.layout.submissions().size();
    }

    @Override
    public RaidArena.Bounds regionBounds(int index) {
        MansionLayout.Cells cells = this.layout.submissions().get(index).cells();
        return new RaidArena.Bounds(this.layout.blockMinX(cells), this.layout.blockMaxX(cells),
                this.layout.blockMinZ(cells), this.layout.blockMaxZ(cells));
    }

    @Override
    public BlockPos regionCenter(int index) {
        return this.layout.submissionCrate(index);
    }

    @Override
    public BlockPos vaultCenter(int index) {
        return this.layout.submissionCrate(index);
    }

    @Override
    public BlockPos vaultAnchor(int index) {
        return this.layout.submissionAnchor(index);
    }

    @Override
    public BlockPos vaultCrate(int index) {
        return this.layout.submissionCrate(index);
    }

    /** Every packed room of every layer, in block coordinates (plan §P1.2, §P1.4). */
    @Override
    public List<RaidArena.Bounds> buildAreas() {
        List<RaidArena.Bounds> areas = new ArrayList<>(this.layout.rooms().size());
        for (MansionLayout.Room room : this.layout.rooms()) {
            areas.add(new RaidArena.Bounds(
                    this.layout.blockMinX(room.cells()), this.layout.blockMaxX(room.cells()),
                    this.layout.blockMinZ(room.cells()), this.layout.blockMaxZ(room.cells())));
        }
        return List.copyOf(areas);
    }

    /** The walkable layers, top first: C {64}, B/A {64, 72}, S {64, 72, 80}. */
    @Override
    public int[] layerYs() {
        List<Integer> ys = this.layout.layerYs();
        int[] result = new int[ys.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = ys.get(index);
        }
        return result;
    }

    @Override
    public List<BlockPos> submissionPoints() {
        return this.layout.submissionPoints();
    }

    @Override
    public List<BlockPos> anchorPoints() {
        return this.layout.anchorPoints();
    }

    /** The resource location of the map's own dimension-driven boss, or {@code null}. */
    @Override
    public String toString() {
        return "MansionGeometry[" + (this.map == null ? "?" : this.map.id())
                + ' ' + this.difficulty + " seed=" + this.seed
                + " rooms=" + this.layout.rooms().size() + ']';
    }

    /**
     * The centre of the boss chamber's floor (design §3.5, plan §P3.4).
     *
     * <p>The chamber is a free-standing platform south of the mansion, on the entrance layer's Y. It
     * is deliberately outside the mansion proper: the only way in is the portal at the back of the
     * statue's dark room, and the only way out is the return anchor standing inside it.
     */
    public BlockPos bossChamberCenter() {
        return new BlockPos(MANSION_OFFSET_X, MansionLayout.MAIN_LAYER_Y,
                this.layout.mapRadius() + BOSS_CHAMBER_GAP + BOSS_CHAMBER_HALF);
    }

    /**
     * Where a raider lands after walking through the dark room's boss portal: inside the chamber,
     * three blocks in from its north wall, facing the middle.
     */
    @Override
    @Nullable
    public BlockPos bossChamberSpawn() {
        return bossChamberCenter().offset(0, 0, -(BOSS_CHAMBER_HALF - 3));
    }

    /** Convenience for the tests: the map-local spawn without a session. */
    public static BlockPos spawnFor(RaidDifficulty difficulty, long seed) {
        return of(null, difficulty, seed).mapSpawn();
    }

    /** A compact {@code x,y,z} rendering of one position, for the landmark log line. */
    private static String format(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** The same rendering for a list of positions. */
    private static String format(List<BlockPos> points) {
        StringBuilder out = new StringBuilder("[");
        for (int index = 0; index < points.size(); index++) {
            if (index > 0) {
                out.append(' ');
            }
            out.append(format(points.get(index)));
        }
        return out.append(']').toString();
    }
}
