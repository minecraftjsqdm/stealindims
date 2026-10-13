package com.homemine.stealindims.raid;

import com.homemine.stealindims.StealInDimsMod;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Rotation;

/**
 * Turns the organic growth of {@link OrganicMansionPlan} into a {@link MansionLayout} (R25-E).
 *
 * <p>Until R25 this class ran vanilla's own generator and read the plan back out of vanilla's grids.
 * That is what made the map a compact manor, so the shape now comes from {@link OrganicMansionPlan} and
 * only the <em>placement</em> is still vanilla's: {@link VanillaMansionPieces#generateOrganic} drives the
 * same {@code MansionPiecePlacer} a real woodland mansion is raised from, so every room, corridor,
 * carpet, indoor wall, door, outer wall and roof section is still a genuine vanilla template.
 *
 * <p>Three things are computed here rather than taken from the generator:
 *
 * <ul>
 *     <li><b>storeys</b> — C 1 / B, A 2 / S 3 — read off the plan, so a shallow difficulty really is a
 *     single-storey building instead of a three-storey one with the upper floors switched off;</li>
 *     <li><b>doorways</b> — recovered from the {@code indoors_door_*} pieces the placer actually
 *     stamped, so the rectangle a walk-in test aims at is the hole in the wall and not a guess. The
 *     opening of that template is its local {@code z = 2..4} column, three blocks wide, which is what
 *     the rotation arithmetic below turns back into world coordinates;</li>
 *     <li><b>the map radius</b> — measured from the cells the growth really used, because the outline is
 *     no longer a rectangle (spec §5.1).</li>
 * </ul>
 *
 * <p>Storey spacing is vanilla's: layer 0 at {@code y = 64}, layer 1 at 72 and layer 2 at <strong>83</strong>
 * ({@code pos.above(8 * floor + (floor == 2 ? 3 : 0))}), with the templates raised from {@code y = 63}
 * because a template's own floor block sits one below its walking surface.
 */
public final class MansionLayoutBuilder {

    public static final int CELL = MansionLayout.CELL_SIZE;

    /** A doorway is two blocks tall, so a standing player fits. */
    public static final int DOOR_HEIGHT = 2;

    /** How wide a doorway's opening is in the vanilla door templates; the walk test uses it. */
    public static final int DOOR_SPAN = 3;

    /** Y a template is placed at, relative to {@link MansionLayout#MAIN_LAYER_Y}: one below the floor. */
    public static final int TEMPLATE_Y = MansionLayout.MAIN_LAYER_Y - 1;

    /** Vanilla's packed-plane masks, repeated here so the layout pass reads them by name. */
    private static final int ROOM_TYPE_MASK = 983040;
    private static final int ROOM_ID_MASK = 65535;
    private static final int ROOM_DOOR_FLAG = 2097152;

    /** The ordinary room archetypes a vanilla room template is dressed as, for loot tiers. */
    private static final MansionLayout.RoomType[] ORDINARY = {
            MansionLayout.RoomType.GUEST,
            MansionLayout.RoomType.LIBRARY,
            MansionLayout.RoomType.DINING,
            MansionLayout.RoomType.COUNCIL,
            MansionLayout.RoomType.CELL,
            MansionLayout.RoomType.STORAGE,
            MansionLayout.RoomType.HALL
    };

    private MansionLayoutBuilder() {
    }

    /**
     * The coarse grid the growth works on (R25-E). It is the same for every difficulty now: the map is
     * sized by how many rooms the growth has to place (C 30 / B 45 / A 60 / S 80), not by how big a
     * square the rooms are packed into.
     */
    public static int gridSizeFor(@SuppressWarnings("unused") RaidDifficulty difficulty) {
        return OrganicMansionPlan.GRID;
    }

    /** How many rooms the entrance storey carries (decision E1). */
    public static int roomTargetFor(RaidDifficulty difficulty) {
        return OrganicMansionPlan.roomTarget(difficulty);
    }

    /** Builds the plan of {@code difficulty} for {@code seed}, centred on {@code mapCenterX}. */
    public static MansionLayout build(RaidDifficulty difficulty, long seed, int mapCenterX) {
        OrganicMansionPlan.Plan grown = OrganicMansionPlan.grow(difficulty, seed);
        int storeys = grown.storeyCount;

        // 1) The cells the growth really used, so the map is as big as the building and no bigger.
        int minCX = Integer.MAX_VALUE;
        int maxCX = Integer.MIN_VALUE;
        int minCZ = Integer.MAX_VALUE;
        int maxCZ = Integer.MIN_VALUE;
        for (int storey = 0; storey < storeys; storey++) {
            for (int cx = 0; cx < OrganicMansionPlan.GRID; cx++) {
                for (int cz = 0; cz < OrganicMansionPlan.GRID; cz++) {
                    if (OrganicMansionPlan.walkable(grown.cells(storey), cx, cz)) {
                        minCX = Math.min(minCX, cx);
                        maxCX = Math.max(maxCX, cx);
                        minCZ = Math.min(minCZ, cz);
                        maxCZ = Math.max(maxCZ, cz);
                    }
                }
            }
        }
        if (minCX > maxCX || minCZ > maxCZ) {
            throw new IllegalStateException("the organic growth produced no walkable cells");
        }
        MansionLayout.Cells bounds = new MansionLayout.Cells(minCX, minCZ, maxCX, maxCZ);

        // 2) Vanilla's `pos` is the origin of cell (startX, startY) and every piece is addressed as
        //    `pos.relative(SOUTH, 8 + (cz - startY) * 8)`; X gets no such band, so `pos` sits one cell
        //    north of row startY. Miss that and the whole mansion lands eight blocks south of the
        //    rectangles the spawner and the walk test read.
        int originX = MansionLayout.originX(mapCenterX, bounds);
        int originZ = MansionLayout.originZ(bounds);
        int startX = grown.entranceX + 1;
        int startY = grown.entranceY + 1;
        BlockPos pos = new BlockPos(originX + startX * CELL, TEMPLATE_Y,
                originZ + startY * CELL - CELL);

        // 3) The templates. Same placer, same ids, same rotations as a vanilla mansion; only the grid
        //    underneath it was grown instead of packed.
        List<VanillaMansionPieces.Piece> pieces = new ArrayList<>(4096);
        VanillaMansionPieces.generateOrganic(pos, Rotation.NONE, pieces, RandomSource.create(seed), grown);

        // 4) The walkable plane of every storey.
        List<MansionLayout.Floor> floors = new ArrayList<>(storeys);
        for (int storey = 0; storey < storeys; storey++) {
            int width = bounds.width();
            int depth = bounds.depth();
            int[] cells = new int[width * depth];
            for (int cx = bounds.minCX(); cx <= bounds.maxCX(); cx++) {
                for (int cz = bounds.minCZ(); cz <= bounds.maxCZ(); cz++) {
                    cells[(cz - bounds.minCZ()) * width + (cx - bounds.minCX())] =
                            planeValue(grown.cells(storey)[cx][cz]);
                }
            }
            floors.add(new MansionLayout.Floor(MansionLayout.layerY(storey), bounds.minCX(),
                    bounds.minCZ(), width, depth, cells));
        }

        // 5) Rooms, plus the entrance hall the growth seeded and the statue room it hosts.
        List<MansionLayout.Room> rooms = new ArrayList<>(grown.roomCount() + 1);
        rooms.add(new MansionLayout.Room(
                new MansionLayout.Cells(grown.entranceX, grown.entranceY,
                        grown.entranceX + 1, grown.entranceY + 1),
                MansionLayout.layerY(0), MansionLayout.RoomType.ENTRANCE));
        for (int storey = 0; storey < storeys; storey++) {
            for (OrganicMansionPlan.RoomBox box : grown.rooms(storey)) {
                rooms.add(new MansionLayout.Room(cells(box), MansionLayout.layerY(storey),
                        ordinaryType(box.id, storey)));
            }
        }

        // 6) The illager statue: a room on the entrance storey whose north wall has nothing but the
        //    mansion's own shell behind it, so the vault really is hidden (R24).
        MansionLayout.Room statueRoom = null;
        MansionLayout.Statue statue = pickStatue(rooms, floors, pieces, originX, originZ);
        if (statue != null) {
            for (int index = 0; index < rooms.size(); index++) {
                MansionLayout.Room room = rooms.get(index);
                if (room.cells().equals(statue.room()) && room.layerY() == statue.layerY()) {
                    statueRoom = room.withType(MansionLayout.RoomType.STATUE);
                    rooms.set(index, statueRoom);
                    break;
                }
            }
        }

        // 7) Submission points: one per granted storey, two on C's single storey (decision R3).
        List<MansionLayout.Submission> submissions =
                pickSubmissions(rooms, grown, statueRoom, difficulty);

        // 8) Safe cells: the entrance hall plus every submission room.
        Set<Long> safeCells = new HashSet<>();
        for (int cx = grown.entranceX; cx <= grown.entranceX + 1; cx++) {
            for (int cz = grown.entranceY; cz <= grown.entranceY + 1; cz++) {
                safeCells.add(MansionLayout.cellKey(cx, cz));
            }
        }
        for (MansionLayout.Submission submission : submissions) {
            addCells(safeCells, submission.cells());
        }

        // 9) Connectivity of every storey, recomputed from the grown plane rather than trusted.
        Map<Integer, Boolean> connected = new HashMap<>();
        Map<Integer, Boolean> repaired = new HashMap<>();
        for (MansionLayout.Floor floor : floors) {
            boolean one = components(floor) == 1;
            connected.put(floor.layerY(), one);
            repaired.put(floor.layerY(), false);
            if (!one) {
                StealInDimsMod.LOGGER.warn("Mansion layer y={} is not one connected component", floor.layerY());
            }
        }

        List<Integer> layerYs = new ArrayList<>(storeys);
        for (int storey = 0; storey < storeys; storey++) {
            layerYs.add(MansionLayout.layerY(storey));
        }

        // 10) The landing spot: the middle of the entrance hall, which is where the entrance
        //     template's own carpet runner runs (its local x = 10, z = 7).
        BlockPos spawn = new BlockPos(pos.getX() + 1, MansionLayout.layerY(0), pos.getZ() + 7);
        BlockPos anchor = spawn.offset(-3, 0, -4);

        return new MansionLayout(mapCenterX, difficulty, seed, layerYs, floors, rooms,
                doorways(pieces), List.of(), submissions, bounds, connected, safeCells,
                statue, repaired, pieces, OrganicMansionPlan.GRID, grown.deadEnds,
                grown.lots.size(), spawn, anchor);
    }

    // ---------------------------------------------------------------- helpers

    private static MansionLayout.Cells cells(OrganicMansionPlan.RoomBox box) {
        // Vanilla's own "interior convention": a room's template is 8·cells−1 blocks wide and sits in
        // the low corner of its cell rectangle, so the wall lines are minCX−1 and maxCX.
        return new MansionLayout.Cells(box.minCX, box.minCZ, box.maxCX, box.maxCZ);
    }

    /** Vanilla's grid value mapped onto {@link MansionLayout}'s vocabulary. */
    private static int planeValue(int vanilla) {
        return switch (vanilla) {
            case OrganicMansionPlan.CORRIDOR -> MansionLayout.CORRIDOR;
            case OrganicMansionPlan.ROOM -> MansionLayout.ROOM;
            case OrganicMansionPlan.START -> MansionLayout.START;
            case OrganicMansionPlan.VOID -> MansionLayout.CLEAR;
            default -> MansionLayout.BLOCKED;
        };
    }

    private static MansionLayout.RoomType ordinaryType(int id, int layer) {
        return ORDINARY[Math.floorMod(id * 31 + layer * 7, ORDINARY.length)];
    }

    /**
     * Picks the room the statue backs onto and where in it the template lands (R24, spec §5.6.2).
     *
     * <p>The rule is <em>the statue stands against a real wall, and the vault is carved out of what is
     * behind it</em>. A room qualifies when the cell (or the two cells) its vault would reach on the
     * north side are not walkable at all — the mansion's own shell, never another room or a corridor.
     * The vault is one block narrower on each side than the statue, so it always fits inside the cell
     * columns the template straddles, and nothing the mod builds is visible from the room.
     *
     * <p>Because the R25 growth keeps a cell of air between any two rooms and the entrance hall sits at
     * the building's east edge, most rooms have such a wall, so the statue normally stays on the
     * entrance storey.
     *
     * <p>Candidates are ranked, in this order:
     *
     * <ol>
     *     <li>the entrance storey, so the boss portal stays on the floor a raider reaches first;</li>
     *     <li>a room that does not carry a staircase — emptying a stairwell's room would cut the storey
     *     above off;</li>
     *     <li>the biggest room (2×2 over 1×2/2×1 over 1×1);</li>
     *     <li>a centred face, i.e. both north cells solid rather than just the one column;</li>
     *     <li>the most enclosed wall, counted as how many of the eight neighbours of each vault cell
     *     are walkable — a wall tucked into a pocket of the building hides the vault best.</li>
     * </ol>
     *
     * @return the placement, or {@code null} when the plan has no room at all
     */
    @Nullable
    private static MansionLayout.Statue pickStatue(List<MansionLayout.Room> rooms,
                                                   List<MansionLayout.Floor> floors,
                                                   List<VanillaMansionPieces.Piece> pieces,
                                                   int originX, int originZ) {
        MansionLayout.Statue best = null;
        int bestScore = Integer.MIN_VALUE;
        for (MansionLayout.Room room : rooms) {
            if (room.type() == MansionLayout.RoomType.ENTRANCE) {
                continue;
            }
            MansionLayout.Floor floor = null;
            for (MansionLayout.Floor candidate : floors) {
                if (candidate.layerY() == room.layerY()) {
                    floor = candidate;
                    break;
                }
            }
            if (floor == null) {
                continue;
            }
            int roomMinX = originX + room.cells().minCX() * CELL;
            int roomMaxX = originX + room.cells().maxCX() * CELL + CELL - 1;
            int roomMinZ = originZ + room.cells().minCZ() * CELL;
            int roomMaxZ = originZ + room.cells().maxCZ() * CELL + CELL - 1;
            int interiorMaxX = roomMaxX - 1;
            boolean stairs = carriesStairs(pieces, room, roomMinX, roomMaxX, roomMinZ, roomMaxZ);
            for (int centreX : statueCentres(room, roomMinX)) {
                int templateMinX = centreX - MansionLayout.STATUE_TUNNEL_LOCAL_X;
                int templateMaxX = templateMinX + MansionLayout.STATUE_WIDTH - 1;
                if (templateMinX < roomMinX || templateMaxX > interiorMaxX) {
                    continue;
                }
                int vaultMinX = templateMinX;
                int vaultMaxX = centreX + MansionLayout.STATUE_TUNNEL_LOCAL_X;
                int northCZ = room.cells().minCZ() - 1;
                int cellWest = Math.floorDiv(vaultMinX - originX, CELL);
                int cellEast = Math.floorDiv(vaultMaxX - originX, CELL);
                int cells = 0;
                int enclosure = 0;
                boolean open = false;
                for (int cell = cellWest; cell <= cellEast; cell++) {
                    cells++;
                    if (floor.isWalkable(cell, northCZ)) {
                        open = true;
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if ((dx != 0 || dz != 0) && floor.isWalkable(cell + dx, northCZ + dz)) {
                                enclosure++;
                            }
                        }
                    }
                }
                if (open) {
                    continue;
                }
                int score = (room.layerY() == MansionLayout.layerY(0) ? 1_000_000 : 0)
                        + (stairs ? 0 : 200_000)
                        + Math.min(room.area(), 4) * 5_000
                        + (centreX == roomMinX + 7 ? 1_000 : 0)
                        + (enclosure * 10 / cells) * 5;
                if (score > bestScore) {
                    bestScore = score;
                    best = statueOf(room, originX, originZ, centreX);
                }
            }
        }
        return best;
    }

    /** Where the statue's tunnel column can sit across the room, best first. */
    private static int[] statueCentres(MansionLayout.Room room, int roomMinX) {
        if (room.width() >= 2) {
            // 16-block rectangle: the middle of the wall, then either half of it.
            return new int[]{roomMinX + 7, roomMinX + 3, roomMinX + 11};
        }
        // 8-block rectangle: the 6-wide template only fits just off either edge.
        return new int[]{roomMinX + 3, roomMinX + 2};
    }

    /**
     * Whether {@code room} holds one of vanilla's staircase templates, which the statue room's clearing
     * pass would tear out and leave the storey above unreachable. It is a preference, not a veto: a plan
     * whose only walled room also holds the stairs still gets its statue.
     */
    private static boolean carriesStairs(List<VanillaMansionPieces.Piece> pieces,
                                        MansionLayout.Room room, int roomMinX, int roomMaxX,
                                        int roomMinZ, int roomMaxZ) {
        for (VanillaMansionPieces.Piece piece : pieces) {
            if (!piece.name.endsWith("_stairs") || piece.pos.getY() != room.layerY() - 1) {
                continue;
            }
            if (piece.pos.getX() >= roomMinX - 2 && piece.pos.getX() <= roomMaxX + 2
                    && piece.pos.getZ() >= roomMinZ - 2 && piece.pos.getZ() <= roomMaxZ + 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where the user's {@code illager_statue.nbt} stands inside {@code room} (spec §5.4 R23, §5.6 R24).
     *
     * <p>The template is stamped verbatim, so the only freedom here is the placement, and it is spent
     * on putting the statue's back flat against the room's wall: the template's {@code z = 0} plane
     * lands on the room's first interior row, the wall is the block behind it, and the pixel face (the
     * template's {@code z = 2} plane) ends up on {@code roomMinZ + 2}. Behind the wall the vault takes
     * the wall row plus the three rows of cavity plus its shell, all of it in cells the plan says are
     * not walkable — which is what {@link #pickStatue} selected the room for.
     */
    private static MansionLayout.Statue statueOf(MansionLayout.Room room, int originX, int originZ,
                                                 int centreX) {
        int roomMinZ = originZ + room.cells().minCZ() * CELL;
        int faceZ = roomMinZ + MansionLayout.STATUE_FACE_LOCAL_Z;
        int eyeY = room.layerY() + MansionLayout.STATUE_EYE_RISE;
        return new MansionLayout.Statue(room.cells(), room.layerY(), centreX, eyeY, faceZ);
    }

    /**
     * Chooses the submission rooms. Only rooms that really carry a doorway are eligible, and the statue
     * room is never one of them, because its floor space belongs to the relief.
     */
    private static List<MansionLayout.Submission> pickSubmissions(List<MansionLayout.Room> rooms,
                                                                  OrganicMansionPlan.Plan grown,
                                                                  @Nullable MansionLayout.Room statueRoom,
                                                                  RaidDifficulty difficulty) {
        List<MansionLayout.Submission> submissions = new ArrayList<>();
        int perFloor = MansionLayout.submissionsPerFloor(difficulty);
        for (int storey = 0; storey < grown.storeyCount; storey++) {
            int layerY = MansionLayout.layerY(storey);
            List<MansionLayout.Room> candidates = new ArrayList<>();
            for (OrganicMansionPlan.RoomBox box : grown.rooms(storey)) {
                if (!hasDoorway(grown, storey, box)) {
                    continue;
                }
                MansionLayout.Room room = null;
                for (MansionLayout.Room candidate : rooms) {
                    if (candidate.cells().equals(cells(box)) && candidate.layerY() == layerY) {
                        room = candidate;
                        break;
                    }
                }
                if (room == null || room.type() == MansionLayout.RoomType.ENTRANCE) {
                    continue;
                }
                if (statueRoom != null && room.cells().equals(statueRoom.cells())) {
                    continue;
                }
                candidates.add(room);
            }
            if (candidates.isEmpty()) {
                StealInDimsMod.LOGGER.warn("Mansion layer y={} has no doored room for a submission point", layerY);
                continue;
            }
            for (int index = 0; index < perFloor; index++) {
                int pick = perFloor == 1 ? 0 : (index * candidates.size()) / perFloor;
                submissions.add(new MansionLayout.Submission(
                        candidates.get(Math.min(pick, candidates.size() - 1)).cells(), layerY));
            }
        }
        return submissions;
    }

    /** Whether the placer has a door to cut in {@code box}: its flagged cell has a corridor beside it. */
    private static boolean hasDoorway(OrganicMansionPlan.Plan grown, int storey,
                                      OrganicMansionPlan.RoomBox box) {
        int[][] plane = grown.roomPlane(storey);
        int[][] cells = grown.cells(storey);
        for (int cx = box.minCX; cx <= box.maxCX; cx++) {
            for (int cz = box.minCZ; cz <= box.maxCZ; cz++) {
                if ((plane[cx][cz] & ROOM_DOOR_FLAG) == 0) {
                    continue;
                }
                int[] dx = {1, 0, -1, 0};
                int[] dz = {0, 1, 0, -1};
                for (int dir = 0; dir < 4; dir++) {
                    if (cells[cx + dx[dir]][cz + dz[dir]] == OrganicMansionPlan.CORRIDOR) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Every doorway the placer actually cut, as the three-wide opening in the wall it stands in.
     *
     * <p>The opening of {@code indoors_door_1/2} is its local {@code z = 2..4} column and it is two
     * blocks tall above the piece's own floor row, so the world span falls straight out of the piece's
     * position and rotation. Reading them back out of the piece list is what makes the walk-in test
     * aim at a hole instead of at a wall: the placer picks the doorway direction at random among a
     * cell's corridor neighbours, so nothing else can predict where it went.
     */
    private static List<MansionLayout.Door> doorways(List<VanillaMansionPieces.Piece> pieces) {
        List<MansionLayout.Door> doors = new ArrayList<>();
        for (VanillaMansionPieces.Piece piece : pieces) {
            if (!piece.name.startsWith("indoors_door")) {
                continue;
            }
            int x = piece.pos.getX();
            int z = piece.pos.getZ();
            int layerY = piece.pos.getY() + 1;
            switch (piece.rotation) {
                case NONE -> doors.add(new MansionLayout.Door(layerY, x, x, z + 2, z + 4));
                case CLOCKWISE_90 -> doors.add(new MansionLayout.Door(layerY, x - 4, x - 2, z, z));
                case CLOCKWISE_180 -> doors.add(new MansionLayout.Door(layerY, x, x, z - 4, z - 2));
                case COUNTERCLOCKWISE_90 -> doors.add(new MansionLayout.Door(layerY, x + 2, x + 4, z, z));
                default -> {
                }
            }
        }
        return doors;
    }

    private static void addCells(Set<Long> safeCells, MansionLayout.Cells cells) {
        for (int cx = cells.minCX(); cx <= cells.maxCX(); cx++) {
            for (int cz = cells.minCZ(); cz <= cells.maxCZ(); cz++) {
                safeCells.add(MansionLayout.cellKey(cx, cz));
            }
        }
    }

    /** How many connected components a storey's walkable cells form. */
    private static int components(MansionLayout.Floor floor) {
        Set<Long> seen = new HashSet<>();
        int count = 0;
        Deque<int[]> queue = new ArrayDeque<>();
        for (int cx = floor.minCX(); cx <= floor.maxCX(); cx++) {
            for (int cz = floor.minCZ(); cz <= floor.maxCZ(); cz++) {
                if (!floor.isWalkable(cx, cz) || !seen.add(MansionLayout.cellKey(cx, cz))) {
                    continue;
                }
                count++;
                queue.add(new int[]{cx, cz});
                while (!queue.isEmpty()) {
                    int[] at = queue.poll();
                    int[][] neighbours = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int[] step : neighbours) {
                        int nx = at[0] + step[0];
                        int nz = at[1] + step[1];
                        if (floor.isWalkable(nx, nz) && seen.add(MansionLayout.cellKey(nx, nz))) {
                            queue.add(new int[]{nx, nz});
                        }
                    }
                }
            }
        }
        return count;
    }
}
