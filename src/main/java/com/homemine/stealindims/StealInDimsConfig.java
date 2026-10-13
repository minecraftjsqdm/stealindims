package com.homemine.stealindims;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The mod's common configuration (plan §P3.5).
 *
 * <p>There is exactly one switch so far: whether {@code RaidDifficulty.S} can be selected. S was held
 * shut through P1 and P2 because its boss encounter did not exist, and the design asked for it to
 * become configurable rather than hard-coded, so a pack that does not want the boss can turn it back
 * off without a rebuild.
 *
 * <p>The default is <em>open</em>: P3 is the phase that shipped the boss, so a fresh install gets the
 * full ladder. The accessor is defensive about being asked before the config has loaded, because
 * {@link com.homemine.stealindims.raid.RaidDifficulty#playable()} is reachable from data generation
 * and from the client's select screen.
 */
public final class StealInDimsConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** Whether the S difficulty is selectable; the boss encounter's master switch. */
    private static final ModConfigSpec.BooleanValue S_DIFFICULTY_PLAYABLE = BUILDER
            .comment("Whether the S difficulty can be selected on the raid select screen.",
                    "S is the difficulty the mansion's boss chamber belongs to; the portal in the",
                    "statue's dark room only opens for it.")
            .define("sDifficultyPlayable", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private StealInDimsConfig() {
    }

    /** Whether S may be departed to. Falls back to the shipped default before the config loads. */
    public static boolean sDifficultyPlayable() {
        try {
            return S_DIFFICULTY_PLAYABLE.get();
        } catch (IllegalStateException notLoadedYet) {
            return true;
        }
    }
}
