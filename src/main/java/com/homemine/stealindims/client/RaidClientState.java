package com.homemine.stealindims.client;

import com.homemine.stealindims.net.RaidStatusPayload;
import java.util.Set;
import java.util.UUID;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Whatever the HUD needs, mirrored from the last {@link RaidStatusPayload} (design §21: a clock, the
 * submitted value and the threshold).
 *
 * <p>The server pushes this once a second while a run is live and follows the settlement with an
 * inactive status, so the client never has to guess when to drop the HUD.
 *
 * <p>It also mirrors the lobby: {@link #phase()} tells the HUD whether to show the ready tally, the
 * countdown or the clock, and {@link #isReady(UUID)} answers the green tick question for a name tag.
 */
public final class RaidClientState {

    /** Mirrors {@code RaidSession.Phase} ordinal values, kept as plain ints so the client stays light. */
    public static final int WAITING = 0;
    public static final int COUNTDOWN = 1;
    public static final int STARTED = 2;

    private static boolean active;
    private static int remainingSeconds;
    private static long submittedValue;
    private static long requiredValue;
    private static int teleportTaxCount;
    private static int phase = STARTED;
    private static int countdownSeconds;
    private static int memberCount;
    private static Set<UUID> readyMembers = Set.of();

    private RaidClientState() {
    }

    public static void applyStatus(RaidStatusPayload payload) {
        active = payload.inRaid();
        remainingSeconds = payload.remainingSeconds();
        submittedValue = payload.submittedValue();
        requiredValue = payload.requiredValue();
        teleportTaxCount = payload.teleportTaxCount();
        phase = payload.phase();
        countdownSeconds = payload.countdownSeconds();
        memberCount = payload.memberCount();
        readyMembers = Set.copyOf(payload.readyMembers());
    }

    public static void clear() {
        active = false;
        remainingSeconds = 0;
        submittedValue = 0L;
        requiredValue = 0L;
        teleportTaxCount = 0;
        phase = STARTED;
        countdownSeconds = 0;
        memberCount = 0;
        readyMembers = Set.of();
    }

    public static boolean isActive() {
        return active;
    }

    public static int remainingSeconds() {
        return remainingSeconds;
    }

    public static long submittedValue() {
        return submittedValue;
    }

    public static long requiredValue() {
        return requiredValue;
    }

    public static int teleportTaxCount() {
        return teleportTaxCount;
    }

    public static int phase() {
        return phase;
    }

    public static int countdownSeconds() {
        return countdownSeconds;
    }

    public static int memberCount() {
        return memberCount;
    }

    /**
     * The threshold multiplier for the current party, as a one-decimal string: 1.0 solo and +30% per
     * extra raider (design §20), matching {@code RaidSession.requiredValue(int)} on the server.
     */
    public static String partyFactor() {
        return String.format(java.util.Locale.ROOT, "%.1f", 1.0 + 0.3 * (Math.max(1, memberCount) - 1));
    }

    public static int readyCount() {
        return readyMembers.size();
    }

    /** Whether the player behind {@code playerId} has readied up: drives the green tick. */
    public static boolean isReady(UUID playerId) {
        return active && readyMembers.contains(playerId);
    }

    /**
     * Formats a second count as {@code mm:ss}, which is what the HUD clock shows. Once the timer has
     * run out with raiders still alive the count goes negative (design §T8), so the sign is part of
     * the output: {@code -00:05} means five seconds past the collapse.
     */
    public static String formatClock(int seconds) {
        int abs = Math.abs(seconds);
        return String.format("%s%02d:%02d", seconds < 0 ? "-" : "", abs / 60, abs % 60);
    }

    /** Formats a value with thousands separators so readouts stay legible. */
    public static String formatValue(long value) {
        return String.format("%,d", value);
    }

    /**
     * A readable name for a host dimension. Vanilla ships no translation for dimension ids, so the hosts
     * this mod knows get their own key and anything else falls back to the raw id.
     */
    public static Component dimensionName(ResourceLocation dimension) {
        String key = "gui.stealindims.dimension." + dimension.getNamespace() + "." + dimension.getPath();
        return Language.getInstance().has(key)
                ? Component.translatable(key)
                : Component.literal(dimension.toString());
    }
}
