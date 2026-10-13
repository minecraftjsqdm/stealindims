package com.homemine.stealindims.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * Builds the top evoker, picking the class that matches the runtime (R27 §3.2).
 *
 * <p>With GeckoLib installed the boss is a {@link TopEvokerAnimatable}, which carries the animatable
 * instance cache and the animation controller. Without it the very same entity type produces a
 * plain {@link OverworldWatcher}, which still runs the whole skill state machine — only the model
 * falls back to vanilla's evoker.
 *
 * <p>The {@code TopEvokerAnimatable} reference lives in {@link #createAnimated} on purpose. That
 * method is only reached through {@link GeoCompat#animationsAvailable()}, so when GeckoLib is
 * absent the JVM never has to resolve a class that implements one of its interfaces.
 */
public final class TopEvokerFactory {

    private TopEvokerFactory() {
    }

    /** {@code EntityType.EntityFactory} for {@code stealindims:overworld_watcher}. */
    public static OverworldWatcher create(EntityType<OverworldWatcher> entityType, Level level) {
        if (GeoCompat.animationsAvailable()) {
            return createAnimated(entityType, level);
        }
        return new OverworldWatcher(entityType, level);
    }

    private static OverworldWatcher createAnimated(EntityType<OverworldWatcher> entityType, Level level) {
        return new TopEvokerAnimatable(entityType, level);
    }
}
