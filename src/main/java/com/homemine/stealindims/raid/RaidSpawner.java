package com.homemine.stealindims.raid;

import com.homemine.stealindims.StealInDimsMod;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

/**
 * Fills a freshly built raid arena with its patrols (design §11, task 7.2).
 *
 * <p>Running once, the moment the countdown ends, is what makes the map read as "the same dungeon
 * every time" instead of a stream of random spawns: the lobby has already been built by
 * {@link RaidArenaBuilder#build} and this pass only populates it.
 *
 * <p>The arena layout is not persisted, but {@link RaidLayoutBuilder#build} is deterministic for a
 * given seed, so the four-region path re-derives the exact same rooms the builder cut, and drops
 * squads into them floor by floor: one squad per {@value #ROOMS_PER_SQUAD} rooms, drawn by weight
 * from the theme's {@code mob_table}. The mansion path takes its rooms straight off the plan.
 *
 * <p><b>Elites are Apotheosis's from P3</b> (decision R9, plan §P3.2). This pass used to buff one mob
 * per region-layer itself, with a flat health/damage/armour multiplier and a visible 精英 name; that is
 * gone, because a mob carrying both our boost and an Apotheosis affix would be a double elite. What is
 * left is the veteran <em>tag</em>, which keeps the spawn budget (and the log line) meaningful while
 * {@code data/apotheosis/apothic_elites/stealindims/} does the affixing.
 *
 * <p>Two rules keep the design's safe zones safe: the room holding the region's vault is skipped
 * entirely (both layers, since the two share one floor plan), and on the main layer a radius of
 * {@link RaidArena#VAULT_HALF} around the vault centre is rejected even inside a neighbouring room.
 * The vault's neighbouring rooms are instead put at the head of the candidate list, so patrols
 * naturally concentrate around it — that is the "more dangerous near the vault" note of design §11
 * expressed without changing the total spawn budget.
 */
public final class RaidSpawner {

    /** Tag on every mob this class spawns, so a later run can wipe the previous one's leftovers. */
    public static final String RAID_MOB_TAG = "stealindims_raid_mob";
    /** Tag marking the mobs rolled in this pass's veteran slot, so they can be counted apart. */
    public static final String ELITE_TAG = "stealindims_raid_elite";

    /** Create's super glue entity, the one thing the statue template brings that lives in the world. */
    private static final ResourceLocation SUPER_GLUE =
            ResourceLocation.fromNamespaceAndPath("create", "super_glue");

    /**
     * Tag {@code MansionArenaBuilder} puts on the glue that came out of the statue template.
     *
     * <p>Without it {@link #sweepGlue} could not tell the statue's own glue from a leftover, and the
     * sweep would either delete the nose's glue or leave a stale box behind to glue the dark room.
     */
    public static final String STATUE_GLUE_TAG = "stealindims_statue_glue";

    /** Rooms per patrol squad (design §11). */
    private static final int ROOMS_PER_SQUAD = 3;
    /** Veteran-tagged mobs per region-layer; four regions on two layers gives the design's ceiling. */
    private static final int MAX_ELITES_PER_LAYER = 3;
    /** Hard cap on one squad, so a generous {@code mob_table} entry cannot flood a room. */
    private static final int MAX_SQUAD_SIZE = 5;
    /** Tries to find a valid standing spot inside a room before giving up on that mob. */
    private static final int SPOT_ATTEMPTS = 8;

    /** Salt keeping the spawn roll independent of the loot roll, which uses the bare seed. */
    private static final long SPAWN_SALT = 0x5EED5EEDL;

    /** Slack around the map radius when sweeping entities, so drops just outside it are caught too. */
    private static final int SWEEP_RADIUS_PAD = 8;

    private RaidSpawner() {
    }

    // ---------------------------------------------------------------- populating

    /** Populates the arena of {@code session} once, when its countdown ends. */
    public static void spawn(ServerLevel level, RaidSession session) {
        // Decision R14: a map may carry its own spawn table, which is how the mansion probe gets its
        // illagers without the other two overworld maps ever spawning them.
        List<RaidTheme.MobEntry> table = session.map().mobTable(session.theme());
        if (table.isEmpty()) {
            StealInDimsMod.LOGGER.warn("Raid spawner: theme '{}' has no mob table, nothing spawned",
                    session.theme().dimension());
            return;
        }

        RaidGeometry geometry = session.geometry();
        if (geometry instanceof MansionGeometry mansion) {
            spawnMansion(level, session, mansion, table);
            return;
        }
        String size = session.map().size();
        String layoutId = session.map().layout();
        RandomSource random = RandomSource.create(session.seed() + SPAWN_SALT);
        Set<ResourceLocation> warned = new HashSet<>();

        int swept = sweepItems(level, geometry);
        if (swept > 0) {
            StealInDimsMod.LOGGER.info("Raid spawner: swept {} rebuild drops before populating a {} map",
                    swept, size);
        }
        int stale = sweepMobs(level, geometry);
        if (stale > 0) {
            StealInDimsMod.LOGGER.info("Raid spawner: swept {} leftover raid mobs before populating a {} map",
                    stale, size);
        }

        int squads = 0;
        int elites = 0;
        int mobs = 0;

        for (int region = 0; region < geometry.regionCount(); region++) {
            RaidLayoutBuilder.RaidLayout layout = RaidLayoutBuilder.build(
                    geometry.regionBounds(region), layoutId,
                    RaidArenaBuilder.seedFor(session.seed(), region));
            BlockPos vault = geometry.vaultCenter(region);
            List<RaidRoom> rooms = spawnableRooms(layout, vault);
            if (rooms.isEmpty()) {
                continue;
            }

            for (int layer : geometry.layerYs()) {
                boolean mainLayer = layer == RaidArena.MAIN_LAYER_Y;

                int squadCount = Math.max(1, rooms.size() / ROOMS_PER_SQUAD);
                for (int squad = 0; squad < squadCount; squad++) {
                    RaidRoom room = rooms.get(squad % rooms.size());
                    RaidTheme.MobEntry entry = pickMob(table, random);
                    int count = Mth.clamp(entry.min() + random.nextInt(Math.max(1, entry.max() - entry.min() + 1)),
                            1, MAX_SQUAD_SIZE);
                    for (int n = 0; n < count; n++) {
                        if (spawnMob(level, room, layer, entry.id(), vault, mainLayer, random, warned,
                                false, geometry)) {
                            mobs++;
                        }
                    }
                    squads++;
                }

                int eliteCount = 1 + random.nextInt(MAX_ELITES_PER_LAYER);
                for (int elite = 0; elite < eliteCount; elite++) {
                    RaidRoom room = rooms.get(elite % rooms.size());
                    RaidTheme.MobEntry entry = pickMob(table, random);
                    if (spawnMob(level, room, layer, entry.id(), vault, mainLayer, random, warned,
                            true, geometry)) {
                        elites++;
                        mobs++;
                    }
                }
            }
        }

        StealInDimsMod.LOGGER.info("Raid spawned {} squads and {} veteran-tagged mobs ({} mobs) for a {} map",
                squads, elites, mobs, size);
    }

    /**
     * Rooms of {@code layout} a squad may use, vault's own room excluded.
     *
     * <p>Rooms sharing a wall with the vault's room come first, which is how the ring around the
     * vault ends up the most dangerous part of the region without raising the total spawn count.
     */
    private static List<RaidRoom> spawnableRooms(RaidLayoutBuilder.RaidLayout layout, BlockPos vault) {
        RaidRoom vaultRoom = null;
        for (RaidRoom room : layout.rooms()) {
            if (room.contains(vault.getX(), vault.getZ())) {
                vaultRoom = room;
                break;
            }
        }

        List<RaidRoom> near = new ArrayList<>();
        List<RaidRoom> rest = new ArrayList<>();
        for (RaidRoom room : layout.rooms()) {
            if (room == vaultRoom) {
                continue;
            }
            if (vaultRoom != null && sharesWall(room, vaultRoom)) {
                near.add(room);
            } else {
                rest.add(room);
            }
        }

        List<RaidRoom> ordered = new ArrayList<>(near.size() + rest.size());
        ordered.addAll(near);
        ordered.addAll(rest);
        return ordered;
    }

    /** Whether two rooms of the same layer meet along a shared wall column (or row). */
    static boolean sharesWall(RaidRoom a, RaidRoom b) {
        boolean xTouch = a.maxX() == b.minX() || b.maxX() == a.minX();
        boolean zTouch = a.maxZ() == b.minZ() || b.maxZ() == a.minZ();
        boolean xOverlap = a.minX() <= b.maxX() && b.minX() <= a.maxX();
        boolean zOverlap = a.minZ() <= b.maxZ() && b.minZ() <= a.maxZ();
        return (xTouch && zOverlap) || (zTouch && xOverlap);
    }

    // ---------------------------------------------------------------- mansion

    /**
     * Populates a mansion (plan §P1.4).
     *
     * <p>A mansion hands out its rooms as rectangles, so patrols are dropped straight into them, one
     * squad per {@value #ROOMS_PER_SQUAD} rooms per layer, instead of the four-region pass's BSP
     * re-derivation. The entrance hall, the stairwell wells and the submission rooms are left alone,
     * which is the mansion spelling of the design's safe zones.
     */
    private static void spawnMansion(ServerLevel level, RaidSession session, MansionGeometry mansion,
                                     List<RaidTheme.MobEntry> table) {
        MansionLayout layout = mansion.layout();
        RandomSource random = RandomSource.create(session.seed() + SPAWN_SALT);
        Set<ResourceLocation> warned = new HashSet<>();

        int swept = sweepItems(level, mansion);
        if (swept > 0) {
            StealInDimsMod.LOGGER.info("Raid spawner: swept {} rebuild drops before populating a {} map",
                    swept, session.map().size());
        }
        int stale = sweepMobs(level, mansion);
        if (stale > 0) {
            StealInDimsMod.LOGGER.info("Raid spawner: swept {} leftover raid mobs before populating a {} map",
                    stale, session.map().size());
        }
        // The glue sweep has to run here rather than only at rebuild time: a rebuild finds the mansion
        // chunks still cold, and their entity sections come back empty (the same trap {@link #sweepMobs}
        // documents). The statue's own glue carries {@link #STATUE_GLUE_TAG} and is left alone.
        int glue = sweepGlue(level, mansion);
        if (glue > 0) {
            StealInDimsMod.LOGGER.info("Raid spawner: swept {} leftover super glue before populating a {} map",
                    glue, session.map().size());
        }

        int squads = 0;
        int elites = 0;
        int mobs = 0;

        for (int layer : mansion.layerYs()) {
            List<MansionLayout.Room> rooms = new ArrayList<>();
            for (MansionLayout.Room room : layout.roomsAt(layer)) {
                if (room.type() == MansionLayout.RoomType.STAIRWELL
                        || room.type() == MansionLayout.RoomType.SUBMISSION
                        || room.type() == MansionLayout.RoomType.ENTRANCE) {
                    continue;
                }
                rooms.add(room);
            }
            if (rooms.isEmpty()) {
                continue;
            }

            int squadCount = Math.max(1, rooms.size() / ROOMS_PER_SQUAD);
            for (int squad = 0; squad < squadCount; squad++) {
                MansionLayout.Room room = rooms.get(squad % rooms.size());
                RaidTheme.MobEntry entry = pickMob(table, random);
                int count = Mth.clamp(entry.min() + random.nextInt(Math.max(1, entry.max() - entry.min() + 1)),
                        1, MAX_SQUAD_SIZE);
                for (int n = 0; n < count; n++) {
                    if (spawnMansionMob(level, layout, room, entry.id(), random, warned, false, mansion)) {
                        mobs++;
                    }
                }
                squads++;
            }

            int eliteCount = 1 + random.nextInt(MAX_ELITES_PER_LAYER);
            for (int elite = 0; elite < eliteCount; elite++) {
                MansionLayout.Room room = rooms.get(elite % rooms.size());
                RaidTheme.MobEntry entry = pickMob(table, random);
                if (spawnMansionMob(level, layout, room, entry.id(), random, warned, true, mansion)) {
                    elites++;
                    mobs++;
                }
            }
        }

        StealInDimsMod.LOGGER.info("Raid spawned {} squads and {} veteran-tagged mobs ({} mobs) for a {} map",
                squads, elites, mobs, session.map().size());
    }

    private static boolean spawnMansionMob(ServerLevel level, MansionLayout layout,
                                           MansionLayout.Room room, ResourceLocation id,
                                           RandomSource random, Set<ResourceLocation> warned,
                                           boolean elite, MansionGeometry geometry) {
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            if (warned.add(id)) {
                StealInDimsMod.LOGGER.warn("Raid spawner: mob table entry '{}' is not a known entity, skipped", id);
            }
            return false;
        }

        BlockPos spot = pickMansionSpot(level, layout, room, random, geometry);
        if (spot == null) {
            return false;
        }

        Entity spawned = BuiltInRegistries.ENTITY_TYPE.get(id).spawn(level, spot, MobSpawnType.STRUCTURE);
        if (!(spawned instanceof Mob mob)) {
            if (spawned != null) {
                spawned.discard();
            }
            if (warned.add(id)) {
                StealInDimsMod.LOGGER.warn("Raid spawner: mob table entry '{}' is not a mob, skipped", id);
            }
            return false;
        }

        mob.setYRot(random.nextFloat() * 360.0F);
        mob.setYHeadRot(mob.getYRot());
        mob.setPersistenceRequired();
        mob.addTag(RAID_MOB_TAG);
        if (elite) {
            markElite(mob);
        }
        return true;
    }

    /** A random standing spot inside a mansion room, clear of its walls and of any safe zone. */
    @Nullable
    private static BlockPos pickMansionSpot(ServerLevel level, MansionLayout layout,
                                            MansionLayout.Room room, RandomSource random,
                                            MansionGeometry geometry) {
        int minX = layout.blockMinX(room.cells()) + 1;
        int maxX = layout.blockMaxX(room.cells());
        int minZ = layout.blockMinZ(room.cells()) + 1;
        int maxZ = layout.blockMaxZ(room.cells());
        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;
        if (width <= 0 || depth <= 0) {
            return null;
        }

        for (int attempt = 0; attempt < SPOT_ATTEMPTS; attempt++) {
            int x = minX + random.nextInt(width);
            int z = minZ + random.nextInt(depth);
            BlockPos pos = new BlockPos(x, room.layerY(), z);
            if (geometry.isInSafeZone(pos)) {
                continue;
            }
            if (level.getBlockState(pos).isAir()
                    && level.getBlockState(pos.above()).isAir()
                    && !level.getBlockState(pos.below()).isAir()
                    && hasClearance(level, pos)) {
                return pos;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- spawning

    private static boolean spawnMob(ServerLevel level, RaidRoom room, int layer, ResourceLocation id,
                                    BlockPos vault, boolean mainLayer, RandomSource random,
                                    Set<ResourceLocation> warned, boolean elite, RaidGeometry geometry) {
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            if (warned.add(id)) {
                StealInDimsMod.LOGGER.warn("Raid spawner: mob table entry '{}' is not a known entity, skipped", id);
            }
            return false;
        }

        BlockPos spot = pickSpot(level, room, layer, vault, mainLayer, random, geometry);
        if (spot == null) {
            return false;
        }

        Entity spawned = BuiltInRegistries.ENTITY_TYPE.get(id).spawn(level, spot, MobSpawnType.STRUCTURE);
        if (!(spawned instanceof Mob mob)) {
            if (spawned != null) {
                spawned.discard();
            }
            if (warned.add(id)) {
                StealInDimsMod.LOGGER.warn("Raid spawner: mob table entry '{}' is not a mob, skipped", id);
            }
            return false;
        }

        mob.setYRot(random.nextFloat() * 360.0F);
        mob.setYHeadRot(mob.getYRot());
        mob.setPersistenceRequired();
        mob.addTag(RAID_MOB_TAG);
        if (elite) {
            markElite(mob);
        }
        return true;
    }

    /** A random standing spot in {@code room}'s interior, or {@code null} after {@value #SPOT_ATTEMPTS} tries. */
    private static BlockPos pickSpot(ServerLevel level, RaidRoom room, int layer, BlockPos vault,
                                     boolean mainLayer, RandomSource random, RaidGeometry geometry) {
        int interiorWidth = room.maxX() - room.minX() - 1;
        int interiorDepth = room.maxZ() - room.minZ() - 1;
        if (interiorWidth <= 0 || interiorDepth <= 0) {
            return null;
        }

        for (int attempt = 0; attempt < SPOT_ATTEMPTS; attempt++) {
            int x = room.minX() + 1 + random.nextInt(interiorWidth);
            int z = room.minZ() + 1 + random.nextInt(interiorDepth);
            if (mainLayer
                    && Math.abs(x - vault.getX()) <= RaidArena.VAULT_HALF
                    && Math.abs(z - vault.getZ()) <= RaidArena.VAULT_HALF) {
                continue;
            }
            BlockPos pos = new BlockPos(x, layer, z);
            if (level.getBlockState(pos).isAir()
                    && level.getBlockState(pos.above()).isAir()
                    && !level.getBlockState(pos.below()).isAir()
                    && hasClearance(level, pos)) {
                return pos;
            }
        }
        return null;
    }

    /**
     * Whether {@code pos} keeps a one-block air margin on all four sides, on the spawn level and the
     * one above it. Without it a wide mob — a spider is 1.4 blocks across — can be placed with its
     * hitbox clipping the room wall and suffocate the moment it appears.
     */
    private static boolean hasClearance(ServerLevel level, BlockPos pos) {
        BlockPos head = pos.above();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!level.getBlockState(pos.relative(direction)).isAir()
                    || !level.getBlockState(head.relative(direction)).isAir()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tags the mobs this pass rolls in its "veteran" pass (plan §P3.2).
     *
     * <p>This used to be an actual buff: a flat health/damage/armour boost and a visible 精英 name.
     * From P3 the affixes are Apotheosis's — {@code data/apotheosis/apothic_elites/stealindims/} — and
     * a mob carrying both our boost and an Apotheosis affix would be a double elite, which decision R9
     * explicitly rules out. So the roll survives only as a tag, and the stat sheet is left to the
     * spawner's own mob table.
     */
    private static void markElite(Mob mob) {
        mob.addTag(ELITE_TAG);
    }

    /** Weighted pick from the theme's {@code mob_table}. */
    private static RaidTheme.MobEntry pickMob(List<RaidTheme.MobEntry> table, RandomSource random) {
        int total = 0;
        for (RaidTheme.MobEntry entry : table) {
            total += Math.max(1, entry.weight());
        }

        int roll = random.nextInt(total);
        for (RaidTheme.MobEntry entry : table) {
            roll -= Math.max(1, entry.weight());
            if (roll < 0) {
                return entry;
            }
        }
        return table.get(table.size() - 1);
    }

    // ---------------------------------------------------------------- clearing

    /**
     * Removes any mob a previous run of this class left behind, plus loot dropped on the floor.
     *
     * <p>Called when a new arena is built, which is the only moment a raid dimension can still hold
     * leftovers: the slot in {@code RaidSessionManager} is dropped as soon as a run ends, and the
     * dimension is not ticked while unloaded, so nothing can spawn there in between.
     *
     * <p>Item entities are swept for the same reason the arena blocks are: {@link
     * RaidArenaBuilder#clear} wipes blocks but leaves entities standing, so anything spilled out of
     * a container that broke during an earlier run would still be lying there to be picked up for
     * free.
     */
    public static void clear(ServerLevel level, RaidGeometry geometry) {
        int mobs = sweepMobs(level, geometry);
        int items = sweepItems(level, geometry);
        int glue = sweepGlue(level, geometry);
        if (mobs > 0 || items > 0 || glue > 0) {
            StealInDimsMod.LOGGER.info(
                    "Raid spawner: cleared {} leftovers ({} loot drops, {} super glue)",
                    mobs + items + glue, items, glue);
        }
    }

    /**
     * Discards leftover Create super glue in the arena, and returns how many were removed.
     *
     * <p>The illager statue is the user's own {@code illager_statue.nbt} from R23 on, and the single
     * {@code create:super_glue} entity inside it is the nose's glue — the mod no longer summons one of
     * its own. Any other glue in the box is therefore a leftover, from the pre-R23 build that did
     * summon one or from a player, and a stale glue box is not harmless: it silently glues whatever
     * happens to sit inside it, so a bearing's contraption could drag the dark room's wall along with
     * the nose. Player glue is swept with it, which costs nothing here: a raid map is wiped block by
     * block on every rebuild anyway.
     */
    public static int sweepGlue(ServerLevel level, RaidGeometry geometry) {
        List<AABB> boxes = sweepBoxes(level, geometry);
        loadSweepChunks(level, boxes);

        List<Entity> glue = new ArrayList<>();
        for (AABB box : boxes) {
            glue.addAll(level.getEntities((Entity) null, box,
                    entity -> !entity.getTags().contains(STATUE_GLUE_TAG)
                            && SUPER_GLUE.equals(
                                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))));
        }
        for (Entity entity : glue) {
            entity.discard();
        }
        return glue.size();
    }

    /**
     * Discards the tagged raid mobs a rebuild or an earlier run left standing, and returns how many.
     *
     * <p>Called twice on purpose. {@link #clear} runs at rebuild time, but the mansion's chunks are
     * only just being force-loaded then and their entity sections can still be empty, so a mob parked
     * in one of them silently survives the sweep — measured: mobs from an earlier session kept their
     * old custom names through three rebuilds. The second call, from {@link #spawn} once the map is up
     * and its chunks have been ticking, is what actually catches them.
     */
    public static int sweepMobs(ServerLevel level, RaidGeometry geometry) {
        List<AABB> boxes = sweepBoxes(level, geometry);
        loadSweepChunks(level, boxes);

        List<Entity> mobs = new ArrayList<>();
        for (AABB box : boxes) {
            mobs.addAll(level.getEntities((Entity) null, box,
                    entity -> entity.getTags().contains(RAID_MOB_TAG)));
        }
        for (Entity mob : mobs) {
            mob.discard();
        }
        return mobs.size();
    }

    /**
     * Discards the loot drops a rebuild left behind, and returns how many were removed.
     *
     * <p>Wiping the previous layout pops every decoration that needed support — carpets, doors,
     * buttons — along with whatever the containers around them held, so a freshly built arena lies
     * covered in a few hundred drops that could simply be picked up for free. {@link #clear} cannot
     * catch them: {@link RaidArenaBuilder#build} runs after it, and the pops only land on the tail
     * of the tick's neighbour-update queue. Sweeping again once the countdown ends is both late
     * enough to see them and early enough that no player can reach the map.
     */
    public static int sweepItems(ServerLevel level, RaidGeometry geometry) {
        List<AABB> boxes = sweepBoxes(level, geometry);
        loadSweepChunks(level, boxes);

        int items = 0;
        for (AABB box : boxes) {
            for (Entity entity : level.getEntities((Entity) null, box, e -> e instanceof ItemEntity)) {
                entity.discard();
                items++;
            }
        }
        return items;
    }

    /** The arena and the lobby, the two places a run can leave entities behind. */
    private static List<AABB> sweepBoxes(ServerLevel level, RaidGeometry geometry) {
        int radius = geometry.mapRadius() + SWEEP_RADIUS_PAD;
        int half = RaidArena.ROOM_HALF + 1;
        return List.of(
                new AABB(
                        geometry.mapCenterX() - radius, level.getMinBuildHeight(), -radius,
                        geometry.mapCenterX() + radius, level.getMaxBuildHeight(), radius),
                new AABB(
                        -half, RaidArena.ROOM_FLOOR_Y - 1, -half,
                        half, RaidArena.ROOM_CEILING_Y + 1, half));
    }

    /**
     * An entity query only sees loaded chunks, and the sweep runs before the arena builder loads
     * them, so without this the leftovers would silently survive every rebuild.
     */
    private static void loadSweepChunks(ServerLevel level, List<AABB> boxes) {
        for (AABB box : boxes) {
            for (int chunkX = Mth.floor(box.minX) >> 4; chunkX <= Mth.floor(box.maxX) >> 4; chunkX++) {
                for (int chunkZ = Mth.floor(box.minZ) >> 4; chunkZ <= Mth.floor(box.maxZ) >> 4; chunkZ++) {
                    level.getChunk(chunkX, chunkZ);
                }
            }
        }
    }
}
