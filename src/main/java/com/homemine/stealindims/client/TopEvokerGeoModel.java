package com.homemine.stealindims.client;

import com.homemine.stealindims.entity.TopEvokerAnimatable;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * The Bedrock geometry, clip file and merged texture atlas that
 * {@code tools/bbmodel2geckolib.py} produced from the user's {@code .bbmodel} (R27 §3.1).
 *
 * <p>The paths are spelled out rather than derived from GeckoLib's {@code DefaultedEntityGeoModel}
 * convention so that the resource locations here are exactly the three files the converter writes
 * and the unit test reads back.
 *
 * <p>GeckoLib 4.9 marks the one-argument {@code getModelResource} / {@code getTextureResource}
 * lookups deprecated in favour of the two-argument ones, but leaves them abstract — so a concrete
 * model has to override them anyway, and the inherited two-argument defaults simply forward here.
 */
@SuppressWarnings("deprecation")
public final class TopEvokerGeoModel extends GeoModel<TopEvokerAnimatable> {

    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath("stealindims", "geo/entity/top_evoker.geo.json");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("stealindims", "textures/entity/top_evoker.png");
    private static final ResourceLocation ANIMATION =
            ResourceLocation.fromNamespaceAndPath("stealindims", "animations/entity/top_evoker.animation.json");

    @Override
    public ResourceLocation getModelResource(TopEvokerAnimatable animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(TopEvokerAnimatable animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(TopEvokerAnimatable animatable) {
        return ANIMATION;
    }
}
