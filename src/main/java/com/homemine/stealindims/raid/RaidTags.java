package com.homemine.stealindims.raid;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import static com.homemine.stealindims.StealInDimsMod.MODID;

/** The datapack tags this mod reads. */
public final class RaidTags {

    /** Items that never spawn inside a raid container and are refused at the entry gate (design §19). */
    public static final TagKey<Item> BANNED =
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(MODID, "raid_banned"));

    /** Storage items explicitly allowed through the entry gate (design §19). */
    public static final TagKey<Item> ALLOWED_STORAGE =
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(MODID, "allowed_storage"));

    private RaidTags() {
    }
}
