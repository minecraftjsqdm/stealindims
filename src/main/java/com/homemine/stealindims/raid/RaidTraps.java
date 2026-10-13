package com.homemine.stealindims.raid;

import com.homemine.stealindims.StealInDimsMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;

/**
 * Scatters the raid map's low-power traps (design §11, task 7.3).
 *
 * <p>Three kinds, all vanilla blocks, none of them lethal on their own: a floor-mounted arrow
 * dispenser under a wooden pressure plate, a tripwire strung across a wide doorway that lights two
 * redstone lamps, and a sand column that a piston shoves out from under itself. All three were
 * validated in the running game before this class existed, and the two rules that came out of that
 * are baked in here: a wooden pressure plate powers the block directly beneath it (which is what
 * lets the dispenser sit in the floor), and a horizontal piston shoves its load one cell over but
 * leaves its head behind, so the sand only falls once the trigger's signal is gone.
 *
 * <p>The run is deployed right after {@link RaidArenaBuilder#build}, so it re-derives the map from
 * the seed exactly the way {@link RaidSpawner} does, and every cell it wants is checked for air
 * first — the loot containers are already standing and must not be overwritten. Placement is kept
 * off the design's safe zones: no trap inside a vault, none within the vault reserve the containers
 * respect, and none in the vault's own entrance passage. Where a trap does go is the design's "main
 * paths and the ring around the vault": doorways on both layers, plus a couple of rockfalls per
 * region in the rooms that share a wall with the vault's room.
 */
public final class RaidTraps {

    /** Salt keeping the trap roll independent of the loot roll (bare seed) and the spawn roll. */
    private static final long TRAP_SALT = 0x7A17_7A17L;

    /** Chance in percent that a doorway carries a trap at all. */
    private static final int DOOR_TRAP_PERCENT = 40;
    /** Of the trapped doorways, the share in percent that becomes an alarm instead of an arrow trap. */
    private static final int ALARM_PERCENT = 60;

    /** Dispenser slots loaded with arrows: one volley per slot, so a trap stays low-power. */
    private static final int ARROW_SLOTS = 9;
    /** Arrows in each loaded slot, enough for a few passes over the plate. */
    private static final int ARROWS_PER_SLOT = 16;

    /** Rockfalls per region, enough to mark the vault's ring without carpeting it. */
    private static final int ROCKFALLS_PER_REGION = 2;
    /** Tries to find a free run of cells for one rockfall before giving up on it. */
    private static final int ROCKFALL_ATTEMPTS = 12;
    /** Cells one rockfall occupies along X: plate, dust, piston, support, landing. */
    private static final int ROCKFALL_LENGTH = 5;
    /** Sand blocks stacked on the support, released once the piston shoves it aside. */
    private static final int ROCKFALL_SAND = 2;

    /** No trap within this Chebyshev distance of a vault: the reserve the containers respect too. */
    private static final int VAULT_RESERVE_RADIUS = RaidArena.VAULT_HALF + 1;
    /** Length of the vault's own entrance passage, which has to stay walkable. */
    private static final int VAULT_APPROACH_LENGTH = 3;
    /** No trap this close to a doorway of its own room, so a trap never seals a passage off. */
    private static final int DOOR_CLEARANCE = 2;
    /** An alarm needs a hook at each end and at least one wire strung between them. */
    private static final int MIN_ALARM_WIDTH = 3;

    private RaidTraps() {
    }

    // ---------------------------------------------------------------- deployment

    /** Deploys the traps of {@code session}'s freshly built arena. */
    public static void deploy(ServerLevel level, RaidSession session) {
        RaidGeometry geometry = session.geometry();
        if (geometry instanceof MansionGeometry mansion) {
            deployMansion(level, session, mansion);
            return;
        }
        String size = session.map().size();
        String layoutId = session.map().layout();
        RandomSource random = RandomSource.create(session.seed() + TRAP_SALT);

        int alarms = 0;
        int arrows = 0;
        int rockfalls = 0;

        for (int region = 0; region < geometry.regionCount(); region++) {
            RaidLayoutBuilder.RaidLayout base = RaidLayoutBuilder.build(
                    geometry.regionBounds(region), layoutId,
                    RaidArenaBuilder.seedFor(session.seed(), region));
            BlockPos vault = geometry.vaultCenter(region);

            for (int layer : geometry.layerYs()) {
                RaidLayoutBuilder.RaidLayout layout = base.withLayer(layer);

                for (RaidLayoutBuilder.Door door : layout.doors()) {
                    if (random.nextInt(100) >= DOOR_TRAP_PERCENT) {
                        continue;
                    }
                    boolean wantAlarm = random.nextInt(100) < ALARM_PERCENT;
                    if (wantAlarm && placeAlarm(level, door, geometry)) {
                        alarms++;
                    } else if (placeArrowTrap(level, door, geometry)) {
                        arrows++;
                    }
                }

                // The vault's ring is the design's most dangerous part; the vault door itself is on
                // the main layer, so that is where a falling column belongs.
                if (layer == RaidArena.MAIN_LAYER_Y) {
                    rockfalls += placeRockfalls(level, layout, vault, geometry, random);
                }
            }
        }

        StealInDimsMod.LOGGER.info(
                "Raid traps for a {} map: {} alarms, {} arrow traps, {} rockfalls",
                size, alarms, arrows, rockfalls);
    }

    // ---------------------------------------------------------------- mansion

    /**
     * The mansion's traps (plan §P1.4).
     *
     * <p>A mansion already knows its rooms as rectangles, so this pass does not re-derive a layout the
     * way the four-region one has to: it walks {@link MansionLayout#rooms()} directly, drops an arrow
     * trap on roughly {@value #DOOR_TRAP_PERCENT}% of the room doorways, and puts a rockfall or two
     * inside the bigger rooms. The forbidden zone is the design's safe zone — the submission rooms and
     * the entrance hall — plus a margin around every doorway, never the old vault radius.
     */
    private static void deployMansion(ServerLevel level, RaidSession session, MansionGeometry geometry) {
        MansionLayout layout = geometry.layout();
        RandomSource random = RandomSource.create(session.seed() + TRAP_SALT);

        int arrows = 0;
        int rockfalls = 0;
        for (MansionLayout.Room room : layout.rooms()) {
            if (room.type() == MansionLayout.RoomType.STAIRWELL
                    || room.type() == MansionLayout.RoomType.SUBMISSION
                    || room.type() == MansionLayout.RoomType.ENTRANCE) {
                continue;
            }
            List<MansionLayout.Door> doors = mansionDoors(layout, room);
            if (!doors.isEmpty() && random.nextInt(100) < DOOR_TRAP_PERCENT) {
                MansionLayout.Door door = doors.get(random.nextInt(doors.size()));
                if (placeMansionArrowTrap(level, door, geometry)) {
                    arrows++;
                }
            }
            if (room.area() >= 2 && placeMansionRockfall(level, layout, room, geometry, random)) {
                rockfalls++;
            }
        }

        StealInDimsMod.LOGGER.info(
                "Raid traps for a {} map: 0 alarms, {} arrow traps, {} rockfalls",
                session.map().size(), arrows, rockfalls);
    }

    /** Every doorway on the boundary of {@code room}, in plan order. */
    private static List<MansionLayout.Door> mansionDoors(MansionLayout layout, MansionLayout.Room room) {
        List<MansionLayout.Door> found = new ArrayList<>();
        int minX = layout.blockMinX(room.cells()) - 1;
        int maxX = layout.blockMaxX(room.cells()) + 1;
        int minZ = layout.blockMinZ(room.cells()) - 1;
        int maxZ = layout.blockMaxZ(room.cells()) + 1;
        for (MansionLayout.Door door : layout.doorsAt(room.layerY())) {
            if (door.minX() >= minX && door.minX() <= maxX
                    && door.minZ() >= minZ && door.minZ() <= maxZ) {
                found.add(door);
            }
        }
        return found;
    }

    /** A floor dispenser under a wooden plate, planted in the one cell of a mansion doorway. */
    private static boolean placeMansionArrowTrap(ServerLevel level, MansionLayout.Door door,
                                                 MansionGeometry geometry) {
        BlockPos pos = new BlockPos(door.minX(), door.layerY(), door.minZ());
        if (!isFree(level, pos) || isFree(level, pos.below()) || !isMansionTrapAllowed(pos, geometry)) {
            return false;
        }
        BlockPos floor = pos.below();
        level.setBlock(floor, Blocks.DISPENSER.defaultBlockState()
                .setValue(DispenserBlock.FACING, Direction.UP), Block.UPDATE_ALL);
        if (level.getBlockEntity(floor) instanceof DispenserBlockEntity dispenser) {
            for (int slot = 0; slot < ARROW_SLOTS; slot++) {
                dispenser.setItem(slot, new ItemStack(Items.ARROW, ARROWS_PER_SLOT));
            }
        }
        level.setBlock(pos, Blocks.OAK_PRESSURE_PLATE.defaultBlockState(), Block.UPDATE_ALL);
        return true;
    }

    /** One mansion rockfall: plate, dust, piston, a stone carrying two blocks of sand, and the drop. */
    private static boolean placeMansionRockfall(ServerLevel level, MansionLayout layout,
                                                MansionLayout.Room room, MansionGeometry geometry,
                                                RandomSource random) {
        int layer = room.layerY();
        int loX = layout.blockMinX(room.cells()) + 1;
        int hiX = layout.blockMaxX(room.cells()) - ROCKFALL_LENGTH + 1;
        int loZ = layout.blockMinZ(room.cells()) + 1;
        int hiZ = layout.blockMaxZ(room.cells());
        if (loX > hiX || loZ > hiZ) {
            return false;
        }

        for (int attempt = 0; attempt < ROCKFALL_ATTEMPTS; attempt++) {
            int x = loX + random.nextInt(hiX - loX + 1);
            int z = loZ + random.nextInt(hiZ - loZ + 1);
            if (!mansionRockfallFits(level, layout, x, z, layer, geometry)) {
                continue;
            }
            BlockPos carrier = new BlockPos(x + 3, layer, z);
            level.setBlock(carrier, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            Block drop = random.nextBoolean() ? Blocks.SAND : Blocks.GRAVEL;
            for (int i = 1; i <= ROCKFALL_SAND; i++) {
                level.setBlock(carrier.above(i), drop.defaultBlockState(), Block.UPDATE_ALL);
            }
            level.setBlock(new BlockPos(x + 2, layer, z), Blocks.PISTON.defaultBlockState()
                    .setValue(DirectionalBlock.FACING, Direction.EAST), Block.UPDATE_ALL);
            level.setBlock(new BlockPos(x + 1, layer, z),
                    Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(new BlockPos(x, layer, z),
                    Blocks.OAK_PRESSURE_PLATE.defaultBlockState(), Block.UPDATE_ALL);
            return true;
        }
        return false;
    }

    private static boolean mansionRockfallFits(ServerLevel level, MansionLayout layout, int x, int z,
                                               int layer, MansionGeometry geometry) {
        for (int i = 0; i < ROCKFALL_LENGTH; i++) {
            if (mansionNearDoor(layout, x + i, z)) {
                return false;
            }
            BlockPos pos = new BlockPos(x + i, layer, z);
            if (!isFree(level, pos) || isFree(level, pos.below())
                    || !isMansionTrapAllowed(pos, geometry)) {
                return false;
            }
        }
        BlockPos carrier = new BlockPos(x + 3, layer, z);
        for (int i = 1; i <= ROCKFALL_SAND; i++) {
            if (!isFree(level, carrier.above(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean mansionNearDoor(MansionLayout layout, int x, int z) {
        for (MansionLayout.Door door : layout.doors()) {
            if (door.distance(x, z) <= DOOR_CLEARANCE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a trap may stand at {@code pos} in a mansion: never in the extraction room, never in a
     * safe zone (a submission room or the entrance hall), never in the statue room or its vault, and
     * never in a stairwell well.
     *
     * <p>R24 added the statue room to the list. The room is a set piece whose only visible feature has
     * to be the pixel face, and its vault is reached by a 2×3 crawl: a dispenser sunk into the floor
     * there both fires point blank in the crawl and drops an {@code oak_pressure_plate} inside the
     * user's own {@code illager_statue.nbt} box, which is meant to be placed verbatim.
     */
    private static boolean isMansionTrapAllowed(BlockPos pos, MansionGeometry geometry) {
        if (RaidArena.isInSafeRoom(pos) || geometry.isInSafeZone(pos)) {
            return false;
        }
        MansionLayout layout = geometry.layout();
        MansionLayout.Statue statue = layout.statue();
        if (statue != null && inStatueSetPiece(layout, statue, pos)) {
            return false;
        }
        for (MansionLayout.Shaft shaft : layout.shafts()) {
            MansionLayout.Cells cell = shaft.cells();
            int minX = layout.blockMinX(cell);
            int maxX = layout.blockMaxX(cell);
            int minZ = layout.blockMinZ(cell);
            int maxZ = layout.blockMaxZ(cell);
            if (pos.getX() >= minX - 1 && pos.getX() <= maxX + 1
                    && pos.getZ() >= minZ - 1 && pos.getZ() <= maxZ + 1) {
                return false;
            }
        }
        return true;
    }

    /** The statue's room rectangle (plus its walls) and the vault behind them. */
    private static boolean inStatueSetPiece(MansionLayout layout, MansionLayout.Statue statue,
                                            BlockPos pos) {
        if (pos.getY() < statue.layerY() - 2 || pos.getY() > statue.layerY() + MansionLayout.STATUE_HEIGHT) {
            return false;
        }
        BlockPos shellMin = statue.chamberShellMin();
        BlockPos shellMax = statue.chamberShellMax();
        if (pos.getX() >= shellMin.getX() - 1 && pos.getX() <= shellMax.getX() + 1
                && pos.getZ() >= shellMin.getZ() - 1 && pos.getZ() <= shellMax.getZ() + 1) {
            return true;
        }
        return pos.getX() >= layout.blockMinX(statue.room()) - 1
                && pos.getX() <= layout.blockMaxX(statue.room()) + 1
                && pos.getZ() >= layout.blockMinZ(statue.room()) - 1
                && pos.getZ() <= layout.blockMaxZ(statue.room()) + 1;
    }

    // ---------------------------------------------------------------- arrow trap

    /**
     * Sinks a dispenser into the floor of every cell of {@code door}'s opening and lays a wooden
     * pressure plate on top of each.
     *
     * <p>The plate powers the block directly beneath it, which is the dispenser, and a dispenser
     * fires on the rising edge — so one step across the doorway is one volley straight up into the
     * walker's legs. Filling every slot keeps it working over several passes without ever being
     * more than a single arrow per trigger.
     */
    private static boolean placeArrowTrap(ServerLevel level, RaidLayoutBuilder.Door door,
                                          RaidGeometry geometry) {
        List<BlockPos> opening = new ArrayList<>();
        for (int x = door.minX(); x <= door.maxX(); x++) {
            for (int z = door.minZ(); z <= door.maxZ(); z++) {
                BlockPos pos = new BlockPos(x, door.layer(), z);
                if (!isFree(level, pos) || isFree(level, pos.below()) || !isTrapAllowed(pos, geometry)) {
                    return false;
                }
                opening.add(pos);
            }
        }

        for (BlockPos pos : opening) {
            BlockPos floor = pos.below();
            level.setBlock(floor, Blocks.DISPENSER.defaultBlockState()
                    .setValue(DispenserBlock.FACING, Direction.UP), Block.UPDATE_ALL);
            if (level.getBlockEntity(floor) instanceof DispenserBlockEntity dispenser) {
                for (int slot = 0; slot < ARROW_SLOTS; slot++) {
                    dispenser.setItem(slot, new ItemStack(Items.ARROW, ARROWS_PER_SLOT));
                }
            }
            level.setBlock(pos, Blocks.OAK_PRESSURE_PLATE.defaultBlockState(), Block.UPDATE_ALL);
        }
        return true;
    }

    // ---------------------------------------------------------------- alarm

    /**
     * Strings a tripwire across a doorway and turns the two wall blocks it hangs from into lamps.
     *
     * <p>A hook is placed in the first and last cell of the opening, each facing the other, so the
     * support behind it is the doorway's own jamb — and a hook powers the block it hangs from, which
     * is the lamp. Hooks and wire have no collision, so the doorway stays walkable.
     *
     * <p>Narrow maze doorways cannot host a hook at each end, so they are left to the arrow trap.
     */
    private static boolean placeAlarm(ServerLevel level, RaidLayoutBuilder.Door door,
                                      RaidGeometry geometry) {
        boolean alongZ = door.minX() == door.maxX();
        int from = alongZ ? door.minZ() : door.minX();
        int to = alongZ ? door.maxZ() : door.maxX();
        if (to - from + 1 < MIN_ALARM_WIDTH) {
            return false;
        }

        Direction step = alongZ ? Direction.SOUTH : Direction.EAST;
        List<BlockPos> opening = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            BlockPos pos = alongZ
                    ? new BlockPos(door.minX(), door.layer(), i)
                    : new BlockPos(i, door.layer(), door.minZ());
            if (!isFree(level, pos) || !isTrapAllowed(pos, geometry)) {
                return false;
            }
            opening.add(pos);
        }

        BlockPos first = opening.get(0);
        BlockPos last = opening.get(opening.size() - 1);
        BlockPos firstLamp = first.relative(step.getOpposite());
        BlockPos lastLamp = last.relative(step);
        if (isFree(level, firstLamp) || isFree(level, lastLamp)
                || !isTrapAllowed(firstLamp, geometry) || !isTrapAllowed(lastLamp, geometry)) {
            return false;
        }

        level.setBlock(firstLamp, Blocks.REDSTONE_LAMP.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(lastLamp, Blocks.REDSTONE_LAMP.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(first, Blocks.TRIPWIRE_HOOK.defaultBlockState()
                .setValue(TripWireHookBlock.FACING, step), Block.UPDATE_ALL);
        level.setBlock(last, Blocks.TRIPWIRE_HOOK.defaultBlockState()
                .setValue(TripWireHookBlock.FACING, step.getOpposite()), Block.UPDATE_ALL);
        for (int i = 1; i < opening.size() - 1; i++) {
            level.setBlock(opening.get(i), Blocks.TRIPWIRE.defaultBlockState(), Block.UPDATE_ALL);
        }
        return true;
    }

    // ---------------------------------------------------------------- rockfall

    /** Drops up to {@value #ROCKFALLS_PER_REGION} rockfalls into the rooms ringing the vault. */
    private static int placeRockfalls(ServerLevel level, RaidLayoutBuilder.RaidLayout layout,
                                      BlockPos vault, RaidGeometry geometry, RandomSource random) {
        List<RaidRoom> rooms = vaultRing(layout, vault);
        if (rooms.isEmpty()) {
            return 0;
        }

        int placed = 0;
        for (int n = 0; n < ROCKFALLS_PER_REGION; n++) {
            RaidRoom room = rooms.get(random.nextInt(rooms.size()));
            if (placeRockfall(level, layout, room, geometry, random)) {
                placed++;
            }
        }
        return placed;
    }

    /**
     * Builds one rockfall: plate, dust, piston, a stone carrying a two-block sand column, and the
     * cell the stone is shoved into.
     *
     * <p>The signal has to be released for the sand to come down: while the plate is held the
     * piston head sits where the carrier was and props the column up. The order below is what keeps
     * the column from dropping while it is being built — the carrier goes down first, then the sand
     * on top of it.
     */
    private static boolean placeRockfall(ServerLevel level, RaidLayoutBuilder.RaidLayout layout,
                                         RaidRoom room, RaidGeometry geometry, RandomSource random) {
        int layer = room.layer();
        int loX = room.minX() + 1;
        int hiX = room.maxX() - 1 - (ROCKFALL_LENGTH - 1);
        int loZ = room.minZ() + 1;
        int hiZ = room.maxZ() - 1;
        if (loX > hiX || loZ > hiZ) {
            return false;
        }

        for (int attempt = 0; attempt < ROCKFALL_ATTEMPTS; attempt++) {
            int x = loX + random.nextInt(hiX - loX + 1);
            int z = loZ + random.nextInt(hiZ - loZ + 1);
            if (!rockfallFits(level, layout, room, x, z, geometry)) {
                continue;
            }

            BlockPos carrier = new BlockPos(x + 3, layer, z);
            level.setBlock(carrier, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            Block drop = random.nextBoolean() ? Blocks.SAND : Blocks.GRAVEL;
            for (int i = 1; i <= ROCKFALL_SAND; i++) {
                level.setBlock(carrier.above(i), drop.defaultBlockState(), Block.UPDATE_ALL);
            }
            level.setBlock(new BlockPos(x + 2, layer, z), Blocks.PISTON.defaultBlockState()
                    .setValue(DirectionalBlock.FACING, Direction.EAST), Block.UPDATE_ALL);
            level.setBlock(new BlockPos(x + 1, layer, z),
                    Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(new BlockPos(x, layer, z),
                    Blocks.OAK_PRESSURE_PLATE.defaultBlockState(), Block.UPDATE_ALL);
            return true;
        }
        return false;
    }

    /** Whether the five cells of a rockfall, plus its sand column, are all free and legal. */
    private static boolean rockfallFits(ServerLevel level, RaidLayoutBuilder.RaidLayout layout,
                                        RaidRoom room, int x, int z, RaidGeometry geometry) {
        int layer = room.layer();
        for (int i = 0; i < ROCKFALL_LENGTH; i++) {
            if (!room.containsInterior(x + i, z) || nearDoorway(layout, x + i, z)) {
                return false;
            }
            BlockPos pos = new BlockPos(x + i, layer, z);
            if (!isFree(level, pos) || isFree(level, pos.below()) || !isTrapAllowed(pos, geometry)) {
                return false;
            }
        }

        BlockPos carrier = new BlockPos(x + 3, layer, z);
        for (int i = 1; i <= ROCKFALL_SAND; i++) {
            if (!isFree(level, carrier.above(i))) {
                return false;
            }
        }
        return true;
    }

    /** Rooms of {@code layout} that share a wall with the vault's room, the vault's own excluded. */
    private static List<RaidRoom> vaultRing(RaidLayoutBuilder.RaidLayout layout, BlockPos vault) {
        RaidRoom vaultRoom = null;
        for (RaidRoom room : layout.rooms()) {
            if (room.contains(vault.getX(), vault.getZ())) {
                vaultRoom = room;
                break;
            }
        }

        List<RaidRoom> ring = new ArrayList<>();
        for (RaidRoom room : layout.rooms()) {
            if (room != vaultRoom && vaultRoom != null && RaidSpawner.sharesWall(room, vaultRoom)) {
                ring.add(room);
            }
        }
        return ring;
    }

    // ---------------------------------------------------------------- rules

    /** Whether {@code (x, z)} is within {@value #DOOR_CLEARANCE} of any doorway of {@code layout}. */
    private static boolean nearDoorway(RaidLayoutBuilder.RaidLayout layout, int x, int z) {
        for (RaidLayoutBuilder.Door door : layout.doors()) {
            if (door.distance(x, z) <= DOOR_CLEARANCE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a trap may stand at {@code pos}: never in the extraction room, never inside a vault,
     * never within the vault reserve the containers respect, and never in a vault's passage.
     */
    private static boolean isTrapAllowed(BlockPos pos, RaidGeometry geometry) {
        if (RaidArena.isInSafeRoom(pos) || geometry.isInSafeZone(pos)) {
            return false;
        }
        if (pos.getY() < RaidArena.MAIN_LAYER_Y) {
            return true;
        }
        for (int region = 0; region < geometry.regionCount(); region++) {
            BlockPos vault = geometry.vaultCenter(region);
            if (Math.max(Math.abs(pos.getX() - vault.getX()),
                    Math.abs(pos.getZ() - vault.getZ())) <= VAULT_RESERVE_RADIUS) {
                return false;
            }
            if (Math.abs(pos.getZ() - vault.getZ()) <= 1
                    && pos.getX() >= vault.getX() - RaidArena.VAULT_HALF - VAULT_APPROACH_LENGTH
                    && pos.getX() <= vault.getX() - RaidArena.VAULT_HALF - 1) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFree(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir();
    }
}
