package com.homemine.stealindims.raid;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * R26: the rule that sends a raider home when the world is reloaded underneath a run, plus the NBT
 * round trip that makes it survive the reload.
 *
 * <p>The return point used to live only in {@link RaidSession}, which is server runtime state. "Save
 * and quit" stops the integrated server, so a single-player reload used to leave the player inside
 * {@code stealindims:raid_*} with no session and no way out — the warp device inside a raid dimension
 * needs a session ({@code RaidPortalBlock.useInsideRaid}). The point is therefore written into the
 * player's persistent data while the run is on: {@code player.getPersistentData()} is a
 * {@link CompoundTag} NeoForge stores on the entity and saves with the world.
 *
 * <p>Nothing in here touches server state, so both the decision and the tag round trip are pinned
 * down by a headless test ({@code RaidReturnPolicyTest}); {@link RaidSessionManager} is what supplies
 * the actual player.
 */
public final class RaidReturnPolicy {

    /** Where the return point sits inside the player's persistent data. Namespaced like the mod's data. */
    public static final String TAG_RETURN_POINT = "stealindims:return_point";
    private static final String TAG_DIMENSION = "dimension";
    private static final String TAG_X = "x";
    private static final String TAG_Y = "y";
    private static final String TAG_Z = "z";

    /** What a "is this player stranded inside a raid dimension?" check has to do about it. */
    public enum Decision {
        /** Not our business: outside a raid dimension, or the player still holds a live seat. */
        LEAVE_ALONE,
        /** Reloaded mid-run: judged a failure and sent to the recorded return point. */
        RETURN_TO_RECORDED_POINT,
        /** Reloaded mid-run with nothing recorded (old save): judged a failure and sent to a fallback. */
        RETURN_TO_FALLBACK
    }

    private RaidReturnPolicy() {
    }

    /**
     * The R26 decision: only a player who is <em>inside a raid dimension without a live session</em>
     * is stranded. A live session keeps the ordinary flow (entry, submission, extraction, death,
     * reconnect) untouched, and anywhere else the player is simply not our problem.
     *
     * @param recordedPoint the point read back from the player's persistent data, or {@code null}
     */
    public static Decision decide(boolean inRaidDimension, boolean hasLiveSession,
                                  @Nullable GlobalPos recordedPoint) {
        if (!inRaidDimension || hasLiveSession) {
            return Decision.LEAVE_ALONE;
        }
        return recordedPoint != null ? Decision.RETURN_TO_RECORDED_POINT : Decision.RETURN_TO_FALLBACK;
    }

    /**
     * Where the player has to end up. {@code null} is only returned for an unknown decision: every
     * in-game call site passes the overworld's shared spawn as the last resort, so a stranded player
     * can never be left where they are.
     *
     * <p>A target that points back into a raid dimension is not usable — a corrupted respawn point
     * must not undo the whole point of the exercise — so it is skipped in favour of the next one.
     */
    @Nullable
    public static GlobalPos target(Decision decision, @Nullable GlobalPos recordedPoint,
                                   @Nullable GlobalPos respawnPoint, @Nullable GlobalPos sharedSpawn) {
        return switch (decision) {
            case LEAVE_ALONE -> null;
            case RETURN_TO_RECORDED_POINT -> usable(recordedPoint) ? recordedPoint
                    : usable(respawnPoint) ? respawnPoint : sharedSpawn;
            case RETURN_TO_FALLBACK -> usable(respawnPoint) ? respawnPoint : sharedSpawn;
        };
    }

    /** A return target is usable when it exists and does not send the player back into a raid map. */
    public static boolean usable(@Nullable GlobalPos point) {
        return point != null && !RaidDimensions.isRaidDimension(point.dimension());
    }

    // ---------------------------------------------------------------- persistent data

    /** Stores the return point in the player's persistent data (written to disk with the world). */
    public static void write(CompoundTag data, GlobalPos point) {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_DIMENSION, point.dimension().location().toString());
        tag.putInt(TAG_X, point.pos().getX());
        tag.putInt(TAG_Y, point.pos().getY());
        tag.putInt(TAG_Z, point.pos().getZ());
        data.put(TAG_RETURN_POINT, tag);
    }

    /** Reads the return point back, or {@code null} when nothing usable is stored. */
    @Nullable
    public static GlobalPos read(CompoundTag data) {
        if (!data.contains(TAG_RETURN_POINT, CompoundTag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag tag = data.getCompound(TAG_RETURN_POINT);
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString(TAG_DIMENSION));
        if (dimension == null) {
            return null;
        }
        if (!tag.contains(TAG_X, CompoundTag.TAG_INT) || !tag.contains(TAG_Y, CompoundTag.TAG_INT)
                || !tag.contains(TAG_Z, CompoundTag.TAG_INT)) {
            return null;
        }
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, dimension);
        return GlobalPos.of(key, new BlockPos(tag.getInt(TAG_X), tag.getInt(TAG_Y), tag.getInt(TAG_Z)));
    }

    /** Drops the stored return point; the run is over or the player has already been moved home. */
    public static void clear(CompoundTag data) {
        data.remove(TAG_RETURN_POINT);
    }

    /** Whether anything is stored at all, without paying for a full decode. */
    public static boolean isStored(CompoundTag data) {
        return data.contains(TAG_RETURN_POINT);
    }
}
