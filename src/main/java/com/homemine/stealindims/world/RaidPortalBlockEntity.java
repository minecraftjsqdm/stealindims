package com.homemine.stealindims.world;

import com.homemine.stealindims.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity of the Advanced Warp Device.
 *
 * <p>It deliberately holds no loot: the transit crate contents are shared by every device in the same
 * host dimension and therefore live in {@link com.homemine.stealindims.raid.RaidStashData}. The
 * per-device state (which raid is currently running, extraction anchor link) lands here in a later
 * stage.
 */
public class RaidPortalBlockEntity extends BlockEntity {

    public RaidPortalBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RAID_PORTAL.get(), pos, state);
    }
}
