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
 * 准备台 - the prep pad in the extraction room (design §4.2, lobby rework).
 *
 * <p>Pressing it in the lobby readies the player up; pressing it again stands them back down. Once
 * every member is ready the session starts its countdown, so this block is the only way to open a run.
 */
public class RaidReadyPadBlock extends Block {

    public RaidReadyPadBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(RaidReadyPadBlock::new);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (player instanceof ServerPlayer serverPlayer) {
            RaidSessionManager.toggleReady(serverPlayer);
        }
        return InteractionResult.SUCCESS;
    }
}
