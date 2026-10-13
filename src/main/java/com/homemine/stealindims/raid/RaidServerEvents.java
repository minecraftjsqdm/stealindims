package com.homemine.stealindims.raid;

import com.homemine.stealindims.entity.OverworldWatcher;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EnderChestBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerSetSpawnEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Everything the raid needs from the game loop: the block ban (design §9), the clock (§7), and the
 * lifecycle hooks that decide what a player keeps (§20).
 *
 * <p>All of these are plain {@code NeoForge.EVENT_BUS} listeners, registered from the mod constructor
 * so the class is loaded on both sides.
 */
public final class RaidServerEvents {

    private RaidServerEvents() {
    }

    // ---------------------------------------------------------------- rules inside a raid

    /**
     * design §9: no breaking blocks, no placing blocks. The ban is dimension-wide rather than
     * participant-only, so a visitor cannot damage an arena either.
     *
     * <p>The decorated pot is the single exception: it is the loot container a player cracks open by
     * hand, and the loot it yields is rolled by us rather than by the vanilla drop table.
     */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!isRaidLevel(event.getLevel())) {
            return;
        }
        if (event.getLevel() instanceof ServerLevel level
                && event.getPlayer() instanceof ServerPlayer player
                && RaidBreakable.isBreakable(event.getState())
                && RaidSessionManager.isInRaid(player)) {
            event.setCanceled(true);
            RaidSessionManager.breakPot(level, event.getPos());
            return;
        }
        event.setCanceled(true);
    }

    /**
     * design §9: a blast may hurt entities but must never touch arena geometry. The whole affected
     * list is dropped rather than filtered down to pots: a pot would otherwise hand out free loot,
     * and the transfer crate — the only way to bank a haul — would be deleted outright.
     */
    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (RaidDimensions.isRaidDimension(event.getLevel().dimension())) {
            event.getAffectedBlocks().clear();
        }
    }

    /** Covers both a single placement and {@code EntityMultiPlaceEvent}, which extends it. */
    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (isRaidLevel(event.getLevel())) {
            event.setCanceled(true);
        }
    }

    /** design §9: a raid dimension may never become anyone's respawn point. */
    @SubscribeEvent
    public static void onSetSpawn(PlayerSetSpawnEvent event) {
        if (RaidDimensions.isRaidDimension(event.getSpawnLevel())) {
            event.setCanceled(true);
        }
    }

    /**
     * design §4.2 / decision A5: the lobby ender chest is where a banned item has to go before its
     * owner may ready up, so it only opens while the lobby is still gathering.
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !RaidDimensions.isRaidDimension(event.getLevel().dimension())
                || !(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof EnderChestBlock)) {
            return;
        }
        RaidSession session = RaidSessionManager.sessionOf(player);
        if (session == null || session.phase() == RaidSession.Phase.WAITING) {
            return;
        }
        event.setCanceled(true);
        player.displayClientMessage(Component.translatable("message.stealindims.ender_chest_locked"), true);
    }

    private static boolean isRaidLevel(Object levelAccessor) {
        return levelAccessor instanceof Level level && RaidDimensions.isRaidDimension(level.dimension());
    }

    // ---------------------------------------------------------------- clock

    /**
     * Anything a raid mob drops is raid loot like anything out of a container, so it carries the same
     * mark: only {@link RaidSpawner#RAID_MOB_TAG} mobs count, which keeps ordinary wildlife a raider
     * may have brought along out of the submission pool.
     */
    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        if (!RaidDimensions.isRaidDimension(entity.level().dimension())
                || !entity.getTags().contains(RaidSpawner.RAID_MOB_TAG)) {
            return;
        }
        for (ItemEntity drop : event.getDrops()) {
            RaidLootMark.mark(drop.getItem());
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MansionArenaBuilder.tick(event.getServer());
        RaidSessionManager.tick(event.getServer());
    }

    // ---------------------------------------------------------------- player lifecycle

    /**
     * design §20: dying forfeits the loot and rolls the equipment. Vanilla fires this before
     * {@code dropAllDeathLoot}, so emptying the inventory here is what stops anything hitting the
     * floor; {@link RaidSessionManager#onDeath} does the rest.
     */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            RaidSessionManager.onDeath(player);
        } else if (event.getEntity() instanceof OverworldWatcher boss) {
            RaidSessionManager.onBossDefeated(boss);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            RaidSessionManager.onRespawn(player);
        }
    }

    /**
     * design §24: a disconnect is treated as an abnormal exit for now. The freeze window that lets a
     * player reconnect into the same run is deferred with the rest of the freeze work.
     */
    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            RaidSessionManager.onLogout(player);
        }
    }

    /** Catches the runs a server restart voided, and finishes a deferred return. */
    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            RaidSessionManager.onLogin(player);
        }
    }

    /** design §18: leaving the raid dimension any other way voids the run. */
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        Player player = event.getEntity();
        if (player instanceof ServerPlayer serverPlayer) {
            ResourceKey<Level> from = event.getFrom();
            RaidSessionManager.onDimensionChanged(serverPlayer, from, event.getTo());
        }
    }
}
