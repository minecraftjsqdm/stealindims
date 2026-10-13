package com.homemine.stealindims.world;

import com.homemine.stealindims.raid.RaidSessionManager;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 撤离传送门 - the extraction portal standing in the prep lobby (design §3.2, user decision A5).
 *
 * <p>While the lobby is waiting it is the door out: clicking it gives the seat back and returns the
 * player to the device they came from, inventory unchanged. Once the run is under way the same block
 * becomes the only way to pull out, settling the run the way the map anchors used to.
 */
public class RaidExitPortalBlock extends Block {

    public RaidExitPortalBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(RaidExitPortalBlock::new);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (player instanceof ServerPlayer serverPlayer) {
            RaidSessionManager.useExitPortal(serverPlayer);
        }
        return InteractionResult.SUCCESS;
    }
}
