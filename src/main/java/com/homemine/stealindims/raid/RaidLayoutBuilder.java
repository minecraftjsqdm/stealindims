package com.homemine.stealindims.raid;

import com.homemine.stealindims.StealInDimsMod;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.util.RandomSource;

/**
 * Subdivides one region of a raid map into rooms with a BSP tree, then derives the doorways between
 * them (design §3, task 3.2).
 *
 * <p>The split runs directly on {@link RaidArena#regionBounds}, so the outermost rooms' walls *are*
 * the region's outer wall — no second wall ring is built, and no double wall ever shows up. Rooms
 * are cut until the region holds {@link #TARGET_ROOMS} of them, always splitting the largest leaf
 * along its longer edge, which keeps rooms as even as the constraints allow.
 *
 * <p>Room edges are bounded by {@link #MIN_ROOM} and {@link #MAX_ROOM}. Splitting a span {@code L}
 * into two rooms that share their wall column means {@code w1 + w2 == L + 1}, so both halves can fit
 * in {@code [MIN, MAX]} only while {@code 2 * MIN <= L + 1 <= 2 * MAX}. The shipped region spans are
 * 31 / 37 / 45, and 7 rooms per region is only reachable with {@code MAX_ROOM} at 23 for the larger
 * two; a granularity of "7 to 15" is geometrically impossible there.
 *
 * <p>Doorways are not chosen while splitting but derived afterwards from which rooms ended up
 * adjacent, at the middle of the overlap of their two edge spans. Connectivity then comes for free:
 * at the root split, every row along the shared wall is covered by one room of each side, and two
 * rooms of width at least {@code MIN_ROOM} sharing a row always have interior rows in common, so a
 * door exists across the split. {@link #checkConnectivity} still verifies it and logs any surprise.
 */
public final class RaidLayoutBuilder {

    /** Smallest room edge, walls included. */
    public static final int MIN_ROOM = 7;
    /** Largest room edge, walls included. See the class comment for why it is not 15. */
    public static final int MAX_ROOM = 23;
    /** Rooms per region and layer. */
    public static final int TARGET_ROOMS = 7;

    /** Doorway width of the maze-flavoured layouts, which read as narrow passages. */
    private static final int DOOR_NARROW = 1;
    /** Doorway width of everything else, wide enough to walk through without aiming. */
    private static final int DOOR_WIDE = 3;

    private RaidLayoutBuilder() {
    }

    /** One region and layer fully laid out: its rooms and the doorways punched between them. */
    public record RaidLayout(List<RaidRoom> rooms, List<Door> doors) {

        /** The same subdivision moved to another layer, so both layers of a region share a layout. */
        public RaidLayout withLayer(int layer) {
            List<RaidRoom> movedRooms = new ArrayList<>(this.rooms.size());
            for (RaidRoom room : this.rooms) {
                movedRooms.add(room.withLayer(layer));
            }
            List<Door> movedDoors = new ArrayList<>(this.doors.size());
            for (Door door : this.doors) {
                movedDoors.add(door.withLayer(layer));
            }
            return new RaidLayout(List.copyOf(movedRooms), List.copyOf(movedDoors));
        }

        /** Biggest room of the subdivision, where the stairwell between the two layers goes. */
        public RaidRoom largestRoom() {
            RaidRoom best = this.rooms.get(0);
            for (RaidRoom room : this.rooms) {
                if (room.area() > best.area()) {
                    best = room;
                }
            }
            return best;
        }
    }

    /**
     * A doorway between two rooms of one layer.
     *
     * <p>{@code minX..maxX × minZ..maxZ} is the opening itself: the shared wall column has to be dug
     * out there, walls included, to make a hole two blocks tall.
     */
    public record Door(int roomA, int roomB, int layer, int minX, int maxX, int minZ, int maxZ) {

        public boolean covers(int x, int z) {
            return x >= this.minX && x <= this.maxX && z >= this.minZ && z <= this.maxZ;
        }

        /** Chebyshev distance from {@code (x, z)} to the opening, used to keep loot out of doors. */
        public int distance(int x, int z) {
            int dx = Math.max(this.minX - x, x - this.maxX);
            int dz = Math.max(this.minZ - z, z - this.maxZ);
            return Math.max(dx, dz);
        }

        public Door withLayer(int newLayer) {
            return new Door(this.roomA, this.roomB, newLayer, this.minX, this.maxX, this.minZ, this.maxZ);
        }
    }

    /**
     * Splits {@code bounds} into {@link #TARGET_ROOMS} rooms on {@link RaidArena#MAIN_LAYER_Y}.
     *
     * @param layout the map's {@code layout} id, which picks the subdivision flavour
     * @param seed   the raid's seed; the same seed always yields the same layout
     */
    public static RaidLayout build(RaidArena.Bounds bounds, String layout, long seed) {
        Style style = styleFor(layout);
        int target = style == Style.HALL ? TARGET_ROOMS + 1 : TARGET_ROOMS;
        RandomSource random = RandomSource.create(seed);

        List<RaidRoom> rooms = new ArrayList<>();
        rooms.add(new RaidRoom(bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ(),
                RaidArena.MAIN_LAYER_Y));

        RaidRoom lastParent = null;
        RaidRoom[] lastHalves = null;
        while (rooms.size() < target) {
            int index = pickSplitCandidate(rooms);
            if (index < 0) {
                StealInDimsMod.LOGGER.warn("Raid layout '{}' stopped at {} rooms for a {}x{} region",
                        layout, rooms.size(), bounds.maxX() - bounds.minX() + 1,
                        bounds.maxZ() - bounds.minZ() + 1);
                break;
            }
            RaidRoom room = rooms.get(index);
            RaidRoom[] halves = split(room, style, random);
            rooms.set(index, halves[0]);
            rooms.add(halves[1]);
            lastParent = room;
            lastHalves = halves;
        }

        if (style == Style.HALL && lastParent != null && lastHalves != null
                && rooms.size() == target) {
            // Undo the last split: the biggest leaf grows back into a hall, which is what makes the
            // ring layouts read as one open room surrounded by small ones.
            rooms.remove(lastHalves[0]);
            rooms.remove(lastHalves[1]);
            rooms.add(lastParent);
        }

        List<Door> doors = deriveDoors(rooms, style);
        checkConnectivity(rooms, doors, layout);
        return new RaidLayout(List.copyOf(rooms), List.copyOf(doors));
    }

    // ---------------------------------------------------------------- subdivision

    private enum Style {
        /** Narrow doors, uneven rooms: reads as a mansion or a hedge maze. */
        MAZE,
        /** Wide doors, even grid: reads as a cavern or a machine hall. */
        GRID,
        /** Like {@link #GRID}, but one room is merged back into a big hall. */
        HALL
    }

    private static Style styleFor(@Nullable String layout) {
        if (layout == null) {
            return Style.GRID;
        }
        return switch (layout) {
            case "mansion_maze", "forest_labyrinth" -> Style.MAZE;
            case "lava_ring", "void_spiral", "sky_islands" -> Style.HALL;
            default -> Style.GRID;
        };
    }

    /** Index of the largest leaf that can still be split, or {@code -1} if none can. */
    private static int pickSplitCandidate(List<RaidRoom> rooms) {
        int best = -1;
        int bestArea = -1;
        for (int i = 0; i < rooms.size(); i++) {
            RaidRoom room = rooms.get(i);
            if (room.area() <= bestArea || !canSplit(room)) {
                continue;
            }
            best = i;
            bestArea = room.area();
        }
        return best;
    }

    private static boolean canSplit(RaidRoom room) {
        return Math.max(room.width(), room.depth()) >= 2 * MIN_ROOM - 1;
    }

    /**
     * Cuts {@code room} in two along its longer edge.
     *
     * <p>The two halves share the column (or row) the cut lands on, so their edges add up to
     * {@code span + 1} and must each land in {@code [MIN_ROOM, MAX_ROOM]}; the cut is only taken from
     * that window. Grid layouts take the middle of the window, maze layouts a random point in it,
     * which is what gives them their uneven rooms.
     */
    private static RaidRoom[] split(RaidRoom room, Style style, RandomSource random) {
        boolean alongX = room.width() >= room.depth();
        int span = alongX ? room.width() : room.depth();
        int origin = alongX ? room.minX() : room.minZ();

        int lo = Math.max(origin + MIN_ROOM - 1, origin + span - MAX_ROOM);
        int hi = Math.min(origin + MAX_ROOM - 1, origin + span - MIN_ROOM);
        int cut;
        if (style == Style.MAZE) {
            cut = lo + random.nextInt(hi - lo + 1);
        } else {
            cut = Math.max(lo, Math.min(hi, origin + (span - 1) / 2));
        }

        RaidRoom low = alongX
                ? new RaidRoom(room.minX(), room.minZ(), cut, room.maxZ(), room.layer())
                : new RaidRoom(room.minX(), room.minZ(), room.maxX(), cut, room.layer());
        RaidRoom high = alongX
                ? new RaidRoom(cut, room.minZ(), room.maxX(), room.maxZ(), room.layer())
                : new RaidRoom(room.minX(), cut, room.maxX(), room.maxZ(), room.layer());
        return new RaidRoom[]{low, high};
    }

    // ---------------------------------------------------------------- doorways

    private static List<Door> deriveDoors(List<RaidRoom> rooms, Style style) {
        int width = style == Style.MAZE ? DOOR_NARROW : DOOR_WIDE;
        List<Door> doors = new ArrayList<>();
        for (int i = 0; i < rooms.size(); i++) {
            for (int j = i + 1; j < rooms.size(); j++) {
                Door door = doorBetween(i, j, rooms.get(i), rooms.get(j), width);
                if (door != null) {
                    doors.add(door);
                }
            }
        }
        return doors;
    }

    /**
     * The doorway between two rooms, or {@code null} when they share no wall.
     *
     * <p>The opening is centred on the overlap of the two edge spans, inset by one on each end: a
     * row that sits on a room's own wall ring cannot be walked through, because both neighbours of
     * the opening on that side are wall too.
     */
    @Nullable
    private static Door doorBetween(int a, int b, RaidRoom first, RaidRoom second, int width) {
        if (first.maxX() == second.minX()) {
            return doorAlongZ(a, b, first, second, second.minX(), width);
        }
        if (second.maxX() == first.minX()) {
            return doorAlongZ(a, b, second, first, first.minX(), width);
        }
        if (first.maxZ() == second.minZ()) {
            return doorAlongX(a, b, first, second, second.minZ(), width);
        }
        if (second.maxZ() == first.minZ()) {
            return doorAlongX(a, b, second, first, first.minZ(), width);
        }
        return null;
    }

    /** Doorway in the wall column {@code wallX}, between a west room and an east room. */
    @Nullable
    private static Door doorAlongZ(int a, int b, RaidRoom west, RaidRoom east, int wallX, int width) {
        int lo = Math.max(west.minZ(), east.minZ()) + 1;
        int hi = Math.min(west.maxZ(), east.maxZ()) - 1;
        int[] span = opening(lo, hi, width);
        if (span == null) {
            return null;
        }
        return new Door(a, b, west.layer(), wallX, wallX, span[0], span[1]);
    }

    /** Doorway in the wall row {@code wallZ}, between a north room and a south room. */
    @Nullable
    private static Door doorAlongX(int a, int b, RaidRoom north, RaidRoom south, int wallZ, int width) {
        int lo = Math.max(north.minX(), south.minX()) + 1;
        int hi = Math.min(north.maxX(), south.maxX()) - 1;
        int[] span = opening(lo, hi, width);
        if (span == null) {
            return null;
        }
        return new Door(a, b, north.layer(), span[0], span[1], wallZ, wallZ);
    }

    /** Centred opening of at most {@code width} blocks inside {@code [lo, hi]}, or {@code null}. */
    @Nullable
    private static int[] opening(int lo, int hi, int width) {
        if (lo > hi) {
            return null;
        }
        int size = Math.min(width, hi - lo + 1);
        int start = Math.max(lo, Math.min(hi - size + 1, (lo + hi - size + 1) / 2));
        return new int[]{start, start + size - 1};
    }

    // ---------------------------------------------------------------- self check

    private static void checkConnectivity(List<RaidRoom> rooms, List<Door> doors, String layout) {
        int[] parent = new int[rooms.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (Door door : doors) {
            union(parent, door.roomA(), door.roomB());
        }

        Set<Integer> roots = new HashSet<>();
        for (int i = 0; i < parent.length; i++) {
            roots.add(find(parent, i));
        }
        if (roots.size() > 1) {
            StealInDimsMod.LOGGER.warn("Raid layout '{}' left {} unconnected room group(s) over {} rooms",
                    layout, roots.size(), rooms.size());
        }
    }

    private static int find(int[] parent, int index) {
        int root = index;
        while (parent[root] != root) {
            root = parent[root];
        }
        while (parent[index] != root) {
            int next = parent[index];
            parent[index] = root;
            index = next;
        }
        return root;
    }

    private static void union(int[] parent, int a, int b) {
        int rootA = find(parent, a);
        int rootB = find(parent, b);
        if (rootA != rootB) {
            parent[rootB] = rootA;
        }
    }
}
