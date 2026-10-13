package com.homemine.stealindims.raid;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;

/**
 * The floor plan of a mansion raid map: pure data, no world, no blocks (design §4.1, plan §P1.1).
 *
 * <p>A mansion is generated on a coarse grid of {@value #CELL_SIZE} block cells, exactly like the
 * vanilla woodland mansion. One {@link Cells} rectangle is therefore 8×8 blocks at the minimum and
 * {@code 32×24} at the maximum the generator will hand out, which is what lets rooms be "any
 * rectangle" instead of the vanilla 1×1 / 1×2 / 2×2 (design §3.2).
 *
 * <p>Everything a builder, a trap deployer or a spawner needs is answered from here, and every
 * coordinate is already in <em>block</em> space once the plan is placed: the generator centres the
 * plan on {@code mapCenterX} and folds that offset into {@link #originX()} / {@link #originZ()}, so
 * {@link #blockMinX} and friends are the final world coordinates.
 *
 * <p>Floors are stored as their raw coarse grid plus the packed {@link Room} list, because two
 * different questions have to be answered from the same plan: "which cells are walkable" (the
 * builder paves and walls those) and "which room is this" (the spawner and the trap pass want the
 * rectangles). Leftover walkable cells that no room claimed are open halls — they read as the
 * mansion's wide passages and are never walled off.
 *
 * @see MansionLayoutBuilder
 * @see MansionGeometry
 */
public final class MansionLayout {

    /** One coarse cell is this many blocks across; the vanilla mansion uses the same number. */
    public static final int CELL_SIZE = 8;

    /**
     * Width in blocks of a doorway's opening. It is centred on the stretch of wall two neighbours
     * share and never reaches a room's corner, because a hole in a corner is walled in on both
     * orthogonal sides and a player cannot walk through it (the P4 defect: every room was sealed).
     */
    public static final int DOOR_SPAN = 3;

    /** Vertical distance between two vanilla storeys below the third; the third is offset further. */
    public static final int FLOOR_GAP = 8;

    /**
     * Where each storey's <em>walking surface</em> sits above {@link #MAIN_LAYER_Y}, in blocks.
     *
     * <p>The real mansion templates are authored for vanilla's own storey spacing,
     * {@code pos.above(8 * floor + (floor == 2 ? 3 : 0))}: the second storey is 8 blocks up and the
     * third is <strong>19</strong>, not 16. P5 keeps that spacing exactly, so the raid layers are
     * {@code 64 / 72 / 83} (plan §2.3). A template placed on layer {@code i} has its own floor block
     * one below the surface, which is why the builder raises the whole mansion from {@code y = 63}.
     */
    public static final int[] LAYER_OFFSETS = {0, FLOOR_GAP, 19};

    /** How many storeys the physical building has. The templates always raise all three. */
    public static final int PHYSICAL_FLOORS = LAYER_OFFSETS.length;

    /** Walkable layer of the entrance floor, shared with the extraction room. */
    public static final int MAIN_LAYER_Y = 64;

    /**
     * Extra blocks {@link #mapRadius()} keeps clear around the outermost walkable cell. It has to be
     * generous now that the mansion is built from templates: the outer walls stand up to two blocks
     * outside a walkable cell and the eaves reach four more, and the entrance template hangs nine
     * blocks west of the first cell.
     */
    private static final int EDGE_MARGIN = 12;

    /**
     * The user's own {@code illager_statue.nbt} is 6 wide, 8 tall and 4 deep (spec §5.4, R23). It is
     * stamped verbatim — no block of it is ever rewritten — so these numbers only describe where it
     * lands, never what it contains.
     */
    public static final int STATUE_WIDTH = 6;
    public static final int STATUE_HEIGHT = 8;
    public static final int STATUE_DEPTH = 4;
    /** Inside that template the pixel face is the {@code z = 2} plane and the glued nose the {@code z = 3}. */
    public static final int STATUE_FACE_LOCAL_Z = 2;
    /** Inside the template the crawl tunnel runs along {@code x = 2..3} at {@code y = 0..2}. */
    public static final int STATUE_TUNNEL_LOCAL_X = 2;
    public static final int STATUE_TUNNEL_WIDTH = 2;
    public static final int STATUE_TUNNEL_HEIGHT = 3;
    /** Interior edge of the dark room sealed behind that tunnel (decision R11). */
    public static final int STATUE_DARK_ROOM = 3;
    /** How far the eye line sits above the floor: the template's {@code lime_wool} eyes are on y = 4. */
    public static final int STATUE_EYE_RISE = 4;
    /**
     * How far behind the pixel face the room's own wall line sits, in blocks (R24).
     *
     * <p>The template's face is its {@code z = 2} plane and its back plane is {@code z = 0}, so the
     * wall the statue leans on is the block right behind {@code z = 0} — three blocks behind the face.
     * R24 places the template's back <em>on</em> the room's interior edge, which puts the wall on
     * {@link Statue#wallZ()} and carves the vault out of that wall and the masonry behind it. R23 kept
     * a one-block gap here ({@code STATUE_SETBACK}) and hung the vault on the room's inside, which is
     * exactly the "box in the middle of the room" the user rejected.
     */
    public static final int STATUE_WALL_BEHIND_FACE = 3;
    /**
     * How deep the vault reaches behind the wall line: one wall row plus the 3×3×3 cavity plus its own
     * one-block shell. Everything past the wall row is masonry the room never sees (R24 §5.6.2).
     */
    public static final int STATUE_VAULT_DEPTH = 1 + STATUE_DARK_ROOM + 1;

    /** Coarse grid values; only {@link #CORRIDOR}, {@link #ROOM} and {@link #START} are walkable. */
    public static final int CLEAR = 0;
    public static final int CORRIDOR = 1;
    public static final int ROOM = 2;
    public static final int START = 3;
    public static final int BLOCKED = 5;

    /** What a packed room is for. The type biases its contents, never its shape. */
    public enum RoomType {
        ENTRANCE,
        HALL,
        CORRIDOR,
        GUEST,
        LIBRARY,
        DINING,
        COUNCIL,
        CELL,
        STORAGE,
        COURTYARD,
        STAIRWELL,
        STATUE,
        SUBMISSION;

        public String id() {
            return this.name().toLowerCase(Locale.ROOT);
        }

        @Nullable
        public static RoomType byId(String id) {
            for (RoomType type : values()) {
                if (type.id().equals(id)) {
                    return type;
                }
            }
            return null;
        }
    }

    /** An inclusive rectangle of coarse cells. Block rectangles are derived through the layout. */
    public record Cells(int minCX, int minCZ, int maxCX, int maxCZ) {

        public int width() {
            return this.maxCX - this.minCX + 1;
        }

        public int depth() {
            return this.maxCZ - this.minCZ + 1;
        }

        public int area() {
            return width() * depth();
        }

        public boolean contains(int cx, int cz) {
            return cx >= this.minCX && cx <= this.maxCX && cz >= this.minCZ && cz <= this.maxCZ;
        }

        public int centerCX() {
            return (this.minCX + this.maxCX) / 2;
        }

        public int centerCZ() {
            return (this.minCZ + this.maxCZ) / 2;
        }

        /** A single cell. */
        public static Cells of(int cx, int cz) {
            return new Cells(cx, cz, cx, cz);
        }
    }

    /** One packed room on one layer. {@code cells} is inclusive and already covers whole cells. */
    public record Room(Cells cells, int layerY, RoomType type) {

        public int width() {
            return this.cells.width();
        }

        public int depth() {
            return this.cells.depth();
        }

        public int area() {
            return this.cells.area();
        }

        public boolean isRoofed() {
            return this.type != RoomType.COURTYARD;
        }

        public Room withType(RoomType newType) {
            return new Room(this.cells, this.layerY, newType);
        }
    }

    /**
     * One layer's coarse grid, cropped to the cells it actually uses.
     *
     * @param cells row-major, {@code (cz - minCZ) * width + (cx - minCX)}
     */
    public record Floor(int layerY, int minCX, int minCZ, int width, int depth, int[] cells) {

        public int at(int cx, int cz) {
            if (cx < this.minCX || cz < this.minCZ
                    || cx >= this.minCX + this.width || cz >= this.minCZ + this.depth) {
                return BLOCKED;
            }
            return this.cells[(cz - this.minCZ) * this.width + (cx - this.minCX)];
        }

        public boolean isWalkable(int cx, int cz) {
            int value = at(cx, cz);
            return value == CORRIDOR || value == ROOM || value == START;
        }

        public int maxCX() {
            return this.minCX + this.width - 1;
        }

        public int maxCZ() {
            return this.minCZ + this.depth - 1;
        }
    }

    /**
     * A doorway punched through a room's own wall: the block column (or row) that has to stay open
     * for two blocks above the layer's floor. Stored in block coordinates.
     */
    public record Door(int layerY, int minX, int maxX, int minZ, int maxZ) {

        public boolean covers(int x, int z) {
            return x >= this.minX && x <= this.maxX && z >= this.minZ && z <= this.maxZ;
        }

        /** Chebyshev distance from {@code (x, z)} to the opening, used to keep traps and loot out. */
        public int distance(int x, int z) {
            int dx = Math.max(this.minX - x, x - this.maxX);
            int dz = Math.max(this.minZ - z, z - this.maxZ);
            return Math.max(dx, dz);
        }
    }

    /** A stairwell shaft burned through two layers at one coarse cell. */
    public record Shaft(Cells cells, int lowerY, int upperY) {
    }

    /** The cell a submission point lives in; crate and anchor are derived from it. */
    public record Submission(Cells cells, int layerY) {
    }

    /**
     * The illager statue room, and the address of the user's own {@code illager_statue.nbt} inside it
     * (design §3.4 decision R12, spec §5.4 R23, spec §5.6 R24).
     *
     * <p>The statue is no longer painted block by block: {@code data/stealindims/structure/
     * illager_statue.nbt} — 6×8×4, 59 blocks and one {@code create:super_glue} entity — is stamped
     * exactly as its author saved it, at {@link #templateOrigin()} with no rotation and no mirror, so
     * the wool pixel face looks south into the room. Nothing in this record changes that template; it
     * only says where the stamp goes and where the parts around it belong.
     *
     * <p>Inside the template (all local coordinates):
     *
     * <ul>
     *     <li>the pixel face is the {@code z = 2} plane — {@code light_gray_wool} field,
     *     {@code gray_wool} outline, {@code white_wool} temples and two {@code lime_wool} eyes on
     *     {@code y = 4};</li>
     *     <li>the glued nose contraption is the {@code z = 3} plane, a 2×4 gray slab the
     *     {@code mechanical_bearing} at {@code (2,3,2)} swings aside;</li>
     *     <li>a 2×3 crawl tunnel runs from the face straight through the back at {@code x = 2..3},
     *     {@code y = 0..2} — it is the only way into the vault, and it is plugged by the nose.</li>
     * </ul>
     *
     * <p><b>R24: the statue backs onto the room's wall, not the room's middle.</b> The template's back
     * plane ({@code z = 0}) is put on the room's first interior block row, so the room's own wall on
     * {@link #wallZ()} is the block its back touches and the vault is carved out of that wall and the
     * solid mass behind it. From the room only the pixel face is visible: the vault's masonry sits one
     * block behind the wall line, and the shell's single opening — the crawl tunnel's mouth — is
     * plugged by the nose. The origin therefore lands on the tunnel's west column two blocks south of
     * the wall: {@code (centreX - 2, layerY, wallZ + 3)}, with the face on {@code wallZ + 3}.
     *
     * <p>The vault reads north to south as {@code [shell 1][cavity 3][shell 1 = the wall line]}, and the
     * 6×8×4 template stands in front of it inside the room, its own back plane flush with the wall.
     */
    public record Statue(Cells room, int layerY, int centerX, int eyeY, int faceZ) {

        /** The world position the template's own {@code (0,0,0)} corner is stamped at. */
        public BlockPos templateOrigin() {
            return new BlockPos(templateMinX(), templateMinY(), templateMinZ());
        }

        /**
         * The block row of the room's own wall: the plane the statue's back leans on (R24).
         *
         * <p>It is also the vault's south shell layer, so the wall there carries the crawl tunnel's
         * mouth. The room's interior starts one block further south, on {@link #templateMinZ()}.
         */
        public int wallZ() {
            return this.faceZ - STATUE_WALL_BEHIND_FACE;
        }

        public int templateMinX() {
            return this.centerX - STATUE_TUNNEL_LOCAL_X;
        }

        public int templateMaxX() {
            return templateMinX() + STATUE_WIDTH - 1;
        }

        public int templateMinY() {
            return this.layerY;
        }

        public int templateMaxY() {
            return templateMinY() + STATUE_HEIGHT - 1;
        }

        /** The template's back plane: the tunnel's north mouth, one block south of the dark room. */
        public int templateMinZ() {
            return this.faceZ - STATUE_FACE_LOCAL_Z;
        }

        /** The front plane: the glued nose contraption swings in front of the face. */
        public int templateMaxZ() {
            return templateMinZ() + STATUE_DEPTH - 1;
        }

        /** The pixel face's own columns; they are the template's, since the face spans its full width. */
        public int faceMinX() {
            return templateMinX();
        }

        public int faceMaxX() {
            return templateMaxX();
        }

        public int faceMinY() {
            return this.layerY;
        }

        public int faceMaxY() {
            return templateMaxY();
        }

        /** The crawl tunnel the nose plugs: the same two columns the template leaves open. */
        public int tunnelMinX() {
            return this.centerX;
        }

        public int tunnelMaxX() {
            return this.centerX + STATUE_TUNNEL_WIDTH - 1;
        }

        public int tunnelMinY() {
            return this.layerY;
        }

        public int tunnelMaxY() {
            return this.layerY + STATUE_TUNNEL_HEIGHT - 1;
        }

        /** The dark room's carved-out interior: 3×3×3, directly behind the tunnel. */
        public int chamberMinX() {
            return this.centerX - 1;
        }

        public int chamberMaxX() {
            return chamberMinX() + STATUE_DARK_ROOM - 1;
        }

        public int chamberMinY() {
            return this.layerY;
        }

        public int chamberMaxY() {
            return chamberMinY() + STATUE_DARK_ROOM - 1;
        }

        public int chamberMinZ() {
            return templateMinZ() - STATUE_DARK_ROOM - 1;
        }

        public int chamberMaxZ() {
            return templateMinZ() - 2;
        }

        /** The dark room's shell: one block of masonry on every side but the tunnel mouth. */
        public BlockPos chamberShellMin() {
            return new BlockPos(chamberMinX() - 1, chamberMinY() - 1, chamberMinZ() - 1);
        }

        public BlockPos chamberShellMax() {
            return new BlockPos(chamberMaxX() + 1, chamberMaxY() + 1, chamberMaxZ() + 1);
        }

        /** Whether the shell block at {@code (x,y,z)} is the mouth the tunnel comes through. */
        public boolean isTunnelMouth(int x, int y, int z) {
            return z == chamberMaxZ() + 1
                    && x >= tunnelMinX() && x <= tunnelMaxX()
                    && y >= tunnelMinY() && y <= tunnelMaxY();
        }

        /** The bearing the template carries at the face's centre, in front of the nose. */
        public BlockPos bearingPos() {
            return new BlockPos(this.centerX, this.layerY + 3, this.faceZ);
        }

        /** The template's own {@code create:hand_crank}, at the face's east edge on the floor. */
        public BlockPos crankPos() {
            return new BlockPos(this.centerX + 2, this.layerY, this.faceZ + 1);
        }

        /** Where the boss portal stands, inside the dark room (decision R7). */
        public BlockPos bossPortal() {
            return new BlockPos(chamberMinX(), this.layerY, chamberMinZ() + 1);
        }

        /** Where the one guaranteed purple-or-gold chest stands, inside the dark room (R11). */
        public BlockPos treasure() {
            return new BlockPos(chamberMaxX(), this.layerY, chamberMinZ() + 1);
        }
    }

    private final int mapCenterX;
    private final RaidDifficulty difficulty;
    private final long seed;
    private final List<Integer> layerYs;
    private final List<Floor> floors;
    private final List<Room> rooms;
    private final List<Door> doors;
    private final List<Shaft> shafts;
    private final List<Submission> submissions;
    private final Cells bounds;
    private final int originX;
    private final int originZ;
    private final int mapRadius;
    private final Map<Integer, Boolean> connected;
    private final Map<Integer, Boolean> repaired;
    private final Set<Long> safeCells;
    private final List<VanillaMansionPieces.Piece> pieces;
    private final int gridSize;
    private final int deadEnds;
    private final int lots;
    private final BlockPos spawn;
    private final BlockPos anchor;
    @Nullable
    private final Statue statue;

    MansionLayout(int mapCenterX, RaidDifficulty difficulty, long seed, List<Integer> layerYs,
                  List<Floor> floors, List<Room> rooms, List<Door> doors, List<Shaft> shafts,
                  List<Submission> submissions, Cells bounds, Map<Integer, Boolean> connected,
                  Set<Long> safeCells, @Nullable Statue statue, Map<Integer, Boolean> repaired,
                  List<VanillaMansionPieces.Piece> pieces, int gridSize, int deadEnds, int lots,
                  BlockPos spawn, BlockPos anchor) {
        this.mapCenterX = mapCenterX;
        this.difficulty = difficulty;
        this.seed = seed;
        this.layerYs = List.copyOf(layerYs);
        this.floors = List.copyOf(floors);
        this.rooms = List.copyOf(rooms);
        this.doors = List.copyOf(doors);
        this.shafts = List.copyOf(shafts);
        this.submissions = List.copyOf(submissions);
        this.bounds = bounds;
        this.connected = Map.copyOf(connected);
        this.repaired = Map.copyOf(repaired);
        this.safeCells = Set.copyOf(safeCells);
        this.statue = statue;
        this.pieces = List.copyOf(pieces);
        this.gridSize = gridSize;
        this.deadEnds = deadEnds;
        this.lots = lots;
        this.spawn = spawn;
        this.anchor = anchor;

        int localMinX = bounds.minCX() * CELL_SIZE;
        int localMaxX = bounds.maxCX() * CELL_SIZE + CELL_SIZE - 1;
        int localMinZ = bounds.minCZ() * CELL_SIZE;
        int localMaxZ = bounds.maxCZ() * CELL_SIZE + CELL_SIZE - 1;
        this.originX = originX(mapCenterX, bounds);
        this.originZ = originZ(bounds);
        int halfW = Math.max(Math.abs(localMinX + this.originX - mapCenterX),
                Math.abs(localMaxX + this.originX - mapCenterX));
        int halfD = Math.max(Math.abs(localMinZ + this.originZ),
                Math.abs(localMaxZ + this.originZ));
        this.mapRadius = Math.max(halfW, halfD) + EDGE_MARGIN;
    }

    /** Every vanilla template the builder has to place, already translated into world coordinates. */
    public List<VanillaMansionPieces.Piece> pieces() {
        return this.pieces;
    }

    /** The vanilla coarse grid's edge length: C 9 / B 11 / A 13 / S 15 (P5 §2.4). */
    public int gridSize() {
        return this.gridSize;
    }

    /** How many corridor stubs the R25 growth left leading nowhere (decision E3). */
    public int deadEnds() {
        return this.deadEnds;
    }

    /** How many of the building's edge cells were left as rubble and low wall stumps. */
    public int lots() {
        return this.lots;
    }

    // ---------------------------------------------------------------- difficulty recipe

    /** How many walkable layers a difficulty gets (design §3.7, decision R4). */
    public static int floorsFor(RaidDifficulty difficulty) {
        return switch (difficulty) {
            case C -> 1;
            case B, A -> 2;
            case S -> 3;
        };
    }

    /** How many submission points one layer carries (design §3.6, decisions R3 + R4). */
    public static int submissionsPerFloor(RaidDifficulty difficulty) {
        return difficulty == RaidDifficulty.C ? 2 : 1;
    }

    /** The walkable Y of layer {@code index}, counting up from the entrance floor. */
    public static int layerY(int index) {
        return MAIN_LAYER_Y + LAYER_OFFSETS[Math.min(Math.max(index, 0), LAYER_OFFSETS.length - 1)];
    }

    /** Every difficulty's layer count, so a test can pin 1 / 2 / 2 / 3 without a world. */
    public static List<Integer> layers() {
        List<Integer> ys = new ArrayList<>();
        for (RaidDifficulty difficulty : RaidDifficulty.values()) {
            int count = floorsFor(difficulty);
            List<Integer> one = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                one.add(layerY(i));
            }
            ys.addAll(one);
        }
        return List.copyOf(ys);
    }

    // ---------------------------------------------------------------- identity

    public int mapCenterX() {
        return this.mapCenterX;
    }

    public RaidDifficulty difficulty() {
        return this.difficulty;
    }

    public long seed() {
        return this.seed;
    }

    /** The walkable layers, top first — the order {@link RaidGeometry#layerYs()} asks for. */
    public List<Integer> layerYs() {
        List<Integer> top = new ArrayList<>(this.layerYs);
        java.util.Collections.reverse(top);
        return List.copyOf(top);
    }

    /** The walkable layers, bottom first, which is the order the builder raises them in. */
    public List<Integer> layerYsBottomUp() {
        return this.layerYs;
    }

    public List<Floor> floors() {
        return this.floors;
    }

    @Nullable
    public Floor floor(int layerY) {
        for (Floor floor : this.floors) {
            if (floor.layerY() == layerY) {
                return floor;
            }
        }
        return null;
    }

    public List<Room> rooms() {
        return this.rooms;
    }

    public List<Room> roomsAt(int layerY) {
        List<Room> at = new ArrayList<>();
        for (Room room : this.rooms) {
            if (room.layerY() == layerY) {
                at.add(room);
            }
        }
        return List.copyOf(at);
    }

    public List<Door> doors() {
        return this.doors;
    }

    public List<Door> doorsAt(int layerY) {
        List<Door> at = new ArrayList<>();
        for (Door door : this.doors) {
            if (door.layerY() == layerY) {
                at.add(door);
            }
        }
        return List.copyOf(at);
    }

    public List<Shaft> shafts() {
        return this.shafts;
    }

    public List<Submission> submissions() {
        return this.submissions;
    }

    /** The illager statue room's geometry, or {@code null} if this plan has none. */
    @Nullable
    public Statue statue() {
        return this.statue;
    }

    public Cells bounds() {
        return this.bounds;
    }

    /** Whether layer {@code layerY}'s walkable cells form one connected component (risk R-b). */
    public boolean isConnected(int layerY) {
        return Boolean.TRUE.equals(this.connected.get(layerY));
    }

    /**
     * Whether the layer only came out in one piece because the generator carved a connecting path
     * after its re-rolls ran out.
     *
     * <p>This is the flag the acceptance test watches: a layer that needed repairing is a layout the
     * generator could not solve on its own, and the design asks for zero of those (risk R-b).
     */
    public boolean isRepaired(int layerY) {
        return Boolean.TRUE.equals(this.repaired.get(layerY));
    }

    public int originX() {
        return this.originX;
    }

    public int originZ() {
        return this.originZ;
    }

    public int mapRadius() {
        return this.mapRadius;
    }

    // ---------------------------------------------------------------- cell <-> block

    /**
     * The block X of cell {@code bounds.minCX()}: the plan is centred on {@code mapCenterX}, so the
     * mansion straddles its map centre the way the four-region layouts straddle
     * {@code RaidArena.MAP_OFFSET_X}.
     */
    public static int originX(int mapCenterX, Cells bounds) {
        int localMinX = bounds.minCX() * CELL_SIZE;
        int localMaxX = bounds.maxCX() * CELL_SIZE + CELL_SIZE - 1;
        return mapCenterX - (localMinX + localMaxX) / 2;
    }

    /** The block Z of cell {@code bounds.minCZ()}: the plan is centred on Z = 0. */
    public static int originZ(Cells bounds) {
        int localMinZ = bounds.minCZ() * CELL_SIZE;
        int localMaxZ = bounds.maxCZ() * CELL_SIZE + CELL_SIZE - 1;
        return -(localMinZ + localMaxZ) / 2;
    }

    public int blockMinX(Cells cells) {
        return this.originX + cells.minCX() * CELL_SIZE;
    }

    public int blockMaxX(Cells cells) {
        return this.originX + cells.maxCX() * CELL_SIZE + CELL_SIZE - 1;
    }

    public int blockMinZ(Cells cells) {
        return this.originZ + cells.minCZ() * CELL_SIZE;
    }

    public int blockMaxZ(Cells cells) {
        return this.originZ + cells.maxCZ() * CELL_SIZE + CELL_SIZE - 1;
    }

    public int cellX(int blockX) {
        return Math.floorDiv(blockX - this.originX, CELL_SIZE);
    }

    public int cellZ(int blockZ) {
        return Math.floorDiv(blockZ - this.originZ, CELL_SIZE);
    }

    /** Centre of a room in block coordinates, on the room's own layer. */
    public BlockPos roomCenter(Room room) {
        int x = (blockMinX(room.cells()) + blockMaxX(room.cells())) / 2;
        int z = (blockMinZ(room.cells()) + blockMaxZ(room.cells())) / 2;
        return new BlockPos(x, room.layerY(), z);
    }

    // ---------------------------------------------------------------- landmarks

    @Nullable
    public Room roomOfType(RoomType type) {
        for (Room room : this.rooms) {
            if (room.type() == type) {
                return room;
            }
        }
        return null;
    }

    /** Where the player lands when entering the mansion: the middle of the vanilla entrance hall. */
    public BlockPos spawn() {
        return this.spawn;
    }

    /**
     * The block the map anchor stands on: a corner of the entrance hall rather than its middle, so
     * the anchor never stands in the way of anyone walking in from the spawn point.
     */
    public BlockPos anchor() {
        return this.anchor;
    }

    /** The transit crate of submission point {@code index}: what {@code submissionPoints()} lists. */
    public BlockPos submissionCrate(int index) {
        Submission submission = this.submissions.get(index);
        int x = (blockMinX(submission.cells()) + blockMaxX(submission.cells())) / 2;
        int z = (blockMinZ(submission.cells()) + blockMaxZ(submission.cells())) / 2;
        return new BlockPos(x, submission.layerY(), z);
    }

    /** The return anchor of submission point {@code index}, two blocks off the crate. */
    public BlockPos submissionAnchor(int index) {
        Submission submission = this.submissions.get(index);
        int x = (blockMinX(submission.cells()) + blockMaxX(submission.cells())) / 2;
        int z = (blockMinZ(submission.cells()) + blockMaxZ(submission.cells())) / 2 + 2;
        int maxZ = blockMaxZ(submission.cells()) - 1;
        return new BlockPos(x, submission.layerY(), Math.min(z, maxZ));
    }

    /** Every place a run can be submitted from, on every layer. */
    public List<BlockPos> submissionPoints() {
        List<BlockPos> points = new ArrayList<>(this.submissions.size());
        for (int index = 0; index < this.submissions.size(); index++) {
            points.add(submissionCrate(index));
        }
        return List.copyOf(points);
    }

    /** Every anchor that walks a raider back to the extraction room. */
    public List<BlockPos> anchorPoints() {
        List<BlockPos> points = new ArrayList<>(this.submissions.size());
        for (int index = 0; index < this.submissions.size(); index++) {
            points.add(submissionAnchor(index));
        }
        return List.copyOf(points);
    }

    /**
     * Whether {@code pos} is a safe zone: a submission room, or the entrance hall. Traps and patrols
     * are kept out of both (design §3.1, plan §P1.4).
     */
    public boolean isInSafeZone(BlockPos pos) {
        int cx = cellX(pos.getX());
        int cz = cellZ(pos.getZ());
        if (!this.safeCells.contains(cellKey(cx, cz))) {
            return false;
        }
        for (Floor floor : this.floors) {
            if (pos.getY() >= floor.layerY() && pos.getY() <= floor.layerY() + 4
                    && floor.isWalkable(cx, cz)) {
                return true;
            }
        }
        return false;
    }

    static long cellKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFF_FFFFL);
    }
}
