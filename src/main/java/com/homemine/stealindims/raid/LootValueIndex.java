package com.homemine.stealindims.raid;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.homemine.stealindims.StealInDimsMod;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

/**
 * Item appraisal table, loaded from {@code data/stealindims/loot_value/*.json}.
 *
 * <p>Every file is a flat {@code item id -> value} map (a {@code "values"} wrapper object is also
 * accepted so pack authors can group entries). Items that are missing from the table appraise as
 * {@code 0}; the loader logs them so the numbers can be filled in later (design §13).
 *
 * <p>The thresholds, the HUD "submitted value" readout and the teleport tax all go through here, so
 * there is exactly one definition of what a haul is worth.
 */
public class LootValueIndex extends SimpleJsonResourceReloadListener {

    public static final String DIRECTORY = "loot_value";

    private static final Gson GSON = new GsonBuilder().setLenient().create();

    private static volatile Map<ResourceLocation, Long> values = Map.of();
    private static volatile boolean warnedAboutMissing = false;

    public LootValueIndex() {
        super(GSON, DIRECTORY);
    }

    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new LootValueIndex());
    }

    public static long valueOf(Item item) {
        if (item == null) {
            return 0L;
        }
        Long value = values.get(BuiltInRegistries.ITEM.getKey(item));
        if (value == null) {
            warnOnce(item);
            return 0L;
        }
        return value;
    }

    /** Value of a whole stack: unit value times count. */
    public static long valueOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0L;
        }
        return valueOf(stack.getItem()) * stack.getCount();
    }

    public static long totalValue(List<ItemStack> stacks) {
        long total = 0L;
        for (ItemStack stack : stacks) {
            total += valueOf(stack);
        }
        return total;
    }

    private static void warnOnce(Item item) {
        if (!warnedAboutMissing) {
            warnedAboutMissing = true;
            StealInDimsMod.LOGGER.warn("Items missing from the loot value table appraise as 0, first one seen: {}",
                    BuiltInRegistries.ITEM.getKey(item));
        }
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, Long> parsed = new HashMap<>();
        warnedAboutMissing = false;

        for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
            ResourceLocation file = entry.getKey();
            try {
                JsonObject json = GsonHelper.convertToJsonObject(entry.getValue(), "loot value");
                JsonObject table = json.has("values") ? GsonHelper.getAsJsonObject(json, "values") : json;
                for (Map.Entry<String, JsonElement> value : table.entrySet()) {
                    ResourceLocation item = ResourceLocation.tryParse(value.getKey());
                    if (item == null) {
                        StealInDimsMod.LOGGER.warn("Skipping malformed item id '{}' in {}", value.getKey(), file);
                        continue;
                    }
                    parsed.put(item, value.getValue().getAsLong());
                }
            } catch (Exception exception) {
                StealInDimsMod.LOGGER.error("Couldn't parse loot value table {}", file, exception);
            }
        }

        values = Map.copyOf(parsed);
        StealInDimsMod.LOGGER.info("Loaded {} loot value entr(ies)", values.size());
    }
}
