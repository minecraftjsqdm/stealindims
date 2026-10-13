package com.homemine.stealindims.raid;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.homemine.stealindims.StealInDimsMod;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

/**
 * Loads every {@code data/stealindims/mansion_room/*.json} into the mansion generator's archetype
 * list (plan §P1.5).
 *
 * <p>The layout has to be reproducible from a seed alone, and the headless generation tests never
 * load a resource manager, so this registry always answers with <em>something</em>:
 * {@link MansionRoomSpec#defaults()} until a reload has happened, and the parsed files afterwards.
 * A malformed file is logged and skipped rather than failing the reload, because an unbuildable map
 * is a far worse failure than a room that falls back to the default mix.
 *
 * <p>Every reload bumps {@link #revision()}, which is part of {@link MansionGeometry}'s cache key:
 * a data pack that changes the mix can never be answered with a layout built from the old one.
 */
public class MansionRoomRegistry extends SimpleJsonResourceReloadListener {

    public static final String DIRECTORY = "mansion_room";

    private static final Gson GSON = new GsonBuilder().setLenient().create();

    private static volatile List<MansionRoomSpec> specs = MansionRoomSpec.defaults();
    private static volatile int revision = 1;

    public MansionRoomRegistry() {
        super(GSON, DIRECTORY);
    }

    /** Hook for the server resource reload; registered from {@link RaidThemeRegistry}. */
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new MansionRoomRegistry());
    }

    /** The archetypes the generator draws from, never empty. */
    public static List<MansionRoomSpec> specs() {
        return specs;
    }

    /** Bumped on every reload so caches keyed on the mix can tell. */
    public static int revision() {
        return revision;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        List<MansionRoomSpec> parsed = new ArrayList<>();
        int skipped = 0;
        for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
            try {
                parsed.add(parse(entry.getKey(), GsonHelper.convertToJsonObject(entry.getValue(), "mansion room")));
            } catch (Exception exception) {
                skipped++;
                StealInDimsMod.LOGGER.error("Couldn't parse mansion room {}", entry.getKey(), exception);
            }
        }
        if (parsed.isEmpty()) {
            StealInDimsMod.LOGGER.warn("Mansion room directory is empty or unreadable, keeping the built-in mix");
            specs = MansionRoomSpec.defaults();
        } else {
            specs = List.copyOf(parsed);
        }
        revision++;
        StealInDimsMod.LOGGER.info("Loaded {} mansion room archetype(s){}",
                specs.size(), skipped == 0 ? "" : ", skipped " + skipped);
    }

    private static MansionRoomSpec parse(ResourceLocation file, JsonObject json) {
        String id = GsonHelper.getAsString(json, "type");
        MansionLayout.RoomType type = MansionLayout.RoomType.byId(id);
        if (type == null) {
            throw new IllegalArgumentException("Unknown mansion room type '" + id + "' in " + file);
        }
        JsonObject cells = GsonHelper.getAsJsonObject(json, "cells");
        int minW = GsonHelper.getAsInt(cells, "min_w", 1);
        int maxW = GsonHelper.getAsInt(cells, "max_w", 1);
        int minD = GsonHelper.getAsInt(cells, "min_h", 1);
        int maxD = GsonHelper.getAsInt(cells, "max_h", 1);
        return new MansionRoomSpec(type,
                GsonHelper.getAsInt(json, "weight", 1),
                minW, maxW, minD, maxD,
                GsonHelper.getAsBoolean(json, "roofed", type != MansionLayout.RoomType.COURTYARD),
                parseProps(json, file),
                parseLootTier(json, file));
    }

    /**
     * The furniture list (P4 defect D6, fix F3). These two fields used to be dropped on the floor by
     * this parser, which is why every room came out an empty box: the data pack described beds,
     * shelves and a loot tier, and nothing ever read them.
     */
    private static List<MansionRoomSpec.Prop> parseProps(JsonObject json, ResourceLocation file) {
        if (!json.has("props")) {
            return List.of();
        }
        List<MansionRoomSpec.Prop> props = new ArrayList<>();
        for (JsonElement element : GsonHelper.getAsJsonArray(json, "props")) {
            JsonObject prop = GsonHelper.convertToJsonObject(element, "prop");
            props.add(new MansionRoomSpec.Prop(
                    GsonHelper.getAsString(prop, "kind"),
                    GsonHelper.getAsString(prop, "at", "center"),
                    GsonHelper.getAsInt(prop, "height", 0)));
        }
        return List.copyOf(props);
    }

    private static MansionRoomSpec.LootTier parseLootTier(JsonObject json, ResourceLocation file) {
        if (!json.has("loot_tier")) {
            return MansionRoomSpec.LootTier.NONE;
        }
        String id = GsonHelper.getAsString(json, "loot_tier");
        MansionRoomSpec.LootTier tier = MansionRoomSpec.LootTier.byId(id);
        if (tier == null) {
            throw new IllegalArgumentException("Unknown loot tier '" + id + "' in " + file);
        }
        return tier;
    }

    /** The archetype a parsed id maps to, or {@code null}; used by the tests. */
    @Nullable
    public static MansionRoomSpec find(MansionLayout.RoomType type) {
        for (MansionRoomSpec spec : specs) {
            if (spec.type() == type) {
                return spec;
            }
        }
        return null;
    }
}
