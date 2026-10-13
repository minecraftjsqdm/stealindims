package com.homemine.stealindims.entity;

/**
 * The nine states of the top evoker encounter, each bound to one of the clips the user's model
 * ships (R27 §3.2).
 *
 * <p>The enum is the single list that both sides share: the server walks it as an authoritative
 * state machine, and the client renders whichever one the server synced (see
 * {@link OverworldWatcher#skill()}). Animation <em>names</em> live here because they are the
 * asset's own identifiers — the converter copies them verbatim into the clip file — while every
 * <em>duration</em> and <em>release frame</em> is read out of that file by
 * {@link TopEvokerSkillTiming}, never guessed.
 *
 * <p>Ordering matters: the ordinals travel over the wire in a synched entity datum, so constants
 * are only ever appended.
 */
public enum TopEvokerSkill {

    /** 01_开战_翻书回首转身 — the spawn performance: invulnerable and rooted for its whole length. */
    INTRO("01_开战_翻书回首转身", null),

    /** 00_威严待机 — the loop the encounter idles in between casts. */
    IDLE("00_威严待机", null),

    /** 02_弩箭大师_五弩依次三重射击 — five crossbows, three bolts each. */
    CROSSBOW("02_弩箭大师_五弩依次三重射击", null),

    /** 03_圆形尖刺_环形召唤 — both arms sweep out; the ring lands on the outward sweep. */
    RING("03_圆形尖刺_环形召唤",
            TopEvokerSkillTiming.minOf("arm_right", "rotation", 2)),

    /**
     * 04_直线尖刺_前指递进 — the right arm levels forward after a high wind-up.
     *
     * <p>The wind-up is the X trough rather than the peak: the converter negates every bone's X
     * rotation to match GeckoLib's convention (see {@code FLIP_ROTATION_X}).
     */
    LINE("04_直线尖刺_前指递进",
            TopEvokerSkillTiming.afterTroughOf("arm_right", "rotation", 0)),

    /**
     * 05_恼鬼释放_召唤施法 — both arms reach their highest point before the vexes arrive.
     *
     * <p>Read as the X trough for the same reason as {@link #LINE}.
     */
    VEX("05_恼鬼释放_召唤施法",
            TopEvokerSkillTiming.minOf("arm_left", "rotation", 0)),

    /** 06_斗士冲撞_前摇 — the crouch that holds until the charge fires. */
    CHARGE_WINDUP("06_斗士冲撞_前摇", null),

    /** 07_斗士冲撞_冲撞期间 — the looping dash. */
    CHARGE("07_斗士冲撞_冲撞期间", null),

    /** 08_斗士冲撞_后摇 — the recovery once the dash stops. */
    CHARGE_RECOVER("08_斗士冲撞_后摇", null);

    private final String animation;
    private final TopEvokerSkillTiming.Selector release;

    TopEvokerSkill(String animation, TopEvokerSkillTiming.Selector release) {
        this.animation = animation;
        this.release = release;
    }

    /** The Blockbench animation name, verbatim — it is the key in {@code top_evoker.animation.json}. */
    public String animation() {
        return this.animation;
    }

    /** Whether the clip loops forever; the other two modes end (or hold) on their own. */
    public boolean loops() {
        return "loop".equals(TopEvokerSkillTiming.clip(this.animation).loop());
    }

    /** How long the clip runs, in ticks, taken from the clip file. */
    public int lengthTicks() {
        return TopEvokerSkillTiming.lengthTicks(this.animation);
    }

    /** The instant this state's effect lands, in ticks, taken from the clip file's keyframes. */
    public int releaseTicks() {
        if (this.release == null) {
            throw new IllegalStateException(this.name() + " has no release frame");
        }
        return TopEvokerSkillTiming.releaseTicks(this.animation, this.release);
    }

    /** Whether this state performs a one-shot effect at {@link #releaseTicks()}. */
    public boolean hasRelease() {
        return this.release != null;
    }
}
