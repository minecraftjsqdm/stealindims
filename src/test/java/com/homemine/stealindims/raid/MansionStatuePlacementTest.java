package com.homemine.stealindims.raid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/**
 * R24, spec §5.6: the statue and its sealed room have to stand <em>against a wall</em>, with the vault
 * carved into what is behind that wall — never as a shelled box in the middle of a room.
 *
 * <p>The user's complaint was concrete: the dark room's chamber was a cobblestone box with the pixel
 * face floating in front of it, sitting between rooms, two blocks clear of the room's own wall. These
 * tests pin the three things that fix it, on the real generator, over every difficulty and a spread of
 * seeds, with no game bootstrap:
 *
 * <ol>
 *     <li>the statue's back plane is flush with the room's wall line — the room's first interior block
 *     row — so the face leans on the wall;</li>
 *     <li>the vault is entirely behind that wall, in cells the walkable plane says are not walkable, so
 *     nothing the mod builds is visible from the room;</li>
 *     <li>the vault has exactly one opening — the 2×3 crawl tunnel the nose plugs — and the other five
 *     faces of the cavity are solid masonry.</li>
 * </ol>
 */
class MansionStatuePlacementTest {

    private static final int CENTRE_X = 120;

    /** Enough seeds to cover the generator's room shapes without making the suite slow. */
    private static final int SEEDS = 60;

    @Test
    void theStatueBacksOntoTheRoomsOwnWall() {
        for (RaidDifficulty difficulty : RaidDifficulty.values()) {
            for (int index = 0; index < SEEDS; index++) {
                MansionLayout layout = plan(difficulty, index);
                MansionLayout.Statue statue = layout.statue();
                assertNotNull(statue, difficulty + " seed " + index + " produced no statue");
                String where = difficulty + " seed " + index + " " + statue.room() + ": ";
                int roomMinZ = layout.blockMinZ(statue.room());

                // The wall line is the block just north of the room's first interior row, and the
                // template's back plane stands on that interior row: no gap, no setback.
                assertEquals(roomMinZ - 1, statue.wallZ(), where + "the wall line moved");
                assertEquals(roomMinZ, statue.templateMinZ(), where + "the statue is not against the wall");
                assertEquals(statue.wallZ() + MansionLayout.STATUE_WALL_BEHIND_FACE, statue.faceZ(),
                        where + "the face is not three blocks in front of the wall");

                // The vault: south shell layer on the wall line, everything else behind it.
                BlockPos shellMin = statue.chamberShellMin();
                BlockPos shellMax = statue.chamberShellMax();
                assertEquals(statue.wallZ(), shellMax.getZ(), where + "the vault is not seated on the wall");
                assertEquals(MansionLayout.STATUE_VAULT_DEPTH, shellMax.getZ() - shellMin.getZ() + 1,
                        where + "the vault is not wall row + cavity + shell deep");
                assertTrue(shellMax.getZ() < roomMinZ,
                        where + "the vault is not entirely behind the wall line");
            }
        }
    }

    @Test
    void theVaultIsEntirelyBehindTheWall() {
        for (RaidDifficulty difficulty : RaidDifficulty.values()) {
            for (int index = 0; index < SEEDS; index++) {
                MansionLayout layout = plan(difficulty, index);
                MansionLayout.Statue statue = layout.statue();
                assertNotNull(statue);
                String where = difficulty + " seed " + index + " " + statue.room() + ": ";
                MansionLayout.Floor floor = layout.floor(statue.layerY());
                assertNotNull(floor, where + "no walkable plane for the statue's storey");

                // Every cell the vault reaches into has to be one the plan never paves — the mansion's
                // own shell. That is the whole reason the room was picked.
                int northCZ = statue.room().minCZ() - 1;
                int cellWest = layout.cellX(statue.chamberShellMin().getX());
                int cellEast = layout.cellX(statue.chamberShellMax().getX());
                for (int cell = cellWest; cell <= cellEast; cell++) {
                    assertFalse(floor.isWalkable(cell, northCZ),
                            where + "the vault opens into walkable cell (" + cell + "," + northCZ + ")");
                }

                // And the statue itself has to stay inside the room's perimeter walls.
                int interiorMaxX = layout.blockMaxX(statue.room()) - 1;
                int interiorMaxZ = layout.blockMaxZ(statue.room()) - 1;
                assertTrue(statue.templateMinX() >= layout.blockMinX(statue.room()),
                        where + "the template pokes through the west wall");
                assertTrue(statue.templateMaxX() <= interiorMaxX,
                        where + "the template pokes through the east wall");
                assertTrue(statue.templateMaxZ() <= interiorMaxZ,
                        where + "the template pokes through the south wall");
                assertTrue(statue.templateMaxZ() < interiorMaxZ - 1,
                        where + "no floor left in front of the face to reach the crank");

                // The face is the template's own z = 2 plane and the eyes its own y = 4 row.
                assertEquals(4, statue.eyeY() - statue.templateMinY(), where + "eye row moved");
                assertEquals(statue.templateMinZ() + MansionLayout.STATUE_FACE_LOCAL_Z, statue.faceZ(),
                        where + "the face plane is not the template's");
            }
        }
    }

    @Test
    void theVaultHasExactlyOneOpeningAndItIsTheNose() {
        for (RaidDifficulty difficulty : RaidDifficulty.values()) {
            for (int index = 0; index < SEEDS; index++) {
                MansionLayout layout = plan(difficulty, index);
                MansionLayout.Statue statue = layout.statue();
                assertNotNull(statue);
                String where = difficulty + " seed " + index + " " + statue.room() + ": ";

                BlockPos min = statue.chamberShellMin();
                BlockPos max = statue.chamberShellMax();
                int[] openings = new int[Direction.values().length];
                int mouth = 0;
                List<BlockPos> mouthCells = new ArrayList<>();
                for (int x = min.getX(); x <= max.getX(); x++) {
                    for (int y = min.getY(); y <= max.getY(); y++) {
                        for (int z = min.getZ(); z <= max.getZ(); z++) {
                            boolean interior = x > min.getX() && x < max.getX()
                                    && y > min.getY() && y < max.getY()
                                    && z > min.getZ() && z < max.getZ();
                            if (interior || !statue.isTunnelMouth(x, y, z)) {
                                continue;
                            }
                            mouth++;
                            mouthCells.add(new BlockPos(x, y, z));
                            openings[faceOf(min, max, x, y, z).ordinal()]++;
                        }
                    }
                }
                assertEquals(MansionLayout.STATUE_TUNNEL_WIDTH * MansionLayout.STATUE_TUNNEL_HEIGHT, mouth,
                        where + "the vault's openings are not exactly the crawl tunnel");
                assertEquals(mouth, openings[Direction.SOUTH.ordinal()],
                        where + "something other than the tunnel's mouth is open");
                for (Direction face : Direction.values()) {
                    if (face != Direction.SOUTH) {
                        assertEquals(0, openings[face.ordinal()],
                                where + "the vault's " + face + " face is open");
                    }
                }
                // The mouth is punched through the room's own wall row, so the builder has to clear the
                // vanilla wall block standing there: skipping the fill is not enough, or the nose would
                // swing aside onto masonry. (Found the hard way in the first R24 live run.)
                for (BlockPos cell : mouthCells) {
                    assertEquals(statue.wallZ(), cell.getZ(),
                            where + "the mouth is not on the wall row: " + cell);
                }
                assertEquals(statue.wallZ(), max.getZ(),
                        where + "the vault's south shell layer is not the room's wall row");

                // The cavity is a 3×3×3 air box whose floor carries the run's guaranteed chest and the
                // boss portal (decisions R11 / R7), both inside it and on the floor.
                assertEquals(MansionLayout.STATUE_DARK_ROOM,
                        statue.chamberMaxX() - statue.chamberMinX() + 1, where + "chamber width");
                assertEquals(MansionLayout.STATUE_DARK_ROOM,
                        statue.chamberMaxY() - statue.chamberMinY() + 1, where + "chamber height");
                assertEquals(MansionLayout.STATUE_DARK_ROOM,
                        statue.chamberMaxZ() - statue.chamberMinZ() + 1, where + "chamber depth");
                BlockPos treasure = statue.treasure();
                BlockPos portal = statue.bossPortal();
                for (BlockPos pos : List.of(treasure, portal)) {
                    assertTrue(pos.getX() >= statue.chamberMinX() && pos.getX() <= statue.chamberMaxX()
                                    && pos.getZ() >= statue.chamberMinZ()
                                    && pos.getZ() <= statue.chamberMaxZ()
                                    && pos.getY() == statue.chamberMinY(),
                            where + "a vault fixture is outside the cavity: " + pos);
                }
                assertFalse(treasure.equals(portal), where + "the chest stands on the portal");
            }
        }
    }

    /**
     * The selection rule of spec §5.6.2: a room whose wall has nothing but the mansion's shell behind
     * it wins, and while the entrance storey has one, the statue stays there — that is what keeps the
     * boss portal on the floor a raider reaches first.
     */
    @Test
    void theWalledRoomOnTheEntranceStoreyWins() {
        int entranceStorey = 0;
        for (RaidDifficulty difficulty : RaidDifficulty.values()) {
            for (int index = 0; index < SEEDS; index++) {
                MansionLayout layout = plan(difficulty, index);
                MansionLayout.Statue statue = layout.statue();
                assertNotNull(statue);
                MansionLayout.Floor floor = layout.floor(MansionLayout.layerY(0));
                boolean walled = false;
                for (MansionLayout.Room room : layout.rooms()) {
                    if (room.layerY() != MansionLayout.layerY(0)
                            || room.type() == MansionLayout.RoomType.ENTRANCE) {
                        continue;
                    }
                    walled = walled || hasWalledNorthFace(layout, floor, room);
                }
                if (walled) {
                    assertEquals(MansionLayout.layerY(0), statue.layerY(),
                            difficulty + " seed " + index
                                    + " left the entrance storey although a walled room was there");
                }
                if (statue.layerY() == MansionLayout.layerY(0)) {
                    entranceStorey++;
                }
            }
        }
        // The generator is kind enough that this is the normal case, not a coincidence: if it ever
        // stopped being true the fallback would be doing the work and this test should be revisited.
        assertTrue(entranceStorey > SEEDS,
                "the statue left the entrance storey far too often: " + entranceStorey);
    }

    /** Whether {@code room} has a north face the vault can be carved into without touching a room. */
    private static boolean hasWalledNorthFace(MansionLayout layout, MansionLayout.Floor floor,
                                              MansionLayout.Room room) {
        int roomMinX = layout.blockMinX(room.cells());
        int roomMaxX = layout.blockMaxX(room.cells());
        int interiorMaxX = roomMaxX - 1;
        int northCZ = room.cells().minCZ() - 1;
        List<Integer> centres = new ArrayList<>();
        if (room.width() >= 2) {
            centres.add(roomMinX + 7);
            centres.add(roomMinX + 3);
            centres.add(roomMinX + 11);
        } else {
            centres.add(roomMinX + 3);
            centres.add(roomMinX + 2);
        }
        for (int centreX : centres) {
            int minX = centreX - MansionLayout.STATUE_TUNNEL_LOCAL_X;
            int maxX = minX + MansionLayout.STATUE_WIDTH - 1;
            if (minX < roomMinX || maxX > interiorMaxX) {
                continue;
            }
            boolean open = false;
            for (int cell = layout.cellX(minX); cell <= layout.cellX(centreX + 2); cell++) {
                open = open || floor.isWalkable(cell, northCZ);
            }
            if (!open) {
                return true;
            }
        }
        return false;
    }

    /** Which face of the shell box {@code (x,y,z)} sits on. */
    private static Direction faceOf(BlockPos min, BlockPos max, int x, int y, int z) {
        if (x == min.getX()) {
            return Direction.WEST;
        }
        if (x == max.getX()) {
            return Direction.EAST;
        }
        if (y == min.getY()) {
            return Direction.DOWN;
        }
        if (y == max.getY()) {
            return Direction.UP;
        }
        return z == min.getZ() ? Direction.NORTH : Direction.SOUTH;
    }

    private static MansionLayout plan(RaidDifficulty difficulty, int index) {
        return MansionLayoutBuilder.build(difficulty, 20261002L + index * 7919L, CENTRE_X);
    }
}
