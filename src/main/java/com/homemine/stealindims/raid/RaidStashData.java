package com.homemine.stealindims.raid;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-level transit crate storage.
 *
 * <p>Design §5 requires every Advanced Warp Device in the same host dimension to share one stash, so
 * the contents live in level saved data keyed by dimension instead of inside a block entity. Replacing
 * or breaking a device therefore keeps the loot, and each host dimension gets its own stash for free.
 */
public class RaidStashData extends SavedData {

    public static final String DATA_NAME = "stealindims_stash";

    private final RaidStash stash = new RaidStash();

    public RaidStashData() {
        this.stash.setChangedListener(this::setDirty);
    }

    public RaidStash stash() {
        return this.stash;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return ContainerHelper.saveAllItems(tag, this.stash.items(), registries);
    }

    public static RaidStashData load(CompoundTag tag, HolderLookup.Provider registries) {
        RaidStashData data = new RaidStashData();
        ContainerHelper.loadAllItems(tag, data.stash.items(), registries);
        return data;
    }

    public static RaidStashData get(ServerLevel level) {
        return level.getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(RaidStashData::new, RaidStashData::load), DATA_NAME);
    }
}
