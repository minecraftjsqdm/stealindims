package com.homemine.stealindims.raid;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.homemine.stealindims.StealInDimsMod;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

/**
 * Loads every {@code data/stealindims/raid_theme/*.json} into a map keyed by host dimension.
 *
 * <p>This registry is the single source of truth for "is this dimension open": a dimension without a
 * theme file simply has no raid, which keeps the content whitelist data driven and lets packs add or
 * remove dimensions without code changes.
 */
public class RaidThemeRegistry extends SimpleJsonResourceReloadListener {

    public static final String DIRECTORY = "raid_theme";

    private static final Gson GSON = new GsonBuilder().setLenient().create();

    private static final String DEFAULT_FLOOR = "minecraft:stone_bricks";
    private static final String DEFAULT_WALL = "minecraft:deepslate_bricks";
    private static final String DEFAULT_ACCENT = "minecraft:copper_block";

    private static final RaidTheme.LootPool EMPTY_POOL =
            new RaidTheme.LootPool(List.of(), List.of(), List.of(), List.of());

    /** Used when a theme declares no {@code mob_table}, so patrol spawning never has nothing to draw from. */
    private static final List<RaidTheme.MobEntry> DEFAULT_MOB_TABLE = List.of(
            new RaidTheme.MobEntry(ResourceLocation.parse("minecraft:zombie"), 10, 2, 4),
            new RaidTheme.MobEntry(ResourceLocation.parse("minecraft:skeleton"), 8, 2, 4));

    private static volatile Map<ResourceLocation, RaidTheme> themes = Map.of();

    public RaidThemeRegistry() {
        super(GSON, DIRECTORY);
    }

    /** Hook for the server resource reload; registered from the mod constructor. */
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new RaidThemeRegistry());
        event.addListener(new LootValueIndex());
        event.addListener(new RaidBreakable());
        event.addListener(new MansionRoomRegistry());
    }

    /** @return the theme for a host dimension, or {@code null} when that dimension has no raid. */
    @Nullable
    public static RaidTheme themeFor(ResourceKey<Level> dimension) {
        return themes.get(dimension.location());
    }

    /** @return the theme registered for {@code dimension}, or {@code null}. */
    @Nullable
    public static RaidTheme byDimension(ResourceLocation dimension) {
        return themes.get(dimension);
    }

    public static List<RaidTheme> all() {
        return List.copyOf(themes.values());
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, RaidTheme> parsed = new HashMap<>();

        for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
            ResourceLocation file = entry.getKey();
            try {
                RaidTheme theme = parse(file, GsonHelper.convertToJsonObject(entry.getValue(), "raid theme"));
                RaidTheme previous = parsed.put(theme.dimension(), theme);
                if (previous != null) {
                    StealInDimsMod.LOGGER.error("Duplicate raid theme for {} in {}, ignoring {}", theme.dimension(), file, previous);
                }
            } catch (Exception exception) {
                StealInDimsMod.LOGGER.error("Couldn't parse raid theme {}", file, exception);
            }
        }

        themes = Map.copyOf(parsed);
        StealInDimsMod.LOGGER.info("Loaded {} raid theme(s)", themes.size());
    }

    private static RaidTheme parse(ResourceLocation file, JsonObject json) {
        ResourceLocation dimension = ResourceLocation.parse(GsonHelper.getAsString(json, "dimension"));
        float yieldMultiplier = GsonHelper.getAsFloat(json, "yield_multiplier", 1.0F);

        List<RaidTheme.RaidMap> maps = new ArrayList<>();
        JsonArray mapArray = GsonHelper.getAsJsonArray(json, "maps");
        for (JsonElement element : mapArray) {
            JsonObject mapJson = GsonHelper.convertToJsonObject(element, "map");
            maps.add(new RaidTheme.RaidMap(
                    GsonHelper.getAsString(mapJson, "id"),
                    GsonHelper.getAsString(mapJson, "name"),
                    GsonHelper.getAsString(mapJson, "layout"),
                    GsonHelper.getAsString(mapJson, "form"),
                    GsonHelper.getAsString(mapJson, "size", "medium"),
                    // Every field below has a default, so a theme written before the mansion rework
                    // parses exactly as it did: unlocked, four-region geometry, theme-wide mob table.
                    GsonHelper.getAsBoolean(mapJson, "locked", false),
                    GsonHelper.getAsString(mapJson, "geometry", "region"),
                    parseMapMobTable(mapJson),
                    // The mansion's own block language (fix F2). Only the mansion reads it; a map that
                    // declares none keeps using the theme palette, so nothing else changes.
                    parseMansionPalette(mapJson)));
        }

        if (maps.isEmpty()) {
            throw new IllegalArgumentException("Raid theme " + file + " declares no maps");
        }

        ResourceLocation boss = json.has("boss") ? ResourceLocation.parse(GsonHelper.getAsString(json, "boss")) : null;
        return new RaidTheme(dimension,
                yieldMultiplier,
                parsePalette(json),
                parseLootPool(json),
                parseDrops(json, "special_loot"),
                parseMobTable(json),
                List.copyOf(maps),
                boss);
    }

    private static List<RaidTheme.MobEntry> parseMobTable(JsonObject json) {
        if (!json.has("mob_table")) {
            return DEFAULT_MOB_TABLE;
        }
        return parseMobEntries(json, "mob_table");
    }

    /**
     * A map-local spawn table, or {@code null} when the map declares none.
     *
     * <p>Same field name and shape as the theme-level table, which is why the parsing is shared; the
     * difference is that a map without one must inherit the theme's rather than fall back to the
     * built-in default, so absence is reported as {@code null} instead of a substitute.
     */
    @Nullable
    private static List<RaidTheme.MobEntry> parseMapMobTable(JsonObject mapJson) {
        if (!mapJson.has("mob_table")) {
            return null;
        }
        List<RaidTheme.MobEntry> mobs = parseMobEntries(mapJson, "mob_table");
        return mobs.isEmpty() ? null : mobs;
    }

    /** Reads one {@code mob_table}-shaped array, without any fallback. */
    private static List<RaidTheme.MobEntry> parseMobEntries(JsonObject json, String key) {
        List<RaidTheme.MobEntry> mobs = new ArrayList<>();
        for (JsonElement element : GsonHelper.getAsJsonArray(json, key)) {
            JsonObject mob = GsonHelper.convertToJsonObject(element, key);
            mobs.add(new RaidTheme.MobEntry(
                    ResourceLocation.parse(GsonHelper.getAsString(mob, "id")),
                    GsonHelper.getAsInt(mob, "weight", 1),
                    GsonHelper.getAsInt(mob, "min", 1),
                    GsonHelper.getAsInt(mob, "max", 1)));
        }
        return List.copyOf(mobs);
    }

    /**
     * A map-local mansion palette, or {@code null} when the map declares none (fix F2).
     *
     * <p>Absence is reported as {@code null} rather than as a filled-in default so the theme file
     * stays the single source of truth: a map with no palette is raised from the theme's own three
     * materials, exactly as it was before P4. Every key inside the object still has a default, so a
     * palette that only wants to change the roof does not have to spell out the other seventeen roles.
     */
    @Nullable
    private static MansionPalette parseMansionPalette(JsonObject mapJson) {
        if (!mapJson.has("palette")) {
            return null;
        }
        JsonObject json = GsonHelper.getAsJsonObject(mapJson, "palette");
        MansionPalette fallback = MansionPalette.defaults();
        return new MansionPalette(
                paletteId(json, "floor", fallback.floor()),
                paletteId(json, "floor_alt", fallback.floorAlt()),
                paletteId(json, "plank", fallback.plank()),
                paletteId(json, "log", fallback.log()),
                paletteId(json, "base", fallback.base()),
                paletteId(json, "plaster", fallback.plaster()),
                paletteId(json, "trim", fallback.trim()),
                paletteId(json, "roof", fallback.roof()),
                paletteId(json, "roof_slab", fallback.roofSlab()),
                paletteId(json, "window", fallback.window()),
                paletteId(json, "carpet", fallback.carpet()),
                paletteId(json, "shelf", fallback.shelf()),
                paletteId(json, "banner", fallback.banner()),
                paletteId(json, "fence", fallback.fence()),
                paletteId(json, "bars", fallback.bars()),
                paletteId(json, "lantern", fallback.lantern()),
                paletteId(json, "door", fallback.door()),
                paletteId(json, "nose", fallback.nose()));
    }

    private static ResourceLocation paletteId(JsonObject json, String key, ResourceLocation fallback) {
        return ResourceLocation.parse(GsonHelper.getAsString(json, key, fallback.toString()));
    }

    private static RaidTheme.RaidPalette parsePalette(JsonObject json) {
        if (!json.has("palette")) {
            return new RaidTheme.RaidPalette(
                    ResourceLocation.parse(DEFAULT_FLOOR),
                    ResourceLocation.parse(DEFAULT_WALL),
                    ResourceLocation.parse(DEFAULT_ACCENT));
        }
        JsonObject palette = GsonHelper.getAsJsonObject(json, "palette");
        return new RaidTheme.RaidPalette(
                ResourceLocation.parse(GsonHelper.getAsString(palette, "floor", DEFAULT_FLOOR)),
                ResourceLocation.parse(GsonHelper.getAsString(palette, "wall", DEFAULT_WALL)),
                ResourceLocation.parse(GsonHelper.getAsString(palette, "accent", DEFAULT_ACCENT)));
    }

    private static RaidTheme.LootPool parseLootPool(JsonObject json) {
        if (!json.has("loot_pool")) {
            return EMPTY_POOL;
        }
        JsonObject pool = GsonHelper.getAsJsonObject(json, "loot_pool");
        return new RaidTheme.LootPool(
                parseDrops(pool, "common"),
                parseDrops(pool, "uncommon"),
                parseDrops(pool, "rare"),
                parseDrops(pool, "epic"));
    }

    private static List<RaidTheme.LootDrop> parseDrops(JsonObject json, String key) {
        if (!json.has(key)) {
            return List.of();
        }
        List<RaidTheme.LootDrop> drops = new ArrayList<>();
        for (JsonElement element : GsonHelper.getAsJsonArray(json, key)) {
            JsonObject drop = GsonHelper.convertToJsonObject(element, key);
            drops.add(new RaidTheme.LootDrop(
                    ResourceLocation.parse(GsonHelper.getAsString(drop, "item")),
                    GsonHelper.getAsInt(drop, "weight", 1),
                    GsonHelper.getAsInt(drop, "min", 1),
                    GsonHelper.getAsInt(drop, "max", 1)));
        }
        return List.copyOf(drops);
    }
}
