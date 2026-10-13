package com.homemine.stealindims.raid;

import com.homemine.stealindims.ModBlocks;
import com.homemine.stealindims.StealInDimsMod;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Deterministically rebuilds the arena of a raid.
 *
 * <p>Only one raid runs per dimension at a time, so the arena always lives at the coordinates
 * described by {@link RaidArena}. Every entry wipes those coordinates and builds them again from the
 * raid's seed, which keeps a re-entry reproducible and avoids leaving anything behind from a
 * previous run.
 *
 * <p>The build is a fixed pipeline: wipe, rebuild the extraction room, subdivide each of the four
 * regions with {@link RaidLayoutBuilder} and raise both of its layers, connect the regions with a
 * ring of corridors, cut the vestibule that leads in from the map's edge, spiral a stairwell down
 * through each region, and finally scatter the loot containers.
 */
public final class RaidArenaBuilder {

    /** Blocks below every floor, purely cosmetic thickness. */
    private static final int FOUNDATION_DEPTH = 1;

    /** Height of a doorway: two blocks, so a standing player fits without crouching. */
    private static final int DOOR_HEIGHT = 2;

    /** How far a corridor reaches into the region it enters, past the shared wall. */
    private static final int INNER_STUB = 2;

    /** Width of the corridors that ring the four regions. */
    private static final int CORRIDOR_WIDTH = 5;
    /** Half of {@link #CORRIDOR_WIDTH}; every corridor is centred on it, so it is odd. */
    private static final int CORRIDOR_HALF = CORRIDOR_WIDTH / 2;

    /** Half width of the stairwell shaft, which is {@code (2 * this + 1)} blocks square. */
    private static final int STAIR_SHAFT_HALF = 2;
    /** Chebyshev radius of the stairwell's railing. */
    private static final int STAIR_RAIL_HALF = 3;

    /** Chebyshev radius around a region's centre kept clear for its vault (built in stage 5). */
    private static final int VAULT_RESERVE_RADIUS = RaidArena.VAULT_HALF + 1;
    /** How far the door passage reaches west of a vault's wall; kept clear of containers too. */
    private static final int VAULT_APPROACH_LENGTH = 3;
    /** Chebyshev radius around a stairwell kept clear of containers. */
    private static final int STAIR_CLEARANCE = STAIR_RAIL_HALF + 2;
    /** Chebyshev radius around a doorway kept clear of containers. */
    private static final int DOOR_CLEARANCE = 2;

    /** Accent a tinted chest is outlined with. */
    private static final int TINT_ACCENT = 0x4A4A4A;
    private static final int TINT_BLUE = 0x3A46C8;
    private static final int TINT_PURPLE = 0x8B2FC9;
    private static final int TINT_GOLD = 0xE0A800;

    /** Sophisticated Storage's chest, re-tinted per tier; without the mod the tier falls to barrels. */
    private static final ResourceLocation SOPHISTICATED_CHEST =
            ResourceLocation.fromNamespaceAndPath("sophisticatedstorage", "chest");

    private RaidArenaBuilder() {
    }

    /** One region's three surface materials, rotated per region so the four read as distinct. */
    private record RegionPalette(BlockState floor, BlockState wall, BlockState accent) {
    }

    /**
     * Wipes and rebuilds the arena for {@code map} in {@code level}.
     *
     * <p>From P1 on this is also the dispatch point: a map whose datapack {@code geometry} is
     * {@code mansion} is handed to {@link MansionArenaBuilder}, which raises its plan in batches over
     * the following ticks instead of writing every block here. The six legacy maps keep running the
     * code below, byte for byte.
     *
     * @return the seed that was used, so the session can report it to the client
     */
    public static long build(ServerLevel level, RaidTheme theme, RaidTheme.RaidMap map,
                             RaidDifficulty difficulty, long seed) {
        RaidGeometry geometry = RaidArena.geometryOf(map, difficulty, seed);
        if (geometry instanceof MansionGeometry mansion) {
            MansionArenaBuilder.clear(level, mansion);
            MansionArenaBuilder.start(level, theme, map, difficulty, seed, mansion);
            return seed;
        }

        RaidSpawner.clear(level, geometry);
        String size = map.size();
        clear(level, geometry);

        BlockState floor = resolve(theme.palette().floor(), Blocks.STONE_BRICKS.defaultBlockState());
        BlockState wall = resolve(theme.palette().wall(), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
        BlockState accent = resolve(theme.palette().accent(), Blocks.COPPER_BLOCK.defaultBlockState());
        BlockState chest = chestState();

        int formHeight = wallHeightFor(map.form());
        boolean ceiling = hasCeiling(map.form());

        buildExtractionRoom(level, floor, wall, accent);

        RaidLayoutBuilder.RaidLayout[] layouts = new RaidLayoutBuilder.RaidLayout[geometry.regionCount()];
        RegionPalette[] palettes = new RegionPalette[geometry.regionCount()];
        BlockPos[] stairs = new BlockPos[geometry.regionCount()];
        for (int region = 0; region < geometry.regionCount(); region++) {
            RaidArena.Bounds bounds = geometry.regionBounds(region);
            RaidLayoutBuilder.RaidLayout layout =
                    RaidLayoutBuilder.build(bounds, map.layout(), seedFor(seed, region));
            layouts[region] = layout;
            palettes[region] = regionPalette(region, palettes.length, floor, wall, accent);
            stairs[region] = stairCenter(geometry, region, layout);
            for (int layer : geometry.layerYs()) {
                buildLayer(level, bounds, layout.withLayer(layer), palettes[region], formHeight, ceiling);
            }
        }

        connectRegions(level, geometry, palettes, formHeight);
        buildEntryWay(level, geometry, palettes, formHeight);
        buildStairs(level, layouts, stairs, palettes);
        buildVaults(level, geometry, palettes);
        placeContainers(level, geometry, size, layouts, stairs, theme, difficulty, seed, chest);
        return seed;
    }

    /**
     * Per-region layout seed, so the four regions never subdivide identically.
     *
     * <p>Public because {@link RaidSpawner} has to re-derive the same rooms: the layout is not
     * persisted anywhere, so the spawn pass rebuilds it from the session's seed.
     */
    public static long seedFor(long seed, int region) {
        return seed * 31L + region;
    }

    // ---------------------------------------------------------------- clearing

    private static void clear(ServerLevel level, RaidGeometry geometry) {
        int radius = geometry.mapRadius() + 2;
        int centerX = geometry.mapCenterX();

        clearBox(level,
                -RaidArena.ROOM_HALF - 1, RaidArena.ROOM_FLOOR_Y - FOUNDATION_DEPTH, -RaidArena.ROOM_HALF - 1,
                RaidArena.ROOM_HALF + 1, RaidArena.ROOM_CEILING_Y + 1, RaidArena.ROOM_HALF + 1);

        clearBox(level,
                centerX - radius, RaidArena.UNDER_LAYER_Y - FOUNDATION_DEPTH - 1, -radius,
                centerX + radius, RaidArena.MAIN_LAYER_Y + 12, radius);
    }

    /**
     * Sets every non-air block inside the inclusive box to air.
     *
     * <p>Walks the box chunk section by chunk section and skips sections that
     * {@linkplain LevelChunkSection#hasOnlyAir() hold nothing but air}. The arena lives in a void
     * dimension, so almost every section is empty and the wipe costs little more than loading the
     * chunks it touches. Flags are {@link Block#UPDATE_CLIENTS} because the arena is rebuilt right
     * afterwards, so neighbour and shape updates would only be redundant work.
     */
    static void clearBox(ServerLevel level, int minX, int minY, int minZ,
                                 int maxX, int maxY, int maxZ) {
        BlockState air = Blocks.AIR.defaultBlockState();
        int minSectionY = level.getMinSection();
        int maxSectionY = level.getMaxSection();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int chunkX = minX >> 4; chunkX <= (maxX >> 4); chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= (maxZ >> 4); chunkZ++) {
                ChunkAccess chunk = level.getChunk(chunkX, chunkZ);
                LevelChunkSection[] sections = chunk.getSections();

                int fromSection = Math.max(minSectionY, minY >> 4);
                int toSection = Math.min(maxSectionY, maxY >> 4);
                for (int sectionY = fromSection; sectionY <= toSection; sectionY++) {
                    LevelChunkSection section = sections[sectionY - minSectionY];
                    if (section == null || section.hasOnlyAir()) {
                        continue;
                    }

                    int baseY = sectionY << 4;
                    for (int localX = 0; localX < 16; localX++) {
                        int x = (chunkX << 4) + localX;
                        if (x < minX || x > maxX) {
                            continue;
                        }
                        for (int localZ = 0; localZ < 16; localZ++) {
                            int z = (chunkZ << 4) + localZ;
                            if (z < minZ || z > maxZ) {
                                continue;
                            }
                            for (int localY = 0; localY < 16; localY++) {
                                int y = baseY + localY;
                                if (y < minY || y > maxY) {
                                    continue;
                                }
                                if (!section.getBlockState(localX, localY, localZ).isAir()) {
                                    level.setBlock(cursor.set(x, y, z), air, Block.UPDATE_CLIENTS);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- extraction room

    /**
     * The extraction room: a sealed box in the void, holding the return anchor and the transit crate.
     * Nothing walkable connects it to the map, so the only ways in and out are the anchor's
     * interactions (design §9).
     */
    static void buildExtractionRoom(ServerLevel level, BlockState floor, BlockState wall,
                                            BlockState accent) {
        int half = RaidArena.ROOM_HALF;
        int floorY = RaidArena.ROOM_FLOOR_Y;
        int ceilingY = RaidArena.ROOM_CEILING_Y;

        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                boolean rim = Math.abs(x) == half || Math.abs(z) == half;
                level.setBlock(new BlockPos(x, floorY, z), rim ? accent : floor, Block.UPDATE_ALL);
                level.setBlock(new BlockPos(x, ceilingY, z), rim ? accent : wall, Block.UPDATE_ALL);

                for (int y = floorY + 1; y < ceilingY; y++) {
                    if (rim) {
                        level.setBlock(new BlockPos(x, y, z), wall, Block.UPDATE_ALL);
                    }
                }
            }
        }

        BlockPos anchor = RaidArena.safeRoomAnchor();
        level.setBlock(anchor, ModBlocks.RAID_PORTAL.get().defaultBlockState(), Block.UPDATE_ALL);
        BlockPos crate = RaidArena.safeRoomCrate();
        level.setBlock(crate, ModBlocks.TRANSFER_CRATE.get().defaultBlockState(), Block.UPDATE_ALL);
        BlockPos readyPad = RaidArena.safeRoomReadyPad();
        level.setBlock(readyPad, ModBlocks.RAID_READY_PAD.get().defaultBlockState(), Block.UPDATE_ALL);
        BlockPos enderChest = RaidArena.safeRoomEnderChest();
        level.setBlock(enderChest, Blocks.ENDER_CHEST.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos exitPortal = RaidArena.safeRoomExitPortal();
        level.setBlock(exitPortal, ModBlocks.RAID_EXIT_PORTAL.get().defaultBlockState(), Block.UPDATE_ALL);
    }

    // ---------------------------------------------------------------- regions

    /** How many wall blocks a form stacks on top of the floor before its accent cap. */
    static int wallHeightFor(@Nullable String form) {
        return switch (form == null ? "" : form) {
            case "enclosed_dungeon" -> 2;
            case "underground_cave" -> 6;
            default -> 1;
        };
    }

    /** Whether a form roofs its rooms. The open forms stay roofless so the void shows through. */
    static boolean hasCeiling(@Nullable String form) {
        return "enclosed_dungeon".equals(form) || "underground_cave".equals(form);
    }

    /**
     * Rotates the theme's three materials through the regions, so regions differ by which material
     * lands on the floor rather than by a separate palette each.
     *
     * @param regionCount how many regions the map has; the rotation cycles through them
     */
    private static RegionPalette regionPalette(int region, int regionCount, BlockState floor,
                                               BlockState wall, BlockState accent) {
        return switch (Math.floorMod(region, regionCount)) {
            case 1 -> new RegionPalette(wall, accent, floor);
            case 2 -> new RegionPalette(accent, floor, wall);
            case 3 -> new RegionPalette(floor, accent, wall);
            default -> new RegionPalette(floor, wall, accent);
        };
    }

    /**
     * Raises one region's floor plan on one layer: paving and foundation over the whole rectangle,
     * then each room's rim as wall plus an accent cap, an optional roof, and finally the doorways
     * punched through the shared walls.
     */
    private static void buildLayer(ServerLevel level, RaidArena.Bounds bounds,
                                   RaidLayoutBuilder.RaidLayout layout, RegionPalette palette,
                                   int formHeight, boolean ceiling) {
        int layer = layout.rooms().get(0).layer();
        int wallTop = layer + formHeight;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                level.setBlock(cursor.set(x, layer - 1, z), palette.floor(), Block.UPDATE_CLIENTS);
                for (int depth = 1; depth <= FOUNDATION_DEPTH; depth++) {
                    level.setBlock(cursor.set(x, layer - 1 - depth, z), palette.wall(),
                            Block.UPDATE_CLIENTS);
                }
            }
        }

        for (RaidRoom room : layout.rooms()) {
            for (int x = room.minX(); x <= room.maxX(); x++) {
                for (int z = room.minZ(); z <= room.maxZ(); z++) {
                    boolean rim = x == room.minX() || x == room.maxX()
                            || z == room.minZ() || z == room.maxZ();
                    if (rim) {
                        for (int y = layer; y < wallTop; y++) {
                            level.setBlock(cursor.set(x, y, z), palette.wall(), Block.UPDATE_CLIENTS);
                        }
                        level.setBlock(cursor.set(x, wallTop, z), palette.accent(), Block.UPDATE_CLIENTS);
                    }
                    if (ceiling) {
                        level.setBlock(cursor.set(x, wallTop + 1, z), palette.wall(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }

        for (RaidLayoutBuilder.Door door : layout.doors()) {
            for (int x = door.minX(); x <= door.maxX(); x++) {
                for (int z = door.minZ(); z <= door.maxZ(); z++) {
                    for (int y = layer; y < layer + DOOR_HEIGHT; y++) {
                        level.setBlock(cursor.set(x, y, z), Blocks.AIR.defaultBlockState(),
                                Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- corridors

    /**
     * Links the four regions into a ring: 0-1 along the north, 1-3 down the east, 3-2 along the
     * south and 2-0 up the west (design §3). Nothing bypasses the ring, so every region is reached
     * through its neighbours.
     */
    private static void connectRegions(ServerLevel level, RaidGeometry geometry, RegionPalette[] palettes,
                                       int formHeight) {
        carveCorridor(level, geometry, true, 0, 1, palettes[0], formHeight);
        carveCorridor(level, geometry, true, 2, 3, palettes[2], formHeight);
        carveCorridor(level, geometry, false, 0, 2, palettes[0], formHeight);
        carveCorridor(level, geometry, false, 1, 3, palettes[1], formHeight);
    }

    /**
     * Cuts one corridor between two neighbouring regions, centred on their shared row (or column),
     * and posts a blue entrance where it arrives at each of them.
     *
     * @param alongX {@code true} when the two regions sit side by side along X, so the corridor runs
     *               along X and its width is measured in Z
     */
    private static void carveCorridor(ServerLevel level, RaidGeometry geometry, boolean alongX,
                                      int regionA, int regionB, RegionPalette palette, int formHeight) {
        int layer = RaidArena.MAIN_LAYER_Y;
        int wallTop = layer + formHeight;
        RaidArena.Bounds a = geometry.regionBounds(regionA);
        RaidArena.Bounds b = geometry.regionBounds(regionB);
        BlockPos centerA = geometry.regionCenter(regionA);

        int minX;
        int maxX;
        int minZ;
        int maxZ;
        if (alongX) {
            minX = a.maxX() - INNER_STUB;
            maxX = b.minX() + INNER_STUB;
            minZ = centerA.getZ() - CORRIDOR_HALF;
            maxZ = centerA.getZ() + CORRIDOR_HALF;
        } else {
            minX = centerA.getX() - CORRIDOR_HALF;
            maxX = centerA.getX() + CORRIDOR_HALF;
            minZ = a.maxZ() - INNER_STUB;
            maxZ = b.minZ() + INNER_STUB;
        }

        // The pavement is one block wider than the passage on each side, so the railing has
        // something to stand on instead of floating.
        int paveMinX = alongX ? minX : minX - 1;
        int paveMaxX = alongX ? maxX : maxX + 1;
        int paveMinZ = alongX ? minZ - 1 : minZ;
        int paveMaxZ = alongX ? maxZ + 1 : maxZ;
        pave(level, palette, layer, paveMinX, paveMaxX, paveMinZ, paveMaxZ);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = layer; y < layer + DOOR_HEIGHT; y++) {
                    level.setBlock(cursor.set(x, y, z), Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_CLIENTS);
                }
            }
        }

        if (alongX) {
            for (int x = minX; x <= maxX; x++) {
                rail(level, geometry, x, paveMinZ, layer, wallTop, palette);
                rail(level, geometry, x, paveMaxZ, layer, wallTop, palette);
            }
        } else {
            for (int z = minZ; z <= maxZ; z++) {
                rail(level, geometry, paveMinX, z, layer, wallTop, palette);
                rail(level, geometry, paveMaxX, z, layer, wallTop, palette);
            }
        }

        markEntrance(level, alongX, alongX ? a.maxX() : a.maxZ(),
                (alongX ? centerA.getZ() : centerA.getX()) - 1,
                (alongX ? centerA.getZ() : centerA.getX()) + 1, -1, layer, wallTop);
        markEntrance(level, alongX, alongX ? b.minX() : b.minZ(),
                (alongX ? centerA.getZ() : centerA.getX()) - 1,
                (alongX ? centerA.getZ() : centerA.getX()) + 1, 1, layer, wallTop);
    }

    /** Paves one rectangle of floor with its foundation underneath. */
    private static void pave(ServerLevel level, RegionPalette palette, int layer,
                             int minX, int maxX, int minZ, int maxZ) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                level.setBlock(cursor.set(x, layer - 1, z), palette.floor(), Block.UPDATE_CLIENTS);
                for (int depth = 1; depth <= FOUNDATION_DEPTH; depth++) {
                    level.setBlock(cursor.set(x, layer - 1 - depth, z), palette.wall(),
                            Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /**
     * Posts a parapet column, unless the column belongs to a region — there the region's own wall
     * already runs along the same line, and overwriting it would seal a doorway.
     */
    private static void rail(ServerLevel level, RaidGeometry geometry, int x, int z, int layer, int wallTop,
                            RegionPalette palette) {
        if (insideAnyRegion(geometry, x, z)) {
            return;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = layer; y < wallTop; y++) {
            level.setBlock(cursor.set(x, y, z), palette.wall(), Block.UPDATE_CLIENTS);
        }
        level.setBlock(cursor.set(x, wallTop, z), palette.accent(), Block.UPDATE_CLIENTS);
    }

    private static boolean insideAnyRegion(RaidGeometry geometry, int x, int z) {
        for (int region = 0; region < geometry.regionCount(); region++) {
            if (geometry.regionBounds(region).contains(x, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Marks where a corridor arrives at a region: blue glass in the wall columns flanking the
     * opening, and a blue carpet running a couple of blocks inside.
     *
     * <p>The glass only reaches down to the doorway's head where the wall is tall enough to carry
     * it. The one-block forms have no lintel, so their openings are frame on the sides only.
     *
     * @param inward which way the region lies from the wall: {@code -1} when the corridor arrives at
     *               the region's far edge, {@code +1} when it arrives at the near one
     */
    private static void markEntrance(ServerLevel level, boolean alongX, int wallCoord,
                                     int spanLow, int spanHigh, int inward, int layer, int wallTop) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int span = spanLow - 1; span <= spanHigh + 1; span++) {
            boolean jamb = span < spanLow || span > spanHigh;
            for (int y = layer; y <= wallTop; y++) {
                if (!jamb && y < layer + DOOR_HEIGHT) {
                    continue;
                }
                level.setBlock(cursor.set(alongX ? wallCoord : span, y, alongX ? span : wallCoord),
                        Blocks.BLUE_STAINED_GLASS.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        for (int span = spanLow; span <= spanHigh; span++) {
            for (int step = 1; step <= CORRIDOR_HALF; step++) {
                int coord = wallCoord + inward * step;
                level.setBlock(cursor.set(alongX ? coord : span, layer, alongX ? span : coord),
                        Blocks.BLUE_CARPET.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    // ---------------------------------------------------------------- map entrance

    /**
     * Cuts the vestibule that leads from the map's outer edge into region 0, and caps its far end so
     * the only way onto the map is through the ring of regions.
     */
    private static void buildEntryWay(ServerLevel level, RaidGeometry geometry, RegionPalette[] palettes,
                                      int formHeight) {
        int layer = RaidArena.MAIN_LAYER_Y;
        int wallTop = layer + formHeight;
        RegionPalette palette = palettes[0];
        RaidArena.Bounds region = geometry.regionBounds(0);
        int centerZ = geometry.regionCenter(0).getZ();

        int westX = geometry.mapCenterX() - geometry.mapRadius();
        int eastX = region.minX() + INNER_STUB;
        int minZ = centerZ - CORRIDOR_HALF;
        int maxZ = centerZ + CORRIDOR_HALF;
        pave(level, palette, layer, westX, eastX, minZ - 1, maxZ + 1);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = westX; x <= eastX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = layer; y < layer + DOOR_HEIGHT; y++) {
                    level.setBlock(cursor.set(x, y, z), Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_CLIENTS);
                }
            }
        }
        for (int z = minZ - 1; z <= maxZ + 1; z++) {
            for (int y = layer; y < wallTop; y++) {
                level.setBlock(cursor.set(westX, y, z), palette.wall(), Block.UPDATE_CLIENTS);
            }
            level.setBlock(cursor.set(westX, wallTop, z), palette.accent(), Block.UPDATE_CLIENTS);
        }
        for (int x = westX; x <= eastX; x++) {
            rail(level, geometry, x, minZ - 1, layer, wallTop, palette);
            rail(level, geometry, x, maxZ + 1, layer, wallTop, palette);
        }

        markEntrance(level, true, region.minX(), centerZ - 1, centerZ + 1, 1, layer, wallTop);

        // Set off to the side of the passage: the anchor is a solid block, and it would otherwise
        // stand squarely in the way of anyone walking in from the spawn point.
        BlockPos anchor = geometry.mapAnchor().offset(0, 0, -CORRIDOR_HALF);
        level.setBlock(anchor, ModBlocks.RAID_PORTAL.get().defaultBlockState(), Block.UPDATE_ALL);
    }

    // ---------------------------------------------------------------- stairwells

    /**
     * Where the stairwell goes inside a region: near the centre of the largest room, but clear of
     * the region's vault, with enough room around it for the railing.
     *
     * <p>When the largest room is no wider than the railing itself, there is no such spot and the
     * room's centre is used; {@link #buildStairs} then leaves the railing out, because it would fall
     * on the room's own walls or across a doorway.
     */
    private static BlockPos stairCenter(RaidGeometry geometry, int region,
                                        RaidLayoutBuilder.RaidLayout layout) {
        RaidRoom room = layout.largestRoom();
        BlockPos center = room.center();
        BlockPos regionCenter = geometry.regionCenter(region);
        int loX = room.minX() + STAIR_RAIL_HALF + 1;
        int hiX = room.maxX() - STAIR_RAIL_HALF - 1;
        int loZ = room.minZ() + STAIR_RAIL_HALF + 1;
        int hiZ = room.maxZ() - STAIR_RAIL_HALF - 1;

        BlockPos best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int x = loX; x <= hiX; x++) {
            for (int z = loZ; z <= hiZ; z++) {
                // The whole stairwell, railing included, has to miss the vault and its passage.
                if (Math.max(Math.abs(x - regionCenter.getX()), Math.abs(z - regionCenter.getZ()))
                        <= VAULT_RESERVE_RADIUS + STAIR_RAIL_HALF) {
                    continue;
                }
                int distance = Math.max(Math.abs(x - center.getX()), Math.abs(z - center.getZ()));
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new BlockPos(x, RaidArena.MAIN_LAYER_Y, z);
                }
            }
        }
        return best != null ? best : center;
    }

    /**
     * Spirals a stairwell down through each region's largest room, joining the two layers.
     *
     * <p>The shaft is dug, a solid newel is raised in its middle, and the shaft's rim becomes the
     * staircase itself: one block per rim cell, each a step lower than the last, running clockwise
     * from the main layer round to the under layer.
     */
    private static void buildStairs(ServerLevel level, RaidLayoutBuilder.RaidLayout[] layouts,
                                    BlockPos[] stairs, RegionPalette[] palettes) {
        int top = RaidArena.MAIN_LAYER_Y;
        int bottom = RaidArena.UNDER_LAYER_Y;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int[][] ring = stairRing(STAIR_SHAFT_HALF);

        for (int region = 0; region < layouts.length; region++) {
            BlockPos shaft = stairs[region];
            RegionPalette palette = palettes[region];
            int centerX = shaft.getX();
            int centerZ = shaft.getZ();

            for (int x = centerX - STAIR_SHAFT_HALF; x <= centerX + STAIR_SHAFT_HALF; x++) {
                for (int z = centerZ - STAIR_SHAFT_HALF; z <= centerZ + STAIR_SHAFT_HALF; z++) {
                    for (int y = bottom; y <= top + 1; y++) {
                        level.setBlock(cursor.set(x, y, z), Blocks.AIR.defaultBlockState(),
                                Block.UPDATE_CLIENTS);
                    }
                }
            }
            for (int x = centerX - 1; x <= centerX + 1; x++) {
                for (int z = centerZ - 1; z <= centerZ + 1; z++) {
                    for (int y = bottom; y < top; y++) {
                        level.setBlock(cursor.set(x, y, z), palette.wall(), Block.UPDATE_CLIENTS);
                    }
                }
            }
            for (int step = 0; step < ring.length; step++) {
                level.setBlock(cursor.set(centerX + ring[step][0], top - 1 - step,
                        centerZ + ring[step][1]), palette.accent(), Block.UPDATE_CLIENTS);
            }

            RaidLayoutBuilder.RaidLayout layout = layouts[region];
            for (int x = centerX - STAIR_RAIL_HALF; x <= centerX + STAIR_RAIL_HALF; x++) {
                for (int z = centerZ - STAIR_RAIL_HALF; z <= centerZ + STAIR_RAIL_HALF; z++) {
                    if (Math.max(Math.abs(x - centerX), Math.abs(z - centerZ)) != STAIR_RAIL_HALF) {
                        continue;
                    }
                    // Leave the landing above the top step open, or the stairwell cannot be entered.
                    if (Math.max(Math.abs(x - (centerX + ring[0][0])),
                            Math.abs(z - (centerZ + ring[0][1]))) <= 1) {
                        continue;
                    }
                    if (isDoorway(layout, x, z)) {
                        continue;
                    }
                    if (!level.getBlockState(cursor.set(x, top, z)).isAir()) {
                        continue;
                    }
                    level.setBlock(cursor.set(x, top, z), palette.wall(), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /**
     * The cells of a square's rim, clockwise from its north-west corner. Serving them in order as
     * descending blocks turns the rim into a spiral staircase.
     */
    private static int[][] stairRing(int half) {
        List<int[]> ring = new ArrayList<>(8 * half);
        for (int z = -half; z <= half; z++) {
            ring.add(new int[]{-half, z});
        }
        for (int x = -half + 1; x <= half; x++) {
            ring.add(new int[]{x, half});
        }
        for (int z = half - 1; z >= -half; z--) {
            ring.add(new int[]{half, z});
        }
        for (int x = half - 1; x > -half; x--) {
            ring.add(new int[]{x, -half});
        }
        return ring.toArray(new int[0][]);
    }

    // ---------------------------------------------------------------- vaults

    /**
     * Raises the sealed vault at the centre of every region: a 9×9 shell, an iron door on its west
     * face, and an anchor plus a transit crate inside (design §3.1, §10).
     *
     * <p>The region's own geometry runs straight through its centre — that is where the four rooms
     * meet — so the shell's inside is carved first and everything is rebuilt from the region's
     * palette. A short passage is cut west of the door so the vault always has a way in; the
     * vault wins over whatever the rooms put there, which is why it is built last.
     */
    private static void buildVaults(ServerLevel level, RaidGeometry geometry, RegionPalette[] palettes) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int layer = RaidArena.MAIN_LAYER_Y;
        int floorY = RaidArena.ROOM_FLOOR_Y;
        int ceilingY = layer + DOOR_HEIGHT;
        int half = RaidArena.VAULT_HALF;

        for (int region = 0; region < geometry.regionCount(); region++) {
            BlockPos center = geometry.vaultCenter(region);
            RegionPalette palette = palettes[region];
            int cx = center.getX();
            int cz = center.getZ();
            int westX = cx - half;

            // Carve the shell's inside, the doorway and the passage west of it.
            for (int x = westX - VAULT_APPROACH_LENGTH; x <= cx + half; x++) {
                int zSpan = x < westX ? 1 : half - 1;
                for (int z = cz - zSpan; z <= cz + zSpan; z++) {
                    for (int y = layer; y <= ceilingY; y++) {
                        level.setBlock(cursor.set(x, y, z), Blocks.AIR.defaultBlockState(),
                                Block.UPDATE_CLIENTS);
                    }
                }
            }

            // Shell: floor under the whole footprint, walls and ceiling over it, door in the west.
            for (int x = westX; x <= cx + half; x++) {
                for (int z = cz - half; z <= cz + half; z++) {
                    level.setBlock(cursor.set(x, floorY, z), palette.floor(), Block.UPDATE_CLIENTS);
                    boolean rim = Math.max(Math.abs(x - cx), Math.abs(z - cz)) == half;
                    if (rim) {
                        for (int y = layer; y < ceilingY; y++) {
                            if (x == westX && z == cz) {
                                continue;
                            }
                            level.setBlock(cursor.set(x, y, z), palette.wall(), Block.UPDATE_CLIENTS);
                        }
                    }
                    level.setBlock(cursor.set(x, ceilingY, z), palette.accent(), Block.UPDATE_CLIENTS);
                }
            }

            BlockState lowerDoor = Blocks.IRON_DOOR.defaultBlockState()
                    .setValue(DoorBlock.FACING, Direction.EAST)
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            level.setBlock(cursor.set(westX, layer, cz), lowerDoor, Block.UPDATE_ALL);
            level.setBlock(cursor.set(westX, layer + 1, cz),
                    lowerDoor.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);

            // One button either side of the door, sitting on the floor of the cells directly next
            // to it, so the iron door can be opened from inside and out. The buttons must be
            // orthogonally adjacent to the door: a wall button on the surrounding brickwork is
            // diagonal to the door and never powers it.
            BlockState button = Blocks.STONE_BUTTON.defaultBlockState()
                    .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR)
                    .setValue(FaceAttachedHorizontalDirectionalBlock.FACING, Direction.EAST);
            level.setBlock(cursor.set(westX - 1, layer, cz), button, Block.UPDATE_ALL);
            level.setBlock(cursor.set(westX + 1, layer, cz), button, Block.UPDATE_ALL);

            level.setBlock(geometry.vaultAnchor(region),
                    ModBlocks.RAID_PORTAL.get().defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(geometry.vaultCrate(region),
                    ModBlocks.TRANSFER_CRATE.get().defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static boolean isDoorway(RaidLayoutBuilder.RaidLayout layout, int x, int z) {
        for (RaidLayoutBuilder.Door door : layout.doors()) {
            if (door.covers(x, z)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- containers

    /**
     * Scatters the difficulty's containers over every room of both layers, in the tiers' fixed
     * proportions (design §4.2, §8).
     *
     * <p>Candidate positions are collected first — room interiors, minus the vaults, the stairwells
     * and anything within {@link #DOOR_CLEARANCE} of a doorway — and shuffled once with the raid's
     * seed, so the same seed always scatters the same loot. Positions are drawn from the shuffled
     * list in order, which is what makes the spread even rather than clumped.
     */
    private static void placeContainers(ServerLevel level, RaidGeometry geometry, String size,
                                        RaidLayoutBuilder.RaidLayout[] layouts, BlockPos[] stairs,
                                        RaidTheme theme, RaidDifficulty difficulty, long seed,
                                        @Nullable BlockState chest) {
        List<BlockPos> pool = new ArrayList<>();
        for (int region = 0; region < geometry.regionCount(); region++) {
            BlockPos vault = geometry.vaultCenter(region);
            BlockPos shaft = stairs[region];
            for (int layer : geometry.layerYs()) {
                RaidLayoutBuilder.RaidLayout layout = layouts[region].withLayer(layer);
                for (RaidRoom room : layout.rooms()) {
                    for (int x = room.minX() + 1; x <= room.maxX() - 1; x++) {
                        for (int z = room.minZ() + 1; z <= room.maxZ() - 1; z++) {
                            if (layer == RaidArena.MAIN_LAYER_Y && Math.max(Math.abs(x - vault.getX()),
                                    Math.abs(z - vault.getZ())) <= VAULT_RESERVE_RADIUS) {
                                continue;
                            }
                            // Keep the vault's door passage clear: a container dropped in it would
                            // seal the vault off.
                            if (layer == RaidArena.MAIN_LAYER_Y
                                    && Math.abs(z - vault.getZ()) <= 1
                                    && x >= vault.getX() - RaidArena.VAULT_HALF - VAULT_APPROACH_LENGTH
                                    && x <= vault.getX() - RaidArena.VAULT_HALF - 1) {
                                continue;
                            }
                            if (Math.max(Math.abs(x - shaft.getX()), Math.abs(z - shaft.getZ()))
                                    <= STAIR_CLEARANCE) {
                                continue;
                            }
                            if (nearDoorway(layout, x, z)) {
                                continue;
                            }
                            pool.add(new BlockPos(x, layer, z));
                        }
                    }
                }
            }
        }
        Util.shuffle(pool, RandomSource.create(seed));

        List<RaidLootGenerator.Kind> queue = new ArrayList<>();
        RaidLootGenerator.Kind[] kinds = RaidLootGenerator.Kind.values();
        int[] counts = difficulty.containerCounts();
        for (int tier = 0; tier < kinds.length; tier++) {
            for (int count = 0; count < counts[tier]; count++) {
                queue.add(kinds[tier]);
            }
        }

        List<BlockPos> placed = new ArrayList<>(queue.size());
        int index = 0;
        for (RaidLootGenerator.Kind kind : queue) {
            BlockPos target = null;
            while (index < pool.size()) {
                BlockPos candidate = pool.get(index++);
                if (level.getBlockState(candidate).isAir()
                        && !level.getBlockState(candidate.below()).isAir()
                        && level.getBlockState(candidate.above()).isAir()) {
                    target = candidate;
                    break;
                }
            }
            if (target == null) {
                break;
            }
            if (placeOne(level, target, kind, theme, difficulty, seed, chest)) {
                placed.add(target);
            }
        }

        int conflicts = 0;
        for (BlockPos pos : placed) {
            if (level.getBlockState(pos).isAir() || level.getBlockState(pos.below()).isAir()
                    || !level.getBlockState(pos.above()).isAir()) {
                conflicts++;
            }
        }
        StealInDimsMod.LOGGER.info("Raid containers for a {} map: {} requested, {} placed, {} conflicts",
                size, queue.size(), placed.size(), conflicts);
    }

    private static boolean nearDoorway(RaidLayoutBuilder.RaidLayout layout, int x, int z) {
        for (RaidLayoutBuilder.Door door : layout.doors()) {
            if (door.distance(x, z) <= DOOR_CLEARANCE) {
                return true;
            }
        }
        return false;
    }

    static boolean placeOne(ServerLevel level, BlockPos pos, RaidLootGenerator.Kind kind,
                                    RaidTheme theme, RaidDifficulty difficulty, long seed,
                                    @Nullable BlockState chest) {
        RandomSource random = RandomSource.create(seed).forkPositional().at(pos);
        List<ItemStack> contents = RaidLootGenerator.contents(theme, difficulty, kind, random);
        if (contents.isEmpty()) {
            return false;
        }
        switch (kind) {
            case POT:
                // A pot holds nothing until it breaks: its loot is derived again from the same
                // (seed, position) at that moment, which is what keeps it from being worth anything
                // to a player who never gets out.
                level.setBlock(pos, Blocks.DECORATED_POT.defaultBlockState(), Block.UPDATE_ALL);
                return true;
            case BARREL:
                return fillBarrel(level, pos, contents);
            default:
                return chest != null && fillChest(level, pos, chest, tintFor(kind), contents)
                        || fillBarrel(level, pos, contents);
        }
    }

    private static int tintFor(RaidLootGenerator.Kind kind) {
        return switch (kind) {
            case BLUE -> TINT_BLUE;
            case PURPLE -> TINT_PURPLE;
            case GOLD -> TINT_GOLD;
            default -> TINT_ACCENT;
        };
    }

    private static boolean fillBarrel(ServerLevel level, BlockPos pos, List<ItemStack> contents) {
        level.setBlock(pos, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
        if (!(level.getBlockEntity(pos) instanceof BarrelBlockEntity barrel)) {
            return false;
        }
        int slot = 0;
        for (ItemStack stack : contents) {
            if (slot >= barrel.getContainerSize()) {
                break;
            }
            barrel.setItem(slot++, stack.copy());
        }
        barrel.setChanged();
        return true;
    }

    /**
     * Fills a Sophisticated Storage chest, tinted for its tier.
     *
     * <p>The mod is optional and not a compile dependency, so everything here is reflective. The
     * block entity the world creates is bare: its inventory only sizes itself when asked, so the
     * slot count is read once before the handler is requested. Any failure at all — the mod absent,
     * a signature changed, the block entity refusing to hand out a handler — returns {@code false},
     * and the caller falls back to a plain barrel.
     */
    private static boolean fillChest(ServerLevel level, BlockPos pos, BlockState state, int tint,
                                     List<ItemStack> contents) {
        level.setBlock(pos, state, Block.UPDATE_ALL);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return false;
        }
        try {
            Object wrapper = invoke(findMethod(blockEntity.getClass(), "getStorageWrapper"), blockEntity);
            if (wrapper != null) {
                invoke(findMethod(wrapper.getClass(), "getNumberOfInventorySlots"), wrapper);
            }
            Object handler = invoke(findMethod(blockEntity.getClass(), "getExternalItemHandler",
                    Direction.class), blockEntity, (Object) null);
            if (handler == null) {
                return false;
            }
            Method slots = findMethod(handler.getClass(), "getSlots");
            Method insert = findMethod(handler.getClass(), "insertItem", int.class, ItemStack.class,
                    boolean.class);
            if (slots == null || insert == null) {
                return false;
            }
            int size = (Integer) invoke(slots, handler);
            if (size <= 0) {
                return false;
            }
            int slot = 0;
            for (ItemStack stack : contents) {
                if (slot >= size) {
                    break;
                }
                insert.invoke(handler, slot++, stack.copy(), false);
            }

            // setColors lives on the storage wrapper, not the block entity; -1 means untinted.
            invoke(findMethod(wrapper.getClass(), "setColors", int.class, int.class), wrapper,
                    tint, TINT_ACCENT);
            invoke(findMethod(blockEntity.getClass(), "setUpdateBlockRender"), blockEntity);
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
            blockEntity.setChanged();
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            StealInDimsMod.LOGGER.debug("Sophisticated Storage chest at {} stayed empty: {}",
                    pos, exception.toString());
            return false;
        }
    }

    @Nullable
    static BlockState chestState() {
        return BuiltInRegistries.BLOCK.getOptional(SOPHISTICATED_CHEST)
                .map(Block::defaultBlockState)
                .orElse(null);
    }

    /**
     * Finds a method by name, searching the class and then its superclasses, and falling back to the
     * public methods it inherits — an inventory handler may only satisfy the interface, not declare
     * the methods itself.
     */
    @Nullable
    private static Method findMethod(Class<?> type, String name, Class<?>... parameters) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                makeAccessible(method);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Keep walking up the hierarchy.
            }
        }
        try {
            Method method = type.getMethod(name, parameters);
            makeAccessible(method);
            return method;
        } catch (NoSuchMethodException exception) {
            return null;
        }
    }

    private static void makeAccessible(Method method) {
        try {
            method.setAccessible(true);
        } catch (RuntimeException ignored) {
            // Already reachable, or the module refuses; invoking it will say which.
        }
    }

    @Nullable
    private static Object invoke(@Nullable Method method, Object target, Object... arguments)
            throws ReflectiveOperationException {
        return method == null ? null : method.invoke(target, arguments);
    }

    // ---------------------------------------------------------------- helpers

    static BlockState resolve(@Nullable ResourceLocation id, BlockState fallback) {
        return BuiltInRegistries.BLOCK.getOptional(id).map(Block::defaultBlockState).orElse(fallback);
    }
}
