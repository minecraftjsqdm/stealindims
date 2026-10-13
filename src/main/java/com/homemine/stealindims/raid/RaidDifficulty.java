package com.homemine.stealindims.raid;

import com.homemine.stealindims.StealInDimsConfig;
import java.util.Locale;
import net.minecraft.network.chat.Component;

/**
 * The four raid difficulties.
 *
 * <p>Higher difficulties give the map less time, keep less of your equipment on failure and pay more:
 * {@link #containerCounts()} scales the number of loot containers spawned across the map, and the
 * blue/purple/gold tiers grow faster than the cheap ones.
 *
 * <p>{@link #requiredValue} is the extraction threshold: loot submitted to the transit crate is only
 * considered a successful run once the total appraised value reaches it. Failing to reach it is
 * settled exactly like dying.
 */
public enum RaidDifficulty {
    /** 25 分钟 / 保留 50% 装备 / 容器 108 个 / 门槛 3 950 */
    C(25 * 60, 0.50F, 6, 1, 3950),
    /** 25 分钟 / 保留 35% 装备 / 容器 126 个 / 门槛 6 450 */
    B(25 * 60, 0.35F, 7, 1, 6450),
    /** 25 分钟 / 保留 25% 装备 / 容器 152 个 / 门槛 13 065 */
    A(25 * 60, 0.25F, 8, 2, 13065),
    /** 30 分钟 / 保留 0% 装备 / 容器 171 个 / 门槛 17 965 / 开放 Boss 战 */
    S(30 * 60, 0.0F, 9, 2, 17965);

    private final int timeLimitSeconds;
    private final float equipmentKeepRatio;
    private final int containerUnits;
    private final int goldUnits;
    private final long requiredValue;

    RaidDifficulty(int timeLimitSeconds, float equipmentKeepRatio, int containerUnits, int goldUnits, long requiredValue) {
        this.timeLimitSeconds = timeLimitSeconds;
        this.equipmentKeepRatio = equipmentKeepRatio;
        this.containerUnits = containerUnits;
        this.goldUnits = goldUnits;
        this.requiredValue = requiredValue;
    }

    public int timeLimitSeconds() {
        return this.timeLimitSeconds;
    }

    /** Fraction of the carried equipment kept when the run fails. */
    public float equipmentKeepRatio() {
        return this.equipmentKeepRatio;
    }

    /** Base unit multiplied by the container tier ratios to size the map's loot spread. */
    public int containerUnits() {
        return this.containerUnits;
    }

    /** How many times the gold container tier is worth relative to one container unit. */
    public int goldUnits() {
        return this.goldUnits;
    }

    /**
     * Number of containers spawned on the map, in tier order: decorated pot, barrel, blue, purple,
     * gold. The nominal ratio is 6:4:4:3:1 for C/B and 6:4:4:3:2 for A/S, so the totals are
     * 108 / 126 / 152 / 171 (design §4.2).
     */
    public int[] containerCounts() {
        int unit = this.containerUnits;
        return new int[]{6 * unit, 4 * unit, 4 * unit, 3 * unit, this.goldUnits * unit};
    }

    /** Minimum appraised value that has to reach the transit crate for the run to count as a success. */
    public long requiredValue() {
        return this.requiredValue;
    }

    /**
     * Whether this difficulty can be selected.
     *
     * <p>S used to be hard-coded shut while its boss encounter did not exist. From P3 it is governed
     * by {@code sDifficultyPlayable} in the common config (plan §P3.5), which ships open now that the
     * boss chamber and the top evoker are in: a pack that does not want the encounter can turn it back
     * off without a rebuild.
     */
    public boolean playable() {
        return this != S || StealInDimsConfig.sDifficultyPlayable();
    }

    /** Whether this difficulty unlocks the boss encounter. */
    public boolean hasBoss() {
        return this == S;
    }

    public Component displayName() {
        return Component.translatable("difficulty.stealindims." + this.name().toLowerCase(Locale.ROOT));
    }

    /** Single letter shown on the difficulty button. */
    public String shortName() {
        return this.name();
    }
}
