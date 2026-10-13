package com.homemine.stealindims.raid;

import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.util.INBTSerializable;

/**
 * Per player raid unlock progress (design §17).
 *
 * <p>Difficulty is a single ladder, so the progress is just "the highest difficulty you have
 * unlocked". Clearing a map on that difficulty unlocks the next one, for every map at once.
 */
public class RaidProgress implements INBTSerializable<CompoundTag> {

    private static final String TAG_UNLOCKED = "HighestUnlocked";

    private RaidDifficulty highestUnlocked = RaidDifficulty.C;

    public RaidDifficulty highestUnlocked() {
        return this.highestUnlocked;
    }

    public boolean isUnlocked(RaidDifficulty difficulty) {
        return difficulty.ordinal() <= this.highestUnlocked.ordinal();
    }

    /**
     * Advances the ladder after a successful run.
     *
     * @return the difficulty that was just unlocked, or {@code null} when nothing changed.
     */
    @Nullable
    public RaidDifficulty unlockAfter(RaidDifficulty cleared) {
        RaidDifficulty[] ladder = RaidDifficulty.values();
        int next = cleared.ordinal() + 1;
        if (next >= ladder.length || ladder[next].ordinal() <= this.highestUnlocked.ordinal()) {
            return null;
        }
        this.highestUnlocked = ladder[next];
        return this.highestUnlocked;
    }

    /**
     * Forces the ladder up to {@code target} (the {@code /mojinunlock} command's job).
     *
     * <p>Unlocking never goes backwards: asking for a difficulty at or below the current one leaves the
     * progress alone and reports {@code null}, so a mistyped command can not take a ladder away.
     *
     * @return the difficulty that was just unlocked, or {@code null} when nothing changed.
     */
    @Nullable
    public RaidDifficulty unlockUpTo(RaidDifficulty target) {
        if (target.ordinal() <= this.highestUnlocked.ordinal()) {
            return null;
        }
        this.highestUnlocked = target;
        return this.highestUnlocked;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_UNLOCKED, this.highestUnlocked.name());
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        this.highestUnlocked = parse(tag.getString(TAG_UNLOCKED));
    }

    private static RaidDifficulty parse(String name) {
        for (RaidDifficulty difficulty : RaidDifficulty.values()) {
            if (difficulty.name().equals(name)) {
                return difficulty;
            }
        }
        return RaidDifficulty.C;
    }
}
