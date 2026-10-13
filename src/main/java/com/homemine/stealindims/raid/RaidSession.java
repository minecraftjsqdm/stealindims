package com.homemine.stealindims.raid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * One running raid (design §5: a player can only be in one raid, and a dimension can only host one).
 *
 * <p>The session owns the clock, the transit crate pool and the return point. Everything that decides
 * whether the run paid off lives here; {@link RaidSessionManager} drives it.
 *
 * <p>From the lobby rework on, a session starts in {@link Phase#WAITING}: the map is already built and
 * everyone is standing in the extraction room (the prep lobby), but the clock does not move and the
 * map is not reachable until every member has readied up.
 */
public final class RaidSession {

    /** Why a run ended, which decides what happens to the submitted pool and the equipment. */
    public enum Outcome {
        /** Reached the threshold and pulled out through the extraction anchor. */
        EXTRACTED,
        /** Pulled out through the anchor but short of the threshold: settled like dying. */
        UNDER_QUOTA,
        /** Died inside the raid. */
        DIED,
        /** Left the raid dimension by other means: submitted loot is lost, equipment is kept. */
        ABANDONED
    }

    /** Where the lobby is in its life cycle. */
    public enum Phase {
        /** In the prep lobby: no clock, no way into the map, waiting for everyone to ready up. */
        WAITING,
        /** Everyone is ready; the 16 second countdown is running and join is locked. */
        COUNTDOWN,
        /** The run proper: the clock is ticking and the map is open. */
        STARTED
    }

    /** Design §20: each member can carry out at most 27 stacks, held in their own personal zone. */
    public static final int PERSONAL_ZONE_SIZE = 27;
    /** The squad shares one 54 slot public storage; whatever overflows the personal zones lands here. */
    public static final int PUBLIC_STORAGE_SIZE = 54;

    private final UUID initiator;
    private final ResourceKey<Level> raidDimension;
    private final ResourceKey<Level> hostDimension;
    private final RaidDifficulty difficulty;
    private final RaidTheme.RaidMap map;
    private final RaidTheme theme;
    private final long seed;
    private final GlobalPos returnPoint;

    private final Set<UUID> members = new LinkedHashSet<>();
    private final Set<UUID> readyMembers = new LinkedHashSet<>();
    private Phase phase = Phase.WAITING;
    private long startGameTime = -1L;
    private long bleedStartGameTime = Long.MAX_VALUE;
    private long countdownEndGameTime;
    private int partySize;
    /** Game time of the last bleed hit, so the 20 tick rhythm does not depend on a global phase. */
    private long lastBleedGameTime;
    /** Minute warnings (8/3/1) already broadcast, so each one fires exactly once. */
    private final Set<Integer> warnedMinutes = new HashSet<>();

    /** Each member's own 27 slot extraction area, created on demand. */
    private final Map<UUID, RaidCrateZone> personalZones = new HashMap<>();
    /** The 54 slot storage every member can see and take from. */
    private final RaidCrateZone publicStorage = new RaidCrateZone(PUBLIC_STORAGE_SIZE);

    private int teleportTaxCount;
    private long teleportTaxValue;
    private List<ItemStack> deathSnapshot = List.of();

    public RaidSession(UUID initiator, ResourceKey<Level> hostDimension, ResourceKey<Level> raidDimension,
                       RaidTheme theme, RaidTheme.RaidMap map, RaidDifficulty difficulty,
                       long seed, GlobalPos returnPoint) {
        this.initiator = initiator;
        this.hostDimension = hostDimension;
        this.raidDimension = raidDimension;
        this.theme = theme;
        this.map = map;
        this.difficulty = difficulty;
        this.seed = seed;
        this.returnPoint = returnPoint;
        this.members.add(initiator);
    }

    // ---------------------------------------------------------------- identity

    /** The raider who opened the lobby. Kept for the legacy single-player call sites. */
    public UUID playerId() {
        return this.initiator;
    }

    public ResourceKey<Level> raidDimension() {
        return this.raidDimension;
    }

    public ResourceKey<Level> hostDimension() {
        return this.hostDimension;
    }

    public RaidDifficulty difficulty() {
        return this.difficulty;
    }

    public RaidTheme theme() {
        return this.theme;
    }

    public RaidTheme.RaidMap map() {
        return this.map;
    }

    /**
     * The geometry this run's map was built with, resolved from the map's {@code geometry} field.
     *
     * <p>Derived on demand from the map, the difficulty and the seed rather than stored, so a session
     * can never disagree with the map it came from. Both implementations are cheap to obtain: the
     * region geometry is a handful of arithmetic, and the mansion geometry memoises the plan it grew
     * from {@code (difficulty, seed, room archetypes)}.
     */
    public RaidGeometry geometry() {
        return RaidGeometry.of(this.map, this.difficulty, this.seed);
    }

    public long seed() {
        return this.seed;
    }

    public GlobalPos returnPoint() {
        return this.returnPoint;
    }

    // ---------------------------------------------------------------- party

    /** Everyone currently in the raid, in join order. */
    public Set<UUID> members() {
        return Set.copyOf(this.members);
    }

    public int playerCount() {
        return this.members.size();
    }

    /**
     * The headcount the shared threshold is priced against. It is frozen the moment the run starts, so
     * pulling out early cannot lower the bar for whoever is still inside.
     */
    public int partySize() {
        return this.phase == Phase.STARTED ? this.partySize : this.members.size();
    }

    public boolean isMember(UUID playerId) {
        return this.members.contains(playerId);
    }

    public void addMember(UUID playerId) {
        this.members.add(playerId);
    }

    /** Drops a member and their ready flag. Readiness of the remaining members is unaffected. */
    public void removeMember(UUID playerId) {
        this.members.remove(playerId);
        this.readyMembers.remove(playerId);
    }

    public boolean isEmpty() {
        return this.members.isEmpty();
    }

    // ---------------------------------------------------------------- readiness

    public Phase phase() {
        return this.phase;
    }

    public boolean isReady(UUID playerId) {
        return this.readyMembers.contains(playerId);
    }

    public Set<UUID> readyMembers() {
        return Set.copyOf(this.readyMembers);
    }

    /** Toggles readiness; only members count. */
    public void setReady(UUID playerId, boolean ready) {
        if (!this.members.contains(playerId)) {
            return;
        }
        if (ready) {
            this.readyMembers.add(playerId);
        } else {
            this.readyMembers.remove(playerId);
        }
    }

    /** True once there is at least one member and every one of them is ready. */
    public boolean allReady() {
        return !this.members.isEmpty() && this.readyMembers.containsAll(this.members);
    }

    /** Starts the countdown that runs until {@code gameTime >= countdownEndGameTime}. */
    public void beginCountdown(long gameTime, int seconds) {
        this.phase = Phase.COUNTDOWN;
        this.countdownEndGameTime = gameTime + (long) seconds * 20L;
    }

    /** Aborts the countdown and goes back to waiting for everyone to ready up again. */
    public void backToWaiting() {
        this.phase = Phase.WAITING;
        this.countdownEndGameTime = 0L;
    }

    public int countdownSeconds(long gameTime) {
        return (int) Math.max(0L, (this.countdownEndGameTime - gameTime) / 20L);
    }

    public boolean isCountdownOver(long gameTime) {
        return this.phase == Phase.COUNTDOWN && gameTime >= this.countdownEndGameTime;
    }

    // ---------------------------------------------------------------- clock

    /** Opens the map and starts the timer for real, freezing the party the threshold is priced for. */
    public void beginStart(long gameTime) {
        this.phase = Phase.STARTED;
        this.partySize = this.members.size();
        this.startGameTime = gameTime;
        this.bleedStartGameTime = gameTime + (long) this.difficulty.timeLimitSeconds() * 20L;
    }

    public int elapsedSeconds(long gameTime) {
        if (this.phase != Phase.STARTED) {
            return 0;
        }
        return (int) Math.max(0L, (gameTime - this.startGameTime) / 20L);
    }

    /** Before the run starts the lobby shows the full time limit, not a countdown. Clamped at zero. */
    public int remainingSeconds(long gameTime) {
        if (this.phase != Phase.STARTED) {
            return this.difficulty.timeLimitSeconds();
        }
        return (int) Math.max(0L, (this.bleedStartGameTime - gameTime) / 20L);
    }

    /** Signed remaining seconds: negative once the bleed out has begun, for the HUD clock. */
    public int signedRemainingSeconds(long gameTime) {
        if (this.phase != Phase.STARTED) {
            return this.difficulty.timeLimitSeconds();
        }
        return (int) ((this.bleedStartGameTime - gameTime) / 20L);
    }

    /** True once the clock has run out but the run has not been settled: the dimension is collapsing. */
    public boolean isBleedingOut(long gameTime) {
        return this.phase == Phase.STARTED && gameTime >= this.bleedStartGameTime;
    }

    /** How long a raider can cling on past the deadline before being finished off (10 seconds). */
    public long bleedOutDeadline() {
        return this.bleedStartGameTime + 200L;
    }

    /** Game time of the last bleed hit; see {@link #markBleed(long)}. */
    public long lastBleedGameTime() {
        return this.lastBleedGameTime;
    }

    /** Records that a bleed hit has just landed. */
    public void markBleed(long gameTime) {
        this.lastBleedGameTime = gameTime;
    }

    /** Returns true the first time {@code minutes} is asked about, false on every later call. */
    public boolean markWarned(int minutes) {
        return this.warnedMinutes.add(minutes);
    }

    // ---------------------------------------------------------------- transit crate

    /** The member's own extraction area, created on first use. */
    public RaidCrateZone personalZone(UUID playerId) {
        return this.personalZones.computeIfAbsent(playerId, id -> new RaidCrateZone(PERSONAL_ZONE_SIZE));
    }

    /** The shared storage every member can reach. */
    public RaidCrateZone publicStorage() {
        return this.publicStorage;
    }

    /** Drops a member's personal zone, returning a snapshot of what was in it. */
    public List<ItemStack> removePersonalZone(UUID playerId) {
        RaidCrateZone zone = this.personalZones.remove(playerId);
        return zone == null ? List.of() : zone.snapshot();
    }

    /** Snapshots every personal zone plus the shared storage. */
    public List<ItemStack> allLootStacks() {
        List<ItemStack> stacks = new ArrayList<>();
        for (RaidCrateZone zone : this.personalZones.values()) {
            stacks.addAll(zone.snapshot());
        }
        stacks.addAll(this.publicStorage.snapshot());
        return stacks;
    }

    /**
     * Extraction threshold for a run with {@code playerCount} raiders: the difficulty's base value plus
     * 30% for every extra player, so a squad still has to bring out a comparable haul each.
     */
    public long requiredValue(int playerCount) {
        return Math.round(this.difficulty.requiredValue() * (1.0 + 0.3 * (playerCount - 1)));
    }

    /** The haul counted against the threshold: every personal zone plus the shared storage. */
    public long submittedValue() {
        return LootValueIndex.totalValue(allLootStacks());
    }

    public boolean meetsThreshold(int playerCount) {
        return this.submittedValue() >= this.requiredValue(playerCount);
    }

    // ---------------------------------------------------------------- teleport tax

    /** Records a teleport. The loot that pays for it is removed by the caller, this only keeps score. */
    public void addTeleportTax(long value) {
        this.teleportTaxCount++;
        this.teleportTaxValue += value;
    }

    public int teleportTaxCount() {
        return this.teleportTaxCount;
    }

    public long teleportTaxValue() {
        return this.teleportTaxValue;
    }

    // ---------------------------------------------------------------- death

    public void setDeathSnapshot(List<ItemStack> snapshot) {
        this.deathSnapshot = List.copyOf(snapshot);
    }

    public List<ItemStack> deathSnapshot() {
        return this.deathSnapshot;
    }
}
