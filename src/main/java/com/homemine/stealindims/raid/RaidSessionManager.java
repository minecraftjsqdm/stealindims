package com.homemine.stealindims.raid;

import com.homemine.stealindims.ModAttachments;
import com.homemine.stealindims.StealInDimsMod;
import com.homemine.stealindims.entity.ModEntities;
import com.homemine.stealindims.entity.OverworldWatcher;
import com.homemine.stealindims.menu.TransferCrateMenu;
import com.homemine.stealindims.net.RaidResultPayload;
import com.homemine.stealindims.net.RaidSelectInfoPayload;
import com.homemine.stealindims.net.RaidStatusPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The server side raid singleton: who is raiding where, and everything that happens at the edges of
 * a run (entry, submissions, extraction, death, timeout, disconnect).
 *
 * <p>Two indexes are kept so both questions the design asks can be answered in constant time: a
 * dimension may only host one raid (§5) and a player may only be in one raid (§5), so
 * {@link #BY_DIMENSION} and {@link #BY_PLAYER} are the two halves of the same record.
 *
 * <p>Loot is told apart from belongings by {@link RaidLootMark} rather than by inventory slots
 * (design §21): the whole backpack goes in, only the marked stacks are submitted or forfeited.
 *
 * <p>Everything here is server runtime state on purpose (design §23): a server restart voids the
 * runs, and the login hook is what cleans up after that.
 */
public final class RaidSessionManager {

    /**
     * Slots that make up "equipment" (design §19: hotbar 9 + armour 4 + offhand 1). They are kept
     * when the run is abandoned and rolled per difficulty when it fails.
     */
    private static final int[] EQUIPMENT_SLOTS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 36, 37, 38, 39, 40};

    /** design §4.2: once everyone is ready the run starts after this many seconds. */
    private static final int COUNTDOWN_SECONDS = 16;

    private static final Map<ResourceKey<Level>, RaidSession> BY_DIMENSION = new HashMap<>();
    private static final Map<UUID, RaidSession> BY_PLAYER = new HashMap<>();

    /** Last Advanced Warp Device each player interacted with, used as the return point. */
    private static final Map<UUID, GlobalPos> PORTAL_MEMORY = new HashMap<>();

    /** Where a player who settled while still inside a raid dimension has to be moved to. */
    private static final Map<UUID, GlobalPos> PENDING_RETURN = new HashMap<>();

    /** Equipment that survived a death roll, handed back on respawn. */
    private static final Map<UUID, List<ItemStack>> DEATH_REFUND = new HashMap<>();

    private RaidSessionManager() {
    }

    // ---------------------------------------------------------------- queries

    @Nullable
    public static RaidSession sessionOf(ServerPlayer player) {
        return BY_PLAYER.get(player.getUUID());
    }

    public static boolean isInRaid(ServerPlayer player) {
        return sessionOf(player) != null;
    }

    /** Whether the dimension is currently occupied by a run (design §5: one team per slot). */
    public static boolean isDimensionBusy(ResourceKey<Level> dimension) {
        return BY_DIMENSION.containsKey(dimension);
    }

    public static void rememberPortal(ServerPlayer player, BlockPos pos) {
        PORTAL_MEMORY.put(player.getUUID(), GlobalPos.of(player.level().dimension(), pos));
    }

    // ---------------------------------------------------------------- select screen

    /** Pushes the select screen's contents to {@code player}, or explains why there is nothing to open. */
    public static void sendSelectInfo(ServerPlayer player) {
        ResourceKey<Level> host = player.serverLevel().dimension();
        RaidTheme theme = RaidThemeRegistry.themeFor(host);
        if (theme == null) {
            player.displayClientMessage(Component.translatable("message.stealindims.no_raid"), true);
            return;
        }

        List<RaidSelectInfoPayload.MapEntry> maps = new ArrayList<>(theme.maps().size());
        for (RaidTheme.RaidMap map : theme.maps()) {
            maps.add(new RaidSelectInfoPayload.MapEntry(map.id(), map.name(), map.layout(), map.form(),
                    map.size(), map.locked()));
        }

        RaidProgress progress = player.getData(ModAttachments.RAID_PROGRESS);
        PacketDistributor.sendToPlayer(player, new RaidSelectInfoPayload(
                host.location(), progress.highestUnlocked().ordinal(), List.copyOf(maps), lobbyInfo(host)));
    }

    /**
     * The lobby a joiner would be folded into, or {@code null} when the slot is free.
     *
     * <p>User decision A2: while a lobby is open everyone else sees that lobby's difficulty and map
     * rather than picking their own, so only one run can be prepared per dimension.
     */
    @Nullable
    private static RaidSelectInfoPayload.LobbyInfo lobbyInfo(ResourceKey<Level> hostDimension) {
        ResourceKey<Level> raidDimension = RaidDimensions.raidDimension(hostDimension);
        RaidSession session = raidDimension == null ? null : BY_DIMENSION.get(raidDimension);
        if (session == null || session.phase() == RaidSession.Phase.STARTED) {
            return null;
        }
        return new RaidSelectInfoPayload.LobbyInfo(
                session.difficulty().ordinal(), session.map().id(), session.playerCount());
    }

    // ---------------------------------------------------------------- entry

    /**
     * Entry (design §19), driven by the client's "出发" button. Everything the player carries comes
     * along: there is no gate screening any more, only the loot mark that rides on the map's own loot.
     */
    public static void startRaid(ServerPlayer player, int difficultyOrdinal, String mapId) {
        // Already holding a seat: drop back into that lobby instead of queueing a second run.
        RaidSession seat = BY_PLAYER.get(player.getUUID());
        if (seat != null) {
            if (seat.phase() == RaidSession.Phase.STARTED) {
                player.displayClientMessage(Component.translatable("message.stealindims.already_in_raid"), true);
                return;
            }
            ServerLevel seatRaid = player.server.getLevel(seat.raidDimension());
            if (seatRaid == null) {
                player.displayClientMessage(Component.translatable("message.stealindims.no_raid"), true);
                return;
            }
            enterLobby(player, seat, seatRaid);
            return;
        }

        ServerLevel hostLevel = player.serverLevel();
        ResourceKey<Level> hostDimension = hostLevel.dimension();
        RaidTheme theme = RaidThemeRegistry.themeFor(hostDimension);
        if (theme == null) {
            player.displayClientMessage(Component.translatable("message.stealindims.no_raid"), true);
            return;
        }

        ResourceKey<Level> raidDimension = RaidDimensions.raidDimension(hostDimension);
        ServerLevel raidLevel = raidDimension == null ? null : player.server.getLevel(raidDimension);
        if (raidLevel == null) {
            player.displayClientMessage(Component.translatable("message.stealindims.no_raid"), true);
            return;
        }

        RaidSession lobby = BY_DIMENSION.get(raidDimension);
        if (lobby != null) {
            // The slot is taken: join the waiting lobby, or be turned away once it has started.
            enterLobby(player, lobby, raidLevel);
            return;
        }

        RaidDifficulty difficulty = difficultyOf(difficultyOrdinal);
        if (difficulty == null) {
            return;
        }
        if (!difficulty.playable()) {
            player.displayClientMessage(Component.translatable("message.stealindims.difficulty_not_playable",
                    difficulty.displayName()), true);
            return;
        }

        RaidProgress progress = player.getData(ModAttachments.RAID_PROGRESS);
        if (!progress.isUnlocked(difficulty)) {
            player.displayClientMessage(Component.translatable("message.stealindims.difficulty_locked",
                    difficulty.displayName()), true);
            return;
        }

        RaidTheme.RaidMap map = theme.map(mapId);
        if (map == null) {
            player.displayClientMessage(Component.translatable("message.stealindims.unknown_map"), true);
            return;
        }

        GlobalPos returnPoint = returnPoint(player, hostDimension);
        long seed = raidLevel.getRandom().nextLong();
        // RaidArenaBuilder is the dispatch point: it wipes and raises the four-region layouts inline,
        // and queues a mansion for the following ticks (plan §P1.3).
        RaidArenaBuilder.build(raidLevel, theme, map, difficulty, seed);

        RaidSession session = new RaidSession(player.getUUID(), hostDimension, raidDimension, theme, map,
                difficulty, seed, returnPoint);
        BY_DIMENSION.put(raidDimension, session);
        if (MansionArenaBuilder.isBuilding(raidDimension)) {
            // A mansion is still going up: the traps would be deployed into half-built rooms, so they
            // wait for the last block. The countdown is held open by tick() until then.
            MansionArenaBuilder.whenDone(raidDimension, () -> RaidTraps.deploy(raidLevel, session));
        } else {
            RaidTraps.deploy(raidLevel, session);
        }
        enterLobby(player, session, raidLevel);
    }

    /**
     * Puts {@code player} in the lobby of {@code session} (design §3: the extraction room doubles as the
     * prep lobby) unless the run has already started, in which case the door is shut.
     *
     * <p>Deliberately shared by first entry, re-entry and joining: joining pins the lobby's difficulty
     * and map rather than the ones the client sent, so a stale screen cannot open a second map.
     */
    private static void enterLobby(ServerPlayer player, RaidSession session, ServerLevel raidLevel) {
        if (session.phase() != RaidSession.Phase.WAITING) {
            player.displayClientMessage(Component.translatable("message.stealindims.lobby_closed"), true);
            return;
        }

        session.addMember(player.getUUID());
        BY_PLAYER.put(player.getUUID(), session);
        // R26: the seat exists only in memory, so the way home has to be on disk before the player
        // ever stands in the raid dimension; a reload uses it to send them back (see onLogin).
        rememberReturnPoint(player, session.returnPoint());
        teleportToLobby(player, raidLevel);
        sendStatuses(player.server, session);
    }

    private static void teleportToLobby(ServerPlayer player, ServerLevel raidLevel) {
        BlockPos spawn = RaidArena.safeRoomSpawn();
        player.teleportTo(raidLevel, spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
                player.getYRot(), player.getXRot());
    }

    // ---------------------------------------------------------------- lobby readiness

    /**
     * design §4.2: pressing the prep pad readies the player up, pressing it again stands them back
     * down. Readying everyone starts the countdown; standing down during the countdown cancels it, so
     * the run only ever starts when the whole lobby agrees.
     */
    public static void toggleReady(ServerPlayer player) {
        RaidSession session = sessionOf(player);
        if (session == null) {
            player.displayClientMessage(Component.translatable("message.stealindims.no_raid"), true);
            return;
        }

        switch (session.phase()) {
            case STARTED -> {
                player.displayClientMessage(Component.translatable("message.stealindims.ready_locked"), true);
                return;
            }
            case COUNTDOWN -> {
                session.setReady(player.getUUID(), false);
                session.backToWaiting();
                broadcast(player.server, session, Component.translatable(
                        "message.stealindims.countdown_cancelled", player.getDisplayName()));
            }
            case WAITING -> {
                if (!session.isReady(player.getUUID()) && hasBannedItems(player)) {
                    // decision A5: a banned item has to go into the lobby's ender chest first.
                    player.displayClientMessage(
                            Component.translatable("message.stealindims.ready_banned"), true);
                    return;
                }
                boolean ready = !session.isReady(player.getUUID());
                session.setReady(player.getUUID(), ready);
                if (ready && session.allReady()) {
                    session.beginCountdown(player.server.overworld().getGameTime(), COUNTDOWN_SECONDS);
                    broadcast(player.server, session, Component.translatable(
                            "message.stealindims.countdown_started", COUNTDOWN_SECONDS));
                }
            }
        }

        sendStatuses(player.server, session);
    }

    /**
     * design §4.2 / decision A5: carrying an item the raid bans blocks readying up.
     *
     * <p>The whole inventory is scanned, not just the equipment slots: the backpack goes in as it is
     * now, so a shulker box parked on it would smuggle the loot straight past the submission crate.
     * The lobby's ender chest is where such an item has to go first.
     */
    public static boolean hasBannedItems(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (isBanned(inventory.getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    /**
     * decision A5: a member may walk out of the lobby at any time before the run starts. The seat goes
     * back and a running countdown is aborted, so leaving costs nothing — and nothing has to be handed
     * back either, since entry no longer takes anything.
     */
    public static void leaveLobby(ServerPlayer player) {
        RaidSession session = sessionOf(player);
        if (session == null) {
            player.displayClientMessage(Component.translatable("message.stealindims.no_raid"), true);
            return;
        }
        exitLobby(player, session, true);
    }

    /**
     * Takes a member out of a lobby that has not started yet. Leaving costs nothing (decision A5): the
     * seat goes back and a running countdown is aborted.
     *
     * @param teleport whether to walk the player back to the device now; a disconnect defers the move
     *                 to the next login instead, so nobody ever wakes up inside the raid map
     */
    private static void exitLobby(ServerPlayer player, RaidSession session, boolean teleport) {
        boolean wasCountingDown = session.phase() == RaidSession.Phase.COUNTDOWN;
        session.removeMember(player.getUUID());
        BY_PLAYER.remove(player.getUUID());
        if (wasCountingDown) {
            // Losing a ready member means the lobby is no longer unanimous.
            session.backToWaiting();
        }

        clearStatus(player);
        if (teleport) {
            player.displayClientMessage(Component.translatable("message.stealindims.left_lobby"), true);
        } else if (player.level().dimension().equals(session.raidDimension())) {
            // Still standing in the lobby: the move happens on the next login. The persistent point
            // stays put so it survives the server going down with the player still inside (R26).
            PENDING_RETURN.put(player.getUUID(), session.returnPoint());
        } else {
            // Already outside: there is nothing left to be returned to.
            forgetReturnPoint(player);
        }

        if (session.isEmpty()) {
            BY_DIMENSION.remove(session.raidDimension(), session);
        } else {
            broadcast(player.server, session, Component.translatable(
                    "message.stealindims.lobby_left", player.getDisplayName()));
            sendStatuses(player.server, session);
        }
        if (teleport) {
            teleportTo(player, session.returnPoint());
        }
    }

    /**
     * The lobby's exit portal (§3.2, decision A5): the way out while the lobby waits, and the way to
     * pull out once the run is under way — the settlement the map anchors used to trigger.
     */
    public static void useExitPortal(ServerPlayer player) {
        RaidSession session = sessionOf(player);
        if (session == null) {
            player.displayClientMessage(Component.translatable("message.stealindims.no_raid"), true);
            return;
        }
        if (session.phase() == RaidSession.Phase.STARTED) {
            requestExtraction(player);
            return;
        }
        leaveLobby(player);
    }

    /** design §19: banned means "external storage", unless the item is on the whitelist. */
    private static boolean isBanned(ItemStack stack) {
        return stack.is(RaidTags.BANNED) && !stack.is(RaidTags.ALLOWED_STORAGE);
    }

    @Nullable
    private static RaidDifficulty difficultyOf(int ordinal) {
        RaidDifficulty[] ladder = RaidDifficulty.values();
        return ordinal >= 0 && ordinal < ladder.length ? ladder[ordinal] : null;
    }

    private static GlobalPos returnPoint(ServerPlayer player, ResourceKey<Level> hostDimension) {
        GlobalPos remembered = PORTAL_MEMORY.get(player.getUUID());
        if (remembered != null && remembered.dimension().equals(hostDimension)) {
            return remembered;
        }
        return GlobalPos.of(hostDimension, player.blockPosition());
    }

    // ---------------------------------------------------------------- inside the raid

    /**
     * The transit crate's "transfer all" button: every marked stack in the bag is sorted by value and
     * moved into the raider's own extraction area, spilling into the shared public storage, and any
     * leftover stays where it is rather than being deleted.
     */
    public static void transferAllMarked(ServerPlayer player) {
        RaidSession session = sessionOf(player);
        if (session == null) {
            return;
        }

        Inventory inventory = player.getInventory();
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (RaidLootMark.isMarked(inventory.getItem(slot))) {
                slots.add(slot);
            }
        }

        if (slots.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.stealindims.nothing_to_submit"), true);
            return;
        }

        // Highest value first, so the personal zone always gets the pick of the haul (design §7.2).
        slots.sort((first, second) -> Long.compare(
                LootValueIndex.valueOf(inventory.getItem(second)),
                LootValueIndex.valueOf(inventory.getItem(first))));

        RaidCrateZone personal = session.personalZone(player.getUUID());
        RaidCrateZone shared = session.publicStorage();
        int personalCount = 0;
        int sharedCount = 0;
        int rejected = 0;
        List<Integer> emptied = new ArrayList<>();
        for (int slot : slots) {
            ItemStack stack = inventory.getItem(slot);
            if (personal.insert(stack)) {
                personalCount++;
                emptied.add(slot);
            } else if (shared.insert(stack)) {
                sharedCount++;
                emptied.add(slot);
            } else {
                rejected++;
            }
        }

        // Only now is it safe to touch the bag: every stack has a decided destination.
        for (int slot : emptied) {
            inventory.setItem(slot, ItemStack.EMPTY);
        }
        inventory.setChanged();
        player.containerMenu.broadcastChanges();

        player.displayClientMessage(Component.translatable("message.stealindims.transferred", personalCount), true);
        if (sharedCount > 0) {
            player.displayClientMessage(Component.translatable(
                    "message.stealindims.transferred_public", sharedCount), false);
        }
        if (rejected > 0) {
            player.displayClientMessage(Component.translatable(
                    "message.stealindims.transferred_rejected", rejected), false);
        }
        sendStatus(player, session, player.server.overworld().getGameTime());
    }

    /** The extraction anchor: settle now, succeeding only when the transit crate holds enough value. */
    public static void requestExtraction(ServerPlayer player) {
        RaidSession session = sessionOf(player);
        if (session == null) {
            return;
        }
        settle(player, session.meetsThreshold(session.partySize())
                ? RaidSession.Outcome.EXTRACTED : RaidSession.Outcome.UNDER_QUOTA);
    }

    /**
     * design §9: a decorated pot is the only block a raid lets a player break, and its loot is rolled
     * by us instead of the vanilla drop table. The caller has already cancelled the break event, so
     * the block has to be removed here.
     */
    public static void breakPot(ServerLevel level, BlockPos pos) {
        level.removeBlock(pos, false);
        RaidSession session = BY_DIMENSION.get(level.dimension());
        if (session == null) {
            return;
        }
        for (ItemStack stack : RaidLootGenerator.forPot(session.theme(), session.difficulty(),
                session.seed(), pos)) {
            Block.popResource(level, pos, stack);
        }
    }

    public static void teleportToMap(ServerPlayer player) {
        RaidSession session = sessionOf(player);
        if (session == null) {
            return;
        }
        ServerLevel level = player.server.getLevel(session.raidDimension());
        if (level == null) {
            return;
        }
        BlockPos pos = session.geometry().mapSpawn();
        player.teleportTo(level, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, player.getYRot(), player.getXRot());
    }

    /**
     * The boss portal at the back of the statue's dark room (design §3.5, plan §P3.4).
     *
     * <p>The caller has already checked that the difficulty owns the boss (decision R7). Walking
     * through lands the raider inside the chamber and wakes the top evoker if it is not standing yet,
     * so the encounter always starts the moment somebody steps in.
     */
    public static void enterBossChamber(ServerPlayer player, RaidSession session) {
        if (!(session.geometry() instanceof MansionGeometry mansion)
                || mansion.bossChamberSpawn() == null) {
            player.displayClientMessage(
                    Component.translatable("message.stealindims.boss_portal_pending"), true);
            return;
        }
        ServerLevel level = player.server.getLevel(session.raidDimension());
        if (level == null) {
            player.displayClientMessage(Component.translatable("message.stealindims.no_raid"), true);
            return;
        }
        BlockPos pos = mansion.bossChamberSpawn();
        spawnBossIfMissing(level, mansion);
        player.teleportTo(level, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 180.0F, 0.0F);
    }

    /** Puts the top evoker in the chamber unless it is already there; one encounter per run. */
    private static void spawnBossIfMissing(ServerLevel level, MansionGeometry mansion) {
        BlockPos center = mansion.bossChamberCenter();
        int half = MansionGeometry.BOSS_CHAMBER_HALF;
        AABB chamber = new AABB(
                center.getX() - half, level.getMinBuildHeight(), center.getZ() - half,
                center.getX() + half + 1.0D, level.getMaxBuildHeight(), center.getZ() + half + 1.0D);
        if (!level.getEntitiesOfClass(OverworldWatcher.class, chamber).isEmpty()) {
            return;
        }
        OverworldWatcher boss = ModEntities.OVERWORLD_WATCHER.get()
                .spawn(level, center, MobSpawnType.STRUCTURE);
        if (boss == null) {
            StealInDimsMod.LOGGER.warn("Could not spawn the boss at {}", center);
            return;
        }
        // The raid mob tag is what makes its drops count as raid loot (design §21).
        boss.addTag(RaidSpawner.RAID_MOB_TAG);
        boss.addTag(OverworldWatcher.BOSS_TAG);
        boss.setPersistenceRequired();
        StealInDimsMod.LOGGER.info("Boss spawned in {} at {}", level.dimension().location(), center);
    }

    /** Broadcasts the kill to the party and keeps the encounter from being respawned. */
    public static void onBossDefeated(OverworldWatcher boss) {
        if (!(boss.level() instanceof ServerLevel level)) {
            return;
        }
        RaidSession session = BY_DIMENSION.get(level.dimension());
        StealInDimsMod.LOGGER.info("Boss defeated in {}", level.dimension().location());
        if (session == null || boss.getServer() == null) {
            return;
        }
        broadcast(boss.getServer(), session, Component.translatable(
                "message.stealindims.boss_defeated", boss.getDisplayName()));
    }

    public static void teleportToSafeRoom(ServerPlayer player) {
        RaidSession session = sessionOf(player);
        if (session == null) {
            return;
        }
        ServerLevel level = player.server.getLevel(session.raidDimension());
        if (level == null) {
            return;
        }
        BlockPos pos = RaidArena.safeRoomSpawn();
        player.teleportTo(level, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, player.getYRot(), player.getXRot());
    }

    // ---------------------------------------------------------------- settlement

    /**
     * Applies the design §20 loss table to one raider and takes them out of the session, leaving the
     * run going for the rest of the party. The seat is given up <em>before</em> the player is moved, so
     * the dimension change the teleport causes is not mistaken for an abnormal exit.
     *
     * @param teleport whether to move the player back to the device right away; death and abnormal
     *                 exits move the player later (respawn / login) instead
     */
    public static void settle(ServerPlayer player, RaidSession.Outcome outcome) {
        settle(player, outcome, true);
    }

    private static void settle(ServerPlayer player, RaidSession.Outcome outcome, boolean teleport) {
        RaidSession session = sessionOf(player);
        if (session == null) {
            return;
        }
        // Snapshot the crate before anything is torn down: the result screen shows the whole pool and
        // this raider's own zone is what they personally carry out.
        List<ItemStack> poolSnapshot = session.allLootStacks();
        List<ItemStack> ownZone = session.personalZone(player.getUUID()).snapshot();
        // Only this raider is out; the seat leaves the session so the run can carry on for the rest.
        session.removeMember(player.getUUID());
        BY_PLAYER.remove(player.getUUID());

        // A raider can be settled while a crate screen is open (death, extraction, disconnect); that
        // menu belongs to this session and must not outlive it.
        if (player.containerMenu instanceof TransferCrateMenu) {
            player.closeContainer();
        }

        Inventory inventory = player.getInventory();
        List<ItemStack> broughtOut = List.of();
        List<ItemStack> equipmentLost = List.of();
        boolean success = false;
        int unlockedOrdinal = -1;

        switch (outcome) {
            case EXTRACTED -> {
                success = true;
                broughtOut = ownZone;
                // Leaving the map is what ends the mark's job (design §19): loot carried out by hand
                // becomes ordinary goods, and the crate's share is handed back on top of it.
                unmarkInventory(player, inventory);
                giveLoot(player, inventory, broughtOut);

                RaidProgress progress = player.getData(ModAttachments.RAID_PROGRESS);
                RaidDifficulty unlocked = progress.unlockAfter(session.difficulty());
                if (unlocked != null) {
                    player.setData(ModAttachments.RAID_PROGRESS, progress);
                    unlockedOrdinal = unlocked.ordinal();
                }
            }
            case UNDER_QUOTA -> {
                discardMarked(player, inventory);
                equipmentLost = rollEquipmentLoss(player, session.difficulty());
            }
            case ABANDONED -> discardMarked(player, inventory);
            case DIED -> {
                // onDeath already emptied the inventory so nothing could drop; decide now what of it
                // comes back, and hand it over when the player respawns.
                List<ItemStack> survivors = new ArrayList<>();
                List<ItemStack> lost = new ArrayList<>();
                float keep = session.difficulty().equipmentKeepRatio();
                for (ItemStack stack : session.deathSnapshot()) {
                    if (player.getRandom().nextFloat() < keep) {
                        survivors.add(stack);
                    } else {
                        lost.add(stack);
                    }
                }
                equipmentLost = List.copyOf(lost);
                if (!survivors.isEmpty()) {
                    DEATH_REFUND.put(player.getUUID(), survivors);
                }
            }
        }

        // Its fate is decided (handed back or discarded), so the zone goes to keep the map from leaking.
        session.removePersonalZone(player.getUUID());

        if (teleport) {
            teleportTo(player, session.returnPoint());
        } else if (player.level().dimension().equals(session.raidDimension())) {
            // Still standing inside the raid: the move happens on respawn or on the next login. The
            // persistent point stays put so a server that goes down here still knows the way out (R26).
            PENDING_RETURN.put(player.getUUID(), session.returnPoint());
        } else {
            // Left the dimension on their own (design §18): the return point is moot.
            forgetReturnPoint(player);
        }

        sendResult(player, session, outcome, poolSnapshot, broughtOut, equipmentLost, success, unlockedOrdinal);

        if (session.isEmpty()) {
            // Nobody is left holding a seat, so the dimension slot goes back and the map can reopen.
            BY_DIMENSION.remove(session.raidDimension(), session);
        }
    }

    /**
     * design §20: a failed run forfeits the loot. Only the marked stacks go — what the raider brought
     * in is theirs, wherever it happens to sit in the bag.
     */
    private static void discardMarked(ServerPlayer player, Inventory inventory) {
        boolean changed = false;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (RaidLootMark.isMarked(inventory.getItem(slot))) {
                inventory.setItem(slot, ItemStack.EMPTY);
                changed = true;
            }
        }
        if (changed) {
            inventory.setChanged();
            player.containerMenu.broadcastChanges();
        }
    }

    /** Takes the raid mark off everything the player is carrying, so a carried-out haul is ordinary loot. */
    private static void unmarkInventory(ServerPlayer player, Inventory inventory) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            RaidLootMark.clear(inventory.getItem(slot));
        }
        inventory.setChanged();
        player.containerMenu.broadcastChanges();
    }

    /**
     * Hands the extraction share back. Unlike the old fixed loot band this merges into whatever room
     * the bag has, and anything that still does not fit is dropped at the player's feet rather than
     * silently deleted.
     */
    private static void giveLoot(ServerPlayer player, Inventory inventory, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            RaidLootMark.clear(stack);
            ItemStack remaining = stack.copy();
            if (!inventory.add(remaining) && !remaining.isEmpty()) {
                player.drop(remaining, false);
            }
        }
        inventory.setChanged();
        player.containerMenu.broadcastChanges();
    }

    /** design §20: per stack roll, the whole stack is lost when the roll misses. */
    private static List<ItemStack> rollEquipmentLoss(ServerPlayer player, RaidDifficulty difficulty) {
        Inventory inventory = player.getInventory();
        List<ItemStack> lost = new ArrayList<>();
        float keep = difficulty.equipmentKeepRatio();

        for (int slot : EQUIPMENT_SLOTS) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || player.getRandom().nextFloat() < keep) {
                continue;
            }
            lost.add(stack.copy());
            inventory.setItem(slot, ItemStack.EMPTY);
        }

        if (!lost.isEmpty()) {
            // The inventory was edited outside a container menu, so push it to the client too.
            inventory.setChanged();
            player.containerMenu.broadcastChanges();
        }
        return List.copyOf(lost);
    }

    // ---------------------------------------------------------------- lifecycle hooks

    /** design §20: dying forfeits everything, the equipment keeps only what the difficulty allows. */
    public static void onDeath(ServerPlayer player) {
        RaidSession session = sessionOf(player);
        if (session == null) {
            return;
        }

        Inventory inventory = player.getInventory();
        List<ItemStack> snapshot = new ArrayList<>(EQUIPMENT_SLOTS.length);
        for (int slot : EQUIPMENT_SLOTS) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                snapshot.add(stack.copy());
            }
        }
        session.setDeathSnapshot(snapshot);

        // Empty the whole inventory: loot is forfeit (design §20) and nothing may drop on the floor.
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            inventory.setItem(slot, ItemStack.EMPTY);
        }
        inventory.setChanged();

        settle(player, RaidSession.Outcome.DIED, false);
    }

    public static void onRespawn(ServerPlayer player) {
        List<ItemStack> refund = DEATH_REFUND.remove(player.getUUID());
        if (refund != null && !refund.isEmpty()) {
            Inventory inventory = player.getInventory();
            int index = 0;
            for (ItemStack stack : refund) {
                while (index < EQUIPMENT_SLOTS.length && !inventory.getItem(EQUIPMENT_SLOTS[index]).isEmpty()) {
                    index++;
                }
                if (index >= EQUIPMENT_SLOTS.length) {
                    break;
                }
                inventory.setItem(EQUIPMENT_SLOTS[index++], stack.copy());
            }
            inventory.setChanged();
            player.containerMenu.broadcastChanges();
        }
        consumePendingReturn(player);
    }

    /**
     * Stage B has no freeze window yet, so a disconnect from a run that has started settles at once as
     * an abnormal exit (design §24 asks for a grace period; that is deferred with the rest of the
     * freeze work). Dropping out of a lobby that never started costs nothing (decision A5).
     */
    public static void onLogout(ServerPlayer player) {
        RaidSession session = sessionOf(player);
        if (session == null) {
            return;
        }
        if (session.phase() == RaidSession.Phase.STARTED) {
            settle(player, RaidSession.Outcome.ABANDONED, false);
        } else {
            exitLobby(player, session, false);
        }
    }

    /**
     * Catches the runs a server restart voided, and completes a deferred return.
     *
     * <p>A login always means "not in a raid": a disconnect settles the raider before they are gone,
     * so any mark left on the inventory belongs to a run that no longer exists (design §23). Clearing
     * it here is what keeps loot marks from a crashed server out of the world for good.
     *
     * <p>R26: a login that finds the player <em>inside a raid dimension</em> means the world was
     * reloaded mid-run — the session and the deferred return are gone with the integrated server. The
     * run is then void and the player is sent back to the point their persistent data remembers. That
     * branch returns before the unmark below on purpose: the marked loot must be forfeited, not
     * laundered into ordinary goods.
     */
    public static void onLogin(ServerPlayer player) {
        RaidSession stale = BY_PLAYER.remove(player.getUUID());
        if (stale != null) {
            // Give the seat back rather than retiring the slot, in case the rest of the party is still
            // inside; the session only goes when nobody is left.
            stale.removeMember(player.getUUID());
            if (stale.isEmpty()) {
                BY_DIMENSION.remove(stale.raidDimension(), stale);
            }
        }
        if (RaidDimensions.isRaidDimension(player.level().dimension()) && returnReloadedRaider(player)) {
            return;
        }
        unmarkInventory(player, player.getInventory());
        consumePendingReturn(player);
    }

    /**
     * design §18: leaving the raid dimension by any means other than the anchor voids the run — but
     * only once the run has started. Before that it is just walking out of the lobby (decision A5).
     *
     * <p>R26 adds the mirror image: <em>arriving</em> in a raid dimension without a seat is the
     * reloaded-run state, so the same "void and go home" treatment applies.
     */
    public static void onDimensionChanged(ServerPlayer player, ResourceKey<Level> from, ResourceKey<Level> to) {
        RaidSession session = sessionOf(player);
        if (session == null) {
            if (RaidDimensions.isRaidDimension(to)) {
                returnReloadedRaider(player);
            }
            return;
        }
        if (!from.equals(session.raidDimension())) {
            return;
        }
        if (session.phase() != RaidSession.Phase.STARTED) {
            exitLobby(player, session, false);
            return;
        }
        player.displayClientMessage(Component.translatable("message.stealindims.raid_abandoned"), true);
        settle(player, RaidSession.Outcome.ABANDONED, false);
    }

    private static void consumePendingReturn(ServerPlayer player) {
        GlobalPos pending = PENDING_RETURN.remove(player.getUUID());
        if (pending != null) {
            teleportTo(player, pending);
        }
    }

    // ---------------------------------------------------------------- R26: runs reloaded mid-flight

    /** Ticks a player has to spend stuck in a raid dimension before the periodic net steps in. */
    private static final int STRANDED_GRACE_TICKS = 100;

    /** When the periodic guard first saw each player stranded, so the grace period can be measured. */
    private static final Map<UUID, Long> STRANDED_SINCE = new HashMap<>();

    /** Records the way home on disk (player persistent data) while a run holds the player. */
    private static void rememberReturnPoint(ServerPlayer player, GlobalPos point) {
        RaidReturnPolicy.write(player.getPersistentData(), point);
    }

    /** Drops the recorded way home; called once the player has actually been moved. */
    private static void forgetReturnPoint(ServerPlayer player) {
        RaidReturnPolicy.clear(player.getPersistentData());
    }

    @Nullable
    private static GlobalPos storedReturnPoint(ServerPlayer player) {
        return RaidReturnPolicy.read(player.getPersistentData());
    }

    /** The player's own bed or anchor, the first fallback when nothing was recorded (old saves). */
    @Nullable
    private static GlobalPos respawnPoint(ServerPlayer player) {
        BlockPos position = player.getRespawnPosition();
        return position == null ? null : GlobalPos.of(player.getRespawnDimension(), position);
    }

    /** The last resort, and the one target that always exists. */
    private static GlobalPos sharedSpawn(ServerPlayer player) {
        return GlobalPos.of(Level.OVERWORLD, player.server.overworld().getSharedSpawnPos());
    }

    /**
     * R26: the no-session failure variant. There is no {@link RaidSession} left to settle, so this
     * does the one thing a settlement would still have done to the raider — the submitted pool is
     * forfeited, and only the marked stacks go, exactly as in
     * {@link RaidSession.Outcome#ABANDONED} — and then walks them back home. Equipment is
     * deliberately not rolled: leaving the world is not a difficulty-scaled loss.
     *
     * <p>The decision itself is taken by {@link RaidReturnPolicy#decide}, so a player who does hold a
     * live seat (or who is not inside a raid dimension at all) is left exactly where they are.
     *
     * <p>Every path that acts ends with the player outside a raid dimension. The recorded point comes
     * first, then the player's respawn point, then the overworld's shared spawn, and if the recorded
     * dimension no longer exists at all the shared spawn is used directly.
     *
     * @return whether the run was judged a failure and the player moved, so callers can decide whether
     *         the ordinary login clean-up still has to run
     */
    private static boolean returnReloadedRaider(ServerPlayer player) {
        GlobalPos recorded = storedReturnPoint(player);
        RaidReturnPolicy.Decision decision = RaidReturnPolicy.decide(
                RaidDimensions.isRaidDimension(player.level().dimension()),
                sessionOf(player) != null,
                recorded);
        if (decision == RaidReturnPolicy.Decision.LEAVE_ALONE) {
            // A live seat, or a dimension change still in flight: nothing to judge, nothing to move.
            return false;
        }

        discardMarked(player, player.getInventory());
        clearStatus(player);
        player.displayClientMessage(Component.translatable("message.stealindims.raid_relog_failed"), false);

        GlobalPos target = RaidReturnPolicy.target(decision, recorded, respawnPoint(player), sharedSpawn(player));
        boolean moved = target != null && teleportHome(player, target);
        if (!moved || RaidDimensions.isRaidDimension(player.level().dimension())) {
            target = sharedSpawn(player);
            moved = teleportHome(player, target);
        }

        if (!moved || RaidDimensions.isRaidDimension(player.level().dimension())) {
            StealInDimsMod.LOGGER.error("R26: {} is still inside {} after the return attempt",
                    player.getGameProfile().getName(), player.level().dimension().location());
        } else {
            StealInDimsMod.LOGGER.info("R26: judged the run failed and returned {} to {}",
                    player.getGameProfile().getName(), target);
        }
        return true;
    }

    /**
     * The periodic half of R26: the login and dimension change hooks are the real gates, this catches
     * the player who reaches a raid dimension without a seat by some other route.
     *
     * <p>The grace period is what keeps it away from the sequences that legitimately spend a few ticks
     * in that state — dying gives up the seat before the respawn moves the body out, and a deferred
     * settlement does the same — so only someone still stuck after five seconds is actually stranded.
     */
    private static void guardStrandedPlayers(MinecraftServer server) {
        List<ServerPlayer> online = server.getPlayerList().getPlayers();
        if (online.isEmpty()) {
            STRANDED_SINCE.clear();
            return;
        }
        long gameTime = server.overworld().getGameTime();
        for (ServerPlayer player : online) {
            boolean stranded = player.isAlive()
                    && RaidDimensions.isRaidDimension(player.level().dimension())
                    && sessionOf(player) == null;
            if (!stranded) {
                STRANDED_SINCE.remove(player.getUUID());
                continue;
            }
            long since = STRANDED_SINCE.computeIfAbsent(player.getUUID(), id -> gameTime);
            if (gameTime - since >= STRANDED_GRACE_TICKS) {
                STRANDED_SINCE.remove(player.getUUID());
                returnReloadedRaider(player);
            }
        }
    }

    /**
     * The R26 return: like {@link #teleportTo} but allowed to nudge the landing spot, because the
     * recorded point can be buried by the time the player comes back. It also forgets the recorded
     * point the moment the player is actually moved, which is what makes repeated logins idempotent.
     */
    private static boolean teleportHome(ServerPlayer player, GlobalPos target) {
        ServerLevel level = player.server.getLevel(target.dimension());
        if (level == null) {
            return false;
        }
        BlockPos pos = standable(level, target.pos().above());
        player.teleportTo(level, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                player.getYRot(), player.getXRot());
        forgetReturnPoint(player);
        return true;
    }

    /** How far up a buried landing spot is chased before the original position is accepted. */
    private static final int STANDABLE_SCAN = 8;

    /** The given spot, or the first spot above it with room for a player and something to stand on. */
    private static BlockPos standable(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = pos.mutable();
        for (int i = 0; i < STANDABLE_SCAN; i++) {
            if (canStand(level, cursor)) {
                return cursor.immutable();
            }
            cursor.setY(cursor.getY() + 1);
        }
        return pos;
    }

    @SuppressWarnings("deprecation") // BlockState#blocksMotion is the vanilla solidity test.
    private static boolean canStand(ServerLevel level, BlockPos pos) {
        if (pos.getY() <= level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) {
            return false;
        }
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        BlockState floor = level.getBlockState(pos.below());
        return !feet.blocksMotion() && !head.blocksMotion() && floor.blocksMotion()
                && feet.getFluidState().isEmpty() && head.getFluidState().isEmpty();
    }

    // ---------------------------------------------------------------- ticking

    public static void tick(MinecraftServer server) {
        // R26's periodic net runs regardless of whether any seat is taken: the whole point is the
        // player who has no seat left.
        if (server.getTickCount() % 20 == 0) {
            guardStrandedPlayers(server);
        }

        if (BY_PLAYER.isEmpty()) {
            return;
        }

        long gameTime = server.overworld().getGameTime();
        boolean broadcast = server.getTickCount() % 20 == 0;
        Set<RaidSession> sessions = new LinkedHashSet<>(BY_PLAYER.values());

        for (RaidSession session : sessions) {
            if (session.isCountdownOver(gameTime)) {
                if (MansionArenaBuilder.isBuilding(session.raidDimension())) {
                    // The map is still going up. The lobby stays put rather than dropping the squad
                    // into a half-built mansion; the countdown simply finishes a little later.
                    continue;
                }
                session.beginStart(gameTime);
                ServerLevel raid = server.getLevel(session.raidDimension());
                if (raid != null) {
                    RaidSpawner.spawn(raid, session);
                }
                RaidSessionManager.broadcast(server, session,
                        Component.translatable("message.stealindims.raid_started"));
            }

            if (session.phase() == RaidSession.Phase.STARTED) {
                warnRemaining(server, session, gameTime);
            }

            // design §5.4: once the clock runs out the dimension collapses. The run is not settled:
            // whoever is still inside takes a bleed hit every second, and ten seconds in is finished
            // off outright, which settles them through the ordinary death path.
            if (session.isBleedingOut(gameTime) && gameTime - session.lastBleedGameTime() >= 20L) {
                session.markBleed(gameTime);
                boolean execute = gameTime >= session.bleedOutDeadline();
                for (ServerPlayer online : onlineMembers(server, session)) {
                    if (!online.level().dimension().equals(session.raidDimension())) {
                        // Only raiders still inside the collapsing dimension bleed.
                        continue;
                    }
                    bleed(online, execute);
                }
            }

            if (broadcast) {
                sendStatuses(server, session, gameTime);
            }
        }
    }

    /** design T5: one warning each at the 8, 3 and 1 minute marks, never repeated. */
    private static void warnRemaining(MinecraftServer server, RaidSession session, long gameTime) {
        int remaining = session.signedRemainingSeconds(gameTime);
        for (int minutes : new int[]{8, 3, 1}) {
            if (remaining <= minutes * 60 && session.markWarned(minutes)) {
                broadcast(server, session, Component.translatable("message.stealindims.time_warning",
                        Component.literal(String.valueOf(minutes)).withStyle(
                                minutes <= 1 ? ChatFormatting.RED : ChatFormatting.YELLOW)));
            }
        }
    }

    /** The death message the collapsing dimension deals (design T3). */
    private static final ResourceKey<DamageType> RAID_TIMEOUT = ResourceKey.create(
            Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(StealInDimsMod.MODID, "raid_timeout"));

    private static DamageSource bleedSource(ServerPlayer player) {
        Holder<DamageType> holder = player.serverLevel().registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(RAID_TIMEOUT);
        return new DamageSource(holder);
    }

    /**
     * design §5.3: one bleed hit, fully penetrating. Armour, enchantments, resistance and absorption
     * are all bypassed by the damage type's tags; absorption is not tag driven, so it is lifted out of
     * the way for the hit and put back afterwards when the raider survives.
     *
     * @param execute true on the ten second deadline, where the hit is meant to be fatal
     */
    private static void bleed(ServerPlayer player, boolean execute) {
        float amount = execute
                ? player.getMaxHealth() * 100.0F
                : Math.max(15.0F, player.getMaxHealth() * 0.25F);
        float absorption = player.getAbsorptionAmount();
        player.setAbsorptionAmount(0.0F);
        player.hurt(bleedSource(player), amount);
        if (player.isAlive()) {
            player.setAbsorptionAmount(absorption);
        }
    }

    /** Everyone in the session who is still connected, in join order. */
    private static List<ServerPlayer> onlineMembers(MinecraftServer server, RaidSession session) {
        List<ServerPlayer> online = new ArrayList<>();
        for (UUID member : session.members()) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null) {
                online.add(player);
            }
        }
        return online;
    }

    /** Chat to everyone in the session, so a lobby event reaches the whole team. */
    private static void broadcast(MinecraftServer server, RaidSession session, Component message) {
        for (ServerPlayer player : onlineMembers(server, session)) {
            player.displayClientMessage(message, false);
        }
    }

    // ---------------------------------------------------------------- payloads

    private static void sendStatuses(MinecraftServer server, RaidSession session) {
        sendStatuses(server, session, server.overworld().getGameTime());
    }

    private static void sendStatuses(MinecraftServer server, RaidSession session, long gameTime) {
        for (ServerPlayer player : onlineMembers(server, session)) {
            sendStatus(player, session, gameTime);
        }
    }

    private static void sendStatus(ServerPlayer player, RaidSession session, long gameTime) {
        PacketDistributor.sendToPlayer(player, new RaidStatusPayload(
                session.signedRemainingSeconds(gameTime),
                session.submittedValue(),
                session.requiredValue(session.partySize()),
                session.teleportTaxCount(),
                session.phase().ordinal(),
                session.countdownSeconds(gameTime),
                session.partySize(),
                List.copyOf(session.readyMembers()),
                true));
    }

    private static void sendResult(ServerPlayer player, RaidSession session, RaidSession.Outcome outcome,
                                   List<ItemStack> poolSnapshot, List<ItemStack> broughtOut,
                                   List<ItemStack> equipmentLost, boolean success, int unlockedOrdinal) {
        long gameTime = player.server.overworld().getGameTime();
        PacketDistributor.sendToPlayer(player, new RaidResultPayload(
                session.hostDimension().location(),
                session.map().name(),
                session.difficulty().ordinal(),
                session.elapsedSeconds(gameTime),
                session.remainingSeconds(gameTime),
                // Priced off the snapshot taken before the seat gave up its zone, so the figure the
                // result screen prints is the very haul the HUD was showing at settlement time.
                LootValueIndex.totalValue(poolSnapshot),
                session.requiredValue(session.partySize()),
                poolSnapshot,
                broughtOut,
                equipmentLost,
                session.teleportTaxCount(),
                session.teleportTaxValue(),
                success,
                unlockedOrdinal));
        clearStatus(player);
    }

    /** Tells the client the raid HUD is over, so no lobby or clock overlay is left behind. */
    private static void clearStatus(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new RaidStatusPayload(0, 0L, 0L, 0, 0, 0, 0, List.of(), false));
    }

    /**
     * The ordinary return move. The recorded point is one block below the landing spot (it is the
     * device the raider clicked, or their own feet block), so the player is put one block above it.
     *
     * @return whether the player was actually moved; a dimension that no longer exists is the one way
     *         this can fail, which is what lets the R26 path fall through to its last resort
     */
    private static boolean teleportTo(ServerPlayer player, GlobalPos target) {
        ServerLevel level = player.server.getLevel(target.dimension());
        if (level == null) {
            return false;
        }
        BlockPos pos = target.pos();
        player.teleportTo(level, pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D,
                player.getYRot(), player.getXRot());
        // The raider is home, so the recorded way home has served its purpose (R26 idempotence).
        forgetReturnPoint(player);
        return true;
    }
}
