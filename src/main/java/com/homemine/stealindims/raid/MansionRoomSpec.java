package com.homemine.stealindims.raid;

import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * One room archetype the mansion generator may place, loaded from
 * {@code data/stealindims/mansion_room/<id>.json} (design §3.3, plan §P1.5).
 *
 * <p>The size window is measured in <em>cells</em>, one cell being {@link MansionLayout#CELL_SIZE}
 * blocks. A window of {@code 2..3 × 2..2} is therefore a room of 16×16 up to 24×16 blocks.
 *
 * <p>The generator's rectangle lottery is fixed at {@code 1..4 × 1..3} cells (design §3.2). An
 * archetype only ever biases the mix: at every packed rectangle the generator draws, by
 * {@link #weight()}, one of the archetypes whose window contains it. That keeps the "rooms are any
 * rectangle" promise while still letting a data pack say "this mansion is mostly libraries".
 *
 * <p>{@link #props()} and {@link #lootTier()} used to be written to disk and never read, which is
 * why every room came out an empty box (P4 defect D6). The builder now walks {@code props} to furnish
 * the room and maps {@code loot_tier} onto the tier of the container that room holds (fix F3), so both
 * fields are load-bearing.
 *
 * @param type     the room type, which decides its contents and whether it gets a roof
 * @param weight   relative chance of drawing this archetype; {@code 0} means "never draw it"
 * @param minW     narrowest room, in cells, this archetype may be drawn at
 * @param maxW     widest room, in cells
 * @param minD     shallowest room, in cells
 * @param maxD     deepest room, in cells
 * @param roofed   whether the room gets a ceiling; courtyards are the only shipped roofless type
 * @param props    the furniture the builder places, in order; empty for the special rooms
 * @param lootTier the container quality a room of this type is stocked with
 */
public record MansionRoomSpec(MansionLayout.RoomType type, int weight,
                              int minW, int maxW, int minD, int maxD, boolean roofed,
                              List<Prop> props, LootTier lootTier) {

    /**
     * One piece of furniture (fix F3). Exactly the shape the data pack writes, so a pack author can
     * see what each field does:
     *
     * @param kind   the furniture archetype, e.g. {@code bed} / {@code bookshelf} / {@code barrels};
     *               the builder has a placer per kind and skips what it does not know
     * @param at     where in the room it goes: {@code center}, {@code walls}, {@code corner} or
     *               {@code all}
     * @param height how tall the piece builds, in blocks; {@code 0} means "as the placer sees fit"
     */
    public record Prop(String kind, String at, int height) {

        public boolean atCenter() {
            return "center".equals(this.at);
        }

        public boolean atWalls() {
            return "walls".equals(this.at);
        }

        public boolean atCorner() {
            return "corner".equals(this.at);
        }
    }

    /**
     * The container quality a room is stocked with (fix F3). The mapping onto
     * {@link RaidLootGenerator.Kind} is what makes {@code loot_tier} more than a comment: a library's
     * purple chest really is a purple chest, and a hall has no container at all.
     */
    public enum LootTier {
        NONE,
        LOW,
        MEDIUM,
        HIGH;

        public String id() {
            return this.name().toLowerCase(Locale.ROOT);
        }

        /** The container this tier is stocked with, or {@code null} when the room gets none. */
        @Nullable
        public RaidLootGenerator.Kind kind() {
            return switch (this) {
                case NONE -> null;
                case LOW -> RaidLootGenerator.Kind.BLUE;
                case MEDIUM -> RaidLootGenerator.Kind.PURPLE;
                case HIGH -> RaidLootGenerator.Kind.GOLD;
            };
        }

        @Nullable
        public static LootTier byId(String id) {
            for (LootTier tier : values()) {
                if (tier.id().equals(id)) {
                    return tier;
                }
            }
            return null;
        }
    }

    public boolean contains(int w, int d) {
        return w >= this.minW && w <= this.maxW && d >= this.minD && d <= this.maxD;
    }

    /** Whether this archetype may be drawn at all: the special rooms are placed by the generator. */
    public boolean drawable() {
        return this.weight > 0
                && this.type != MansionLayout.RoomType.ENTRANCE
                && this.type != MansionLayout.RoomType.CORRIDOR
                && this.type != MansionLayout.RoomType.STAIRWELL
                && this.type != MansionLayout.RoomType.SUBMISSION
                && this.type != MansionLayout.RoomType.STATUE;
    }

    /** Whether the room carries a piece of the given kind; used by the builder and the tests. */
    public boolean hasProp(String kind) {
        for (Prop prop : this.props) {
            if (prop.kind().equals(kind)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The shipped mix, used verbatim when no data pack overrides it and by the headless tests, which
     * never load a resource manager. It mirrors {@code data/stealindims/mansion_room/*.json}.
     */
    public static List<MansionRoomSpec> defaults() {
        return List.of(
                new MansionRoomSpec(MansionLayout.RoomType.ENTRANCE, 0, 1, 2, 1, 2, true,
                        List.of(prop("chandelier", "center", 4), prop("carpet", "center", 0),
                                prop("banner", "walls", 4)),
                        LootTier.NONE),
                new MansionRoomSpec(MansionLayout.RoomType.HALL, 5, 2, 4, 2, 3, true,
                        List.of(prop("chandelier", "center", 4), prop("carpet", "center", 0),
                                prop("banner", "walls", 4), prop("lantern", "walls", 0)),
                        LootTier.NONE),
                new MansionRoomSpec(MansionLayout.RoomType.CORRIDOR, 0, 1, 1, 1, 3, true,
                        List.of(prop("carpet", "center", 0), prop("lantern", "walls", 0)),
                        LootTier.NONE),
                new MansionRoomSpec(MansionLayout.RoomType.GUEST, 8, 1, 2, 1, 2, true,
                        List.of(prop("bed", "corner", 0), prop("wardrobe", "walls", 0),
                                prop("carpet", "center", 0), prop("lantern", "center", 0)),
                        LootTier.LOW),
                new MansionRoomSpec(MansionLayout.RoomType.LIBRARY, 5, 2, 3, 1, 2, true,
                        List.of(prop("bookshelf", "walls", 3), prop("enchanting_table", "center", 0),
                                prop("hidden_chest", "corner", 0), prop("lantern", "center", 0)),
                        LootTier.MEDIUM),
                new MansionRoomSpec(MansionLayout.RoomType.DINING, 5, 1, 2, 2, 2, true,
                        List.of(prop("table", "center", 0), prop("smoker", "corner", 0),
                                prop("barrels", "walls", 0), prop("chandelier", "center", 4)),
                        LootTier.MEDIUM),
                new MansionRoomSpec(MansionLayout.RoomType.COUNCIL, 4, 2, 3, 2, 3, true,
                        List.of(prop("cartography_table", "center", 0), prop("banner", "walls", 4),
                                prop("locked_door", "walls", 0), prop("bookshelf", "walls", 2)),
                        LootTier.MEDIUM),
                new MansionRoomSpec(MansionLayout.RoomType.CELL, 5, 1, 1, 2, 3, true,
                        List.of(prop("bars", "center", 2), prop("iron_door", "walls", 0),
                                prop("carpet", "corner", 0)),
                        LootTier.LOW),
                new MansionRoomSpec(MansionLayout.RoomType.STORAGE, 7, 1, 2, 1, 1, true,
                        List.of(prop("barrels", "walls", 0), prop("chest", "corner", 0),
                                prop("lantern", "center", 0)),
                        LootTier.HIGH),
                new MansionRoomSpec(MansionLayout.RoomType.COURTYARD, 3, 2, 3, 2, 3, false,
                        List.of(prop("tree", "center", 5), prop("well", "corner", 0)),
                        LootTier.NONE),
                new MansionRoomSpec(MansionLayout.RoomType.STAIRWELL, 0, 1, 1, 1, 1, true,
                        List.of(), LootTier.NONE),
                new MansionRoomSpec(MansionLayout.RoomType.STATUE, 0, 2, 3, 2, 3, true,
                        List.of(prop("illager_statue", "center", 7)), LootTier.NONE),
                new MansionRoomSpec(MansionLayout.RoomType.SUBMISSION, 0, 1, 1, 1, 1, true,
                        List.of(), LootTier.NONE));
    }

    private static Prop prop(String kind, String at, int height) {
        return new Prop(kind, at, height);
    }
}
