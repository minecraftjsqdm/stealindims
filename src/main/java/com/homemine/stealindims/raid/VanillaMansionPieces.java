package com.homemine.stealindims.raid;

import com.google.common.collect.Lists;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Tuple;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/**
 * The vanilla woodland-mansion generator, ported verbatim from
 * {@code net.minecraft.world.level.levelgen.structure.structures.WoodlandMansionPieces} (MC 1.21.1).
 *
 * <p>P5 puts the real mansion templates into the raid map, and the only way to lay them out the way
 * vanilla does is to run vanilla's own generator. The port exists instead of a call into the vanilla
 * class for three reasons:
 *
 * <ol>
 *     <li>vanilla {@code generateMansion} needs a
 *     {@code net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager}
 *     only to hand it to each piece; a port has no such dependency and therefore runs on a bare JVM,
 *     which is what lets the layout be unit-tested at all;</li>
 *     <li>vanilla's {@code MansionGrid} hard-codes an 11×11 grid, and P5 scales the map by grid size
 *     (C 9 / B 11 / A 13 / S 15);</li>
 *     <li>the raid builder wants a plain list of {@code (template, position, rotation, mirror)}, not
 *     a list of structure pieces.</li>
 * </ol>
 *
 * <p>Everything below the class header is the vanilla algorithm, unchanged apart from the four edits
 * listed in {@code _tmp/port_mansion.py}. Corridor growth, edge cleaning, room identification, the
 * third floor, the outer-wall walk, the roofs and every room placement are byte-for-byte vanilla, so
 * the result is a genuine woodland mansion and not an imitation.
 */
public final class VanillaMansionPieces {

    /** The grid vanilla ships with: 11×11 cells of 8 blocks, so an 88×88 block footprint. */
    public static final int DEFAULT_SIZE = 11;

    private VanillaMansionPieces() {
    }

    /** The radius, in cells, of the blocked ring vanilla keeps around a mansion. */
    private static int border(int size) {
        return size >= 11 ? 2 : 1;
    }

    /** The template position transform vanilla asks {@code StructureTemplate} for, inlined. */
    public static BlockPos zeroPositionWithTransform(BlockPos pos, Mirror mirror, Rotation rotation,
                                                     int sizeX, int sizeZ) {
        sizeX--;
        sizeZ--;
        int i = mirror == Mirror.FRONT_BACK ? sizeX : 0;
        int j = mirror == Mirror.LEFT_RIGHT ? sizeZ : 0;
        return switch (rotation) {
            case COUNTERCLOCKWISE_90 -> pos.offset(j, 0, sizeX - i);
            case CLOCKWISE_90 -> pos.offset(sizeZ - j, 0, i);
            case CLOCKWISE_180 -> pos.offset(sizeX - i, 0, sizeZ - j);
            case NONE -> pos.offset(i, 0, j);
        };
    }

    /** One template to place: exactly what {@code StructureTemplateManager} needs to be asked for. */
    public static final class Piece {

        public final String name;
        public final BlockPos pos;
        public final Rotation rotation;
        public final Mirror mirror;

        public Piece(String name, BlockPos pos, Rotation rotation) {
            this(name, pos, rotation, Mirror.NONE);
        }

        public Piece(String name, BlockPos pos, Rotation rotation, Mirror mirror) {
            this.name = name;
            this.pos = pos;
            this.rotation = rotation;
            this.mirror = mirror;
        }

        /** The full template id vanilla resolves the piece's name against. */
        public String templateId() {
            return "minecraft:woodland_mansion/" + this.name;
        }

        @Override
        public String toString() {
            return this.name + "@" + this.pos.toShortString() + "/" + this.rotation + "/" + this.mirror;
        }
    }

    /**
     * Vanilla's entry point, minus the template manager and with a selectable grid size.
     *
     * @return the grid the mansion was grown on, so the caller can read the walkable cells, the room
     *         ids and the entrance out of it instead of reverse-engineering them from the pieces
     */
    public static MansionGrid generateMansion(BlockPos pos, Rotation rotation, List<Piece> pieces,
                                              RandomSource random, int size) {
        VanillaMansionPieces.MansionGrid woodlandmansionpieces$mansiongrid =
                new VanillaMansionPieces.MansionGrid(random, size);
        VanillaMansionPieces.MansionPiecePlacer woodlandmansionpieces$mansionpieceplacer =
                new VanillaMansionPieces.MansionPiecePlacer(random);
        woodlandmansionpieces$mansionpieceplacer.createMansion(pos, rotation, pieces, woodlandmansionpieces$mansiongrid);
        return woodlandmansionpieces$mansiongrid;
    }

    /**
     * R25-E: the organic entry point.
     *
     * <p>The plan is the {@link OrganicMansionPlan}'s — rooms accreted one at a time across 1–2 cell gaps,
     * short bent corridors, dead ends and partial upper storeys — but the pieces are still laid out by
     * vanilla's own placer. Rooms, corridors, carpets, indoor walls and doors, the outer wall walk and
     * every roof section come out of the same templates and the same code paths a real woodland mansion
     * uses; only the shape of the grid underneath them changed.
     */
    public static MansionGrid generateOrganic(BlockPos pos, Rotation rotation, List<Piece> pieces,
                                              RandomSource random, OrganicMansionPlan.Plan plan) {
        MansionGrid grid = new MansionGrid(OrganicMansionPlan.GRID, plan.entranceX, plan.entranceY, plan);
        MansionPiecePlacer placer = new MansionPiecePlacer(random);
        placer.createMansion(pos, rotation, pieces, grid);
        placer.scatterLots(pieces, pos, rotation, plan);
        return grid;
    }

    static class FirstFloorRoomCollection extends VanillaMansionPieces.FloorRoomCollection {
        @Override
        public String get1x1(RandomSource p_229995_) {
            return "1x1_a" + (p_229995_.nextInt(5) + 1);
        }

        @Override
        public String get1x1Secret(RandomSource p_230000_) {
            return "1x1_as" + (p_230000_.nextInt(4) + 1);
        }

        @Override
        public String get1x2SideEntrance(RandomSource p_229997_, boolean p_229998_) {
            return "1x2_a" + (p_229997_.nextInt(9) + 1);
        }

        @Override
        public String get1x2FrontEntrance(RandomSource p_230002_, boolean p_230003_) {
            return "1x2_b" + (p_230002_.nextInt(5) + 1);
        }

        @Override
        public String get1x2Secret(RandomSource p_230005_) {
            return "1x2_s" + (p_230005_.nextInt(2) + 1);
        }

        @Override
        public String get2x2(RandomSource p_230007_) {
            return "2x2_a" + (p_230007_.nextInt(4) + 1);
        }

        @Override
        public String get2x2Secret(RandomSource p_230009_) {
            return "2x2_s1";
        }
    }

    abstract static class FloorRoomCollection {
        public abstract String get1x1(RandomSource random);

        public abstract String get1x1Secret(RandomSource random);

        public abstract String get1x2SideEntrance(RandomSource random, boolean isStairs);

        public abstract String get1x2FrontEntrance(RandomSource random, boolean isStairs);

        public abstract String get1x2Secret(RandomSource random);

        public abstract String get2x2(RandomSource random);

        public abstract String get2x2Secret(RandomSource random);
    }

    public static final class MansionGrid {
        private static final int CLEAR = 0;
        private static final int CORRIDOR = 1;
        private static final int ROOM = 2;
        private static final int START_ROOM = 3;
        private static final int TEST_ROOM = 4;
        private static final int BLOCKED = 5;
        private static final int ROOM_1x1 = 65536;
        private static final int ROOM_1x2 = 131072;
        private static final int ROOM_2x2 = 262144;
        private static final int ROOM_ORIGIN_FLAG = 1048576;
        private static final int ROOM_DOOR_FLAG = 2097152;
        private static final int ROOM_STAIRS_FLAG = 4194304;
        private static final int ROOM_CORRIDOR_FLAG = 8388608;
        private static final int ROOM_TYPE_MASK = 983040;
        private static final int ROOM_ID_MASK = 65535;
        private final RandomSource random;
        public final int size;
        public final SimpleGrid baseGrid;
        public final SimpleGrid thirdFloorGrid;
        public final SimpleGrid[] floorRooms;
        /**
         * R25-E: the walkable plane of <em>every</em> storey, in the order the placer raises them.
         *
         * <p>Vanilla's generator reuses one grid for the ground and the second storey and only the third
         * has a plane of its own; the organic plan grows each storey separately, so the arrays are read
         * instead of the two fields above. For a vanilla grid this is
         * {@code {baseGrid, baseGrid, thirdFloorGrid}}, which is exactly what the placer used to assume.
         */
        public final SimpleGrid[] storeyGrids;
        /** How many storeys this plan really has: 1 / 2 / 3. */
        public final int storeyCount;
        /** Whether {@link #storeyGrids} holds organic storeys instead of vanilla's shared two-ply grid. */
        public final boolean organic;
        public final int entranceX;
        public final int entranceY;

        public MansionGrid(RandomSource random) {
            this(random, DEFAULT_SIZE);
        }

        public MansionGrid(RandomSource random, int size) {
            this.random = random;
            this.size = size;
            int n = size;
            int border = VanillaMansionPieces.border(n);
            int longRun = n - 5;
            int shortRun = (n - 5) / 2;
            this.entranceX = n - 4;
            this.entranceY = 4;
            this.baseGrid = new SimpleGrid(n, n, 5);
            this.baseGrid.set(this.entranceX, this.entranceY, this.entranceX + 1, this.entranceY + 1, 3);
            this.baseGrid.set(this.entranceX - 1, this.entranceY, this.entranceX - 1, this.entranceY + 1, 2);
            this.baseGrid.set(this.entranceX + 2, this.entranceY - 2, this.entranceX + 3, this.entranceY + 3, 5);
            this.baseGrid.set(this.entranceX + 1, this.entranceY - 2, this.entranceX + 1, this.entranceY - 1, 1);
            this.baseGrid.set(this.entranceX + 1, this.entranceY + 2, this.entranceX + 1, this.entranceY + 3, 1);
            this.baseGrid.set(this.entranceX - 1, this.entranceY - 1, 1);
            this.baseGrid.set(this.entranceX - 1, this.entranceY + 2, 1);
            this.baseGrid.set(0, 0, n, border - 1, 5);
            this.baseGrid.set(0, n - border, n, n, 5);
            this.recursiveCorridor(this.baseGrid, this.entranceX, this.entranceY - 2, Direction.WEST, longRun);
            this.recursiveCorridor(this.baseGrid, this.entranceX, this.entranceY + 3, Direction.WEST, longRun);
            this.recursiveCorridor(this.baseGrid, this.entranceX - 2, this.entranceY - 1, Direction.WEST, shortRun);
            this.recursiveCorridor(this.baseGrid, this.entranceX - 2, this.entranceY + 2, Direction.WEST, shortRun);

            while (this.cleanEdges(this.baseGrid)) {
            }

            this.floorRooms = new SimpleGrid[3];
            this.floorRooms[0] = new SimpleGrid(n, n, 5);
            this.floorRooms[1] = new SimpleGrid(n, n, 5);
            this.floorRooms[2] = new SimpleGrid(n, n, 5);
            this.identifyRooms(this.baseGrid, this.floorRooms[0]);
            this.identifyRooms(this.baseGrid, this.floorRooms[1]);
            this.floorRooms[0].set(this.entranceX + 1, this.entranceY, this.entranceX + 1, this.entranceY + 1, 8388608);
            this.floorRooms[1].set(this.entranceX + 1, this.entranceY, this.entranceX + 1, this.entranceY + 1, 8388608);
            this.thirdFloorGrid = new VanillaMansionPieces.SimpleGrid(this.baseGrid.width, this.baseGrid.height, 5);
            this.setupThirdFloor();
            this.identifyRooms(this.thirdFloorGrid, this.floorRooms[2]);
            this.storeyGrids = new VanillaMansionPieces.SimpleGrid[]{
                this.baseGrid, this.baseGrid, this.thirdFloorGrid};
            this.storeyCount = 3;
            this.organic = false;
        }

        /**
         * R25-E: a grid whose storeys are the {@link OrganicMansionPlan}'s own.
         *
         * <p>{@code OrganicMansionPlan} grows the rooms, the corridors and every upper annex itself, so
         * there is no {@code identifyRooms} pass here: the packed room plane of each storey is handed in
         * with its ids, types and door flags already written. Bounds outside the grid read back as
         * {@code 5} ({@code BLOCKED}), which is what both the outer-wall walk and the roofs expect.
         */
        public MansionGrid(int size, int entranceX, int entranceY, OrganicMansionPlan.Plan plan) {
            this.random = RandomSource.create(0L);
            this.size = size;
            this.entranceX = entranceX;
            this.entranceY = entranceY;
            this.storeyCount = plan.storeyCount;
            this.organic = true;
            this.storeyGrids = new VanillaMansionPieces.SimpleGrid[OrganicMansionPlan.MAX_STOREYS];
            this.floorRooms = new VanillaMansionPieces.SimpleGrid[OrganicMansionPlan.MAX_STOREYS];
            for (int storey = 0; storey < OrganicMansionPlan.MAX_STOREYS; storey++) {
                this.storeyGrids[storey] = plane(plan.cells(storey));
                this.floorRooms[storey] = plane(plan.roomPlane(storey));
            }
            this.baseGrid = this.storeyGrids[0];
            this.thirdFloorGrid = this.storeyGrids[OrganicMansionPlan.MAX_STOREYS - 1];
        }

        /** Wraps a {@link OrganicMansionPlan} cell array as a grid the vanilla placer can read. */
        private static VanillaMansionPieces.SimpleGrid plane(int[][] cells) {
            VanillaMansionPieces.SimpleGrid grid =
                new VanillaMansionPieces.SimpleGrid(OrganicMansionPlan.GRID, OrganicMansionPlan.GRID, 5);
            for (int x = 0; x < OrganicMansionPlan.GRID; x++) {
                for (int y = 0; y < OrganicMansionPlan.GRID; y++) {
                    grid.set(x, y, cells[x][y]);
                }
            }
            return grid;
        }

        public static boolean isHouse(VanillaMansionPieces.SimpleGrid layout, int x, int y) {
            int i = layout.get(x, y);
            return i == 1 || i == 2 || i == 3 || i == 4;
        }

        public boolean isRoomId(VanillaMansionPieces.SimpleGrid layout, int x, int y, int floor, int roomId) {
            return (this.floorRooms[floor].get(x, y) & 65535) == roomId;
        }

        @Nullable
        public Direction get1x2RoomDirection(VanillaMansionPieces.SimpleGrid layout, int x, int y, int floor, int roomId) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                if (this.isRoomId(layout, x + direction.getStepX(), y + direction.getStepZ(), floor, roomId)) {
                    return direction;
                }
            }

            return null;
        }

        private void recursiveCorridor(VanillaMansionPieces.SimpleGrid layout, int x, int y, Direction p_direction, int length) {
            if (length > 0) {
                layout.set(x, y, 1);
                layout.setif(x + p_direction.getStepX(), y + p_direction.getStepZ(), 0, 1);

                for (int i = 0; i < 8; i++) {
                    Direction direction = Direction.from2DDataValue(this.random.nextInt(4));
                    if (direction != p_direction.getOpposite() && (direction != Direction.EAST || !this.random.nextBoolean())) {
                        int j = x + p_direction.getStepX();
                        int k = y + p_direction.getStepZ();
                        if (layout.get(j + direction.getStepX(), k + direction.getStepZ()) == 0
                            && layout.get(j + direction.getStepX() * 2, k + direction.getStepZ() * 2) == 0) {
                            this.recursiveCorridor(
                                layout,
                                x + p_direction.getStepX() + direction.getStepX(),
                                y + p_direction.getStepZ() + direction.getStepZ(),
                                direction,
                                length - 1
                            );
                            break;
                        }
                    }
                }

                Direction direction1 = p_direction.getClockWise();
                Direction direction2 = p_direction.getCounterClockWise();
                layout.setif(x + direction1.getStepX(), y + direction1.getStepZ(), 0, 2);
                layout.setif(x + direction2.getStepX(), y + direction2.getStepZ(), 0, 2);
                layout.setif(x + p_direction.getStepX() + direction1.getStepX(), y + p_direction.getStepZ() + direction1.getStepZ(), 0, 2);
                layout.setif(x + p_direction.getStepX() + direction2.getStepX(), y + p_direction.getStepZ() + direction2.getStepZ(), 0, 2);
                layout.setif(x + p_direction.getStepX() * 2, y + p_direction.getStepZ() * 2, 0, 2);
                layout.setif(x + direction1.getStepX() * 2, y + direction1.getStepZ() * 2, 0, 2);
                layout.setif(x + direction2.getStepX() * 2, y + direction2.getStepZ() * 2, 0, 2);
            }
        }

        private boolean cleanEdges(VanillaMansionPieces.SimpleGrid grid) {
            boolean flag = false;

            for (int i = 0; i < grid.height; i++) {
                for (int j = 0; j < grid.width; j++) {
                    if (grid.get(j, i) == 0) {
                        int k = 0;
                        k += isHouse(grid, j + 1, i) ? 1 : 0;
                        k += isHouse(grid, j - 1, i) ? 1 : 0;
                        k += isHouse(grid, j, i + 1) ? 1 : 0;
                        k += isHouse(grid, j, i - 1) ? 1 : 0;
                        if (k >= 3) {
                            grid.set(j, i, 2);
                            flag = true;
                        } else if (k == 2) {
                            int l = 0;
                            l += isHouse(grid, j + 1, i + 1) ? 1 : 0;
                            l += isHouse(grid, j - 1, i + 1) ? 1 : 0;
                            l += isHouse(grid, j + 1, i - 1) ? 1 : 0;
                            l += isHouse(grid, j - 1, i - 1) ? 1 : 0;
                            if (l <= 1) {
                                grid.set(j, i, 2);
                                flag = true;
                            }
                        }
                    }
                }
            }

            return flag;
        }

        private void setupThirdFloor() {
            List<Tuple<Integer, Integer>> list = Lists.newArrayList();
            VanillaMansionPieces.SimpleGrid woodlandmansionpieces$simplegrid = this.floorRooms[1];

            for (int i = 0; i < this.thirdFloorGrid.height; i++) {
                for (int j = 0; j < this.thirdFloorGrid.width; j++) {
                    int k = woodlandmansionpieces$simplegrid.get(j, i);
                    int l = k & 983040;
                    if (l == 131072 && (k & 2097152) == 2097152) {
                        list.add(new Tuple<>(j, i));
                    }
                }
            }

            if (list.isEmpty()) {
                this.thirdFloorGrid.set(0, 0, this.thirdFloorGrid.width, this.thirdFloorGrid.height, 5);
            } else {
                Tuple<Integer, Integer> tuple = list.get(this.random.nextInt(list.size()));
                int l1 = woodlandmansionpieces$simplegrid.get(tuple.getA(), tuple.getB());
                woodlandmansionpieces$simplegrid.set(tuple.getA(), tuple.getB(), l1 | 4194304);
                Direction direction1 = this.get1x2RoomDirection(this.baseGrid, tuple.getA(), tuple.getB(), 1, l1 & 65535);
                int i2 = tuple.getA() + direction1.getStepX();
                int i1 = tuple.getB() + direction1.getStepZ();

                for (int j1 = 0; j1 < this.thirdFloorGrid.height; j1++) {
                    for (int k1 = 0; k1 < this.thirdFloorGrid.width; k1++) {
                        if (!isHouse(this.baseGrid, k1, j1)) {
                            this.thirdFloorGrid.set(k1, j1, 5);
                        } else if (k1 == tuple.getA() && j1 == tuple.getB()) {
                            this.thirdFloorGrid.set(k1, j1, 3);
                        } else if (k1 == i2 && j1 == i1) {
                            this.thirdFloorGrid.set(k1, j1, 3);
                            this.floorRooms[2].set(k1, j1, 8388608);
                        }
                    }
                }

                List<Direction> list1 = Lists.newArrayList();

                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    if (this.thirdFloorGrid.get(i2 + direction.getStepX(), i1 + direction.getStepZ()) == 0) {
                        list1.add(direction);
                    }
                }

                if (list1.isEmpty()) {
                    this.thirdFloorGrid.set(0, 0, this.thirdFloorGrid.width, this.thirdFloorGrid.height, 5);
                    woodlandmansionpieces$simplegrid.set(tuple.getA(), tuple.getB(), l1);
                } else {
                    Direction direction2 = list1.get(this.random.nextInt(list1.size()));
                    this.recursiveCorridor(this.thirdFloorGrid, i2 + direction2.getStepX(), i1 + direction2.getStepZ(), direction2, 4);

                    while (this.cleanEdges(this.thirdFloorGrid)) {
                    }
                }
            }
        }

        private void identifyRooms(VanillaMansionPieces.SimpleGrid grid, VanillaMansionPieces.SimpleGrid floorRooms) {
            ObjectArrayList<Tuple<Integer, Integer>> objectarraylist = new ObjectArrayList<>();

            for (int i = 0; i < grid.height; i++) {
                for (int j = 0; j < grid.width; j++) {
                    if (grid.get(j, i) == 2) {
                        objectarraylist.add(new Tuple<>(j, i));
                    }
                }
            }

            Util.shuffle(objectarraylist, this.random);
            int k3 = 10;

            for (Tuple<Integer, Integer> tuple : objectarraylist) {
                int k = tuple.getA();
                int l = tuple.getB();
                if (floorRooms.get(k, l) == 0) {
                    int i1 = k;
                    int j1 = k;
                    int k1 = l;
                    int l1 = l;
                    int i2 = 65536;
                    if (floorRooms.get(k + 1, l) == 0
                        && floorRooms.get(k, l + 1) == 0
                        && floorRooms.get(k + 1, l + 1) == 0
                        && grid.get(k + 1, l) == 2
                        && grid.get(k, l + 1) == 2
                        && grid.get(k + 1, l + 1) == 2) {
                        j1 = k + 1;
                        l1 = l + 1;
                        i2 = 262144;
                    } else if (floorRooms.get(k - 1, l) == 0
                        && floorRooms.get(k, l + 1) == 0
                        && floorRooms.get(k - 1, l + 1) == 0
                        && grid.get(k - 1, l) == 2
                        && grid.get(k, l + 1) == 2
                        && grid.get(k - 1, l + 1) == 2) {
                        i1 = k - 1;
                        l1 = l + 1;
                        i2 = 262144;
                    } else if (floorRooms.get(k - 1, l) == 0
                        && floorRooms.get(k, l - 1) == 0
                        && floorRooms.get(k - 1, l - 1) == 0
                        && grid.get(k - 1, l) == 2
                        && grid.get(k, l - 1) == 2
                        && grid.get(k - 1, l - 1) == 2) {
                        i1 = k - 1;
                        k1 = l - 1;
                        i2 = 262144;
                    } else if (floorRooms.get(k + 1, l) == 0 && grid.get(k + 1, l) == 2) {
                        j1 = k + 1;
                        i2 = 131072;
                    } else if (floorRooms.get(k, l + 1) == 0 && grid.get(k, l + 1) == 2) {
                        l1 = l + 1;
                        i2 = 131072;
                    } else if (floorRooms.get(k - 1, l) == 0 && grid.get(k - 1, l) == 2) {
                        i1 = k - 1;
                        i2 = 131072;
                    } else if (floorRooms.get(k, l - 1) == 0 && grid.get(k, l - 1) == 2) {
                        k1 = l - 1;
                        i2 = 131072;
                    }

                    int j2 = this.random.nextBoolean() ? i1 : j1;
                    int k2 = this.random.nextBoolean() ? k1 : l1;
                    int l2 = 2097152;
                    if (!grid.edgesTo(j2, k2, 1)) {
                        j2 = j2 == i1 ? j1 : i1;
                        k2 = k2 == k1 ? l1 : k1;
                        if (!grid.edgesTo(j2, k2, 1)) {
                            k2 = k2 == k1 ? l1 : k1;
                            if (!grid.edgesTo(j2, k2, 1)) {
                                j2 = j2 == i1 ? j1 : i1;
                                k2 = k2 == k1 ? l1 : k1;
                                if (!grid.edgesTo(j2, k2, 1)) {
                                    l2 = 0;
                                    j2 = i1;
                                    k2 = k1;
                                }
                            }
                        }
                    }

                    for (int i3 = k1; i3 <= l1; i3++) {
                        for (int j3 = i1; j3 <= j1; j3++) {
                            if (j3 == j2 && i3 == k2) {
                                floorRooms.set(j3, i3, 1048576 | l2 | i2 | k3);
                            } else {
                                floorRooms.set(j3, i3, i2 | k3);
                            }
                        }
                    }

                    k3++;
                }
            }
        }
    }

    static class MansionPiecePlacer {
        private final RandomSource random;
        private int startX;
        private int startY;

        public MansionPiecePlacer(RandomSource random) {
            this.random = random;
        }

        public void createMansion(
            BlockPos pos, Rotation rotation, List<Piece> pieces, VanillaMansionPieces.MansionGrid grid
        ) {
            VanillaMansionPieces.PlacementData woodlandmansionpieces$placementdata = new VanillaMansionPieces.PlacementData();
            woodlandmansionpieces$placementdata.position = pos;
            woodlandmansionpieces$placementdata.rotation = rotation;
            woodlandmansionpieces$placementdata.wallType = "wall_flat";
            VanillaMansionPieces.PlacementData woodlandmansionpieces$placementdata1 = new VanillaMansionPieces.PlacementData();
            this.entrance(pieces, woodlandmansionpieces$placementdata);
            woodlandmansionpieces$placementdata1.position = woodlandmansionpieces$placementdata.position.above(8);
            woodlandmansionpieces$placementdata1.rotation = woodlandmansionpieces$placementdata.rotation;
            woodlandmansionpieces$placementdata1.wallType = "wall_window";
            if (!pieces.isEmpty()) {
            }

            VanillaMansionPieces.SimpleGrid woodlandmansionpieces$storeygrid0 = grid.storeyGrids[0];
            VanillaMansionPieces.SimpleGrid woodlandmansionpieces$storeygrid1 =
                grid.storeyCount > 1 ? grid.storeyGrids[1] : null;
            VanillaMansionPieces.SimpleGrid woodlandmansionpieces$storeygrid2 =
                grid.storeyCount > 2 ? grid.storeyGrids[2] : null;
            this.startX = grid.entranceX + 1;
            this.startY = grid.entranceY + 1;
            int i = grid.entranceX + 1;
            int j = grid.entranceY;
            this.traverseOuterWalls(
                pieces, woodlandmansionpieces$placementdata, woodlandmansionpieces$storeygrid0, Direction.SOUTH, this.startX, this.startY, i, j
            );
            if (woodlandmansionpieces$storeygrid1 != null) {
                this.traverseOuterWalls(
                    pieces, woodlandmansionpieces$placementdata1, woodlandmansionpieces$storeygrid1, Direction.SOUTH, this.startX, this.startY, i, j
                );
            }
            if (woodlandmansionpieces$storeygrid2 != null) {
                VanillaMansionPieces.PlacementData woodlandmansionpieces$placementdata2 = new VanillaMansionPieces.PlacementData();
                woodlandmansionpieces$placementdata2.position = pos.above(19);
                woodlandmansionpieces$placementdata2.rotation = rotation;
                woodlandmansionpieces$placementdata2.wallType = "wall_window";
                boolean flag = false;

                for (int k = 0; k < woodlandmansionpieces$storeygrid2.height && !flag; k++) {
                    for (int l = woodlandmansionpieces$storeygrid2.width - 1; l >= 0 && !flag; l--) {
                        if (VanillaMansionPieces.MansionGrid.isHouse(woodlandmansionpieces$storeygrid2, l, k)) {
                            woodlandmansionpieces$placementdata2.position = woodlandmansionpieces$placementdata2.position
                                .relative(rotation.rotate(Direction.SOUTH), 8 + (k - this.startY) * 8);
                            woodlandmansionpieces$placementdata2.position = woodlandmansionpieces$placementdata2.position
                                .relative(rotation.rotate(Direction.EAST), (l - this.startX) * 8);
                            this.traverseWallPiece(pieces, woodlandmansionpieces$placementdata2);
                            this.traverseOuterWalls(pieces, woodlandmansionpieces$placementdata2, woodlandmansionpieces$storeygrid2, Direction.SOUTH, l, k, l, k);
                            flag = true;
                        }
                    }
                }
            }

            if (grid.organic) {
                // R25-E: a roof on every storey, over the cells the storey above does not reach. The
                // base offset is vanilla's own: the eaves start two blocks below the room top and the
                // plank closes it one above, which for storeys 1 and 2 is vanilla's +16 and +27.
                for (int storey = 0; storey < grid.storeyCount; storey++) {
                    VanillaMansionPieces.SimpleGrid above =
                        storey + 1 < grid.storeyCount ? grid.storeyGrids[storey + 1] : null;
                    this.createRoof(pieces, pos.above(OrganicMansionPlan.STOREY_BASE[storey] + 8),
                        rotation, grid.storeyGrids[storey], above);
                }
            } else {
                this.createRoof(pieces, pos.above(16), rotation, woodlandmansionpieces$storeygrid0, woodlandmansionpieces$storeygrid1);
                this.createRoof(pieces, pos.above(27), rotation, woodlandmansionpieces$storeygrid1, null);
            }
            if (!pieces.isEmpty()) {
            }

            VanillaMansionPieces.FloorRoomCollection[] awoodlandmansionpieces$floorroomcollection = new VanillaMansionPieces.FloorRoomCollection[]{
                new VanillaMansionPieces.FirstFloorRoomCollection(),
                new VanillaMansionPieces.SecondFloorRoomCollection(),
                new VanillaMansionPieces.ThirdFloorRoomCollection()
            };

            for (int l2 = 0; l2 < grid.storeyCount; l2++) {
                BlockPos blockpos = pos.above(OrganicMansionPlan.STOREY_BASE[l2]);
                VanillaMansionPieces.SimpleGrid woodlandmansionpieces$simplegrid2 = grid.floorRooms[l2];
                VanillaMansionPieces.SimpleGrid woodlandmansionpieces$simplegrid3 = grid.storeyGrids[l2];
                String s = l2 == 0 ? "carpet_south_1" : "carpet_south_2";
                String s1 = l2 == 0 ? "carpet_west_1" : "carpet_west_2";

                for (int i1 = 0; i1 < woodlandmansionpieces$simplegrid3.height; i1++) {
                    for (int j1 = 0; j1 < woodlandmansionpieces$simplegrid3.width; j1++) {
                        if (woodlandmansionpieces$simplegrid3.get(j1, i1) == 1) {
                            BlockPos blockpos1 = blockpos.relative(rotation.rotate(Direction.SOUTH), 8 + (i1 - this.startY) * 8);
                            blockpos1 = blockpos1.relative(rotation.rotate(Direction.EAST), (j1 - this.startX) * 8);
                            pieces.add(new Piece("corridor_floor", blockpos1, rotation));
                            if (woodlandmansionpieces$simplegrid3.get(j1, i1 - 1) == 1
                                || (woodlandmansionpieces$simplegrid2.get(j1, i1 - 1) & 8388608) == 8388608) {
                                pieces.add(
                                    new Piece(
                                        
                                        "carpet_north",
                                        blockpos1.relative(rotation.rotate(Direction.EAST), 1).above(),
                                        rotation
                                    )
                                );
                            }

                            if (woodlandmansionpieces$simplegrid3.get(j1 + 1, i1) == 1
                                || (woodlandmansionpieces$simplegrid2.get(j1 + 1, i1) & 8388608) == 8388608) {
                                pieces.add(
                                    new Piece(
                                        
                                        "carpet_east",
                                        blockpos1.relative(rotation.rotate(Direction.SOUTH), 1).relative(rotation.rotate(Direction.EAST), 5).above(),
                                        rotation
                                    )
                                );
                            }

                            if (woodlandmansionpieces$simplegrid3.get(j1, i1 + 1) == 1
                                || (woodlandmansionpieces$simplegrid2.get(j1, i1 + 1) & 8388608) == 8388608) {
                                pieces.add(
                                    new Piece(
                                        
                                        s,
                                        blockpos1.relative(rotation.rotate(Direction.SOUTH), 5).relative(rotation.rotate(Direction.WEST), 1),
                                        rotation
                                    )
                                );
                            }

                            if (woodlandmansionpieces$simplegrid3.get(j1 - 1, i1) == 1
                                || (woodlandmansionpieces$simplegrid2.get(j1 - 1, i1) & 8388608) == 8388608) {
                                pieces.add(
                                    new Piece(
                                        
                                        s1,
                                        blockpos1.relative(rotation.rotate(Direction.WEST), 1).relative(rotation.rotate(Direction.NORTH), 1),
                                        rotation
                                    )
                                );
                            }
                        }
                    }
                }

                String s2 = l2 == 0 ? "indoors_wall_1" : "indoors_wall_2";
                String s3 = l2 == 0 ? "indoors_door_1" : "indoors_door_2";
                List<Direction> list = Lists.newArrayList();

                for (int k1 = 0; k1 < woodlandmansionpieces$simplegrid3.height; k1++) {
                    for (int l1 = 0; l1 < woodlandmansionpieces$simplegrid3.width; l1++) {
                        boolean flag1 = l2 == 2 && woodlandmansionpieces$simplegrid3.get(l1, k1) == 3;
                        if (woodlandmansionpieces$simplegrid3.get(l1, k1) == 2 || flag1) {
                            int i2 = woodlandmansionpieces$simplegrid2.get(l1, k1);
                            int j2 = i2 & 983040;
                            int k2 = i2 & 65535;
                            flag1 = flag1 && (i2 & 8388608) == 8388608;
                            list.clear();
                            if ((i2 & 2097152) == 2097152) {
                                for (Direction direction : Direction.Plane.HORIZONTAL) {
                                    if (woodlandmansionpieces$simplegrid3.get(l1 + direction.getStepX(), k1 + direction.getStepZ()) == 1) {
                                        list.add(direction);
                                    }
                                }
                            }

                            Direction direction1 = null;
                            if (!list.isEmpty()) {
                                direction1 = list.get(this.random.nextInt(list.size()));
                            } else if ((i2 & 1048576) == 1048576) {
                                direction1 = Direction.UP;
                            }

                            BlockPos blockpos3 = blockpos.relative(rotation.rotate(Direction.SOUTH), 8 + (k1 - this.startY) * 8);
                            blockpos3 = blockpos3.relative(rotation.rotate(Direction.EAST), -1 + (l1 - this.startX) * 8);
                            if (VanillaMansionPieces.MansionGrid.isHouse(woodlandmansionpieces$simplegrid3, l1 - 1, k1)
                                && !grid.isRoomId(woodlandmansionpieces$simplegrid3, l1 - 1, k1, l2, k2)) {
                                pieces.add(
                                    new Piece(
                                        direction1 == Direction.WEST ? s3 : s2, blockpos3, rotation
                                    )
                                );
                            }

                            if (woodlandmansionpieces$simplegrid3.get(l1 + 1, k1) == 1 && !flag1) {
                                BlockPos blockpos2 = blockpos3.relative(rotation.rotate(Direction.EAST), 8);
                                pieces.add(
                                    new Piece(
                                        direction1 == Direction.EAST ? s3 : s2, blockpos2, rotation
                                    )
                                );
                            }

                            if (VanillaMansionPieces.MansionGrid.isHouse(woodlandmansionpieces$simplegrid3, l1, k1 + 1)
                                && !grid.isRoomId(woodlandmansionpieces$simplegrid3, l1, k1 + 1, l2, k2)) {
                                BlockPos blockpos4 = blockpos3.relative(rotation.rotate(Direction.SOUTH), 7);
                                blockpos4 = blockpos4.relative(rotation.rotate(Direction.EAST), 7);
                                pieces.add(
                                    new Piece(
                                        
                                        direction1 == Direction.SOUTH ? s3 : s2,
                                        blockpos4,
                                        rotation.getRotated(Rotation.CLOCKWISE_90)
                                    )
                                );
                            }

                            if (woodlandmansionpieces$simplegrid3.get(l1, k1 - 1) == 1 && !flag1) {
                                BlockPos blockpos5 = blockpos3.relative(rotation.rotate(Direction.NORTH), 1);
                                blockpos5 = blockpos5.relative(rotation.rotate(Direction.EAST), 7);
                                pieces.add(
                                    new Piece(
                                        
                                        direction1 == Direction.NORTH ? s3 : s2,
                                        blockpos5,
                                        rotation.getRotated(Rotation.CLOCKWISE_90)
                                    )
                                );
                            }

                            VanillaMansionPieces.FloorRoomCollection collection = collectionFor(
                                grid, l2, l1, k1, awoodlandmansionpieces$floorroomcollection);
                            if (j2 == 65536) {
                                this.addRoom1x1(pieces, blockpos3, rotation, direction1, collection);
                            } else if (j2 == 131072 && direction1 != null) {
                                Direction direction3 = grid.get1x2RoomDirection(woodlandmansionpieces$simplegrid3, l1, k1, l2, k2);
                                boolean flag2 = (i2 & 4194304) == 4194304;
                                this.addRoom1x2(pieces, blockpos3, rotation, direction3, direction1, collection, flag2);
                            } else if (j2 == 262144 && direction1 != null && direction1 != Direction.UP) {
                                Direction direction2 = direction1.getClockWise();
                                if (!grid.isRoomId(woodlandmansionpieces$simplegrid3, l1 + direction2.getStepX(), k1 + direction2.getStepZ(), l2, k2)) {
                                    direction2 = direction2.getOpposite();
                                }

                                this.addRoom2x2(pieces, blockpos3, rotation, direction2, direction1, collection);
                            } else if (j2 == 262144 && direction1 == Direction.UP) {
                                this.addRoom2x2Secret(pieces, blockpos3, rotation, collection);
                            }
                        }
                    }
                }
            }
        }

        /**
         * Which room menu a cell is dressed from (R25-E, decision E4).
         *
         * <p>The vanilla menus are 8 and 11 blocks tall, and the organic plan uses both: a ground-floor
         * cell that has a room stacked on it gets the 8-high menu, because the room above is its ceiling,
         * and a cell that is the top of its stack gets the 11-high menu, because the roof's eaves cover
         * the extra three rows exactly the way vanilla's own second storey does.
         */
        private static VanillaMansionPieces.FloorRoomCollection collectionFor(
            VanillaMansionPieces.MansionGrid grid,
            int storey,
            int cx,
            int cz,
            VanillaMansionPieces.FloorRoomCollection[] menus
        ) {
            if (!grid.organic || storey != 0) {
                return menus[storey];
            }
            if (grid.storeyCount > 1
                && VanillaMansionPieces.MansionGrid.isHouse(grid.storeyGrids[1], cx, cz)) {
                return menus[0];
            }
            return menus[1];
        }

        /**
         * R25-E: the empty lots.
         *
         * <p>Spec §5.1 asks for 5–10% of the building's edge to read as an abandoned yard rather than as
         * floor, so each lot gets a low wall stump from the mansion's own set — {@code small_wall} where
         * the lot is open, {@code small_wall_corner} where it is squeezed between two neighbours. The
         * pieces stand on the footing the builder already lays one cell past every walkable cell, so
         * nothing floats.
         */
        public void scatterLots(List<Piece> pieces, BlockPos pos, Rotation rotation,
                                OrganicMansionPlan.Plan plan) {
            for (OrganicMansionPlan.Lot lot : plan.lots) {
                BlockPos at = this.cellOrigin(pos, rotation, lot.cx(), lot.cz());
                pieces.add(new Piece(lot.corner() ? "small_wall_corner" : "small_wall", at, rotation));
            }
        }

        /** The block the corner cell of a coarse cell stands on, exactly as the placer addresses it. */
        private BlockPos cellOrigin(BlockPos pos, Rotation rotation, int cx, int cz) {
            BlockPos at = pos.relative(rotation.rotate(Direction.SOUTH), 8 + (cz - this.startY) * 8);
            return at.relative(rotation.rotate(Direction.EAST), (cx - this.startX) * 8);
        }

        private void traverseOuterWalls(
            List<Piece> pieces,
            VanillaMansionPieces.PlacementData data,
            VanillaMansionPieces.SimpleGrid layout,
            Direction p_direction,
            int startX,
            int startY,
            int entranceX,
            int entranceY
        ) {
            int i = startX;
            int j = startY;
            Direction direction = p_direction;
            int guard = layout.width * layout.height * 16 + 256;

            do {
                if (--guard < 0) {
                    // R25-E guard: this boundary walk cannot terminate on a region that pinches itself,
                    // and a silent infinite loop would hang the build. The organic plan opens every
                    // pinch before it gets here, so this only fires on a plan that slipped past.
                    com.homemine.stealindims.StealInDimsMod.LOGGER.error(
                        "Mansion outer-wall walk gave up at cell {},{} heading {} for a {}x{} region",
                        i, j, p_direction, layout.width, layout.height);
                    return;
                }
                if (!VanillaMansionPieces.MansionGrid.isHouse(layout, i + p_direction.getStepX(), j + p_direction.getStepZ())) {
                    this.traverseTurn(pieces, data);
                    p_direction = p_direction.getClockWise();
                    if (i != entranceX || j != entranceY || direction != p_direction) {
                        this.traverseWallPiece(pieces, data);
                    }
                } else if (VanillaMansionPieces.MansionGrid.isHouse(layout, i + p_direction.getStepX(), j + p_direction.getStepZ())
                    && VanillaMansionPieces.MansionGrid.isHouse(
                        layout,
                        i + p_direction.getStepX() + p_direction.getCounterClockWise().getStepX(),
                        j + p_direction.getStepZ() + p_direction.getCounterClockWise().getStepZ()
                    )) {
                    this.traverseInnerTurn(pieces, data);
                    i += p_direction.getStepX();
                    j += p_direction.getStepZ();
                    p_direction = p_direction.getCounterClockWise();
                } else {
                    i += p_direction.getStepX();
                    j += p_direction.getStepZ();
                    if (i != entranceX || j != entranceY || direction != p_direction) {
                        this.traverseWallPiece(pieces, data);
                    }
                }
            } while (i != entranceX || j != entranceY || direction != p_direction);
        }

        private void createRoof(
            List<Piece> pieces,
            BlockPos pos,
            Rotation rotation,
            VanillaMansionPieces.SimpleGrid layout,
            @Nullable VanillaMansionPieces.SimpleGrid nextFloorLayout
        ) {
            for (int i = 0; i < layout.height; i++) {
                for (int j = 0; j < layout.width; j++) {
                    BlockPos $$27 = pos.relative(rotation.rotate(Direction.SOUTH), 8 + (i - this.startY) * 8);
                    $$27 = $$27.relative(rotation.rotate(Direction.EAST), (j - this.startX) * 8);
                    boolean flag = nextFloorLayout != null && VanillaMansionPieces.MansionGrid.isHouse(nextFloorLayout, j, i);
                    if (VanillaMansionPieces.MansionGrid.isHouse(layout, j, i) && !flag) {
                        pieces.add(new Piece("roof", $$27.above(3), rotation));
                        if (!VanillaMansionPieces.MansionGrid.isHouse(layout, j + 1, i)) {
                            BlockPos blockpos1 = $$27.relative(rotation.rotate(Direction.EAST), 6);
                            pieces.add(new Piece("roof_front", blockpos1, rotation));
                        }

                        if (!VanillaMansionPieces.MansionGrid.isHouse(layout, j - 1, i)) {
                            BlockPos blockpos5 = $$27.relative(rotation.rotate(Direction.EAST), 0);
                            blockpos5 = blockpos5.relative(rotation.rotate(Direction.SOUTH), 7);
                            pieces.add(
                                new Piece(
                                    "roof_front", blockpos5, rotation.getRotated(Rotation.CLOCKWISE_180)
                                )
                            );
                        }

                        if (!VanillaMansionPieces.MansionGrid.isHouse(layout, j, i - 1)) {
                            BlockPos blockpos6 = $$27.relative(rotation.rotate(Direction.WEST), 1);
                            pieces.add(
                                new Piece(
                                    "roof_front", blockpos6, rotation.getRotated(Rotation.COUNTERCLOCKWISE_90)
                                )
                            );
                        }

                        if (!VanillaMansionPieces.MansionGrid.isHouse(layout, j, i + 1)) {
                            BlockPos blockpos7 = $$27.relative(rotation.rotate(Direction.EAST), 6);
                            blockpos7 = blockpos7.relative(rotation.rotate(Direction.SOUTH), 6);
                            pieces.add(
                                new Piece(
                                    "roof_front", blockpos7, rotation.getRotated(Rotation.CLOCKWISE_90)
                                )
                            );
                        }
                    }
                }
            }

            if (nextFloorLayout != null) {
                for (int k = 0; k < layout.height; k++) {
                    for (int i1 = 0; i1 < layout.width; i1++) {
                        BlockPos blockpos3 = pos.relative(rotation.rotate(Direction.SOUTH), 8 + (k - this.startY) * 8);
                        blockpos3 = blockpos3.relative(rotation.rotate(Direction.EAST), (i1 - this.startX) * 8);
                        boolean flag1 = VanillaMansionPieces.MansionGrid.isHouse(nextFloorLayout, i1, k);
                        if (VanillaMansionPieces.MansionGrid.isHouse(layout, i1, k) && flag1) {
                            if (!VanillaMansionPieces.MansionGrid.isHouse(layout, i1 + 1, k)) {
                                BlockPos blockpos8 = blockpos3.relative(rotation.rotate(Direction.EAST), 7);
                                pieces.add(new Piece("small_wall", blockpos8, rotation));
                            }

                            if (!VanillaMansionPieces.MansionGrid.isHouse(layout, i1 - 1, k)) {
                                BlockPos blockpos9 = blockpos3.relative(rotation.rotate(Direction.WEST), 1);
                                blockpos9 = blockpos9.relative(rotation.rotate(Direction.SOUTH), 6);
                                pieces.add(
                                    new Piece(
                                        "small_wall", blockpos9, rotation.getRotated(Rotation.CLOCKWISE_180)
                                    )
                                );
                            }

                            if (!VanillaMansionPieces.MansionGrid.isHouse(layout, i1, k - 1)) {
                                BlockPos blockpos10 = blockpos3.relative(rotation.rotate(Direction.WEST), 0);
                                blockpos10 = blockpos10.relative(rotation.rotate(Direction.NORTH), 1);
                                pieces.add(
                                    new Piece(
                                        "small_wall", blockpos10, rotation.getRotated(Rotation.COUNTERCLOCKWISE_90)
                                    )
                                );
                            }

                            if (!VanillaMansionPieces.MansionGrid.isHouse(layout, i1, k + 1)) {
                                BlockPos blockpos11 = blockpos3.relative(rotation.rotate(Direction.EAST), 6);
                                blockpos11 = blockpos11.relative(rotation.rotate(Direction.SOUTH), 7);
                                pieces.add(
                                    new Piece(
                                        "small_wall", blockpos11, rotation.getRotated(Rotation.CLOCKWISE_90)
                                    )
                                );
                            }

                            if (!VanillaMansionPieces.MansionGrid.isHouse(layout, i1 + 1, k)) {
                                if (!VanillaMansionPieces.MansionGrid.isHouse(layout, i1, k - 1)) {
                                    BlockPos blockpos12 = blockpos3.relative(rotation.rotate(Direction.EAST), 7);
                                    blockpos12 = blockpos12.relative(rotation.rotate(Direction.NORTH), 2);
                                    pieces.add(
                                        new Piece(
                                            "small_wall_corner", blockpos12, rotation
                                        )
                                    );
                                }

                                if (!VanillaMansionPieces.MansionGrid.isHouse(layout, i1, k + 1)) {
                                    BlockPos blockpos13 = blockpos3.relative(rotation.rotate(Direction.EAST), 8);
                                    blockpos13 = blockpos13.relative(rotation.rotate(Direction.SOUTH), 7);
                                    pieces.add(
                                        new Piece(
                                            "small_wall_corner", blockpos13, rotation.getRotated(Rotation.CLOCKWISE_90)
                                        )
                                    );
                                }
                            }

                            if (!VanillaMansionPieces.MansionGrid.isHouse(layout, i1 - 1, k)) {
                                if (!VanillaMansionPieces.MansionGrid.isHouse(layout, i1, k - 1)) {
                                    BlockPos blockpos14 = blockpos3.relative(rotation.rotate(Direction.WEST), 2);
                                    blockpos14 = blockpos14.relative(rotation.rotate(Direction.NORTH), 1);
                                    pieces.add(
                                        new Piece(
                                            "small_wall_corner", blockpos14, rotation.getRotated(Rotation.COUNTERCLOCKWISE_90)
                                        )
                                    );
                                }

                                if (!VanillaMansionPieces.MansionGrid.isHouse(layout, i1, k + 1)) {
                                    BlockPos blockpos15 = blockpos3.relative(rotation.rotate(Direction.WEST), 1);
                                    blockpos15 = blockpos15.relative(rotation.rotate(Direction.SOUTH), 8);
                                    pieces.add(
                                        new Piece(
                                            "small_wall_corner", blockpos15, rotation.getRotated(Rotation.CLOCKWISE_180)
                                        )
                                    );
                                }
                            }
                        }
                    }
                }
            }

            for (int l = 0; l < layout.height; l++) {
                for (int j1 = 0; j1 < layout.width; j1++) {
                    BlockPos blockpos4 = pos.relative(rotation.rotate(Direction.SOUTH), 8 + (l - this.startY) * 8);
                    blockpos4 = blockpos4.relative(rotation.rotate(Direction.EAST), (j1 - this.startX) * 8);
                    boolean flag2 = nextFloorLayout != null && VanillaMansionPieces.MansionGrid.isHouse(nextFloorLayout, j1, l);
                    if (VanillaMansionPieces.MansionGrid.isHouse(layout, j1, l) && !flag2) {
                        if (!VanillaMansionPieces.MansionGrid.isHouse(layout, j1 + 1, l)) {
                            BlockPos blockpos16 = blockpos4.relative(rotation.rotate(Direction.EAST), 6);
                            if (!VanillaMansionPieces.MansionGrid.isHouse(layout, j1, l + 1)) {
                                BlockPos blockpos2 = blockpos16.relative(rotation.rotate(Direction.SOUTH), 6);
                                pieces.add(
                                    new Piece("roof_corner", blockpos2, rotation)
                                );
                            } else if (VanillaMansionPieces.MansionGrid.isHouse(layout, j1 + 1, l + 1)) {
                                BlockPos blockpos18 = blockpos16.relative(rotation.rotate(Direction.SOUTH), 5);
                                pieces.add(
                                    new Piece("roof_inner_corner", blockpos18, rotation)
                                );
                            }

                            if (!VanillaMansionPieces.MansionGrid.isHouse(layout, j1, l - 1)) {
                                pieces.add(
                                    new Piece(
                                        "roof_corner", blockpos16, rotation.getRotated(Rotation.COUNTERCLOCKWISE_90)
                                    )
                                );
                            } else if (VanillaMansionPieces.MansionGrid.isHouse(layout, j1 + 1, l - 1)) {
                                BlockPos blockpos19 = blockpos4.relative(rotation.rotate(Direction.EAST), 9);
                                blockpos19 = blockpos19.relative(rotation.rotate(Direction.NORTH), 2);
                                pieces.add(
                                    new Piece(
                                        "roof_inner_corner", blockpos19, rotation.getRotated(Rotation.CLOCKWISE_90)
                                    )
                                );
                            }
                        }

                        if (!VanillaMansionPieces.MansionGrid.isHouse(layout, j1 - 1, l)) {
                            BlockPos blockpos17 = blockpos4.relative(rotation.rotate(Direction.EAST), 0);
                            blockpos17 = blockpos17.relative(rotation.rotate(Direction.SOUTH), 0);
                            if (!VanillaMansionPieces.MansionGrid.isHouse(layout, j1, l + 1)) {
                                BlockPos blockpos20 = blockpos17.relative(rotation.rotate(Direction.SOUTH), 6);
                                pieces.add(
                                    new Piece(
                                        "roof_corner", blockpos20, rotation.getRotated(Rotation.CLOCKWISE_90)
                                    )
                                );
                            } else if (VanillaMansionPieces.MansionGrid.isHouse(layout, j1 - 1, l + 1)) {
                                BlockPos blockpos21 = blockpos17.relative(rotation.rotate(Direction.SOUTH), 8);
                                blockpos21 = blockpos21.relative(rotation.rotate(Direction.WEST), 3);
                                pieces.add(
                                    new Piece(
                                        "roof_inner_corner", blockpos21, rotation.getRotated(Rotation.COUNTERCLOCKWISE_90)
                                    )
                                );
                            }

                            if (!VanillaMansionPieces.MansionGrid.isHouse(layout, j1, l - 1)) {
                                pieces.add(
                                    new Piece(
                                        "roof_corner", blockpos17, rotation.getRotated(Rotation.CLOCKWISE_180)
                                    )
                                );
                            } else if (VanillaMansionPieces.MansionGrid.isHouse(layout, j1 - 1, l - 1)) {
                                BlockPos blockpos22 = blockpos17.relative(rotation.rotate(Direction.SOUTH), 1);
                                pieces.add(
                                    new Piece(
                                        "roof_inner_corner", blockpos22, rotation.getRotated(Rotation.CLOCKWISE_180)
                                    )
                                );
                            }
                        }
                    }
                }
            }
        }

        private void entrance(List<Piece> pieces, VanillaMansionPieces.PlacementData data) {
            Direction direction = data.rotation.rotate(Direction.WEST);
            pieces.add(
                new Piece(
                    "entrance", data.position.relative(direction, 9), data.rotation
                )
            );
            data.position = data.position.relative(data.rotation.rotate(Direction.SOUTH), 16);
        }

        private void traverseWallPiece(List<Piece> pieces, VanillaMansionPieces.PlacementData data) {
            pieces.add(
                new Piece(
                    
                    data.wallType,
                    data.position.relative(data.rotation.rotate(Direction.EAST), 7),
                    data.rotation
                )
            );
            data.position = data.position.relative(data.rotation.rotate(Direction.SOUTH), 8);
        }

        private void traverseTurn(List<Piece> pieces, VanillaMansionPieces.PlacementData data) {
            data.position = data.position.relative(data.rotation.rotate(Direction.SOUTH), -1);
            pieces.add(new Piece("wall_corner", data.position, data.rotation));
            data.position = data.position.relative(data.rotation.rotate(Direction.SOUTH), -7);
            data.position = data.position.relative(data.rotation.rotate(Direction.WEST), -6);
            data.rotation = data.rotation.getRotated(Rotation.CLOCKWISE_90);
        }

        private void traverseInnerTurn(List<Piece> pieces, VanillaMansionPieces.PlacementData data) {
            data.position = data.position.relative(data.rotation.rotate(Direction.SOUTH), 6);
            data.position = data.position.relative(data.rotation.rotate(Direction.EAST), 8);
            data.rotation = data.rotation.getRotated(Rotation.COUNTERCLOCKWISE_90);
        }

        private void addRoom1x1(
            List<Piece> pieces,
            BlockPos pos,
            Rotation p_rotation,
            Direction direction,
            VanillaMansionPieces.FloorRoomCollection floorRooms
        ) {
            Rotation rotation = Rotation.NONE;
            String s = floorRooms.get1x1(this.random);
            if (direction != Direction.EAST) {
                if (direction == Direction.NORTH) {
                    rotation = rotation.getRotated(Rotation.COUNTERCLOCKWISE_90);
                } else if (direction == Direction.WEST) {
                    rotation = rotation.getRotated(Rotation.CLOCKWISE_180);
                } else if (direction == Direction.SOUTH) {
                    rotation = rotation.getRotated(Rotation.CLOCKWISE_90);
                } else {
                    s = floorRooms.get1x1Secret(this.random);
                }
            }

            BlockPos blockpos = zeroPositionWithTransform(new BlockPos(1, 0, 0), Mirror.NONE, rotation, 7, 7);
            rotation = rotation.getRotated(p_rotation);
            blockpos = blockpos.rotate(p_rotation);
            BlockPos blockpos1 = pos.offset(blockpos.getX(), 0, blockpos.getZ());
            pieces.add(new Piece(s, blockpos1, rotation));
        }

        private void addRoom1x2(
            List<Piece> pieces,
            BlockPos pos,
            Rotation rotation,
            Direction frontDirection,
            Direction sideDirection,
            VanillaMansionPieces.FloorRoomCollection floorRooms,
            boolean isStairs
        ) {
            if (sideDirection == Direction.EAST && frontDirection == Direction.SOUTH) {
                BlockPos blockpos13 = pos.relative(rotation.rotate(Direction.EAST), 1);
                pieces.add(
                    new Piece(
                        floorRooms.get1x2SideEntrance(this.random, isStairs), blockpos13, rotation
                    )
                );
            } else if (sideDirection == Direction.EAST && frontDirection == Direction.NORTH) {
                BlockPos blockpos12 = pos.relative(rotation.rotate(Direction.EAST), 1);
                blockpos12 = blockpos12.relative(rotation.rotate(Direction.SOUTH), 6);
                pieces.add(
                    new Piece(
                        floorRooms.get1x2SideEntrance(this.random, isStairs), blockpos12, rotation, Mirror.LEFT_RIGHT
                    )
                );
            } else if (sideDirection == Direction.WEST && frontDirection == Direction.NORTH) {
                BlockPos blockpos11 = pos.relative(rotation.rotate(Direction.EAST), 7);
                blockpos11 = blockpos11.relative(rotation.rotate(Direction.SOUTH), 6);
                pieces.add(
                    new Piece(
                        
                        floorRooms.get1x2SideEntrance(this.random, isStairs),
                        blockpos11,
                        rotation.getRotated(Rotation.CLOCKWISE_180)
                    )
                );
            } else if (sideDirection == Direction.WEST && frontDirection == Direction.SOUTH) {
                BlockPos blockpos10 = pos.relative(rotation.rotate(Direction.EAST), 7);
                pieces.add(
                    new Piece(
                        floorRooms.get1x2SideEntrance(this.random, isStairs), blockpos10, rotation, Mirror.FRONT_BACK
                    )
                );
            } else if (sideDirection == Direction.SOUTH && frontDirection == Direction.EAST) {
                BlockPos blockpos9 = pos.relative(rotation.rotate(Direction.EAST), 1);
                pieces.add(
                    new Piece(
                        
                        floorRooms.get1x2SideEntrance(this.random, isStairs),
                        blockpos9,
                        rotation.getRotated(Rotation.CLOCKWISE_90),
                        Mirror.LEFT_RIGHT
                    )
                );
            } else if (sideDirection == Direction.SOUTH && frontDirection == Direction.WEST) {
                BlockPos blockpos8 = pos.relative(rotation.rotate(Direction.EAST), 7);
                pieces.add(
                    new Piece(
                        
                        floorRooms.get1x2SideEntrance(this.random, isStairs),
                        blockpos8,
                        rotation.getRotated(Rotation.CLOCKWISE_90)
                    )
                );
            } else if (sideDirection == Direction.NORTH && frontDirection == Direction.WEST) {
                BlockPos blockpos7 = pos.relative(rotation.rotate(Direction.EAST), 7);
                blockpos7 = blockpos7.relative(rotation.rotate(Direction.SOUTH), 6);
                pieces.add(
                    new Piece(
                        
                        floorRooms.get1x2SideEntrance(this.random, isStairs),
                        blockpos7,
                        rotation.getRotated(Rotation.CLOCKWISE_90),
                        Mirror.FRONT_BACK
                    )
                );
            } else if (sideDirection == Direction.NORTH && frontDirection == Direction.EAST) {
                BlockPos blockpos6 = pos.relative(rotation.rotate(Direction.EAST), 1);
                blockpos6 = blockpos6.relative(rotation.rotate(Direction.SOUTH), 6);
                pieces.add(
                    new Piece(
                        
                        floorRooms.get1x2SideEntrance(this.random, isStairs),
                        blockpos6,
                        rotation.getRotated(Rotation.COUNTERCLOCKWISE_90)
                    )
                );
            } else if (sideDirection == Direction.SOUTH && frontDirection == Direction.NORTH) {
                BlockPos blockpos5 = pos.relative(rotation.rotate(Direction.EAST), 1);
                blockpos5 = blockpos5.relative(rotation.rotate(Direction.NORTH), 8);
                pieces.add(
                    new Piece(
                        floorRooms.get1x2FrontEntrance(this.random, isStairs), blockpos5, rotation
                    )
                );
            } else if (sideDirection == Direction.NORTH && frontDirection == Direction.SOUTH) {
                BlockPos blockpos4 = pos.relative(rotation.rotate(Direction.EAST), 7);
                blockpos4 = blockpos4.relative(rotation.rotate(Direction.SOUTH), 14);
                pieces.add(
                    new Piece(
                        
                        floorRooms.get1x2FrontEntrance(this.random, isStairs),
                        blockpos4,
                        rotation.getRotated(Rotation.CLOCKWISE_180)
                    )
                );
            } else if (sideDirection == Direction.WEST && frontDirection == Direction.EAST) {
                BlockPos blockpos3 = pos.relative(rotation.rotate(Direction.EAST), 15);
                pieces.add(
                    new Piece(
                        
                        floorRooms.get1x2FrontEntrance(this.random, isStairs),
                        blockpos3,
                        rotation.getRotated(Rotation.CLOCKWISE_90)
                    )
                );
            } else if (sideDirection == Direction.EAST && frontDirection == Direction.WEST) {
                BlockPos blockpos2 = pos.relative(rotation.rotate(Direction.WEST), 7);
                blockpos2 = blockpos2.relative(rotation.rotate(Direction.SOUTH), 6);
                pieces.add(
                    new Piece(
                        
                        floorRooms.get1x2FrontEntrance(this.random, isStairs),
                        blockpos2,
                        rotation.getRotated(Rotation.COUNTERCLOCKWISE_90)
                    )
                );
            } else if (sideDirection == Direction.UP && frontDirection == Direction.EAST) {
                BlockPos blockpos1 = pos.relative(rotation.rotate(Direction.EAST), 15);
                pieces.add(
                    new Piece(
                        floorRooms.get1x2Secret(this.random), blockpos1, rotation.getRotated(Rotation.CLOCKWISE_90)
                    )
                );
            } else if (sideDirection == Direction.UP && frontDirection == Direction.SOUTH) {
                BlockPos blockpos = pos.relative(rotation.rotate(Direction.EAST), 1);
                blockpos = blockpos.relative(rotation.rotate(Direction.NORTH), 0);
                pieces.add(
                    new Piece(floorRooms.get1x2Secret(this.random), blockpos, rotation)
                );
            }
        }

        private void addRoom2x2(
            List<Piece> pieces,
            BlockPos pos,
            Rotation p_rotation,
            Direction frontDirection,
            Direction sideDirection,
            VanillaMansionPieces.FloorRoomCollection floorRooms
        ) {
            int i = 0;
            int j = 0;
            Rotation rotation = p_rotation;
            Mirror mirror = Mirror.NONE;
            if (sideDirection == Direction.EAST && frontDirection == Direction.SOUTH) {
                i = -7;
            } else if (sideDirection == Direction.EAST && frontDirection == Direction.NORTH) {
                i = -7;
                j = 6;
                mirror = Mirror.LEFT_RIGHT;
            } else if (sideDirection == Direction.NORTH && frontDirection == Direction.EAST) {
                i = 1;
                j = 14;
                rotation = p_rotation.getRotated(Rotation.COUNTERCLOCKWISE_90);
            } else if (sideDirection == Direction.NORTH && frontDirection == Direction.WEST) {
                i = 7;
                j = 14;
                rotation = p_rotation.getRotated(Rotation.COUNTERCLOCKWISE_90);
                mirror = Mirror.LEFT_RIGHT;
            } else if (sideDirection == Direction.SOUTH && frontDirection == Direction.WEST) {
                i = 7;
                j = -8;
                rotation = p_rotation.getRotated(Rotation.CLOCKWISE_90);
            } else if (sideDirection == Direction.SOUTH && frontDirection == Direction.EAST) {
                i = 1;
                j = -8;
                rotation = p_rotation.getRotated(Rotation.CLOCKWISE_90);
                mirror = Mirror.LEFT_RIGHT;
            } else if (sideDirection == Direction.WEST && frontDirection == Direction.NORTH) {
                i = 15;
                j = 6;
                rotation = p_rotation.getRotated(Rotation.CLOCKWISE_180);
            } else if (sideDirection == Direction.WEST && frontDirection == Direction.SOUTH) {
                i = 15;
                mirror = Mirror.FRONT_BACK;
            }

            BlockPos blockpos = pos.relative(p_rotation.rotate(Direction.EAST), i);
            blockpos = blockpos.relative(p_rotation.rotate(Direction.SOUTH), j);
            pieces.add(
                new Piece(floorRooms.get2x2(this.random), blockpos, rotation, mirror)
            );
        }

        private void addRoom2x2Secret(
            List<Piece> pieces,
            BlockPos pos,
            Rotation rotation,
            VanillaMansionPieces.FloorRoomCollection floorRooms
        ) {
            BlockPos blockpos = pos.relative(rotation.rotate(Direction.EAST), 1);
            pieces.add(
                new Piece(
                    floorRooms.get2x2Secret(this.random), blockpos, rotation, Mirror.NONE
                )
            );
        }
    }

    static class PlacementData {
        public Rotation rotation;
        public BlockPos position;
        public String wallType;
    }

    static class SecondFloorRoomCollection extends VanillaMansionPieces.FloorRoomCollection {
        @Override
        public String get1x1(RandomSource p_230144_) {
            return "1x1_b" + (p_230144_.nextInt(4) + 1);
        }

        @Override
        public String get1x1Secret(RandomSource p_230149_) {
            return "1x1_as" + (p_230149_.nextInt(4) + 1);
        }

        @Override
        public String get1x2SideEntrance(RandomSource p_230146_, boolean p_230147_) {
            return p_230147_ ? "1x2_c_stairs" : "1x2_c" + (p_230146_.nextInt(4) + 1);
        }

        @Override
        public String get1x2FrontEntrance(RandomSource p_230151_, boolean p_230152_) {
            return p_230152_ ? "1x2_d_stairs" : "1x2_d" + (p_230151_.nextInt(5) + 1);
        }

        @Override
        public String get1x2Secret(RandomSource p_230154_) {
            return "1x2_se" + (p_230154_.nextInt(1) + 1);
        }

        @Override
        public String get2x2(RandomSource p_230156_) {
            return "2x2_b" + (p_230156_.nextInt(5) + 1);
        }

        @Override
        public String get2x2Secret(RandomSource p_230158_) {
            return "2x2_s1";
        }
    }

    public static final class SimpleGrid {
        private final int[][] grid;
        public final int width;
        public final int height;
        private final int valueIfOutside;

        public SimpleGrid(int width, int height, int valueIfOutside) {
            this.width = width;
            this.height = height;
            this.valueIfOutside = valueIfOutside;
            this.grid = new int[width][height];
        }

        public void set(int x, int y, int value) {
            if (x >= 0 && x < this.width && y >= 0 && y < this.height) {
                this.grid[x][y] = value;
            }
        }

        public void set(int minX, int minY, int maxX, int maxY, int value) {
            for (int i = minY; i <= maxY; i++) {
                for (int j = minX; j <= maxX; j++) {
                    this.set(j, i, value);
                }
            }
        }

        public int get(int x, int y) {
            return x >= 0 && x < this.width && y >= 0 && y < this.height
                ? this.grid[x][y]
                : this.valueIfOutside;
        }

        public void setif(int x, int y, int oldValue, int newValue) {
            if (this.get(x, y) == oldValue) {
                this.set(x, y, newValue);
            }
        }

        public boolean edgesTo(int x, int y, int expectedValue) {
            return this.get(x - 1, y) == expectedValue
                || this.get(x + 1, y) == expectedValue
                || this.get(x, y + 1) == expectedValue
                || this.get(x, y - 1) == expectedValue;
        }
    }

    static class ThirdFloorRoomCollection extends VanillaMansionPieces.SecondFloorRoomCollection {
    }
}
