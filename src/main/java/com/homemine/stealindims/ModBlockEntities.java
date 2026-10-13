package com.homemine.stealindims;

import com.homemine.stealindims.world.IllagerCrankBlockEntity;
import com.homemine.stealindims.world.RaidPortalBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, StealInDimsMod.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RaidPortalBlockEntity>> RAID_PORTAL =
            BLOCK_ENTITIES.register("raid_portal", () -> BlockEntityType.Builder.of(
                    RaidPortalBlockEntity::new, ModBlocks.RAID_PORTAL.get()).build(null));

    /** Holds the illager statue's crank stage so it survives a chunk unload (risk R-e). */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<IllagerCrankBlockEntity>> ILLAGER_CRANK =
            BLOCK_ENTITIES.register("illager_crank", () -> BlockEntityType.Builder.of(
                    IllagerCrankBlockEntity::new, ModBlocks.ILLAGER_CRANK.get()).build(null));

    private ModBlockEntities() {
    }
}
