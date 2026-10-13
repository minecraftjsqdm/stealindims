package com.homemine.stealindims.raid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * The generation gate of the vanilla-template mansion (P5 §2).
 *
 * <p>From P5 the plan is no longer a hand-made floor plan: it is what
 * {@link VanillaMansionPieces#generateMansion} — vanilla's own {@code MansionPiecePlacer} — produced,
 * with the rooms read back out of vanilla's packed room plane. That is still pure computation over a
 * {@link net.minecraft.util.RandomSource}, so all of it can be asserted on a plain JVM with no game
 * bootstrap, which is the only way to pin the numbers at all: the in-game seed is drawn from
 * {@code raidLevel.getRandom().nextLong()}.
 *
 * <p>The connectivity check is deliberately <em>recomputed here</em> rather than read off
 * {@link MansionLayout#isConnected(int)}: trusting the flag the generator set would make the test
 * tautological, which is exactly the failure mode risk R-b warns about.
 */
class MansionLayoutBuilderTest {

    /** A fixed seed, so a failure is always reproducible. */
    private static final long SEED = 20261002L;
    /** The X the mansion is centred on; matches {@code MansionGeometry.MANSION_OFFSET_X}. */
    private static final int CENTRE_X = MansionGeometry.MANSION_OFFSET_X;

    /** The storey offsets vanilla authors its templates for, in blocks above the entrance floor. */
    private static final int[] OFFSETS = {0, 8, 19};

    @Test
    void everyDifficultyGrowsOnTheSameGrid() {
        // R25-E: the map is no longer sized by a square of cells — the dial is the room count (E1) and
        // the growth decides how much of the grid it needs.
        for (RaidDifficulty difficulty : RaidDifficulty.values()) {
            assertEquals(OrganicMansionPlan.GRID, MansionLayoutBuilder.gridSizeFor(difficulty),
                    difficulty + " grid changed");
            assertEquals(MansionLayoutBuilder.gridSizeFor(difficulty), plan(difficulty).gridSize(),
                    difficulty + " plan does not carry its own grid size");
        }
    }

    @Test
    void storeySpacingIsVanillas() {
        // Vanilla: pos.above(8 * floor + (floor == 2 ? 3 : 0)) -> 64 / 72 / 83, not 64 / 72 / 80.
        assertEquals(64, MansionLayout.layerY(0), "entrance floor moved");
        assertEquals(72, MansionLayout.layerY(1), "second storey moved");
        assertEquals(83, MansionLayout.layerY(2), "third storey moved: vanilla puts it at +19");
        assertEquals(List.of(64), plan(RaidDifficulty.C).layerYsBottomUp(), "C storeys changed");
        assertEquals(List.of(64, 72), plan(RaidDifficulty.B).layerYsBottomUp(), "B storeys changed");
        assertEquals(List.of(64, 72), plan(RaidDifficulty.A).layerYsBottomUp(), "A storeys changed");
        assertEquals(List.of(64, 72, 83), plan(RaidDifficulty.S).layerYsBottomUp(), "S storeys changed");

        MansionLayout layout = plan(RaidDifficulty.S);
        assertEquals(83, layout.layerYs().get(0), "the top storey is no longer reported first");
        assertEquals(64, layout.layerYs().get(2), "the entrance storey is no longer reported last");
    }

    @Test
    void submissionCountsMatchTheDifficulty() {
        assertEquals(2, plan(RaidDifficulty.C).submissions().size(), "C submissions changed");
        assertEquals(2, plan(RaidDifficulty.B).submissions().size(), "B submissions changed");
        assertEquals(2, plan(RaidDifficulty.A).submissions().size(), "A submissions changed");
        assertEquals(3, plan(RaidDifficulty.S).submissions().size(), "S submissions changed");
        // C has one storey but still needs two submission points, so they share it (decision R3).
        MansionLayout c = plan(RaidDifficulty.C);
        assertEquals(c.layerYsBottomUp().get(0), c.submissions().get(0).layerY(),
                "C's first submission is not on its only storey");
        assertEquals(c.layerYsBottomUp().get(0), c.submissions().get(1).layerY(),
                "C's second submission is not on its only storey");
    }

    /**
     * R25-E: the building is as tall as the difficulty grants — C one storey, B/A two, S three — and the
     * upper layers are real annexes rather than the same footprint repeated.
     */
    @Test
    void theBuildingHasAsManyStoreysAsTheDifficultyGrants() {
        assertEquals(OrganicMansionPlan.MAX_STOREYS, MansionLayout.PHYSICAL_FLOORS,
                "the tallest building changed");
        for (RaidDifficulty difficulty : RaidDifficulty.values()) {
            MansionLayout layout = plan(difficulty);
            int storeys = MansionLayout.floorsFor(difficulty);
            assertEquals(storeys, layout.floors().size(), difficulty + " storey count changed");
            assertEquals(storeys, layout.layerYsBottomUp().size(),
                    difficulty + " reports a different number of walkable layers");
            for (int storey = 0; storey < storeys; storey++) {
                assertTrue(hasRoomTemplateAt(layout, MansionLayoutBuilder.TEMPLATE_Y + OFFSETS[storey]),
                        difficulty + " has no rooms on storey +" + OFFSETS[storey]);
            }
            for (int storey = storeys; storey < OFFSETS.length; storey++) {
                assertFalse(hasRoomTemplateAt(layout, MansionLayoutBuilder.TEMPLATE_Y + OFFSETS[storey]),
                        difficulty + " still raises rooms on storey +" + OFFSETS[storey]);
            }
        }
    }

    /** Whether any vanilla room template stands at {@code y}. */
    private static boolean hasRoomTemplateAt(MansionLayout layout, int y) {
        for (VanillaMansionPieces.Piece piece : layout.pieces()) {
            if (piece.pos.getY() == y && isRoomTemplate(piece.name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRoomTemplate(String name) {
        return name.startsWith("1x1_") || name.startsWith("1x2_") || name.startsWith("2x2_");
    }

    /** The pieces have to be the real vanilla template ids, and they have to sit inside the plan. */
    @Test
    void everyPieceIsAVanillaMansionTemplate() {
        for (RaidDifficulty difficulty : RaidDifficulty.values()) {
            MansionLayout layout = plan(difficulty);
            assertFalse(layout.pieces().isEmpty(), difficulty + " placed no templates");
            int minX = layout.blockMinX(layout.bounds()) - 32;
            int maxX = layout.blockMaxX(layout.bounds()) + 32;
            int minZ = layout.blockMinZ(layout.bounds()) - 32;
            int maxZ = layout.blockMaxZ(layout.bounds()) + 32;
            for (VanillaMansionPieces.Piece piece : layout.pieces()) {
                assertTrue(isVanillaTemplate(piece.name), "unknown template id: " + piece.name);
                assertTrue(piece.pos.getX() >= minX && piece.pos.getX() <= maxX,
                        "piece " + piece + " is outside the plan in X");
                assertTrue(piece.pos.getZ() >= minZ && piece.pos.getZ() <= maxZ,
                        "piece " + piece + " is outside the plan in Z");
            }
        }
    }

    /** Same seed, same mansion; a different seed has to change it. */
    @Test
    void thePlanIsDeterministic() {
        MansionLayout first = plan(RaidDifficulty.A);
        MansionLayout again = MansionLayoutBuilder.build(RaidDifficulty.A, SEED, CENTRE_X);
        assertEquals(first.pieces().size(), again.pieces().size(), "piece count is not deterministic");
        for (int index = 0; index < first.pieces().size(); index++) {
            VanillaMansionPieces.Piece a = first.pieces().get(index);
            VanillaMansionPieces.Piece b = again.pieces().get(index);
            assertEquals(a.name + "@" + a.pos + "/" + a.rotation, b.name + "@" + b.pos + "/" + b.rotation,
                    "piece " + index + " is not deterministic");
        }
        MansionLayout other = MansionLayoutBuilder.build(RaidDifficulty.A, SEED + 1, CENTRE_X);
        assertFalse(fingerprint(first).equals(fingerprint(other)), "two seeds produced the same plan");
    }

    /** Risk R-b: no storey may ship with an unreachable wing. */
    @Test
    void everyStoreyIsConnected() {
        for (RaidDifficulty difficulty : RaidDifficulty.values()) {
            MansionLayout layout = plan(difficulty);
            for (MansionLayout.Floor floor : layout.floors()) {
                List<Integer> sizes = components(floor);
                assertEquals(1, sizes.size(),
                        difficulty + " storey y=" + floor.layerY() + " has " + sizes.size()
                                + " components, sizes=" + sizes);
                assertTrue(layout.isConnected(floor.layerY()),
                        difficulty + " storey y=" + floor.layerY() + " disagrees with its own flag");
            }
        }
    }

    /** Rooms are the vanilla three sizes and nothing else — that is the whole point of P5. */
    @Test
    void everyRoomIsOneOfVanillasThreeSizes() {
        for (RaidDifficulty difficulty : RaidDifficulty.values()) {
            MansionLayout layout = plan(difficulty);
            assertFalse(layout.rooms().isEmpty(), difficulty + " has no rooms at all");
            Set<String> shapes = new HashSet<>();
            for (MansionLayout.Room room : layout.rooms()) {
                String shape = room.width() + "x" + room.depth();
                shapes.add(shape);
                assertTrue(shape.equals("1x1") || shape.equals("1x2") || shape.equals("2x1")
                                || shape.equals("2x2"),
                        difficulty + " produced a room outside vanilla's 1x1/1x2/2x2 menu: " + shape);
                assertTrue(layout.blockMinX(room.cells()) > 0,
                        "room " + room.cells() + " sits at the world origin");
            }
            assertTrue(shapes.contains("1x1"), difficulty + " has no 1x1 room");
        }
    }

    /**
     * R25-E decision E1: the entrance storey carries C 30 / B 45 / A 60 / S 80 rooms, the entrance hall
     * included, and each higher layer is the {@code 15%} annex on top of it.
     */
    @Test
    void roomCountsAreTheDecidedOnes() {
        for (RaidDifficulty difficulty : RaidDifficulty.values()) {
            MansionLayout layout = plan(difficulty);
            int target = MansionLayoutBuilder.roomTargetFor(difficulty);
            assertEquals(target, layout.roomsAt(MansionLayout.layerY(0)).size(),
                    difficulty + " entrance storey room count changed");
            int storeys = MansionLayout.floorsFor(difficulty);
            for (int storey = 1; storey < storeys; storey++) {
                int below = layout.roomsAt(MansionLayout.layerY(storey - 1)).size();
                int here = layout.roomsAt(MansionLayout.layerY(storey)).size();
                assertEquals(Math.max(1, Math.round(below * 0.15F)), here,
                        difficulty + " storey " + storey + " is not the 15% annex of the one below");
            }
        }
    }

    /**
     * R25-E: no two rooms may share a wall. Any pair of rooms on a layer has to keep at least one whole
     * cell between them on the four faces, which is what makes the plan read as separate blocks strung
     * together by corridors instead of as one packed slab.
     */
    @Test
    void noTwoRoomsShareAWall() {
        for (RaidDifficulty difficulty : RaidDifficulty.values()) {
            MansionLayout layout = plan(difficulty);
            for (MansionLayout.Floor floor : layout.floors()) {
                List<MansionLayout.Room> rooms = layout.roomsAt(floor.layerY());
                for (int a = 0; a < rooms.size(); a++) {
                    for (int b = a + 1; b < rooms.size(); b++) {
                        assertFalse(touch(rooms.get(a).cells(), rooms.get(b).cells()),
                                difficulty + " rooms " + rooms.get(a).cells() + " and "
                                        + rooms.get(b).cells() + " share a wall on y=" + floor.layerY());
                    }
                }
            }
        }
    }

    /** Whether two cell rectangles overlap or sit face to face. */
    private static boolean touch(MansionLayout.Cells a, MansionLayout.Cells b) {
        boolean acrossX = a.minCX() - 1 <= b.maxCX() && b.minCX() <= a.maxCX() + 1
                && a.minCZ() <= b.maxCZ() && b.minCZ() <= a.maxCZ();
        boolean acrossZ = a.minCZ() - 1 <= b.maxCZ() && b.minCZ() <= a.maxCZ() + 1
                && a.minCX() <= b.maxCX() && b.minCX() <= a.maxCX();
        return acrossX || acrossZ;
    }

    /**
     * R25-E decisions E3 and the empty-lot rule: the growth really left dead-end stubs, really left 5–10%
     * of the building's edge as rubble, and really did not fill its bounding box — a solid outline would
     * mean the rooms are still packed shoulder to shoulder.
     */
    @Test
    void theGrowthLeavesDeadEndsRubbleAndAnIrregularOutline() {
        for (RaidDifficulty difficulty : RaidDifficulty.values()) {
            MansionLayout layout = plan(difficulty);
            assertTrue(layout.deadEnds() > 0, difficulty + " has no dead-end corridors");
            assertTrue(layout.lots() > 0, difficulty + " has no rubble lots");
            int walkable = 0;
            for (MansionLayout.Floor floor : layout.floors()) {
                for (int cx = floor.minCX(); cx <= floor.maxCX(); cx++) {
                    for (int cz = floor.minCZ(); cz <= floor.maxCZ(); cz++) {
                        if (floor.isWalkable(cx, cz)) {
                            walkable++;
                        }
                    }
                }
            }
            int box = layout.bounds().area() * layout.floors().size();
            assertTrue(walkable < box * 6 / 10,
                    difficulty + " filled " + walkable + " of " + box + " cells: the outline is a slab again");
        }
    }

    /**
     * R25-E: the outline is no longer a rectangle, so the map is as big as the growth made it — and it
     * still has to stop short of the extraction room at X = 0, whose wipe box reaches X = ±6.
     */
    @Test
    void theMapNeverReachesTheExtractionRoom() {
        for (RaidDifficulty difficulty : RaidDifficulty.values()) {
            MansionGeometry geometry = MansionGeometry.of(null, difficulty, SEED);
            int radius = geometry.mapRadius();
            assertTrue((long) MansionGeometry.MANSION_OFFSET_X - radius > RaidArena.ROOM_HALF + 1,
                    difficulty + " map reaches into the extraction room: centre="
                            + MansionGeometry.MANSION_OFFSET_X + " radius=" + radius);
            assertFalse(geometry.isInMap(RaidArena.safeRoomSpawn()),
                    difficulty + " counts the extraction room as part of the map");
        }
    }

    /** Bigger difficulty, more rooms: that is how R25-E scales the map instead of locking a grid. */
    @Test
    void roomCountGrowsWithTheDifficulty() {
        int c = plan(RaidDifficulty.C).roomsAt(MansionLayout.layerY(0)).size();
        int b = plan(RaidDifficulty.B).roomsAt(MansionLayout.layerY(0)).size();
        int a = plan(RaidDifficulty.A).roomsAt(MansionLayout.layerY(0)).size();
        int s = plan(RaidDifficulty.S).roomsAt(MansionLayout.layerY(0)).size();
        assertTrue(c < b && b < a && a < s,
                "room counts are not monotonic in the difficulty: " + c + " / " + b + " / " + a + " / " + s);
    }

    /**
     * Every room must have a way in: some cell of it shares an edge with a walkable cell that is not
     * part of the same room. This is the headless half of the walk-in acceptance — the in-game half
     * actually walks the doorway.
     */
    @Test
    void everyRoomTouchesAWalkableNeighbour() {
        for (RaidDifficulty difficulty : RaidDifficulty.values()) {
            MansionLayout layout = plan(difficulty);
            for (MansionLayout.Room room : layout.rooms()) {
                MansionLayout.Floor floor = layout.floor(room.layerY());
                assertNotNull(floor, "no walkable plane for storey y=" + room.layerY());
                boolean door = false;
                for (int cx = room.cells().minCX(); cx <= room.cells().maxCX() && !door; cx++) {
                    for (int cz = room.cells().minCZ(); cz <= room.cells().maxCZ() && !door; cz++) {
                        int[][] neighbours = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                        for (int[] step : neighbours) {
                            int nx = cx + step[0];
                            int nz = cz + step[1];
                            if (floor.isWalkable(nx, nz) && !room.cells().contains(nx, nz)) {
                                door = true;
                                break;
                            }
                        }
                    }
                }
                assertTrue(door, difficulty + " room " + room.cells() + " on y=" + room.layerY()
                        + " has no walkable neighbour");
            }
        }
    }

    /**
     * Every room template has to stand inside the rectangle the plan says its room occupies.
     *
     * <p>This is the cell-to-block mapping's regression gate: vanilla places pieces with a
     * {@code +8} band on Z and none on X, and getting that wrong still produces a beautiful mansion
     * that is eight blocks south of every rectangle the spawner, the traps and the walk-in test use.
     */
    @Test
    void everyRoomTemplateStandsInsideItsRoomRectangle() {
        for (RaidDifficulty difficulty : RaidDifficulty.values()) {
            MansionLayout layout = plan(difficulty);
            for (VanillaMansionPieces.Piece piece : layout.pieces()) {
                if (!piece.name.startsWith("1x1_") && !piece.name.startsWith("1x2_")
                        && !piece.name.startsWith("2x2_")) {
                    continue;
                }
                boolean inside = false;
                for (MansionLayout.Room room : layout.rooms()) {
                    if (room.layerY() != piece.pos.getY() + 1) {
                        continue;
                    }
                    if (piece.pos.getX() >= layout.blockMinX(room.cells()) - 2
                            && piece.pos.getX() <= layout.blockMaxX(room.cells()) + 2
                            && piece.pos.getZ() >= layout.blockMinZ(room.cells()) - 2
                            && piece.pos.getZ() <= layout.blockMaxZ(room.cells()) + 2) {
                        inside = true;
                        break;
                    }
                }
                assertTrue(inside, difficulty + " template " + piece + " is not inside any room rectangle");
            }
        }
    }

    /** Doorways are real wall lines with a walkable cell on both sides. */
    @Test
    void everyDoorwayIsAnEdgeBetweenTwoWalkableCells() {
        for (RaidDifficulty difficulty : RaidDifficulty.values()) {
            MansionLayout layout = plan(difficulty);
            assertFalse(layout.doors().isEmpty(), difficulty + " produced no doorways at all");
            for (MansionLayout.Door door : layout.doors()) {
                boolean alongZ = door.minX() == door.maxX();
                int span = alongZ ? door.maxZ() - door.minZ() : door.maxX() - door.minX();
                assertTrue(span >= MansionLayoutBuilder.DOOR_SPAN - 1,
                        "doorway narrower than " + MansionLayoutBuilder.DOOR_SPAN + ": " + door);
                assertTrue(layout.doorsAt(door.layerY()).contains(door),
                        "a doorway is filed under the wrong storey");
            }
        }
    }

    /** The statue room: a genuine vanilla room on the entrance storey, backed onto one of its walls. */
    @Test
    void theStatueRoomIsARealRoomOnTheEntranceStorey() {
        for (RaidDifficulty difficulty : RaidDifficulty.values()) {
            MansionLayout layout = plan(difficulty);
            MansionLayout.Statue statue = layout.statue();
            assertNotNull(statue, difficulty + " produced no statue room");
            assertEquals(MansionLayout.layerY(0), statue.layerY(),
                    difficulty + " put the statue off the entrance storey");
            assertTrue(statue.room().area() >= 1, difficulty + " statue room has no cells");

            int roomMinX = layout.blockMinX(statue.room());
            int roomMaxX = layout.blockMaxX(statue.room());
            assertTrue(statue.templateMinX() >= roomMinX && statue.templateMaxX() <= roomMaxX - 1,
                    difficulty + " statue face pokes through a room side wall");
            // R24: the vault is behind the room's north wall, not hanging on the room's inside.
            assertTrue(statue.chamberShellMax().getZ() < layout.blockMinZ(statue.room()),
                    difficulty + " statue vault stands inside the room");
        }
    }

    /**
     * R23 + R24: the statue is the user's own 6×8×4 template, and the room holds it with the sealed
     * vault carved into the wall its back leans on.
     *
     * <p>The template is stamped verbatim, so the only thing under test here is the placement: the
     * back plane sits on the room's first interior row (the wall is the block behind it), the face is
     * the template's own {@code z = 2} plane with the {@code lime_wool} eyes on {@code y = 4}, the vault
     * reaches from the wall line back through its cavity and shell, its only opening is the tunnel the
     * nose plugs, and there is still floor in front of the face for the player who has to reach the
     * template's own hand crank. The per-seed sweep of all of that lives in
     * {@link MansionStatuePlacementTest}.
     */
    @Test
    void theStatueTemplateFitsBehindItsDarkRoom() {
        for (RaidDifficulty difficulty : RaidDifficulty.values()) {
            MansionLayout layout = plan(difficulty);
            MansionLayout.Statue statue = layout.statue();
            assertNotNull(statue, difficulty + " produced no statue room");
            String where = difficulty + " statue: ";
            int roomMinX = layout.blockMinX(statue.room());
            int roomMaxX = layout.blockMaxX(statue.room());
            int roomMinZ = layout.blockMinZ(statue.room());
            int roomMaxZ = layout.blockMaxZ(statue.room());

            // The template's own box: the user's 6×8×4, inside the room's perimeter walls.
            assertEquals(MansionLayout.STATUE_WIDTH,
                    statue.templateMaxX() - statue.templateMinX() + 1, where + "template width");
            assertEquals(MansionLayout.STATUE_HEIGHT,
                    statue.templateMaxY() - statue.templateMinY() + 1, where + "template height");
            assertEquals(MansionLayout.STATUE_DEPTH,
                    statue.templateMaxZ() - statue.templateMinZ() + 1, where + "template depth");
            assertTrue(statue.templateMinX() >= roomMinX && statue.templateMaxX() <= roomMaxX - 1,
                    where + "the template pokes through a side wall");
            assertTrue(statue.templateMaxZ() <= roomMaxZ - 1,
                    where + "the template pokes through the south wall");

            // R24: the back plane is the room's first interior row and the wall is the block behind it.
            assertEquals(roomMinZ, statue.templateMinZ(),
                    where + "the statue is not flush against the room's north wall");
            assertEquals(roomMinZ - 1, statue.wallZ(), where + "the wall line moved");

            // The face is the template's z=2 plane, and its lime eyes sit on the layout's eye line.
            assertEquals(statue.templateMinZ() + MansionLayout.STATUE_FACE_LOCAL_Z, statue.faceZ(),
                    where + "the face plane is not the template's own");
            assertEquals(4, statue.eyeY() - statue.templateMinY(),
                    where + "the eye line is not on the template's y=4 row");

            // The vault: 3×3×3 behind the wall, its southern shell layer on the wall line itself.
            assertEquals(MansionLayout.STATUE_DARK_ROOM,
                    statue.chamberMaxZ() - statue.chamberMinZ() + 1, where + "vault depth");
            assertEquals(MansionLayout.STATUE_DARK_ROOM,
                    statue.chamberMaxX() - statue.chamberMinX() + 1, where + "vault width");
            assertEquals(MansionLayout.STATUE_DARK_ROOM,
                    statue.chamberMaxY() - statue.chamberMinY() + 1, where + "vault height");
            assertEquals(statue.wallZ(), statue.chamberShellMax().getZ(),
                    where + "the vault's shell does not reach the wall line");
            assertEquals(statue.templateMinZ() - 1, statue.chamberMaxZ() + 1,
                    where + "the shell does not separate the vault from the template");
            assertTrue(statue.tunnelMinX() >= statue.chamberMinX()
                            && statue.tunnelMaxX() <= statue.chamberMaxX(),
                    where + "the tunnel's mouth is not inside the vault's wall");

            // Room to stand in front of the face, and the template's own crank reachable there.
            assertTrue(statue.templateMaxZ() < roomMaxZ - 2, where + "no floor left in front of the face");
            BlockPos crank = statue.crankPos();
            assertEquals(statue.templateMaxZ(), crank.getZ(), where + "the crank is not on the template");
            assertTrue(crank.getX() >= roomMinX && crank.getX() <= roomMaxX - 1,
                    where + "the template's crank is outside the room");

            // The two things we put in the vault: both on its floor, both inside it, apart.
            BlockPos portal = statue.bossPortal();
            BlockPos treasure = statue.treasure();
            for (BlockPos pos : List.of(portal, treasure)) {
                assertTrue(pos.getX() >= statue.chamberMinX() && pos.getX() <= statue.chamberMaxX()
                                && pos.getZ() >= statue.chamberMinZ() && pos.getZ() <= statue.chamberMaxZ()
                                && pos.getY() == statue.chamberMinY(),
                        where + "the vault's contents are outside it: " + pos);
            }
            assertFalse(portal.equals(treasure), where + "the chest stands on the portal");
        }
    }

    /** The map spawn has to land on the entrance storey, inside the entrance hall. */
    @Test
    void theSpawnSitsInTheEntranceHall() {
        for (RaidDifficulty difficulty : RaidDifficulty.values()) {
            MansionLayout layout = plan(difficulty);
            BlockPos spawn = layout.spawn();
            assertEquals(MansionLayout.layerY(0), spawn.getY(), difficulty + " spawn is off the ground");
            assertTrue(layout.isInSafeZone(spawn), difficulty + " spawn is not in the entrance safe zone");
            assertFalse(layout.anchor().equals(spawn), difficulty + " anchor sits on the spawn");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static boolean isVanillaTemplate(String name) {
        return name.startsWith("1x1_") || name.startsWith("1x2_") || name.startsWith("2x2_")
                || name.equals("corridor_floor") || name.startsWith("carpet_")
                || name.equals("entrance") || name.startsWith("wall_")
                || name.startsWith("small_wall") || name.startsWith("indoors_")
                || name.startsWith("roof");
    }

    private static MansionLayout plan(RaidDifficulty difficulty) {
        return MansionLayoutBuilder.build(difficulty, SEED, CENTRE_X);
    }

    /** A seed-independent identity of a plan, so two seeds can be compared on a bare JVM. */
    private static String fingerprint(MansionLayout layout) {
        StringBuilder out = new StringBuilder();
        for (VanillaMansionPieces.Piece piece : layout.pieces()) {
            out.append(piece.name).append(piece.pos.toShortString()).append(piece.rotation).append(';');
        }
        return out.toString();
    }

    /** The component sizes of a storey's walkable cells, recomputed from the raw plane. */
    private static List<Integer> components(MansionLayout.Floor floor) {
        Set<Long> seen = new HashSet<>();
        List<Integer> sizes = new ArrayList<>();
        Deque<int[]> queue = new ArrayDeque<>();
        for (int cx = floor.minCX(); cx <= floor.maxCX(); cx++) {
            for (int cz = floor.minCZ(); cz <= floor.maxCZ(); cz++) {
                if (!floor.isWalkable(cx, cz) || seen.contains(MansionLayout.cellKey(cx, cz))) {
                    continue;
                }
                int size = 0;
                seen.add(MansionLayout.cellKey(cx, cz));
                queue.add(new int[]{cx, cz});
                while (!queue.isEmpty()) {
                    int[] at = queue.poll();
                    size++;
                    int[][] neighbours = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int[] step : neighbours) {
                        int nx = at[0] + step[0];
                        int nz = at[1] + step[1];
                        if (floor.isWalkable(nx, nz) && seen.add(MansionLayout.cellKey(nx, nz))) {
                            queue.add(new int[]{nx, nz});
                        }
                    }
                }
                sizes.add(size);
            }
        }
        sizes.sort(Integer::compareTo);
        return sizes;
    }
}
