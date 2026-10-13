package com.homemine.stealindims.client;

import com.homemine.stealindims.ModMenus;
import com.homemine.stealindims.StealInDimsMod;
import com.homemine.stealindims.entity.GeoCompat;
import com.homemine.stealindims.entity.ModEntities;
import net.minecraft.client.renderer.entity.EvokerRenderer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * Client-only registrations.
 *
 * <p>Registering the screen is not optional: without it {@code MenuScreens.create} logs a warning and
 * never builds the client menu, so the server would think a container is open while the client has
 * nothing to send clicks to.
 *
 * <p>The raid HUD goes on top of every vanilla layer, so no other overlay can cover the clock.
 *
 * <p>The boss is drawn with the user's own Blockbench model when GeckoLib is installed (R27), and
 * borrows vanilla's {@link EvokerRenderer} — which is generic over any {@code SpellcasterIllager} —
 * when it is not. {@link GeckoLibClientIntegration} holds every GeckoLib reference so that the
 * fallback branch never has to load a class that implements GeckoLib's interfaces.
 */
@EventBusSubscriber(modid = StealInDimsMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class StealInDimsClient {

    private static final ResourceLocation RAID_HUD =
            ResourceLocation.fromNamespaceAndPath(StealInDimsMod.MODID, "raid_hud");

    private StealInDimsClient() {
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.RAID_PORTAL.get(), RaidPortalScreen::new);
        event.register(ModMenus.TRANSFER_CRATE.get(), TransferCrateScreen::new);
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        if (GeoCompat.animationsAvailable()) {
            GeckoLibClientIntegration.registerBossRenderer(event);
        } else {
            event.registerEntityRenderer(ModEntities.OVERWORLD_WATCHER.get(), EvokerRenderer::new);
        }
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(RAID_HUD, RaidHudOverlay::render);
    }
}
