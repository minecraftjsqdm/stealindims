package com.homemine.stealindims.world;

import com.homemine.stealindims.menu.RaidPortalMenu;
import com.homemine.stealindims.raid.RaidArena;
import com.homemine.stealindims.raid.RaidDimensions;
import com.homemine.stealindims.raid.RaidSession;
import com.homemine.stealindims.raid.RaidSessionManager;
import com.homemine.stealindims.raid.RaidStashData;
import com.homemine.stealindims.raid.RaidThemeRegistry;
import com.mojang.serialization.MapCodec;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 高级折跃器 - the Advanced Warp Device (design §6).
 *
 * <p>One block, four jobs, dispatched by where it stands and how it is clicked:
 * <ul>
 *     <li>A host dimension with a theme: plain click sends the select screen's contents, sneak click
 *     opens the shared transit crate (§19). Nothing is confiscated at entry any more (design §19),
 *     so the crate is optional storage a squad may use to pool goods.</li>
 *     <li>The extraction room's anchor: plain click moves the player to the map once the run has
 *     started, and only reminds them to ready up before that (design §10).</li>
 *     <li>The map's anchor, and the anchor inside each region's vault: plain click takes the player
 *     back to the extraction room (§9, §10).</li>
 *     <li>Anywhere else it is inert, which is what keeps unconfigured dimensions closed.</li>
 * </ul>
 */
public class RaidPortalBlock extends Block implements EntityBlock {

    public RaidPortalBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(RaidPortalBlock::new);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RaidPortalBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }

        if (RaidDimensions.isRaidDimension(level.dimension())) {
            return useInsideRaid(serverPlayer, pos);
        }

        if (RaidThemeRegistry.themeFor(level.dimension()) == null) {
            serverPlayer.displayClientMessage(Component.translatable("message.stealindims.no_raid"), true);
            return InteractionResult.SUCCESS;
        }

        // Remember where the device is, so the run can return the player to it (§6).
        RaidSessionManager.rememberPortal(serverPlayer, pos);

        if (serverPlayer.isShiftKeyDown()) {
            serverPlayer.openMenu(new SimpleMenuProvider(
                    (containerId, inventory, opener) -> new RaidPortalMenu(containerId, inventory,
                            RaidStashData.get(serverLevel).stash()),
                    Component.translatable("container.stealindims.raid_portal")));
        } else {
            RaidSessionManager.sendSelectInfo(serverPlayer);
        }
        return InteractionResult.SUCCESS;
    }

    /** The two anchors: the one in the extraction room, and the one on the map's platform. */
    private InteractionResult useInsideRaid(ServerPlayer player, BlockPos pos) {
        RaidSession session = RaidSessionManager.sessionOf(player);
        if (session == null) {
            player.displayClientMessage(Component.translatable("message.stealindims.no_raid"), true);
            return InteractionResult.SUCCESS;
        }

        if (session.geometry().isInSafeZone(pos)) {
            player.displayClientMessage(Component.translatable("message.stealindims.vault_anchor"), true);
            RaidSessionManager.teleportToSafeRoom(player);
        } else if (session.geometry().isInMap(pos)) {
            RaidSessionManager.teleportToSafeRoom(player);
        } else if (session.phase() != RaidSession.Phase.STARTED) {
            // The lobby is still open: the map stays shut until everyone has readied up (design §4.2).
            player.displayClientMessage(Component.translatable("message.stealindims.ready_waiting"), true);
        } else {
            RaidSessionManager.teleportToMap(player);
        }
        return InteractionResult.SUCCESS;
    }
}
