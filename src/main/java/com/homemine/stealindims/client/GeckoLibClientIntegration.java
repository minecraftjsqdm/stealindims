package com.homemine.stealindims.client;

import com.homemine.stealindims.entity.ModEntities;
import com.homemine.stealindims.entity.OverworldWatcher;
import com.homemine.stealindims.entity.TopEvokerAnimatable;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * The single place where client registration touches GeckoLib (R27 §3.2).
 *
 * <p>{@link StealInDimsClient} only calls into this class after
 * {@code GeoCompat.animationsAvailable()} has said yes, so a pack without GeckoLib never resolves
 * {@link TopEvokerGeoRenderer} or any of the GeckoLib types it extends, and the fallback
 * {@code EvokerRenderer} is registered instead.
 *
 * <p>The cast is the price of keeping the boss's entity class free of GeckoLib: the entity type is
 * declared as {@code EntityType<OverworldWatcher>} (so the rest of the mod, and the fallback
 * renderer, only ever see the plain class) while the renderer is generic over the animated
 * subclass. At runtime every instance really is a {@link TopEvokerAnimatable} whenever this branch
 * is taken — {@code TopEvokerFactory} guarantees it — so the cast cannot fail.
 */
final class GeckoLibClientIntegration {

    private GeckoLibClientIntegration() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void registerBossRenderer(EntityRenderersEvent.RegisterRenderers event) {
        EntityRendererProvider<OverworldWatcher> provider =
                (EntityRendererProvider) (EntityRendererProvider<TopEvokerAnimatable>) TopEvokerGeoRenderer::new;
        event.registerEntityRenderer(ModEntities.OVERWORLD_WATCHER.get(), provider);
    }
}
