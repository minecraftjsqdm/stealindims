package com.homemine.stealindims.client;

import com.homemine.stealindims.entity.TopEvokerAnimatable;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Draws the top evoker with GeckoLib (R27 §3.2).
 *
 * <p>Nothing is overridden: {@code GeoEntityRenderer} already handles the model, the texture, the
 * animation controller and the death fade, and the geometry is authored at 1 unit = 1/16 block so
 * it lands at the entity's feet without a scale correction.
 */
public final class TopEvokerGeoRenderer extends GeoEntityRenderer<TopEvokerAnimatable> {

    public TopEvokerGeoRenderer(EntityRendererProvider.Context context) {
        super(context, new TopEvokerGeoModel());
    }
}
