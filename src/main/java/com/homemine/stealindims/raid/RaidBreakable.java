package com.homemine.stealindims.raid;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.homemine.stealindims.StealInDimsMod;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

/**
 * Blocks a raider is allowed to break by hand inside a raid dimension, loaded from
 * {@code data/stealindims/raid_breakable/*.json}.
 *
 * <p>Every file carries a {@code "blocks"} array of block ids; anything not listed is banned, so the
 * arena geometry cannot be dug through. The default table lists the decorated pot only, which is the
 * loot container a player cracks open (design §4.4).
 */
public class RaidBreakable extends SimpleJsonResourceReloadListener {

    public static final String DIRECTORY = "raid_breakable";

    private static final Gson GSON = new GsonBuilder().setLenient().create();

    private static volatile Set<Block> breakable = Set.of();

    public RaidBreakable() {
        super(GSON, DIRECTORY);
    }

    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new RaidBreakable());
    }

    public static boolean isBreakable(BlockState state) {
        return state != null && breakable.contains(state.getBlock());
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        Set<Block> parsed = new HashSet<>();

        for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
            ResourceLocation file = entry.getKey();
            try {
                JsonObject json = GsonHelper.convertToJsonObject(entry.getValue(), "raid breakable");
                JsonArray blocks = GsonHelper.getAsJsonArray(json, "blocks");
                for (JsonElement element : blocks) {
                    ResourceLocation id = ResourceLocation.tryParse(element.getAsString());
                    if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                        StealInDimsMod.LOGGER.warn("Skipping unknown block '{}' in {}", element, file);
                        continue;
                    }
                    parsed.add(BuiltInRegistries.BLOCK.get(id));
                }
            } catch (Exception exception) {
                StealInDimsMod.LOGGER.error("Couldn't parse raid breakable table {}", file, exception);
            }
        }

        breakable = Set.copyOf(parsed);
        StealInDimsMod.LOGGER.info("Loaded {} breakable block(s) for raids", breakable.size());
    }
}
