package com.homemine.stealindims.entity;

import net.neoforged.fml.ModList;

/**
 * The one place that asks whether GeckoLib is installed (R27 §3.2, "soft dependency").
 *
 * <p>The top evoker's model and its nine animations need GeckoLib, but this mod must still load
 * without it. The rule that makes that work is: <em>no class outside the {@code client} Glue and
 * {@code TopEvokerAnimatable} may mention a GeckoLib type</em>. Those classes are only ever
 * resolved on the far side of the {@link #animationsAvailable()} branch, so the JVM never has to
 * load them when the dependency is missing, and the boss quietly falls back to vanilla's
 * {@code EvokerRenderer}.
 */
public final class GeoCompat {

    private static Boolean available;

    private GeoCompat() {
    }

    /**
     * Whether GeckoLib is present, cached after the first call.
     *
     * <p>Deliberately not a constant: the mod list is only complete once NeoForge has finished
     * constructing it, and the first caller can be as early as entity-type registration.
     */
    public static boolean animationsAvailable() {
        Boolean cached = available;
        if (cached == null) {
            cached = ModList.get() != null && ModList.get().isLoaded("geckolib");
            available = cached;
        }
        return cached;
    }

    /** Test seam: forget the cached answer so a test can exercise both branches. */
    static void resetForTest() {
        available = null;
    }
}
