package com.homemine.stealindims.entity;

import com.homemine.stealindims.StealInDimsMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The mod's own entities (design §3.5, plan §P3.3).
 *
 * <p>One entry so far: the top evoker that waits at the end of the mansion's boss chamber. It is a
 * real registered {@code EntityType} rather than a re-skinned vanilla evoker so that it can carry its
 * own attributes, its own loot table and its own boss bar without touching vanilla mobs.
 *
 * <p>The hitbox is sized to the user's model rather than to a vanilla evoker (R27 §3.2, decision
 * R27-c): the Blockbench geometry measures 42.6 × 46.1 × 18.9 units, i.e. 2.66 × 2.88 × 1.18
 * blocks, so the entity is 2.7 wide and 2.9 tall with its eyes at the model's head (2.0 blocks up).
 * The boss chamber's interior is 7 blocks tall and 25 × 25 across, and the portal lands raiders 9
 * blocks off the centre, so the bigger box fits the room and never overlaps a landing raider.
 */
public final class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, StealInDimsMod.MODID);

    /** 顶位唤魔者 - the mansion's boss, reached only through the statue's dark room on S. */
    public static final DeferredHolder<EntityType<?>, EntityType<OverworldWatcher>> OVERWORLD_WATCHER =
            ENTITY_TYPES.register("overworld_watcher", () -> EntityType.Builder
                    .of(TopEvokerFactory::create, MobCategory.MONSTER)
                    .sized(2.7F, 2.9F)
                    .eyeHeight(2.0F)
                    .clientTrackingRange(12)
                    .build("stealindims:overworld_watcher"));

    private ModEntities() {
    }
}
