package com.homemine.stealindims.entity;

/**
 * The trigger and pacing of 06/07/08_斗士冲撞 — the top evoker's charge (R29).
 *
 * <p>R29 merged the map-expansion build's {@code OverworldWatcher$ChargeGoal} into our encounter.
 * Its <em>numbers</em> live here and nowhere else: the goal it used to be is gone, and
 * {@link OverworldWatcher} consults these criteria from inside its own animation state machine, so
 * the mod still has exactly one charge — the one that drives the three charge clips.
 *
 * <p>Kept free of every Minecraft type on purpose: the criteria are the contract, and the unit test
 * next to this class pins them without booting a server.
 *
 * <table>
 *   <caption>Merged parameters</caption>
 *   <tr><td>goal priority</td><td>{@value #GOAL_PRIORITY} (float goal sits at 0)</td></tr>
 *   <tr><td>charge length</td><td>{@value #CHARGE_TICKS} ticks</td></tr>
 *   <tr><td>cooldown after a charge</td><td>{@value #CHARGE_COOLDOWN} ticks (≈3.5 s)</td></tr>
 *   <tr><td>trigger window</td><td>distance² strictly inside ({@value #MIN_DISTANCE_SQR},
 *       {@value #MAX_DISTANCE_SQR}) ⇒ 3–14 blocks, on the ground, living target, cooldown elapsed</td></tr>
 *   <tr><td>landing hit</td><td>distance² &lt; {@value #HIT_DISTANCE_SQR} ⇒ hurt + ravager attack</td></tr>
 *   <tr><td>navigation</td><td>{@code moveTo(target, }{@value #NAVIGATION_SPEED}{@code )}</td></tr>
 *   <tr><td>look control</td><td>{@code setLookAt(target, }{@value #LOOK_SPEED}, {@value #LOOK_SPEED}{@code )}</td></tr>
 * </table>
 */
public final class TopEvokerCharge {

    /** The goal slot the expansion installed its {@code ChargeGoal} in; {@code FloatGoal} holds 0. */
    public static final int GOAL_PRIORITY = 1;

    /** How long one charge lasts, in ticks ({@code CHARGE_TICKS}). */
    public static final int CHARGE_TICKS = 40;

    /** How long the boss may not charge again after one ends ({@code CHARGE_COOLDOWN}). */
    public static final int CHARGE_COOLDOWN = 70;

    /** 3² — the window is exclusive, so a target at exactly 3 blocks does not start a charge. */
    public static final double MIN_DISTANCE_SQR = 9.0D;

    /** 14² — exclusive as well, matching {@code distance > 9.0 && distance < 196.0}. */
    public static final double MAX_DISTANCE_SQR = 196.0D;

    /** Squared reach of the landing blow: {@code distanceToSqr(target) < 6.0}. */
    public static final double HIT_DISTANCE_SQR = 6.0D;

    /** The speed modifier the charge hands the navigator ({@code navigation.moveTo(target, 2.0)}). */
    public static final double NAVIGATION_SPEED = 2.0D;

    /** The turn rate the charge passes to the look control ({@code setLookAt(target, 30, 30)}). */
    public static final float LOOK_SPEED = 30.0F;

    /** Volume of the ravager roar played on a landing hit. */
    public static final float HIT_SOUND_VOLUME = 1.0F;

    /** Pitch of the ravager roar played on a landing hit. */
    public static final float HIT_SOUND_PITCH = 1.0F;

    private TopEvokerCharge() {
    }

    /**
     * Whether a target at {@code distanceSqr} is inside the 3–14 block charge window.
     *
     * <p>Both ends are exclusive: {@code distance > 9.0 && distance < 196.0}, exactly as the
     * expansion's {@code ChargeGoal.canUse()} evaluated it.
     */
    public static boolean withinWindow(double distanceSqr) {
        return distanceSqr > MIN_DISTANCE_SQR && distanceSqr < MAX_DISTANCE_SQR;
    }

    /**
     * The whole trigger, as one predicate.
     *
     * @param distanceSqr     squared distance from the boss to its target
     * @param hasLivingTarget whether a target exists and is alive
     * @param onGround        whether the boss is standing on the ground
     * @param cooldownTicks   ticks left of {@link #CHARGE_COOLDOWN}; {@code <= 0} means ready
     */
    public static boolean canTrigger(double distanceSqr, boolean hasLivingTarget, boolean onGround, int cooldownTicks) {
        return hasLivingTarget && onGround && cooldownTicks <= 0 && withinWindow(distanceSqr);
    }

    /** Whether a target at {@code distanceSqr} is close enough for the charge to connect. */
    public static boolean hits(double distanceSqr) {
        return distanceSqr < HIT_DISTANCE_SQR;
    }
}
