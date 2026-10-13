package com.homemine.stealindims.world;

import com.homemine.stealindims.raid.RaidSession;
import com.homemine.stealindims.raid.RaidSessionManager;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The portal at the back of the illager statue's dark room (design §3.5, decision R7).
 *
 * <p>It is generated on <em>every</em> difficulty and only S can walk through it. A refusal is a
 * message rather than a silent nothing, because the whole point of generating it everywhere is that
 * the door is visibly there and visibly shut.
 *
 * <p>Where it leads is the boss chamber {@link com.homemine.stealindims.raid.MansionGeometry}
 * publishes; the destination itself is wired up with the boss encounter in P3, so for now an S
 * raider is told the chamber is still being dug.
 */
public class BossPortalBlock extends Block {

    public BossPortalBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(BossPortalBlock::new);
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
        if (!session.difficulty().hasBoss()) {
            // Decision R7: the door is always built, and only S may pass.
            serverPlayer.displayClientMessage(
                    Component.translatable("message.stealindims.boss_portal_refused"), true);
            return InteractionResult.SUCCESS;
        }
        RaidSessionManager.enterBossChamber(serverPlayer, session);
        return InteractionResult.SUCCESS;
    }
}
