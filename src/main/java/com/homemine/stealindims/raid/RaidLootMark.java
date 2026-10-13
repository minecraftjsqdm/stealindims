package com.homemine.stealindims.raid;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * The raid loot mark: a boolean flag stuffed into an item's vanilla custom data.
 *
 * <p>The old rule told loot apart from belongings by <em>where</em> a stack sat — the 27 slot "loot
 * area" of the main inventory was cleared, submitted and forfeited as one block. That only worked
 * while the entry gate confiscated the rest of the bag. The whole inventory is carried in now, so the
 * stack itself has to say where it came from: {@link RaidLootGenerator} and mob drops set this mark,
 * the transit crate submits everything that carries it, and leaving the raid dimension takes it off
 * again (design §19).
 *
 * <p>Written through {@link DataComponents#CUSTOM_DATA} rather than a component of our own, which
 * keeps it plain NBT — no registry entry to add, and the mark rides along with the stack.
 */
public final class RaidLootMark {

    /** Key inside {@code minecraft:custom_data} that marks a stack as raid loot. */
    private static final String KEY = "StealInDimsRaidLoot";

    private RaidLootMark() {
    }

    /** Marks {@code stack} as taken out of a raid. An empty stack is left alone. */
    public static void mark(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (tag.getBoolean(KEY)) {
            return;
        }
        tag.putBoolean(KEY, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public static boolean isMarked(ItemStack stack) {
        return !stack.isEmpty()
                && stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).contains(KEY);
    }

    /** Removes the mark, leaving any other custom data on the stack untouched. */
    public static void clear(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.contains(KEY)) {
            return;
        }
        CompoundTag tag = data.copyTag();
        tag.remove(KEY);
        if (tag.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }
}
