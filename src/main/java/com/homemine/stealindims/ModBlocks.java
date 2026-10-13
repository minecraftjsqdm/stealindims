package com.homemine.stealindims;

import com.homemine.stealindims.world.BossPortalBlock;
import com.homemine.stealindims.world.IllagerCrankBlock;
import com.homemine.stealindims.world.RaidExitPortalBlock;
import com.homemine.stealindims.world.RaidPortalBlock;
import com.homemine.stealindims.world.RaidReadyPadBlock;
import com.homemine.stealindims.world.TransferCrateBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(StealInDimsMod.MODID);

    /** 高级折跃器 */
    public static final DeferredBlock<RaidPortalBlock> RAID_PORTAL = BLOCKS.registerBlock(
            "raid_portal",
            RaidPortalBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(3.5F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .lightLevel(state -> 7)
                    .sound(SoundType.METAL));

    /** 转运箱 - 撤离点内的提交终端 */
    public static final DeferredBlock<TransferCrateBlock> TRANSFER_CRATE = BLOCKS.registerBlock(
            "transfer_crate",
            TransferCrateBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(3.5F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .lightLevel(state -> 10)
                    .sound(SoundType.WOOD));

    /** 准备台 - pressing it in the prep lobby readies the player up for the run */
    public static final DeferredBlock<RaidReadyPadBlock> RAID_READY_PAD = BLOCKS.registerBlock(
            "raid_ready_pad",
            RaidReadyPadBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(3.5F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .lightLevel(state -> 7)
                    .sound(SoundType.STONE));

    /** 撤离传送门 - lobby exit before the run, extraction portal during it (decision A5) */
    public static final DeferredBlock<RaidExitPortalBlock> RAID_EXIT_PORTAL = BLOCKS.registerBlock(
            "raid_exit_portal",
            RaidExitPortalBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(3.5F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .lightLevel(state -> 11)
                    .sound(SoundType.GLASS));

    /**
     * 刌民像曲柄 - the crank on the illager statue's nose (design §3.4).
     *
     * <p>One right-click is one quarter turn. It carries the stage in a block entity so the nose
     * survives a chunk unload, which is risk R-e.
     */
    public static final DeferredBlock<IllagerCrankBlock> ILLAGER_CRANK = BLOCKS.registerBlock(
            "illager_crank",
            IllagerCrankBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(3.5F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .lightLevel(state -> 6)
                    .sound(SoundType.WOOD));

    /**
     * Boss 传送门 - the way from the statue's dark room to the boss chamber (design §3.5).
     *
     * <p>Built on every difficulty; only S may pass (decision R7).
     */
    public static final DeferredBlock<BossPortalBlock> BOSS_PORTAL = BLOCKS.registerBlock(
            "boss_portal",
            BossPortalBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(3.5F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .lightLevel(state -> 12)
                    .sound(SoundType.GLASS));

    private ModBlocks() {
    }
}
