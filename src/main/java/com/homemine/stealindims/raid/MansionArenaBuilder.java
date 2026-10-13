package com.homemine.stealindims.raid;

import com.homemine.stealindims.ModBlocks;
import com.homemine.stealindims.StealInDimsMod;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.phys.AABB;

/**
 * Raises a vanilla woodland mansion into the world (P5 §2, design §3.2, decision R17).
 *
 * <p>P5 replaced the hand-made build with the real thing: {@link MansionLayout} now carries the
 * template list vanilla's own generator produced, and this class <em>stamps</em> it — one
 * {@code .nbt} from {@code data/minecraft/structure/woodland_mansion/} at a time, with vanilla's
 * rotation, mirror and {@code BlockIgnoreProcessor}. Floors, walls, windows, furniture, staircases
 * and roofs therefore need no code here at all.
 *
 * <p>What is left to build is only what vanilla has no template for: the cobblestone footing the
 * mansion stands on in a void dimension, the emptied statue room and the sealed vault carved into the
 * wall its statue leans on, the transit crates and return anchors, the landing pocket in the entrance
 * hall and the free-standing boss chamber. The statue itself is one more template — the user's own
 * {@code stealindims:illager_statue}, stamped verbatim by {@link #stampStatue} (spec §5.4, R23,
 * spec §5.6, R24).
 *
 * <p>The build is batched on purpose, in three passes: {@value #PIECES_PER_TICK} vanilla templates
 * per server tick, then {@value #BLOCKS_PER_TICK} blocks per tick for the mod's own placements, then
 * the statue. A fifteen-by-fifteen S mansion is roughly a thousand templates; writing them in one go
 * would stall the server, and it is only safe to take the time because a raid map is raised while
 * everyone is still locked in the prep lobby.
 *
 * <p>That asynchrony is why {@link #whenDone} exists: the trap pass has to run against a finished map,
 * so {@code RaidSessionManager} hands it over as a completion callback instead of calling it inline
 * the way the four-region builder does.
 *
 * <p>The templates' own loot is not left to the players (P5 §2.5): every chest, trapped chest, barrel
 * and spawner a template brings is collected while it is stamped and wiped once the pass is done, and
 * the run's containers come from {@link #placeContainers} and the room {@code loot_tier} instead.
 */
public final class MansionArenaBuilder {

    /** Blocks written per server tick while a mansion is going up; tuned against the S layer count. */
    public static final int BLOCKS_PER_TICK = 4096;

    /**
     * Vanilla templates stamped per server tick. A fifteen-by-fifteen S mansion is several hundred
     * pieces, and every piece is a few hundred block writes plus shape updates, so the templates are
     * applied in their own batched pass ahead of the mod's own placements.
     */
    public static final int PIECES_PER_TICK = 24;

    /** The vanilla blocks the templates carry loot in; they are wiped and replaced by ours (P5 §2.5). */
    private static final List<Block> VANILLA_LOOT = List.of(
            Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.SPAWNER);


    /**
     * The user's own statue, shipped in the mod's own resources (spec §5.4, R23).
     *
     * <p>This is stamped verbatim: no block of it is replaced, recoloured, re-scaled or deleted, and
     * its single {@code create:super_glue} entity is deliberately <em>not</em> ignored, because that
     * entity is what lets the mechanical bearing carry the nose as a contraption.
     */
    private static final ResourceLocation STATUE_TEMPLATE =
            ResourceLocation.fromNamespaceAndPath("stealindims", "illager_statue");

    /** Create's super glue entity, the only entity the statue template carries. */
    private static final ResourceLocation SUPER_GLUE =
            ResourceLocation.fromNamespaceAndPath("create", "super_glue");

    /** Salt keeping the dark room's purple-or-gold roll independent of the loot and spawn rolls. */
    private static final long TREASURE_SALT = 0x7A0B_7A0BL;

    /** Chebyshev distance from a doorway that a container keeps clear. */
    private static final int DOOR_CLEARANCE = 2;

    /** Interior height of the boss chamber; taller than a mansion room, for the fang circles. */
    private static final int BOSS_CHAMBER_WALL_HEIGHT = 6;


    /** The jobs still going up, keyed by raid dimension; one raid per dimension (design §5). */
    private static final Map<ResourceKey<Level>, Job> JOBS = new LinkedHashMap<>();

    private MansionArenaBuilder() {
    }

    /** One block to write, with whether it needs a full update or only a client one. */
    private record Placement(int x, int y, int z, BlockState state, boolean update) {
    }

    /**
     * What a plan produced: the mod's own blocks to write, plus any container slot a special room
     * reserved. The mansion itself is not in here — its templates come from
     * {@link MansionLayout#pieces()} and go through {@code StructureTemplate.placeInWorld} in their
     * own batched pass.
     */
    private record Plan(List<Placement> placements, Map<BlockPos, RaidLootGenerator.Kind> reserved) {
    }

    /** A mansion that is still being raised. */
    private static final class Job {

        final ServerLevel level;
        final MansionGeometry geometry;
        final RaidTheme theme;
        final RaidDifficulty difficulty;
        final RaidTheme.RaidMap map;
        final long seed;
        final RandomSource random;
        final List<VanillaMansionPieces.Piece> pieces;
        final List<Placement> placements;
        final Map<BlockPos, RaidLootGenerator.Kind> reserved;
        /** Where the vanilla templates put their own loot; wiped once every piece has landed. */
        final List<BlockPos> vanillaLoot = new ArrayList<>();
        final Map<String, Integer> missing = new TreeMap<>();
        final long startedAt = System.nanoTime();
        int pieceCursor;
        int cursor;
        @Nullable
        Runnable onDone;

        Job(ServerLevel level, MansionGeometry geometry, RaidTheme theme, RaidDifficulty difficulty,
            RaidTheme.RaidMap map, long seed, Plan plan) {
            this.level = level;
            this.geometry = geometry;
            this.theme = theme;
            this.difficulty = difficulty;
            this.map = map;
            this.seed = seed;
            this.random = RandomSource.create(seed);
            this.pieces = geometry.layout().pieces();
            this.placements = plan.placements();
            this.reserved = plan.reserved();
        }
    }

    // ---------------------------------------------------------------- entry points

    /** Starts raising {@code geometry}'s mansion; the work continues across ticks. */
    public static void start(ServerLevel level, RaidTheme theme, RaidTheme.RaidMap map,
                             RaidDifficulty difficulty, long seed, MansionGeometry geometry) {
        Materials materials = materials(map, theme);
        // The extraction room is small enough to raise in one go, and it has to be there before the
        // raiders are moved into it — the batched pass below is what takes its time. It is dressed in
        // the mansion's own palette so the prep lobby does not give the style away (fix F2).
        RaidArenaBuilder.buildExtractionRoom(level, materials.floor(), materials.base(),
                materials.plank());

        Plan plan = plan(level, theme, map, geometry, materials);
        Job job = new Job(level, geometry, theme, difficulty, map, seed, plan);
        JOBS.put(level.dimension(), job);
        StealInDimsMod.LOGGER.info(
                "Mansion build queued: {} vanilla templates + {} mod blocks, {} pieces/tick, {} blocks/tick",
                job.pieces.size(), plan.placements().size(), PIECES_PER_TICK, BLOCKS_PER_TICK);
        // The template census is the evidence that the map really is a woodland mansion: it is the
        // exact list of .nbt ids the vanilla generator asked for.
        Map<String, Integer> census = new TreeMap<>();
        for (VanillaMansionPieces.Piece piece : job.pieces) {
            census.merge(piece.name, 1, Integer::sum);
        }
        int rooms = 0;
        for (String name : census.keySet()) {
            if (name.startsWith("1x1_") || name.startsWith("1x2_") || name.startsWith("2x2_")) {
                rooms += census.get(name);
            }
        }
        StealInDimsMod.LOGGER.info("Mansion templates: {} distinct ids, {} room templates, {}",
                census.size(), rooms, census);
        // Every doorway is logged so a build can be checked against the world: a sealed room is a
        // functional defect that no count in the plan can reveal (P4).
        for (MansionLayout.Door door : geometry.layout().doors()) {
            StealInDimsMod.LOGGER.info("Mansion door: layer={} x={}..{} z={}..{}",
                    door.layerY(), door.minX(), door.maxX(), door.minZ(), door.maxZ());
        }
        step(job);
    }

    public static boolean isBuilding(ResourceKey<Level> dimension) {
        return JOBS.containsKey(dimension);
    }

    /** Registers what to run once the mansion of {@code dimension} is fully raised. */
    public static void whenDone(ResourceKey<Level> dimension, Runnable action) {
        Job job = JOBS.get(dimension);
        if (job == null) {
            action.run();
            return;
        }
        job.onDone = action;
    }

    /** Applies one batch to every mansion that is going up; called from the server tick. */
    public static void tick(MinecraftServer server) {
        if (JOBS.isEmpty()) {
            return;
        }
        List<Job> jobs = new ArrayList<>(JOBS.values());
        for (Job job : jobs) {
            if (server.getLevel(job.level.dimension()) == null) {
                JOBS.remove(job.level.dimension());
                continue;
            }
            step(job);
        }
    }

    private static void step(Job job) {
        if (job.pieceCursor < job.pieces.size()) {
            StructureTemplateManager manager = job.level.getStructureManager();
            int budget = PIECES_PER_TICK;
            while (budget-- > 0 && job.pieceCursor < job.pieces.size()) {
                stamp(job, manager, job.pieces.get(job.pieceCursor++));
            }
            if (job.pieceCursor >= job.pieces.size()) {
                wipeVanillaLoot(job);
            }
            return;
        }

        if (job.cursor < job.placements.size()) {
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            int budget = BLOCKS_PER_TICK;
            while (budget-- > 0 && job.cursor < job.placements.size()) {
                Placement placement = job.placements.get(job.cursor++);
                job.level.setBlock(cursor.set(placement.x(), placement.y(), placement.z()),
                        placement.state(), placement.update() ? Block.UPDATE_ALL : Block.UPDATE_CLIENTS);
            }
            if (job.cursor < job.placements.size()) {
                return;
            }
        }

        JOBS.remove(job.level.dimension());
        if (!job.missing.isEmpty()) {
            StealInDimsMod.LOGGER.error("Mansion templates missing from the structure manager: {}", job.missing);
        }
        // The user's statue goes in last, after the room has been cleared and the dark room raised, so
        // that nothing of ours can land on top of it (spec §5.4, R23).
        stampStatue(job);
        placeContainers(job);
        long millis = (System.nanoTime() - job.startedAt) / 1_000_000L;
        StealInDimsMod.LOGGER.info("Mansion built: {} templates + {} blocks in {} ms",
                job.pieces.size(), job.placements.size(), millis);

        Runnable action = job.onDone;
        if (action != null) {
            action.run();
        }
    }

    /**
     * Stamps one vanilla template into the world (P5 §2.2).
     *
     * <p>This is {@code TemplateStructurePiece.postProcess} minus the data markers: the settings are
     * vanilla's own (same rotation, same mirror, same {@code BlockIgnoreProcessor.STRUCTURE_BLOCK}),
     * so every block lands exactly where a real woodland mansion would put it. Dropping the data
     * markers is deliberate — they are what spawns vanilla's chests, evokers and vindicators — and the
     * chests the templates bake in as real blocks are collected here and wiped once the pass is done.
     */
    private static void stamp(Job job, StructureTemplateManager manager,
                              VanillaMansionPieces.Piece piece) {
        StructureTemplate template = manager.getOrCreate(ResourceLocation.parse(piece.templateId()));
        if (template.getSize().equals(Vec3i.ZERO)) {
            job.missing.merge(piece.name, 1, Integer::sum);
            return;
        }
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(true)
                .setRotation(piece.rotation)
                .setMirror(piece.mirror)
                .addProcessor(BlockIgnoreProcessor.STRUCTURE_BLOCK);
        template.placeInWorld(job.level, piece.pos, piece.pos, settings, job.random,
                Block.UPDATE_CLIENTS);
        for (Block loot : VANILLA_LOOT) {
            for (StructureTemplate.StructureBlockInfo info : template.filterBlocks(piece.pos, settings, loot)) {
                job.vanillaLoot.add(info.pos());
            }
        }
    }

    /** Replaces every chest, trapped chest, barrel and spawner a template brought with air. */
    private static void wipeVanillaLoot(Job job) {
        if (job.vanillaLoot.isEmpty()) {
            return;
        }
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (BlockPos pos : job.vanillaLoot) {
            job.level.setBlock(at.set(pos), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        StealInDimsMod.LOGGER.info("Mansion wiped {} vanilla loot blocks from the templates",
                job.vanillaLoot.size());
    }

    // ---------------------------------------------------------------- materials

    /**
     * The resolved block states one mansion is painted with (fix F2).
     *
     * <p>Resolved once per build so the plan pass never touches the registry again: everything below
     * is a plain {@link BlockState}. Every entry has a vanilla woodland-mansion fallback, so a theme
     * that names a block from a mod that is not installed degrades to dark oak rather than crashing.
     */
    private record Materials(BlockState floor, BlockState floorAlt, BlockState plank, BlockState log,
                             BlockState base, BlockState plaster, BlockState trim, BlockState roof,
                             BlockState roofSlab, BlockState window, BlockState carpet, BlockState shelf,
                             BlockState banner, BlockState fence, BlockState bars, BlockState lantern,
                             BlockState door) {
    }

    private static Materials materials(RaidTheme.RaidMap map, RaidTheme theme) {
        MansionPalette palette = map.palette(theme);
        return new Materials(
                resolve(palette.floor(), Blocks.DARK_OAK_PLANKS.defaultBlockState()),
                resolve(palette.floorAlt(), Blocks.COBBLESTONE.defaultBlockState()),
                resolve(palette.plank(), Blocks.DARK_OAK_PLANKS.defaultBlockState()),
                resolve(palette.log(), Blocks.DARK_OAK_LOG.defaultBlockState()),
                resolve(palette.base(), Blocks.COBBLESTONE.defaultBlockState()),
                resolve(palette.plaster(), Blocks.BIRCH_PLANKS.defaultBlockState()),
                resolve(palette.trim(), Blocks.MOSSY_STONE_BRICKS.defaultBlockState()),
                resolve(palette.roof(), Blocks.DARK_OAK_STAIRS.defaultBlockState()),
                resolve(palette.roofSlab(), Blocks.DARK_OAK_SLAB.defaultBlockState()),
                resolve(palette.window(), Blocks.GLASS_PANE.defaultBlockState()),
                resolve(palette.carpet(), Blocks.RED_CARPET.defaultBlockState()),
                resolve(palette.shelf(), Blocks.BOOKSHELF.defaultBlockState()),
                resolve(palette.banner(), Blocks.WHITE_WALL_BANNER.defaultBlockState()),
                resolve(palette.fence(), Blocks.DARK_OAK_FENCE.defaultBlockState()),
                resolve(palette.bars(), Blocks.IRON_BARS.defaultBlockState()),
                resolve(palette.lantern(), Blocks.LANTERN.defaultBlockState()),
                resolve(palette.door(), Blocks.DARK_OAK_DOOR.defaultBlockState()));
    }

    /**
     * Resolves a palette entry to a block state, falling back when the named block is not installed.
     * The templates carry their own blocks, so this only matters for the few things the builder adds:
     * the footing, the statue's plinth and the extraction room.
     */
    private static BlockState resolve(ResourceLocation id, BlockState fallback) {
        return BuiltInRegistries.BLOCK.getOptional(id).map(Block::defaultBlockState).orElse(fallback);
    }

    // ---------------------------------------------------------------- planning

    /**
     * Turns a plan into block placements (P5 §2.5).
     *
     * <p>Almost nothing is built here any more, and that is the point: the mansion's floors, walls,
     * windows, rooms, furniture and roofs all come from the vanilla templates, stamped by
     * {@link #stamp}. What is left is the small set of things vanilla does not have —
     * the footing the templates stand on, the emptied statue room, the transit crates and anchors,
     * the landing pocket in the entrance hall and the free-standing boss chamber.
     */
    private static Plan plan(ServerLevel level, RaidTheme theme, RaidTheme.RaidMap map,
                             MansionGeometry geometry, Materials m) {
        MansionLayout layout = geometry.layout();
        List<Placement> out = new ArrayList<>(60_000);
        Map<BlockPos, RaidLootGenerator.Kind> reserved = new LinkedHashMap<>();

        // 1) The footing. A vanilla mansion stands on the ground; this one stands in a void
        //    dimension, so the ground storey gets two courses of cobblestone and a one-cell skirt,
        //    which is also what stops the outer wall pieces from hanging over nothing.
        foundation(out, layout, m);

        // 2) The illager statue room (R17 / R23): one vanilla 2×2 room is emptied, the user's own
        //    illager_statue.nbt is stamped into it verbatim (see stampStatue, in its own pass after
        //    these placements), and only the parts the mod adds — the sealed dark room behind the
        //    tunnel, its boss portal and its chest — are built here.
        MansionLayout.Statue statue = layout.statue();
        if (statue != null) {
            clearInterior(out, layout, statue);
            buildDarkRoom(out, statue, m);
        } else {
            StealInDimsMod.LOGGER.warn("Mansion has no statue room, the boss portal will be unreachable");
        }

        // 3) Submission points: the transit crate and the return anchor, on every granted storey
        //    (decisions R3 + R4). A one-block pocket is dug for each, which is what the design asks
        //    for inside a room the templates already furnished.
        for (int index = 0; index < layout.submissions().size(); index++) {
            BlockPos crate = layout.submissionCrate(index);
            BlockPos anchor = layout.submissionAnchor(index);
            digPocket(out, m, crate);
            put(out, crate.getX(), crate.getY(), crate.getZ(),
                    ModBlocks.TRANSFER_CRATE.get().defaultBlockState(), true);
            digPocket(out, m, anchor);
            put(out, anchor.getX(), anchor.getY(), anchor.getZ(),
                    ModBlocks.RAID_PORTAL.get().defaultBlockState(), true);
        }

        // 4) The landing spot and the map anchor in the vanilla entrance hall.
        BlockPos spawn = layout.spawn();
        BlockPos mapAnchor = layout.anchor();
        digPocket(out, m, spawn);
        digPocket(out, m, mapAnchor);
        put(out, mapAnchor.getX(), mapAnchor.getY(), mapAnchor.getZ(),
                ModBlocks.RAID_PORTAL.get().defaultBlockState(), true);

        // 5) The boss chamber: a sealed platform south of the mansion, reached only through the dark
        //    room's portal, and only on S (design §3.5, decision R7).
        buildBossChamber(out, geometry, m);

        // 6) Open every doorway the plan declared (R25-E). The wall line itself already carries a
        //    vanilla `indoors_door` template, but a room template is only 8 blocks wide and its own
        //    first row often has masonry on it — the 1x2 garden room (`1x2_a6`) even has a four-high
        //    dark-oak wall built into exactly the row a doorway lands on. Clearing the same three-wide,
        //    three-high opening one row either side of the wall line is what makes the declared door
        //    real; the corridor side is already open, so in practice only that one room-side row goes.
        punchDoorways(out, layout);

        return new Plan(List.copyOf(out), Map.copyOf(reserved));
    }

    /**
     * Opens the declared doorways through the masonry one row either side of every wall line.
     *
     * <p>Both sides are cleared on purpose: the plan knows the wall line but not which side the room is
     * on, and the corridor side is open anyway, so the extra rows are a no-op.
     */
    private static void punchDoorways(List<Placement> out, MansionLayout layout) {
        int height = MansionLayoutBuilder.DOOR_HEIGHT + 1;
        for (MansionLayout.Door door : layout.doors()) {
            for (int y = door.layerY(); y < door.layerY() + height; y++) {
                for (int x = door.minX() - 1; x <= door.maxX() + 1; x++) {
                    for (int z = door.minZ() - 1; z <= door.maxZ() + 1; z++) {
                        clear(out, x, y, z);
                    }
                }
            }
        }
    }

    /**
     * Two courses of cobblestone under the ground storey, extended by one cell so the outer wall
     * templates — which stand just outside the walkable cells — land on something solid.
     */
    private static void foundation(List<Placement> out, MansionLayout layout, Materials m) {
        MansionLayout.Floor ground = layout.floors().get(0);
        int top = MansionLayoutBuilder.TEMPLATE_Y - 1;
        for (int cx = ground.minCX() - 1; cx <= ground.maxCX() + 1; cx++) {
            for (int cz = ground.minCZ() - 1; cz <= ground.maxCZ() + 1; cz++) {
                if (!nearWalkable(ground, cx, cz)) {
                    continue;
                }
                int baseX = layout.originX() + cx * MansionLayout.CELL_SIZE;
                int baseZ = layout.originZ() + cz * MansionLayout.CELL_SIZE;
                for (int dx = 0; dx < MansionLayout.CELL_SIZE; dx++) {
                    for (int dz = 0; dz < MansionLayout.CELL_SIZE; dz++) {
                        put(out, baseX + dx, top, baseZ + dz, m.base(), false);
                        put(out, baseX + dx, top - 1, baseZ + dz, m.base(), false);
                    }
                }
            }
        }
    }

    /** Whether a cell is walkable or touches one, so the footing gets a one-cell skirt. */
    private static boolean nearWalkable(MansionLayout.Floor floor, int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (floor.isWalkable(cx + dx, cz + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Empties the statue room's vanilla interior down to its own perimeter walls and floor.
     *
     * <p>R24: a room's template is 8·width−1 by 8·depth−1 blocks and sits in the <em>low</em> corner of
     * its cell rectangle, so a 2×2 room's interior is exactly {@code [minX, maxX-1] × [minZ, maxZ-1]}
     * and its walls are the rows and columns just outside that. Clearing the whole of it is what keeps
     * the template's furniture (bookshelves, tables, the potted plants of a 1×1) from ending up inside
     * the stamped statue: the statue's box is mostly air, so anything left in it shows through the
     * one-block-thick pixel face.
     *
     * <p>The clear stops one block below the statue's own head row, so the storey above keeps its floor:
     * the template is 8 tall but only its {@code z = 2} plane reaches {@code layerY + 7}, and the stamp
     * writes that row itself.
     */
    private static void clearInterior(List<Placement> out, MansionLayout layout,
                                      MansionLayout.Statue statue) {
        int minX = layout.blockMinX(statue.room());
        int maxX = layout.blockMaxX(statue.room()) - 1;
        int minZ = layout.blockMinZ(statue.room());
        int maxZ = layout.blockMaxZ(statue.room()) - 1;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = statue.layerY(); y < statue.layerY() + MansionLayout.STATUE_HEIGHT - 1; y++) {
                    clear(out, x, y, z);
                }
            }
        }
    }

    /** Digs a two-high pocket for a crate, an anchor or the landing spot, and floors it. */
    private static void digPocket(List<Placement> out, Materials m, BlockPos pos) {
        clear(out, pos.getX(), pos.getY(), pos.getZ());
        clear(out, pos.getX(), pos.getY() + 1, pos.getZ());
        put(out, pos.getX(), pos.getY() - 1, pos.getZ(), m.floor(), false);
    }

    /**
     * Raises the boss chamber (plan §P3.4).
     *
     * <p>A plain sealed hall: plank floor, plaster walls with the mansion's beam cap, a lantern grid,
     * and the return anchor a couple of blocks off the landing pad. The top evoker itself is spawned
     * by {@code RaidSessionManager} the first time a raider walks in, so the chamber is built on every
     * difficulty but stays empty on all but S.
     */
    private static void buildBossChamber(List<Placement> out, MansionGeometry geometry, Materials m) {
        BlockPos center = geometry.bossChamberCenter();
        int half = MansionGeometry.BOSS_CHAMBER_HALF;
        int layer = center.getY();
        int centerX = center.getX();
        int centerZ = center.getZ();
        int wallTop = layer + BOSS_CHAMBER_WALL_HEIGHT;

        for (int x = centerX - half; x <= centerX + half; x++) {
            for (int z = centerZ - half; z <= centerZ + half; z++) {
                boolean rim = Math.abs(x - centerX) == half || Math.abs(z - centerZ) == half;
                put(out, x, layer - 1, z, rim ? m.base() : m.floor(), false);
                put(out, x, layer - 2, z, m.base(), false);
                put(out, x, wallTop + 1, z, rim ? m.log() : m.plank(), false);
                if (rim) {
                    for (int y = layer; y < wallTop; y++) {
                        put(out, x, y, z, y == layer ? m.base() : m.plaster(), false);
                    }
                    put(out, x, wallTop, z, m.log(), false);
                }
            }
        }

        BlockPos spawn = geometry.bossChamberSpawn();
        if (spawn != null) {
            put(out, spawn.getX() + 2, spawn.getY(), spawn.getZ(),
                    ModBlocks.RAID_PORTAL.get().defaultBlockState(), true);
        }

        for (int x = centerX - half + 4; x <= centerX + half - 4; x += 6) {
            for (int z = centerZ - half + 4; z <= centerZ + half - 4; z += 6) {
                put(out, x, wallTop, z, hanging(m.lantern()), false);
            }
        }
    }

    // ---------------------------------------------------------------- the illager statue (R23)

    /**
     * Builds the only part of the statue room the mod still owns: the sealed 3×3×3 vault behind the
     * statue's crawl tunnel, its shell, and the boss portal inside it (decisions R7 / R11).
     *
     * <p>The statue itself, its Create drivetrain and its nose are no longer built here. The user's
     * {@code illager_statue.nbt} carries all of that — the wool pixel face, the hand crank, the
     * encased chain drives, the mechanical bearing and the {@code create:super_glue} entity that
     * glues the nose to it — and it is stamped unmodified by {@link #stampStatue}. The old
     * block-by-block relief, the hand-placed bearing and the mod's own 90° stage rotation are gone:
     * they would have fought the real Create contraption for the same nose (spec §5.4, R23).
     *
     * <p>R24 moved the vault out of the room: the shell's southern layer <em>is</em> the room's own
     * north wall ({@link MansionLayout.Statue#wallZ()}), the 3×3×3 cavity and the rest of the shell sit
     * in the cell behind that wall — which {@code pickStatue} picked precisely because nothing walkable
     * is there — and its only opening is the tunnel mouth the nose plugs. From the room all that can be
     * seen is the pixel face leaning on an unbroken wall.
     *
     * <p>The shell is painted in the mansion's own language rather than in one foreign lump: the row
     * that doubles as the wall keeps the wall's material (dark oak planks, with the cobblestone footing
     * course on the floor row), and the mass behind it is the same cobblestone the mansion stands on.
     */
    private static void buildDarkRoom(List<Placement> out, MansionLayout.Statue statue, Materials m) {
        BlockPos min = statue.chamberShellMin();
        BlockPos max = statue.chamberShellMax();
        int wallZ = statue.wallZ();
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    boolean interior = x > min.getX() && x < max.getX()
                            && y > min.getY() && y < max.getY()
                            && z > min.getZ() && z < max.getZ();
                    // Clear first, fill second. The vault is carved out of blocks vanilla already
                    // put there — its southern layer *is* the room's north wall, and a doorway or a
                    // neighbouring wall can cross the cell behind it — so every shell cell has to be
                    // emptied before the masonry goes in. Skipping the fill is what leaves the crawl
                    // tunnel's mouth open through that wall.
                    clear(out, x, y, z);
                    if (interior || statue.isTunnelMouth(x, y, z)) {
                        continue;
                    }
                    // The wall row stays the wall: dark oak planks above the cobblestone footing,
                    // exactly the courses vanilla's own wall pieces use there.
                    boolean wallCourse = z == wallZ && y > statue.layerY() - 1;
                    put(out, x, y, z, wallCourse ? m.plank() : m.base(), false);
                }
            }
        }

        BlockPos portal = statue.bossPortal();
        put(out, portal.getX(), portal.getY(), portal.getZ(),
                ModBlocks.BOSS_PORTAL.get().defaultBlockState(), true);
    }

    /**
     * Stamps the user's statue (spec §5.4, R23).
     *
     * <p>{@code StructureTemplateManager.get} →
     * {@code StructureTemplate.placeInWorld(level, pos, pos, settings, random, flags)}, with the
     * template's own origin as both the offset and the pivot. The settings pin {@link Rotation#NONE}:
     * the template's pixel face already looks along {@code +z}, which is into the room, so the
     * placement needs no turn — and none is wanted, since any rotation would be a change to what the
     * author built.
     *
     * <p>{@code setIgnoreEntities} is deliberately never called. The template carries exactly one
     * entity, {@code create:super_glue}, and the glue box it stores over the nose is what lets the
     * mechanical bearing carry the nose as a contraption instead of leaving it stuck to the face.
     *
     * <p>Nothing else is touched: the {@code create:hand_crank[facing=south]}, the run of
     * {@code create:encased_chain_drive} and the {@code create:mechanical_bearing[facing=south]} are
     * all placed from the template's own block list, block entities included.
     */
    private static void stampStatue(Job job) {
        MansionLayout.Statue statue = job.geometry.layout().statue();
        if (statue == null) {
            return;
        }
        StructureTemplateManager manager = job.level.getStructureManager();
        StructureTemplate template = manager.get(STATUE_TEMPLATE).orElse(null);
        if (template == null || template.getSize().equals(Vec3i.ZERO)) {
            StealInDimsMod.LOGGER.error(
                    "Statue template {} is missing from the structure manager, the statue room stays empty",
                    STATUE_TEMPLATE);
            return;
        }
        BlockPos origin = statue.templateOrigin();
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(Rotation.NONE)
                .setMirror(Mirror.NONE)
                // The stamp is a literal copy, so vanilla must not re-shape it against its new
                // surroundings afterwards. That pass is not cosmetic here: the user's statue carries
                // one biomesoplenty:bush, and re-shaping asks a plant standing on the room's planks
                // whether it can survive, which removes it. "Known shape" is vanilla's own switch for
                // "this is the final shape, place it as authored"; each block still gets its normal
                // neighbour updates from setBlock, which is what Create's kinetics need.
                .setKnownShape(true);
        // Whatever was glued inside the statue's own box belongs to an older build (the mod used to
        // summon that glue itself before R23) or to a schematic, and it would compete with the
        // template's own glue for the same nose.
        for (Entity stale : glueIn(job, statue)) {
            stale.discard();
        }
        template.placeInWorld(job.level, origin, origin, settings, job.random, Block.UPDATE_ALL);
        // The template's glue is in the world now. Tag it, so the arena's own sweep can wipe leftover
        // glue boxes without deleting the one the nose needs (RaidSpawner#STATUE_GLUE_TAG).
        int glued = 0;
        for (Entity glue : glueIn(job, statue)) {
            glue.addTag(RaidSpawner.STATUE_GLUE_TAG);
            glued++;
        }
        StealInDimsMod.LOGGER.info(
                "Mansion statue stamped from {} ({}x{}x{}) at {} rotation={} mirror={} knownShape={} entities={}",
                STATUE_TEMPLATE, template.getSize().getX(), template.getSize().getY(),
                template.getSize().getZ(), origin.toShortString(), settings.getRotation(),
                settings.getMirror(), settings.getKnownShape(), glued);
    }

    /** Create's super glue entities standing inside the statue's own 6×8×4 box. */
    private static List<Entity> glueIn(Job job, MansionLayout.Statue statue) {
        return job.level.getEntities((Entity) null,
                new AABB(statue.templateMinX(), statue.templateMinY(), statue.templateMinZ(),
                        statue.templateMaxX() + 1.0D, statue.templateMaxY() + 1.0D,
                        statue.templateMaxZ() + 1.0D),
                entity -> SUPER_GLUE.equals(
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())));
    }

    // ---------------------------------------------------------------- containers

    /**
     * Stocks the mansion's containers (fix F3).
     *
     * <p>Two sources feed one queue. First the slots the furniture reserved — a wardrobe, a hidden
     * chest, a barrel stack — each asking for the kind its room's {@code loot_tier} maps to. Then the
     * difficulty's own quota, drawn from the pool of rooms whose tier matches the kind's tier, falling
     * back to any room only when that tier has run out of floor. That is what ties
     * {@code loot_tier} to container quality instead of leaving it a comment.
     *
     * <p>Run once the batched build has landed, because a container has to see real floor beneath it
     * to be placed at all.
     */
    private static void placeContainers(Job job) {
        MansionLayout layout = job.geometry.layout();
        RandomSource random = RandomSource.create(job.seed);

        Map<MansionRoomSpec.LootTier, List<BlockPos>> byTier =
                new EnumMap<>(MansionRoomSpec.LootTier.class);
        for (MansionRoomSpec.LootTier tier : MansionRoomSpec.LootTier.values()) {
            byTier.put(tier, new ArrayList<>());
        }
        List<BlockPos> anyRoom = new ArrayList<>();
        for (MansionLayout.Room room : layout.rooms()) {
            if (room.type() == MansionLayout.RoomType.STAIRWELL
                    || room.type() == MansionLayout.RoomType.SUBMISSION
                    || room.type() == MansionLayout.RoomType.STATUE) {
                // The statue room is skipped for a reason R23 made concrete: the template leaves most
                // of its 6×8×4 box empty, and a container dropped into one of those empty cells is a
                // block inside the statue that the user's file does not have. Its floor space belongs
                // to the statue, not to the loot pass — the run's guaranteed chest is in the dark room.
                continue;
            }
            MansionRoomSpec spec = MansionRoomRegistry.find(room.type());
            if (spec == null) {
                continue;
            }
            int minX = layout.blockMinX(room.cells()) + 1;
            int maxX = layout.blockMaxX(room.cells());
            int minZ = layout.blockMinZ(room.cells()) + 1;
            int maxZ = layout.blockMaxZ(room.cells());
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, room.layerY(), z);
                    if (job.geometry.isInSafeZone(pos)) {
                        continue;
                    }
                    if (nearDoor(layout, pos) || nearShaft(layout, pos)) {
                        continue;
                    }
                    byTier.get(spec.lootTier()).add(pos);
                    anyRoom.add(pos);
                }
            }
        }
        for (List<BlockPos> pool : byTier.values()) {
            Util.shuffle(pool, random);
        }
        Util.shuffle(anyRoom, random);

        List<RaidLootGenerator.Kind> queue = new ArrayList<>();
        RaidLootGenerator.Kind[] kinds = RaidLootGenerator.Kind.values();
        int[] counts = job.difficulty.containerCounts();
        for (int tier = 0; tier < kinds.length; tier++) {
            for (int count = 0; count < counts[tier]; count++) {
                queue.add(kinds[tier]);
            }
        }

        BlockState chest = RaidArenaBuilder.chestState();
        List<BlockPos> placed = new ArrayList<>(queue.size());
        Map<MansionRoomSpec.LootTier, Integer> tierCursor = new EnumMap<>(MansionRoomSpec.LootTier.class);
        List<BlockPos> reserved = new ArrayList<>(job.reserved.keySet());
        boolean[] reservedUsed = new boolean[reserved.size()];
        int anyCursor = 0;

        for (RaidLootGenerator.Kind kind : queue) {
            BlockPos target = takeReserved(job, reserved, reservedUsed, kind);
            if (target == null) {
                target = takePool(job, byTier, tierCursor, kind);
            }
            if (target == null) {
                int[] advanced = {anyCursor};
                target = takeAny(job, anyRoom, advanced);
                anyCursor = advanced[0];
            }
            if (target == null) {
                continue;
            }
            if (RaidArenaBuilder.placeOne(job.level, target, kind, job.theme, job.difficulty,
                    job.seed, chest)) {
                placed.add(target);
            }
        }

        // The dark room's chest: exactly one, purple or gold, rolled from the run's own seed. Decision
        // R11 asks for it to be an ordinary container of its tier, so it goes through the same
        // placement call every other chest does — same fill, same tier, no key needed.
        MansionLayout.Statue statue = layout.statue();
        if (statue != null) {
            BlockPos treasure = statue.treasure();
            RaidLootGenerator.Kind kind = RandomSource.create(job.seed + TREASURE_SALT).nextBoolean()
                    ? RaidLootGenerator.Kind.PURPLE : RaidLootGenerator.Kind.GOLD;
            if (RaidArenaBuilder.placeOne(job.level, treasure, kind, job.theme, job.difficulty,
                    job.seed, chest)) {
                StealInDimsMod.LOGGER.info("Mansion dark room stocked with a {} chest at {}",
                        kind, treasure.toShortString());
            } else {
                StealInDimsMod.LOGGER.warn("Mansion dark room chest could not be placed at {}", treasure);
            }
        }

        int conflicts = 0;
        for (BlockPos pos : placed) {
            if (job.level.getBlockState(pos).isAir() || job.level.getBlockState(pos.below()).isAir()
                    || !job.level.getBlockState(pos.above()).isAir()) {
                conflicts++;
            }
        }
        StealInDimsMod.LOGGER.info(
                "Mansion containers for a {} map: {} requested, {} placed, {} conflicts",
                job.map.size(), queue.size(), placed.size(), conflicts);
    }

    /** The first reserved slot of {@code kind}, consumed so two containers cannot share it. */
    @Nullable
    private static BlockPos takeReserved(Job job, List<BlockPos> order, boolean[] used,
                                         RaidLootGenerator.Kind kind) {
        for (int index = 0; index < order.size(); index++) {
            if (used[index] || job.reserved.get(order.get(index)) != kind) {
                continue;
            }
            BlockPos pos = order.get(index);
            if (clearForContainer(job, pos)) {
                used[index] = true;
                return pos;
            }
        }
        return null;
    }

    /** The next clear slot in the pool of rooms whose {@code loot_tier} maps onto {@code kind}. */
    @Nullable
    private static BlockPos takePool(Job job, Map<MansionRoomSpec.LootTier, List<BlockPos>> byTier,
                                     Map<MansionRoomSpec.LootTier, Integer> cursors,
                                     RaidLootGenerator.Kind kind) {
        for (MansionRoomSpec.LootTier tier : MansionRoomSpec.LootTier.values()) {
            if (tier.kind() != kind) {
                continue;
            }
            List<BlockPos> pool = byTier.get(tier);
            int cursor = cursors.getOrDefault(tier, 0);
            while (cursor < pool.size()) {
                BlockPos candidate = pool.get(cursor++);
                if (clearForContainer(job, candidate)) {
                    cursors.put(tier, cursor);
                    return candidate;
                }
            }
            cursors.put(tier, cursor);
        }
        return null;
    }

    /** The shared fallback pool, used only once a kind's own tier has run out of clear floor. */
    @Nullable
    private static BlockPos takeAny(Job job, List<BlockPos> anyRoom, int[] cursor) {
        while (cursor[0] < anyRoom.size()) {
            BlockPos candidate = anyRoom.get(cursor[0]++);
            if (clearForContainer(job, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean clearForContainer(Job job, BlockPos pos) {
        return job.level.getBlockState(pos).isAir()
                && !job.level.getBlockState(pos.below()).isAir()
                && job.level.getBlockState(pos.above()).isAir();
    }

    private static boolean nearDoor(MansionLayout layout, BlockPos pos) {
        for (MansionLayout.Door door : layout.doors()) {
            if (door.distance(pos.getX(), pos.getZ()) <= DOOR_CLEARANCE) {
                return true;
            }
        }
        return false;
    }

    private static boolean nearShaft(MansionLayout layout, BlockPos pos) {
        // The stairwells are vanilla templates now, so a plan never carries a hand-dug shaft. The
        // hook stays because RaidTraps reads the same list.
        return false;
    }

    // ---------------------------------------------------------------- clearing

    /**
     * Wipes the mansion's footprint, the four-region footprint that used to sit west of it, and the
     * extraction room, then drops every leftover mob and item the sweep can reach.
     *
     * <p>The four-region block is wiped too on purpose: a dimension that hosted one of the legacy maps
     * keeps its platforms floating in the void, and the two layouts are close enough that the mansion
     * would otherwise be built next to a field of ruins.
     */
    public static void clear(ServerLevel level, MansionGeometry geometry) {
        int radius = geometry.mapRadius() + 2;
        int centerX = geometry.mapCenterX();
        int legacyRadius = new RegionGeometry("large", null).mapRadius() + 2;
        int minX = Math.min(centerX - radius, RaidArena.MAP_OFFSET_X - legacyRadius);
        int maxX = Math.max(centerX + radius, RaidArena.MAP_OFFSET_X + legacyRadius);
        int span = Math.max(radius, legacyRadius);
        // The wipe has to reach the top of the tallest roof the templates raise: the third storey is
        // at +19 and its roof starts at +27, so the band reaches 12 blocks above layer 2.
        int maxY = MansionLayout.layerY(MansionLayout.PHYSICAL_FLOORS - 1) + 12;

        RaidArenaBuilder.clearBox(level,
                -RaidArena.ROOM_HALF - 1, RaidArena.ROOM_FLOOR_Y - 1, -RaidArena.ROOM_HALF - 1,
                RaidArena.ROOM_HALF + 1, RaidArena.ROOM_CEILING_Y + 1, RaidArena.ROOM_HALF + 1);
        // The whole Y band either layout can occupy is cleared, so a taller previous map cannot leave
        // a layer floating above the new one.
        RaidArenaBuilder.clearBox(level, minX, RaidArena.UNDER_LAYER_Y - 2, -span, maxX, maxY, span);
        RaidSpawner.clear(level, geometry);
    }

    // ---------------------------------------------------------------- helpers

    private static void put(List<Placement> out, int x, int y, int z, BlockState state,
                            boolean update) {
        out.add(new Placement(x, y, z, state, update));
    }

    /** An air placement, recorded like any other so the batch keeps a single write order. */
    private static void clear(List<Placement> out, int x, int y, int z) {
        out.add(new Placement(x, y, z, Blocks.AIR.defaultBlockState(), false));
    }

    /** Sets a block state's {@code facing} property, if it has one, without a compile dependency. */
    private static BlockState facing(BlockState state, Direction direction) {
        for (Property<?> property : state.getProperties()) {
            if (property instanceof DirectionProperty directional
                    && "facing".equals(directional.getName())) {
                return state.setValue(directional, direction);
            }
        }
        return state;
    }

    /** A lantern hung from the ceiling above it, spelled out generically so any lantern works. */
    private static BlockState hanging(BlockState state) {
        return withProperty(state, "hanging", "true");
    }

    /**
     * Sets a named property by value name without knowing its type, so the builder can dress modded
     * and vanilla states alike from data alone. An unknown property or value is left alone.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState withProperty(BlockState state, String name, String value) {
        for (Property property : state.getProperties()) {
            if (!property.getName().equals(name)) {
                continue;
            }
            for (Object candidate : property.getPossibleValues()) {
                if (String.valueOf(candidate).equals(value)) {
                    return state.setValue(property, (Comparable) candidate);
                }
            }
        }
        return state;
    }

    /** The lantern block constant, kept so the compiler can see it is used by the entity-free path. */
    static {
        assert LanternBlock.HANGING != null || true;
    }
}
