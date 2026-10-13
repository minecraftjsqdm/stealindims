package com.homemine.stealindims.raid;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.util.RandomSource;

/**
 * R25-E: the organic floor plan of a mansion raid map.
 *
 * <p>P5 built the map out of vanilla's own {@code MansionPiecePlacer}, and that generator packs its
 * rooms into a solid slab: {@code recursiveCorridor} paints a huge blob of room cells and
 * {@code identifyRooms} then tiles it with 1×1 / 1×2 / 2×2 rooms that <em>share their wall lines</em>.
 * The user's verdict was that the map still reads as one solid manor rather than as the rambling,
 * one-room-at-a-time house the design asks for.
 *
 * <p>This class replaces the shape only (spec §5.1):
 *
 * <ol>
 *     <li><b>accretion</b>: from the entrance hall, repeatedly pick a face of an existing room, leave a
 *     gap of <b>1–2 cells</b> of nothing behind it, and drop the next room outside that gap;</li>
 *     <li><b>a main chain and branch clusters</b> (decision E2): {@value #CHAIN_SHARE} of the growth
 *     steps extend the room the last step built, so one arm keeps running outward, and the rest fork
 *     off rooms that already stand;</li>
 *     <li><b>short, offset, bent corridors</b> (decision E3): 1–{@value #MAX_CORRIDOR_CELLS} cells that
 *     may turn once, so a room rarely lines up with the one it hangs off;</li>
 *     <li><b>dead ends</b>: about {@value #DEAD_END_SHARE} of the corridors get a stub that leads
 *     nowhere;</li>
 *     <li><b>local upper storeys</b> (decision E4): each higher layer is a connected annex stacked on
 *     cells the layer below already owns, sized at {@value #STACK_RATIO} of the layer below;</li>
 *     <li><b>empty lots</b>: about {@value #LOT_SHARE} of the building's edge is left as rubble and low
 *     wall stumps instead of floor.</li>
 * </ol>
 *
 * <p>Rooms are still vanilla's three sizes — 1×1 / 1×2 / 2×2 cells — because that is what the vanilla
 * room templates fit, and the outline is whatever the growth produced rather than a rectangle, which is
 * what {@link MansionLayout#mapRadius()} then measures.
 *
 * <p>Two invariants are enforced rather than hoped for: no two room rectangles ever come within one
 * cell of each other (so rooms never share a wall, which is the whole point), and every layer's
 * walkable cells form exactly one component. Both are asserted again in
 * {@code MansionLayoutBuilderTest}.
 */
final class OrganicMansionPlan {

    /** Cells per side. It is a ceiling, not a target: the grown mansion is usually smaller. */
    static final int GRID = 26;

    /** Cells the growth may not touch, so the outer walls and roofs have somewhere to stand. */
    static final int BORDER = 1;

    /**
     * The entrance hall's low-x cell. It sits at the east edge of the grid with vanilla's own blocked
     * pocket behind it, because {@code MansionPiecePlacer#entrance} hangs its 21×19×16 template nine
     * blocks west of the hall and four blocks past its east wall.
     */
    static final int ENTRANCE_X = GRID - 5;
    static final int ENTRANCE_Y = GRID / 2;

    /** Where each storey's templates start, in blocks above {@code MansionLayout.MAIN_LAYER_Y - 1}. */
    static final int[] STOREY_BASE = {0, 8, 19};
    static final int MAX_STOREYS = STOREY_BASE.length;

    static final int VOID = 0;
    static final int CORRIDOR = 1;
    static final int ROOM = 2;
    static final int START = 3;

    /** Vanilla's packed room-plane bits, repeated so the plan can write them by name. */
    static final int TYPE_1X1 = 65536;
    static final int TYPE_1X2 = 131072;
    static final int TYPE_2X2 = 262144;
    static final int FLAG_ORIGIN = 1048576;
    static final int FLAG_DOOR = 2097152;
    static final int FLAG_STAIRS = 4194304;
    static final int FLAG_START = 8388608;

    /** 0 = east, 1 = south, 2 = west, 3 = north. */
    private static final int[] DX = {1, 0, -1, 0};
    private static final int[] DZ = {0, 1, 0, -1};

    /** How much of a storey is repeated one layer up: the local two-storey annexes (E4). */
    private static final float STACK_RATIO = 0.15F;
    /** Share of growth steps that extend the current arm instead of forking off an old room (E2). */
    private static final float CHAIN_SHARE = 0.6F;
    /** Share of corridors that get a stub leading nowhere (E3). */
    private static final float DEAD_END_SHARE = 0.12F;
    /** Share of the building's edge left as rubble and low wall stumps. */
    private static final float LOT_SHARE = 0.07F;
    /** Longest corridor, in cells, including the bend (E3). */
    private static final int MAX_CORRIDOR_CELLS = 4;
    /** How many sub-seeds a difficulty may try before it settles for a smaller mansion. */
    private static final int MAX_ATTEMPTS = 12;

    private OrganicMansionPlan() {
    }

    // ---------------------------------------------------------------- difficulty recipe

    /** Rooms on the entrance storey: C 30 / B 45 / A 60 / S 80 (decision E1). */
    static int roomTarget(RaidDifficulty difficulty) {
        return switch (difficulty) {
            case C -> 30;
            case B -> 45;
            case A -> 60;
            case S -> 80;
        };
    }

    /** Storeys the building really has: C 1 / B, A 2 / S 3. Same ladder as the playable layers. */
    static int storeysFor(RaidDifficulty difficulty) {
        return switch (difficulty) {
            case C -> 1;
            case B, A -> 2;
            case S -> 3;
        };
    }

    // ---------------------------------------------------------------- the plan

    /** One room rectangle in cells, with the doorway the placer has to know about. */
    static final class RoomBox {

        final int minCX;
        final int minCZ;
        final int maxCX;
        final int maxCZ;
        int id;
        int doorCX = -1;
        int doorCZ = -1;
        boolean stairs;

        RoomBox(int minCX, int minCZ, int maxCX, int maxCZ) {
            this.minCX = minCX;
            this.minCZ = minCZ;
            this.maxCX = maxCX;
            this.maxCZ = maxCZ;
        }

        int width() {
            return this.maxCX - this.minCX + 1;
        }

        int depth() {
            return this.maxCZ - this.minCZ + 1;
        }

        int area() {
            return width() * depth();
        }

        boolean contains(int cx, int cz) {
            return cx >= this.minCX && cx <= this.maxCX && cz >= this.minCZ && cz <= this.maxCZ;
        }

        int centerCX() {
            return (this.minCX + this.maxCX) / 2;
        }

        int centerCZ() {
            return (this.minCZ + this.maxCZ) / 2;
        }
    }

    /** One empty lot: a cell of the building's edge that stays rubble instead of floor. */
    record Lot(int cx, int cz, boolean corner) {
    }

    /** The grown plan: per layer, the walkable cells, the packed room plane and the room rectangles. */
    static final class Plan {

        final int[][][] cells = new int[MAX_STOREYS][][];
        final int[][][] roomPlane = new int[MAX_STOREYS][][];
        final List<List<RoomBox>> rooms = new ArrayList<>(MAX_STOREYS);
        final List<Lot> lots = new ArrayList<>();
        int storeyCount;
        int entranceX;
        int entranceY;
        int deadEnds;

        int[][] cells(int storey) {
            return this.cells[storey];
        }

        int[][] roomPlane(int storey) {
            return this.roomPlane[storey];
        }

        List<RoomBox> rooms(int storey) {
            return this.rooms.get(storey);
        }

        /** Every room of every built storey; the entrance hall is not one of them. */
        int roomCount() {
            int total = 0;
            for (int storey = 0; storey < this.storeyCount; storey++) {
                total += this.rooms.get(storey).size();
            }
            return total;
        }

        int walkableCells() {
            int total = 0;
            for (int storey = 0; storey < this.storeyCount; storey++) {
                for (int cx = 0; cx < GRID; cx++) {
                    for (int cz = 0; cz < GRID; cz++) {
                        if (walkable(this.cells[storey], cx, cz)) {
                            total++;
                        }
                    }
                }
            }
            return total;
        }
    }

    // ---------------------------------------------------------------- growth

    /**
     * Grows the plan of {@code difficulty} from {@code seed}, deterministically.
     *
     * <p>A sub-seed is tried until the entrance storey really reaches its room target; the growth is
     * greedy, so a seed that paints itself into a corner is dropped rather than patched.
     */
    static Plan grow(RaidDifficulty difficulty, long seed) {
        int target = roomTarget(difficulty);
        int storeys = storeysFor(difficulty);
        int wanted = target - 1;
        Plan best = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            RandomSource random = RandomSource.create(seed + attempt * 0x9E3779B97F4A7C15L);
            Plan plan = newPlan(storeys);
            growGround(plan, random, wanted);
            if (best == null || plan.rooms(0).size() > best.rooms(0).size()) {
                best = plan;
            }
            if (plan.rooms(0).size() >= wanted) {
                best = plan;
                break;
            }
        }
        RandomSource random = RandomSource.create(seed ^ 0x5DEECE66DL);
        finishStorey(best, 0);
        for (int storey = 1; storey < storeys; storey++) {
            stackStorey(best, storey, random);
            finishStorey(best, storey);
        }
        scatterRuins(best);
        return best;
    }

    private static Plan newPlan(int storeys) {
        Plan plan = new Plan();
        plan.storeyCount = storeys;
        plan.entranceX = ENTRANCE_X;
        plan.entranceY = ENTRANCE_Y;
        for (int storey = 0; storey < MAX_STOREYS; storey++) {
            plan.cells[storey] = new int[GRID][GRID];
            plan.roomPlane[storey] = new int[GRID][GRID];
            plan.rooms.add(new ArrayList<>());
        }
        return plan;
    }

    /** Vanilla's entrance area, then the accretion loop on top of it. */
    private static void growGround(Plan plan, RandomSource random, int wanted) {
        int[][] cells = plan.cells(0);
        int ex = plan.entranceX;
        int ey = plan.entranceY;

        // The hall itself, flagged START exactly as vanilla flags its entrance room cells, so the piece
        // placer never walls it off and the entrance template's own staircase stays the way up.
        for (int x = ex; x <= ex + 1; x++) {
            for (int z = ey; z <= ey + 1; z++) {
                cells[x][z] = START;
            }
        }
        // Vanilla's vestibule: the two corridor stubs off the hall's east column and the run west.
        corridor(cells, ex + 1, ey - 2, ex + 1, ey - 1);
        corridor(cells, ex + 1, ey + 2, ex + 1, ey + 3);
        corridor(cells, ex, ey - 2, ex - 1, ey - 2);
        corridor(cells, ex, ey + 3, ex - 1, ey + 3);
        corridor(cells, ex - 1, ey - 1, ex - 1, ey - 1);
        corridor(cells, ex - 1, ey + 2, ex - 1, ey + 2);

        List<RoomBox> rooms = plan.rooms(0);
        List<int[]> roomCells = new ArrayList<>();
        for (int x = ex; x <= ex + 1; x++) {
            for (int z = ey; z <= ey + 1; z++) {
                roomCells.add(new int[]{x, z});
            }
        }
        RoomBox chain = null;
        int chainDir = -1;
        int guard = wanted * 30;
        int sinceProgress = 0;

        while (rooms.size() < wanted && guard-- > 0) {
            int px;
            int pz;
            int[] order;
            boolean chaining = chain != null && random.nextFloat() < CHAIN_SHARE;
            if (chaining) {
                px = chain.minCX + random.nextInt(chain.width());
                pz = chain.minCZ + random.nextInt(chain.depth());
                order = shuffledDirections(random, chainDir);
            } else {
                int[] cell = roomCells.get(random.nextInt(roomCells.size()));
                px = cell[0];
                pz = cell[1];
                order = shuffledDirections(random, -1);
            }

            Candidate placed = null;
            int placedDir = -1;
            // A cell of air on every side of a room is what makes the block read as separate buildings
            // rather than as a slab, but a 80-room S map runs out of room for it. Once the strict rule
            // stops finding anywhere to build, the growth falls back to keeping a gap only on the four
            // faces, which lets rooms touch at a corner: still no shared wall, just a tighter weave.
            boolean relax = sinceProgress > Math.max(8, wanted / 2);
            for (int dir : order) {
                if (dir < 0 || cells[px + DX[dir]][pz + DZ[dir]] != VOID) {
                    continue;
                }
                int firstGap = random.nextBoolean() ? 1 : 2;
                for (int gap : new int[]{firstGap, 3 - firstGap}) {
                    Candidate candidate = attempt(cells, random, px, pz, dir, gap, false);
                    if (candidate == null && relax) {
                        candidate = attempt(cells, random, px, pz, dir, gap, true);
                    }
                    if (candidate != null) {
                        placed = candidate;
                        placedDir = dir;
                        break;
                    }
                }
                if (placed != null) {
                    break;
                }
            }

            if (placed == null) {
                if (++sinceProgress > wanted * 8) {
                    break;
                }
                continue;
            }
            sinceProgress = 0;
            for (int[] cell : placed.path()) {
                cells[cell[0]][cell[1]] = CORRIDOR;
            }
            for (int x = placed.box().minCX; x <= placed.box().maxCX; x++) {
                for (int z = placed.box().minCZ; z <= placed.box().maxCZ; z++) {
                    cells[x][z] = ROOM;
                    roomCells.add(new int[]{x, z});
                }
            }
            rooms.add(placed.box());
            chain = placed.box();
            chainDir = placedDir;
        }
    }

    /** A room that would fit, plus the corridor that hangs it off the parent cell. */
    private record Candidate(RoomBox box, List<int[]> path) {
    }

    /**
     * Tries one room outside {@code (px, pz)} in {@code dir}, across a gap of {@code gap} cells.
     *
     * <p>The corridor is an L: it leaves the parent cell sideways, runs to the room's entry column (or
     * row) and then turns once toward the room. Its length is {@code gap + |offset|}, capped at
     * {@value #MAX_CORRIDOR_CELLS}, which is what makes rooms land off-axis from their neighbours
     * (decision E3).
     *
     * <p>Nothing is written here: the caller commits the whole candidate at once, so a rejected
     * candidate leaves no trace.
     */
    @Nullable
    private static Candidate attempt(int[][] cells, RandomSource random, int px, int pz, int dir,
                                     int gap, boolean relaxed) {
        int kind = random.nextInt(100);
        int nearLen;
        int wideLen;
        if (kind < 50) {
            nearLen = 1;
            wideLen = 1;
        } else if (kind < 88) {
            if (random.nextBoolean()) {
                nearLen = 2;
                wideLen = 1;
            } else {
                nearLen = 1;
                wideLen = 2;
            }
        } else {
            nearLen = 2;
            wideLen = 2;
        }

        int budget = MAX_CORRIDOR_CELLS - gap;
        if (budget < 0) {
            return null;
        }
        List<Integer> offsets = new ArrayList<>(budget * 2 + 1);
        for (int offset = -budget; offset <= budget; offset++) {
            offsets.add(offset);
        }
        shuffle(offsets, random);

        for (int offset : offsets) {
            int entryX;
            int entryZ;
            int minX;
            int maxX;
            int minZ;
            int maxZ;
            if (dir == 0 || dir == 2) {
                entryZ = pz + offset;
                int runStart = entryZ - random.nextInt(wideLen);
                minZ = runStart;
                maxZ = runStart + wideLen - 1;
                if (dir == 0) {
                    entryX = px + gap;
                    minX = px + gap + 1;
                    maxX = minX + nearLen - 1;
                } else {
                    entryX = px - gap;
                    maxX = px - gap - 1;
                    minX = maxX - nearLen + 1;
                }
            } else {
                entryX = px + offset;
                int runStart = entryX - random.nextInt(wideLen);
                minX = runStart;
                maxX = runStart + wideLen - 1;
                if (dir == 1) {
                    entryZ = pz + gap;
                    minZ = pz + gap + 1;
                    maxZ = minZ + nearLen - 1;
                } else {
                    entryZ = pz - gap;
                    maxZ = pz - gap - 1;
                    minZ = maxZ - nearLen + 1;
                }
            }

            if (minX < BORDER || minZ < BORDER || maxX > GRID - 1 - BORDER
                    || maxZ > GRID - 1 - BORDER) {
                continue;
            }
            if (reserved(minX, minZ, maxX, maxZ)) {
                continue;
            }
            if (!roomIsClear(cells, minX - 1, minZ - 1, maxX + 1, maxZ + 1, relaxed)) {
                continue;
            }
            List<int[]> path = corridorPath(cells, px, pz, entryX, entryZ, dir);
            if (path == null) {
                continue;
            }
            return new Candidate(new RoomBox(minX, minZ, maxX, maxZ), path);
        }
        return null;
    }

    /**
     * Whether a rectangle holds no room cells, so the new one keeps a cell of air on every side.
     *
     * @param relaxed when set, the four corners of the surrounding ring are ignored, so two rooms may
     *                meet at a corner. The plan's sealing pass then opens that corner with a corridor
     *                cell, so the two rooms still never share a wall.
     */
    private static boolean roomIsClear(int[][] cells, int minX, int minZ, int maxX, int maxZ,
                                       boolean relaxed) {
        int x0 = Math.max(minX, 0);
        int z0 = Math.max(minZ, 0);
        int x1 = Math.min(maxX, GRID - 1);
        int z1 = Math.min(maxZ, GRID - 1);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (relaxed && (x < minX + 1 || x > maxX - 1) && (z < minZ + 1 || z > maxZ - 1)) {
                    continue;
                }
                if (cells[x][z] == ROOM || cells[x][z] == START) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The L-shaped corridor from the parent cell to the room's entry cell, or {@code null} when it
     * would cross something that is neither void nor an existing corridor.
     */
    @Nullable
    private static List<int[]> corridorPath(int[][] cells, int px, int pz, int entryX, int entryZ,
                                            int dir) {
        List<int[]> path = new ArrayList<>(MAX_CORRIDOR_CELLS);
        int cx = px + DX[dir];
        int cz = pz + DZ[dir];
        if (dir == 0 || dir == 2) {
            int step = entryZ >= cz ? 1 : -1;
            for (int z = cz; ; z += step) {
                if (!freeForCorridor(cells, cx, z)) {
                    return null;
                }
                path.add(new int[]{cx, z});
                if (z == entryZ) {
                    break;
                }
            }
            int stepX = entryX >= cx ? 1 : -1;
            for (int x = cx + stepX; ; x += stepX) {
                if (!freeForCorridor(cells, x, entryZ)) {
                    return null;
                }
                path.add(new int[]{x, entryZ});
                if (x == entryX) {
                    break;
                }
            }
        } else {
            int step = entryX >= cx ? 1 : -1;
            for (int x = cx; ; x += step) {
                if (!freeForCorridor(cells, x, cz)) {
                    return null;
                }
                path.add(new int[]{x, cz});
                if (x == entryX) {
                    break;
                }
            }
            int stepZ = entryZ >= cz ? 1 : -1;
            for (int z = cz + stepZ; ; z += stepZ) {
                if (!freeForCorridor(cells, entryX, z)) {
                    return null;
                }
                path.add(new int[]{entryX, z});
                if (z == entryZ) {
                    break;
                }
            }
        }
        if (path.size() > MAX_CORRIDOR_CELLS) {
            return null;
        }
        return path;
    }

    private static boolean freeForCorridor(int[][] cells, int cx, int cz) {
        if (cx < BORDER || cz < BORDER || cx > GRID - 1 - BORDER || cz > GRID - 1 - BORDER) {
            return false;
        }
        if (reserved(cx, cz)) {
            return false;
        }
        int value = cells[cx][cz];
        return value == VOID || value == CORRIDOR;
    }

    /**
     * Vanilla's own blocked pocket around the entrance (R25-E).
     *
     * <p>The outer-wall walk starts at the hall's north-east corner and closes its loop by coming back
     * down the hall's east column. That only lines up if the columns east of the hall are empty and the
     * hall's own column carries nothing beyond vanilla's four vestibule cells — grow a room into either
     * and the walk turns the wrong way at the first step and never closes.
     */
    private static boolean reserved(int cx, int cz) {
        if (cx > ENTRANCE_X + 1) {
            return true;
        }
        return cx == ENTRANCE_X + 1 && (cz < ENTRANCE_Y - 2 || cz > ENTRANCE_Y + 3);
    }

    private static boolean reserved(int minX, int minZ, int maxX, int maxZ) {
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                if (reserved(cx, cz)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- upper storeys

    /**
     * Stacks one layer on the layer below: a connected annex (decision E4).
     *
     * <p>The annex is a breadth-first walk over the layer below, but whole rooms are taken at once and
     * only rooms count toward the quota, so the result is <em>rooms connected by the corridors that
     * already exist beneath them</em> and nothing else. Every cell the annex claims is a cell the layer
     * below already walks on, which is what makes the storeys line up.
     *
     * <p>On the entrance storey the walk starts at the hall, so the entrance template's own staircase
     * lands on the annex; the topmost storey starts at a 1×2 room instead and flags it as the staircase
     * room, because the dark-oak staircase templates are 1×2 rooms with the climb built into them.
     */
    private static void stackStorey(Plan plan, int storey, RandomSource random) {
        int[][] below = plan.cells(storey - 1);
        int[][] above = plan.cells(storey);
        int[][] belowPlane = plan.roomPlane(storey - 1);
        List<RoomBox> belowRooms = plan.rooms(storey - 1);
        List<RoomBox> taken = plan.rooms(storey);
        int target = Math.max(1, Math.round(belowRooms.size() * STACK_RATIO));
        RoomBox landing = null;

        Deque<int[]> queue = new ArrayDeque<>();
        boolean[][] seen = new boolean[GRID][GRID];
        if (storey == 1) {
            for (int x = plan.entranceX; x <= plan.entranceX + 1; x++) {
                for (int z = plan.entranceY; z <= plan.entranceY + 1; z++) {
                    push(queue, seen, x, z);
                }
            }
            push(queue, seen, plan.entranceX + 1, plan.entranceY - 2);
            push(queue, seen, plan.entranceX + 1, plan.entranceY - 1);
            push(queue, seen, plan.entranceX + 1, plan.entranceY + 2);
            push(queue, seen, plan.entranceX + 1, plan.entranceY + 3);
            push(queue, seen, plan.entranceX - 1, plan.entranceY - 2);
            push(queue, seen, plan.entranceX - 1, plan.entranceY + 3);
        } else {
            // The topmost storey is reached by a vanilla staircase template, which is 22 blocks tall and
            // therefore already fills the room it stands in on the layer above. That room is therefore
            // <em>not</em> part of the annex: it becomes the landing, flagged START exactly the way
            // vanilla's own third floor flags its stairwell, so nothing is stamped over the climb.
            landing = pickStairRoom(belowRooms, random);
            if (landing == null) {
                return;
            }
            landing.stairs = true;
            for (int x = landing.minCX; x <= landing.maxCX; x++) {
                for (int z = landing.minCZ; z <= landing.maxCZ; z++) {
                    above[x][z] = START;
                    belowPlane[x][z] |= FLAG_STAIRS;
                    push(queue, seen, x, z);
                }
            }
        }

        while (!queue.isEmpty() && taken.size() < target) {
            int[] at = queue.poll();
            int cx = at[0];
            int cz = at[1];
            if (!walkable(below, cx, cz)) {
                continue;
            }
            if (above[cx][cz] == VOID) {
                above[cx][cz] = below[cx][cz];
            }
            if ((below[cx][cz] == ROOM || below[cx][cz] == START) && taken.size() < target) {
                RoomBox room = roomAt(belowRooms, cx, cz);
                if (room != null && room != landing && !taken.contains(room)) {
                    taken.add(room);
                    for (int x = room.minCX; x <= room.maxCX; x++) {
                        for (int z = room.minCZ; z <= room.maxCZ; z++) {
                            above[x][z] = below[x][z];
                            push(queue, seen, x, z);
                        }
                    }
                }
            }
            for (int dir = 0; dir < 4; dir++) {
                push(queue, seen, cx + DX[dir], cz + DZ[dir]);
            }
        }
    }

    private static void push(Deque<int[]> queue, boolean[][] seen, int cx, int cz) {
        if (cx < 0 || cz < 0 || cx >= GRID || cz >= GRID || seen[cx][cz]) {
            return;
        }
        seen[cx][cz] = true;
        queue.add(new int[]{cx, cz});
    }

    /** A 1×2 room to hang the staircase on: the template that carries the climb is 1×2 only. */
    @Nullable
    private static RoomBox pickStairRoom(List<RoomBox> rooms, RandomSource random) {
        List<RoomBox> candidates = new ArrayList<>();
        for (RoomBox room : rooms) {
            if (room.area() == 2 && !room.stairs) {
                candidates.add(room);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    @Nullable
    private static RoomBox roomAt(List<RoomBox> rooms, int cx, int cz) {
        for (RoomBox room : rooms) {
            if (room.contains(cx, cz)) {
                return room;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- sealing

    /**
     * Fills the enclosed pockets of a storey's cells and opens its diagonal pinches, so the vanilla
     * outer-wall walk sees a region it can actually walk around.
     *
     * <p>The walk follows the region's boundary cell by cell and has no way to cope with an enclosed
     * pocket (it never reaches it) or with two region cells that meet only at a corner (the boundary
     * touches itself at that corner and the walk can keep going round the wrong loop). The growth
     * produces both, because it deliberately leaves a cell of air between rooms; both are fixed by
     * paving the offending cell as corridor, which is walkable and therefore harmless.
     */
    private static void seal(int[][] cells) {
        boolean changed = true;
        while (changed) {
            changed = fillPockets(cells);
            changed |= openPinches(cells);
        }
    }

    /** Paves every non-walkable cell the border cannot reach; true when anything changed. */
    private static boolean fillPockets(int[][] cells) {
        boolean[][] outside = new boolean[GRID][GRID];
        Deque<int[]> queue = new ArrayDeque<>();
        for (int cx = 0; cx < GRID; cx++) {
            for (int cz = 0; cz < GRID; cz++) {
                if (cx == 0 || cz == 0 || cx == GRID - 1 || cz == GRID - 1) {
                    push(queue, outside, cx, cz);
                }
            }
        }
        while (!queue.isEmpty()) {
            int[] at = queue.poll();
            if (walkable(cells, at[0], at[1])) {
                continue;
            }
            for (int dir = 0; dir < 4; dir++) {
                push(queue, outside, at[0] + DX[dir], at[1] + DZ[dir]);
            }
        }
        boolean changed = false;
        for (int cx = 0; cx < GRID; cx++) {
            for (int cz = 0; cz < GRID; cz++) {
                if (!walkable(cells, cx, cz) && !outside[cx][cz]) {
                    cells[cx][cz] = CORRIDOR;
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** Paves one cell of every corner-only contact between two region cells; true when anything changed. */
    private static boolean openPinches(int[][] cells) {
        boolean changed = false;
        for (int cx = 0; cx + 1 < GRID; cx++) {
            for (int cz = 0; cz + 1 < GRID; cz++) {
                if (walkable(cells, cx, cz) && walkable(cells, cx + 1, cz + 1)
                        && !walkable(cells, cx + 1, cz) && !walkable(cells, cx, cz + 1)) {
                    cells[cx + 1][cz] = CORRIDOR;
                    changed = true;
                } else if (walkable(cells, cx + 1, cz) && walkable(cells, cx, cz + 1)
                        && !walkable(cells, cx, cz) && !walkable(cells, cx + 1, cz + 1)) {
                    cells[cx][cz] = CORRIDOR;
                    changed = true;
                }
            }
        }
        return changed;
    }

    // ---------------------------------------------------------------- finishing a storey

    /** Adds the stubs, seals the layer, then numbers its rooms and picks their doorways. */
    private static void finishStorey(Plan plan, int storey) {
        int[][] cells = plan.cells(storey);
        plan.deadEnds += addDeadEnds(cells, RandomSource.create(
                0x51ED270BL + storey * 7919L + plan.entranceX * 31L + plan.rooms(storey).size()));
        seal(cells);
        numberRooms(plan, storey);
    }

    /** Short stub corridors that stop in nothing: the map's dead ends (decision E3). */
    private static int addDeadEnds(int[][] cells, RandomSource random) {
        List<int[]> corridors = new ArrayList<>();
        for (int cx = 0; cx < GRID; cx++) {
            for (int cz = 0; cz < GRID; cz++) {
                if (cells[cx][cz] == CORRIDOR) {
                    corridors.add(new int[]{cx, cz});
                }
            }
        }
        if (corridors.isEmpty()) {
            return 0;
        }
        shuffle(corridors, random);
        int wanted = Math.max(1, Math.round(corridors.size() * DEAD_END_SHARE));
        int made = 0;
        for (int[] cell : corridors) {
            if (made >= wanted) {
                break;
            }
            for (int dir : shuffledDirections(random, -1)) {
                int length = 1 + random.nextInt(3);
                List<int[]> stub = new ArrayList<>(length);
                int cx = cell[0];
                int cz = cell[1];
                boolean ok = true;
                for (int step = 0; step < length; step++) {
                    cx += DX[dir];
                    cz += DZ[dir];
                    if (cx < BORDER || cz < BORDER || cx > GRID - 1 - BORDER
                            || cz > GRID - 1 - BORDER || reserved(cx, cz) || cells[cx][cz] != VOID) {
                        ok = false;
                        break;
                    }
                    stub.add(new int[]{cx, cz});
                }
                if (!ok || stub.isEmpty()) {
                    continue;
                }
                for (int[] at : stub) {
                    cells[at[0]][at[1]] = CORRIDOR;
                }
                made++;
                break;
            }
        }
        return made;
    }

    /**
     * Numbers every room of a storey and flags its doorway.
     *
     * <p>The doorway cell has to be a corner of a 2×2 room (that is what tells the vanilla placer which
     * perpendicular direction the room runs in) and any cell of a smaller one, and it has to touch a
     * corridor — that adjacency is what makes the placer cut an {@code indoors_door} instead of an
     * {@code indoors_wall}. Cells with exactly one corridor neighbour are preferred, because then the
     * placer has no choice to make and the doorway is where the plan says it is.
     *
     * <p>A room that ends up touching no corridor at all cannot be entered and cannot even be given a
     * template by the vanilla placer, so it is paved over as corridor rather than left as a sealed box.
     */
    private static void numberRooms(Plan plan, int storey) {
        int[][] cells = plan.cells(storey);
        int[][] plane = plan.roomPlane(storey);
        List<RoomBox> rooms = plan.rooms(storey);
        List<RoomBox> dropped = new ArrayList<>();
        int id = 10;
        for (RoomBox room : rooms) {
            int[] door = pickDoor(cells, room);
            if (door == null) {
                dropped.add(room);
                continue;
            }
            int type = room.area() >= 4 ? TYPE_2X2 : room.area() == 2 ? TYPE_1X2 : TYPE_1X1;
            room.id = id++;
            room.doorCX = door[0];
            room.doorCZ = door[1];
            for (int cx = room.minCX; cx <= room.maxCX; cx++) {
                for (int cz = room.minCZ; cz <= room.maxCZ; cz++) {
                    if (!walkable(cells, cx, cz)) {
                        continue;
                    }
                    int value = type | room.id;
                    if (cx == room.doorCX && cz == room.doorCZ) {
                        value |= FLAG_ORIGIN | FLAG_DOOR;
                    }
                    if (room.stairs) {
                        value |= FLAG_STAIRS;
                    }
                    plane[cx][cz] = value;
                }
            }
        }
        for (RoomBox room : dropped) {
            rooms.remove(room);
            for (int cx = room.minCX; cx <= room.maxCX; cx++) {
                for (int cz = room.minCZ; cz <= room.maxCZ; cz++) {
                    cells[cx][cz] = CORRIDOR;
                }
            }
        }
        // The hall and any staircase landing: vanilla flags those cells ROOM_CORRIDOR_FLAG so the
        // corridor carpets run into them and so the placer walls them without stamping a room over them.
        for (int cx = 0; cx < GRID; cx++) {
            for (int cz = 0; cz < GRID; cz++) {
                if (cells[cx][cz] == START) {
                    plane[cx][cz] |= FLAG_START;
                }
            }
        }
    }

    /** The room cell that carries the doorway, best first, or {@code null} when the room has no way in. */
    @Nullable
    private static int[] pickDoor(int[][] cells, RoomBox room) {
        List<int[]> one = new ArrayList<>();
        List<int[]> many = new ArrayList<>();
        for (int cx = room.minCX; cx <= room.maxCX; cx++) {
            for (int cz = room.minCZ; cz <= room.maxCZ; cz++) {
                if (room.area() >= 4 && cx != room.minCX && cx != room.maxCX
                        && cz != room.minCZ && cz != room.maxCZ) {
                    continue;
                }
                int touching = 0;
                for (int dir = 0; dir < 4; dir++) {
                    if (cells[cx + DX[dir]][cz + DZ[dir]] == CORRIDOR) {
                        touching++;
                    }
                }
                if (touching == 1) {
                    one.add(new int[]{cx, cz});
                } else if (touching > 1) {
                    many.add(new int[]{cx, cz});
                }
            }
        }
        if (!one.isEmpty()) {
            return one.get(0);
        }
        return many.isEmpty() ? null : many.get(0);
    }

    // ---------------------------------------------------------------- empty lots

    /**
     * Picks the building's edge cells that stay rubble and low wall stumps (spec §5.1).
     *
     * <p>A lot is any empty cell with a walkable neighbour and no reserved status. The stump it gets is
     * two blocks wide inside the lot's own cell, so it never reaches a room's template box, and the
     * footing the builder already lays one cell past every walkable cell keeps it off the void.
     */
    private static void scatterRuins(Plan plan) {
        int[][] cells = plan.cells(0);
        List<int[]> edge = new ArrayList<>();
        for (int cx = BORDER; cx <= GRID - 1 - BORDER; cx++) {
            for (int cz = BORDER; cz <= GRID - 1 - BORDER; cz++) {
                if (cells[cx][cz] != VOID || reserved(cx, cz)) {
                    continue;
                }
                boolean touches = false;
                for (int dir = 0; dir < 4 && !touches; dir++) {
                    touches = walkable(cells, cx + DX[dir], cz + DZ[dir]);
                }
                if (touches) {
                    edge.add(new int[]{cx, cz});
                }
            }
        }
        if (edge.isEmpty()) {
            return;
        }
        RandomSource random = RandomSource.create(0x2545F4914F6CDD1DL ^ plan.walkableCells());
        shuffle(edge, random);
        int wanted = Math.max(1, Math.round(plan.walkableCells() * LOT_SHARE));
        int count = Math.min(wanted, edge.size());
        for (int index = 0; index < count; index++) {
            int[] cell = edge.get(index);
            plan.lots.add(new Lot(cell[0], cell[1], index % 3 == 0));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void corridor(int[][] cells, int x0, int z0, int x1, int z1) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                if (cells[x][z] == VOID) {
                    cells[x][z] = CORRIDOR;
                }
            }
        }
    }

    /** Whether a cell is part of the walkable region; outside the grid counts as blocked. */
    static boolean walkable(int[][] cells, int cx, int cz) {
        if (cx < 0 || cz < 0 || cx >= GRID || cz >= GRID) {
            return false;
        }
        int value = cells[cx][cz];
        return value == CORRIDOR || value == ROOM || value == START;
    }

    private static int[] shuffledDirections(RandomSource random, int first) {
        List<Integer> all = new ArrayList<>(4);
        for (int dir = 0; dir < 4; dir++) {
            if (dir != first) {
                all.add(dir);
            }
        }
        shuffle(all, random);
        int[] order = new int[4];
        int index = 0;
        if (first >= 0) {
            order[index++] = first;
        }
        for (int dir : all) {
            order[index++] = dir;
        }
        return order;
    }

    private static void shuffle(List<?> list, RandomSource random) {
        for (int index = list.size() - 1; index > 0; index--) {
            int other = random.nextInt(index + 1);
            java.util.Collections.swap(list, index, other);
        }
    }
}
