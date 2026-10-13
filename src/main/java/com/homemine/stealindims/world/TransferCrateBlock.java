package com.homemine.stealindims.world;

import com.homemine.stealindims.menu.TransferCrateMenu;
import com.homemine.stealindims.raid.RaidSession;
import com.homemine.stealindims.raid.RaidSessionManager;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 转运箱 - the transit crate that sits in the extraction room (design §10).
 *
 * <p>Right-clicking opens the crate screen: three rows of the raider's own extraction area over six
 * rows of shared public storage. Nothing is handed over automatically any more - the value of the
 * haul is settled from what the zones hold when the run is settled.
 */
public class TransferCrateBlock extends Block {

    public TransferCrateBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(TransferCrateBlock::new);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }

        RaidSession session = RaidSessionManager.sessionOf(serverPlayer);
        if (session == null) {
            serverPlayer.displayClientMessage(Component.translatable("message.stealindims.no_raid"), true);
            return InteractionResult.SUCCESS;
        }

        serverPlayer.openMenu(new SimpleMenuProvider(
                (containerId, inventory, opener) -> new TransferCrateMenu(containerId, inventory,
                        session.personalZone(serverPlayer.getUUID()), session.publicStorage()),
                Component.translatable("container.stealindims.transfer_crate")));
        return InteractionResult.SUCCESS;
    }
}
