package com.homemine.stealindims.raid;

import net.minecraft.resources.ResourceLocation;

/**
 * The block language of the mansion build (P4 defect D5, fix F2).
 *
 * <p>Until P4 the mansion was raised with the <em>theme</em> palette — the overworld theme's
 * {@code stone_bricks / deepslate_bricks / copper_block} — which is exactly why the map read as a
 * trial chamber instead of a woodland mansion. This record is a mansion's own palette: dark oak
 * timber, cobble and mossy brick footings, birch plaster, glazed panes, carpets, shelves, banners,
 * fences, bars and lanterns, plus the dark oak stair block the roof and the staircases are made of.
 *
 * <p>It is loaded from the map entry's own {@code palette} object in
 * {@code data/stealindims/raid_theme/<theme>.json} ({@link RaidTheme.RaidMap#mansionPalette()}), so a
 * data pack can restyle the whole mansion without a rebuild — nothing here is baked into the builder.
 * The {@link #defaults()} set is only the parse-time fallback for a theme file that predates P4; the
 * shipped overworld theme spells every key out.
 *
 * <p>Identity matters more than the individual roles here: {@link #IDENTITY} is what a test pins and
 * what the acceptance screenshots are judged against.
 */
public record MansionPalette(ResourceLocation floor, ResourceLocation floorAlt, ResourceLocation plank,
                             ResourceLocation log, ResourceLocation base, ResourceLocation plaster,
                             ResourceLocation trim, ResourceLocation roof, ResourceLocation roofSlab,
                             ResourceLocation window, ResourceLocation carpet, ResourceLocation shelf,
                             ResourceLocation banner, ResourceLocation fence, ResourceLocation bars,
                             ResourceLocation lantern, ResourceLocation door, ResourceLocation nose) {

    /** The woodland mansion set, used only when a theme file declares no mansion palette. */
    public static MansionPalette defaults() {
        return new MansionPalette(
                id("minecraft:dark_oak_planks"),
                id("minecraft:cobblestone"),
                id("minecraft:dark_oak_planks"),
                id("minecraft:dark_oak_log"),
                id("minecraft:cobblestone"),
                id("minecraft:birch_planks"),
                id("minecraft:mossy_stone_bricks"),
                id("minecraft:dark_oak_stairs"),
                id("minecraft:dark_oak_slab"),
                id("minecraft:glass_pane"),
                id("minecraft:red_carpet"),
                id("minecraft:bookshelf"),
                id("minecraft:white_wall_banner"),
                id("minecraft:dark_oak_fence"),
                id("minecraft:iron_bars"),
                id("minecraft:lantern"),
                id("minecraft:dark_oak_door"),
                id("minecraft:dark_oak_planks"));
    }

    /**
     * A mansion palette built out of a theme's three materials, so a map that asks for mansion
     * geometry without declaring a palette still comes out in its own theme rather than in stone.
     */
    public static MansionPalette from(RaidTheme.RaidPalette palette) {
        return new MansionPalette(palette.floor(), palette.floor(), palette.wall(), palette.wall(),
                palette.wall(), palette.wall(), palette.accent(), palette.wall(), palette.accent(),
                palette.accent(), palette.accent(), palette.wall(), palette.accent(), palette.wall(),
                palette.wall(), palette.accent(), palette.wall(), palette.accent());
    }

    /**
     * The roles the acceptance check greps for: a mansion has to be dark oak timber, cobble footings,
     * birch plaster and glazed panes, and must not be built out of the trial-chamber stones.
     */
    public static java.util.List<ResourceLocation> IDENTITY =
            java.util.List.of(id("minecraft:dark_oak_log"), id("minecraft:cobblestone"),
                    id("minecraft:birch_planks"), id("minecraft:glass_pane"));

    private static ResourceLocation id(String value) {
        return ResourceLocation.parse(value);
    }
}
