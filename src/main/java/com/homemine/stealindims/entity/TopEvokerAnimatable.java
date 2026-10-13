package com.homemine.stealindims.entity;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.instance.SingletonAnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * The top evoker as GeckoLib sees it (R27 §3.2).
 *
 * <p>This is the only common-side class in the mod that mentions GeckoLib, and it is only ever
 * instantiated through {@link TopEvokerFactory}'s guarded branch — so a pack without GeckoLib
 * never loads it and the boss stays a plain {@link OverworldWatcher}.
 *
 * <p>The controller is intentionally trivial. The animation to play is <em>not</em> decided here:
 * the server runs the encounter and syncs a {@link TopEvokerSkill} ordinal, and the client simply
 * plays the clip that ordinal names. That keeps the client free of any combat logic while the
 * timings stay identical on both sides, because both read them from the same clip file.
 *
 * <p>The {@code RawAnimation} for each clip is built once and cached. The loop mode comes from the
 * clip's own {@code loop} field — {@code loop}, {@code play_once} or {@code hold_on_last_frame} —
 * which is what the converter mapped out of Blockbench's {@code loop} / {@code once} / {@code hold}.
 */
public class TopEvokerAnimatable extends OverworldWatcher implements GeoEntity {

    private static final String CONTROLLER = "top_evoker_skills";

    /** Ticks GeckoLib blends across when the state changes, so clips do not snap. */
    private static final int TRANSITION_TICKS = 3;

    private static final Map<String, RawAnimation> CLIPS = new HashMap<>();

    private final AnimatableInstanceCache cache = new SingletonAnimatableInstanceCache(this);

    public TopEvokerAnimatable(EntityType<? extends Evoker> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, CONTROLLER, TRANSITION_TICKS, this::animate));
    }

    private PlayState animate(AnimationState<TopEvokerAnimatable> state) {
        return state.setAndContinue(clip(this.skill().animation()));
    }

    private static RawAnimation clip(String animation) {
        return CLIPS.computeIfAbsent(animation, name -> switch (TopEvokerSkillTiming.clip(name).loop()) {
            case "loop" -> RawAnimation.begin().thenLoop(name);
            case "hold_on_last_frame" -> RawAnimation.begin().thenPlayAndHold(name);
            default -> RawAnimation.begin().thenPlay(name);
        });
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
