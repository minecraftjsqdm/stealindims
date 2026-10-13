package com.homemine.stealindims.raid;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;

/**
 * A raid theme, loaded from {@code data/stealindims/raid_theme/<name>.json}.
 *
 * <p>{@link #dimension} is the <em>host</em> dimension the theme belongs to, not the raid dimension:
 * the raid dimension is derived from it through {@link RaidDimensions#raidDimension}.
 *
 * <p>The theme also owns everything that makes a raid look and pay out the way it does: the block
 * {@link RaidPalette}, the {@link LootPool} every container is filled from and the {@link #specialLoot}
 * that is shared across dimensions.
 */
public record RaidTheme(ResourceLocation dimension,
                        float yieldMultiplier,
                        RaidPalette palette,
                        LootPool lootPool,
                        List<LootDrop> specialLoot,
                        List<MobEntry> mobTable,
                        List<RaidMap> maps,
                        @Nullable ResourceLocation boss) {

    /**
     * One selectable map inside the theme.
     *
     * @param layout            the {@code RaidLayoutGenerator} template id, e.g. {@code cavern_grid}
     * @param form              the terrain shape, one of {@code enclosed_dungeon} / {@code open_platforms} / {@code underground_cave}
     * @param size              the footprint scale, one of {@code small} / {@code medium} / {@code large}
     * @param locked            whether the map is hidden behind the "locked" row of the select screen;
     *                          it stays visible but cannot be departed to (design §6)
     * @param geometry          which {@link RaidGeometry} strategy builds this map, {@code region} by
     *                          default; the mansion rework adds {@code mansion} in P1
     * @param mobTableOverride  a map-local spawn table that overrides the theme's, or {@code null} to
     *                          use the theme table; only the mansion probe needs one (decision R14)
     * @param mansionPalette    the block language the mansion geometry is raised with, or {@code null}
     *                          to derive one from the theme palette; only the mansion reads it (fix F2)
     */
    public record RaidMap(String id, String name, String layout, String form, String size,
                          boolean locked, String geometry,
                          @Nullable List<MobEntry> mobTableOverride,
                          @Nullable MansionPalette mansionPalette) {

        /** A map without its own mansion palette; kept for the older call sites and the tests. */
        public RaidMap(String id, String name, String layout, String form, String size, boolean locked,
                       String geometry, @Nullable List<MobEntry> mobTableOverride) {
            this(id, name, layout, form, size, locked, geometry, mobTableOverride, null);
        }

        /** The palette the mansion builder paints with: its own, or one derived from the theme. */
        public MansionPalette palette(RaidTheme theme) {
            if (this.mansionPalette != null) {
                return this.mansionPalette;
            }
            return MansionPalette.from(theme.palette());
        }

        /**
         * The spawn table this map draws its patrols from: its own when it declares one, the theme's
         * otherwise.
         */
        public List<MobEntry> mobTable(RaidTheme theme) {
            if (this.mobTableOverride != null && !this.mobTableOverride.isEmpty()) {
                return this.mobTableOverride;
            }
            return theme == null ? List.of() : theme.mobTable();
        }
    }

    /** Building blocks used by {@link RaidArenaBuilder}. */
    public record RaidPalette(ResourceLocation floor, ResourceLocation wall, ResourceLocation accent) {
    }

    /** One weighted entry of a loot table. */
    public record LootDrop(ResourceLocation item, int weight, int min, int max) {

        public int rollCount(net.minecraft.util.RandomSource random, float countMultiplier) {
            int rolled = this.min + random.nextInt(Math.max(1, this.max - this.min + 1));
            return Math.max(1, Math.round(rolled * countMultiplier));
        }
    }

    /**
     * One weighted entry of the spawn table.
     *
     * @param id     the entity type to spawn, e.g. {@code minecraft:zombie}
     * @param weight the relative chance of picking this entry for a squad
     * @param min    least mobs of this kind a squad holds
     * @param max    most mobs of this kind a squad holds
     */
    public record MobEntry(ResourceLocation id, int weight, int min, int max) {
    }

    /**
     * Rarity layered loot, lowest tier first.
     *
     * <p>Higher raid difficulties shift the tier roll upwards, which is how "harder map, better loot"
     * is expressed without touching the dimension's own drop list.
     */
    public record LootPool(List<LootDrop> common, List<LootDrop> uncommon, List<LootDrop> rare, List<LootDrop> epic) {

        public static final List<String> TIERS = List.of("common", "uncommon", "rare", "epic");

        public List<LootDrop> tier(int index) {
            return switch (index) {
                case 0 -> this.common;
                case 1 -> this.uncommon;
                case 2 -> this.rare;
                default -> this.epic;
            };
        }

        public boolean isEmpty() {
            return this.common.isEmpty() && this.uncommon.isEmpty() && this.rare.isEmpty() && this.epic.isEmpty();
        }
    }

    public boolean hasBoss() {
        return this.boss != null;
    }

    @Nullable
    public RaidMap map(String id) {
        for (RaidMap map : this.maps) {
            if (map.id().equals(id)) {
                return map;
            }
        }
        return null;
    }
}
