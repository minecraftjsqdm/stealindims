package com.homemine.stealindims.entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 顶位唤魔者 — the boss of the mansion raid (design §3.5, plan §P3.3, R27).
 *
 * <p>R27 replaces the placeholder appearance with the user's own Blockbench model
 * (顶级唤魔者_冲撞三段_无恼鬼尖刺) and gives the encounter the behaviour that model was authored
 * for. The class hierarchy is split in two so the mod still loads without GeckoLib:
 *
 * <ul>
 *   <li>this class owns the <em>authoritative</em> state machine and never mentions a GeckoLib
 *       type. It is what runs when the optional dependency is missing, rendered by vanilla's
 *       {@code EvokerRenderer};</li>
 *   <li>{@link TopEvokerAnimatable} subclasses it and adds the animatable plumbing when GeckoLib
 *       <em>is</em> installed.</li>
 * </ul>
 *
 * <p>Nothing here hard-codes a moment of the fight. Every phase length and every release frame is
 * read out of {@code top_evoker.animation.json} by {@link TopEvokerSkillTiming}, so the fight is
 * re-timed automatically whenever the model is re-exported (R27 §3.2).
 *
 * <p>The vanilla evoker's AI is replaced wholesale: {@link #registerGoals()} installs only the
 * float and target goals, and {@link #customServerAiStep()} drives movement, casting and the
 * charge itself. That is deliberate — the inherited spellcaster goals would fire their own fang
 * circles and vex summons on top of the nine scripted clips.
 *
 * <p>R29 merged the map-expansion build's charge into that state machine. Its {@code ChargeGoal} is
 * <em>not</em> copied in — the trigger criteria and pacing it defined now live in
 * {@link TopEvokerCharge} and are consulted from {@link #tickIdle()}, and the charge itself is the
 * same {@link TopEvokerSkill#CHARGE_WINDUP} → {@link TopEvokerSkill#CHARGE} →
 * {@link TopEvokerSkill#CHARGE_RECOVER} run that drives the three charge clips. One charge, not two.
 */
public class OverworldWatcher extends Evoker {

    /** Tag every boss carries, so a sweep can tell a leftover encounter from a wanderer. */
    public static final String BOSS_TAG = "stealindims_boss";

    /** The synced state: an ordinal of {@link TopEvokerSkill}. */
    private static final EntityDataAccessor<Integer> DATA_SKILL =
            SynchedEntityData.defineId(OverworldWatcher.class, EntityDataSerializers.INT);

    /** Closer than this (squared) the boss stands its ground and casts; farther, it walks in. */
    private static final double CHASE_DISTANCE_SQR = 100.0D;

    /** Ticks spent idling between casts, by health phase. */
    private static final int COOLDOWN_OPEN = 70;
    private static final int COOLDOWN_BLOODIED = 50;
    private static final int COOLDOWN_DESPERATE = 30;

    /** 02_弩箭大师: five volleys of three bolts, spread like the model's ±8.32-unit bolt bones. */
    private static final int BOLTS_PER_VOLLEY = 3;
    private static final double BOLT_SPREAD = 0.52D;
    private static final double BOLT_DAMAGE = 7.0D;
    private static final float BOLT_SPEED = 3.0F;

    /** 03_圆形尖刺: the ring of fangs the arms sweep out. */
    private static final int RING_FANGS = 12;
    private static final double RING_RADIUS = 3.0D;

    /** 04_直线尖刺: the line that marches away from the caster. */
    private static final int LINE_FANGS = 10;
    private static final double LINE_FIRST = 1.6D;
    private static final double LINE_STEP = 1.6D;
    private static final int LINE_STAGGER = 2;

    /**
     * 06/07/08_斗士冲撞: the dash itself.
     *
     * <p>The clip holds the crouch and the run cycle but moves nothing forward — its only position
     * channel is {@code boss_root}'s vertical bob — so the locomotion is applied here. Length,
     * trigger window and landing reach are the merged expansion values in {@link TopEvokerCharge}.
     */
    private static final double CHARGE_SPEED = 0.55D;

    private final ServerBossEvent bossEvent = new ServerBossEvent(
            Component.translatable("entity.stealindims.overworld_watcher"),
            BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.NOTCHED_10);

    /** Server-side only: how long the current state has been running, in ticks. */
    private int stateTicks;
    private int skillCooldown;
    private int volleyIndex;
    private boolean released;
    private int chargeTicks;
    /** Ticks left before the charge may fire again (R29: 70 after every charge). */
    private int chargeCooldown;
    private Vec3 chargeDirection = Vec3.ZERO;
    private final Set<UUID> chargeVictims = new HashSet<>();
    private TopEvokerSkill lastCast;

    public OverworldWatcher(EntityType<? extends Evoker> entityType, Level level) {
        super(entityType, level);
        this.xpReward = 150;
        this.setPersistenceRequired();
    }

    /** Far more health than a vanilla evoker, so the three phases actually get played out. */
    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 220.0)
                .add(Attributes.MOVEMENT_SPEED, 0.32)
                .add(Attributes.FOLLOW_RANGE, 40.0)
                .add(Attributes.ARMOR, 10.0)
                .add(Attributes.ATTACK_DAMAGE, 9.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.8);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SKILL, TopEvokerSkill.INTRO.ordinal());
    }

    /**
     * Only the float and target goals survive.
     *
     * <p>Deliberately not calling {@code super.registerGoals()}: the inherited evoker goals would
     * cast vanilla fangs and summon vanilla vexes independently of the scripted clips, which is
     * exactly the interference R27 §3.2 rules out.
     *
     * <p>R29 note: the expansion installed its {@code ChargeGoal} at priority
     * {@link TopEvokerCharge#GOAL_PRIORITY}. Here that slot is unnecessary — the charge is one of the
     * nine states this class steps through, not a goal racing the others — so the merged trigger is
     * evaluated in {@link #tickIdle()} instead and no second charge can ever start.
     */
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, false));
    }

    // ------------------------------------------------------------------ synced state

    /** The state both sides agree on; the client plays whichever clip this names. */
    public TopEvokerSkill skill() {
        TopEvokerSkill[] values = TopEvokerSkill.values();
        int ordinal = this.entityData.get(DATA_SKILL);
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : TopEvokerSkill.IDLE;
    }

    private void setSkill(TopEvokerSkill next) {
        if (this.skill() == next) {
            return;
        }
        this.entityData.set(DATA_SKILL, next.ordinal());
        this.stateTicks = 0;
        this.volleyIndex = 0;
        this.released = false;
        this.chargeTicks = 0;
        this.chargeVictims.clear();
        this.getNavigation().stop();
        if (next == TopEvokerSkill.IDLE) {
            this.skillCooldown = this.cooldownForPhase();
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide) {
            this.bossEvent.setProgress(this.getHealth() / this.getMaxHealth());
        }
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        this.bossEvent.addPlayer(player);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        this.bossEvent.removePlayer(player);
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        // The expansion's ChargeGoal ticked its cooldown from canUse(), i.e. on every tick the boss
        // was not already charging. Same clock, same place in the tick.
        if (this.chargeCooldown > 0) {
            this.chargeCooldown--;
        }
        this.stateTicks++;
        switch (this.skill()) {
            case INTRO -> this.tickIntro();
            case IDLE -> this.tickIdle();
            case CROSSBOW -> this.tickCrossbow();
            case RING -> this.tickCast(TopEvokerSkill.RING, this::castRing);
            case LINE -> this.tickCast(TopEvokerSkill.LINE, this::castLine);
            case VEX -> this.tickCast(TopEvokerSkill.VEX, this::castVexes);
            case CHARGE_WINDUP -> this.tickChargeWindup();
            case CHARGE -> this.tickCharge();
            case CHARGE_RECOVER -> this.tickChargeRecover();
        }
    }

    /** The spawn performance: rooted, invulnerable, and looking the raider over. */
    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return this.skill() == TopEvokerSkill.INTRO || super.isInvulnerableTo(source);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    /** Whether this watcher is still standing somewhere in {@code level}. */
    public boolean isAliveIn(ServerLevel level) {
        return this.isAlive() && this.level() == level;
    }

    // ------------------------------------------------------------------ state machine

    private void tickIntro() {
        this.getNavigation().stop();
        this.faceTarget(20.0F);
        if (this.stateTicks >= TopEvokerSkill.INTRO.lengthTicks()) {
            this.setSkill(TopEvokerSkill.IDLE);
        }
    }

    private void tickIdle() {
        LivingEntity target = this.getTarget();
        if (target == null || !target.isAlive()) {
            this.getNavigation().stop();
            return;
        }
        this.faceTarget(30.0F);
        if (this.distanceToSqr(target) > CHASE_DISTANCE_SQR) {
            this.getNavigation().moveTo(target, 1.0D);
        } else {
            this.getNavigation().stop();
        }
        if (--this.skillCooldown <= 0) {
            // The merged charge trigger has priority over the ranged rotation: if the raider is
            // standing 3–14 blocks away on solid ground and the cooldown has run out, the boss
            // charges instead of casting (R29 §2.1).
            if (this.chargeReady(target)) {
                this.setSkill(TopEvokerSkill.CHARGE_WINDUP);
            } else {
                this.setSkill(this.pickSkill());
            }
        }
    }

    /** The merged {@code ChargeGoal.canUse()} predicate, evaluated against this boss's live state. */
    private boolean chargeReady(LivingEntity target) {
        return TopEvokerCharge.canTrigger(this.distanceToSqr(target), target.isAlive(),
                this.onGround(), this.chargeCooldown);
    }

    private void tickCrossbow() {
        this.getNavigation().stop();
        this.faceTarget(30.0F);
        int[] volleys = TopEvokerSkillTiming.volleyTicks(TopEvokerSkill.CROSSBOW.animation());
        while (this.volleyIndex < volleys.length && this.stateTicks >= volleys[this.volleyIndex]) {
            this.fireVolley();
            this.volleyIndex++;
        }
        this.finishWhenDone(TopEvokerSkill.CROSSBOW);
    }

    /** A cast whose whole effect lands on the clip's release frame. */
    private void tickCast(TopEvokerSkill cast, Consumer<ServerLevel> effect) {
        this.getNavigation().stop();
        this.faceTarget(30.0F);
        if (!this.released && this.stateTicks >= cast.releaseTicks()
                && this.level() instanceof ServerLevel level) {
            effect.accept(level);
            this.released = true;
        }
        this.finishWhenDone(cast);
    }

    private void finishWhenDone(TopEvokerSkill cast) {
        if (this.stateTicks >= cast.lengthTicks()) {
            this.setSkill(TopEvokerSkill.IDLE);
        }
    }

    private void tickChargeWindup() {
        this.getNavigation().stop();
        this.setDeltaMovement(0.0D, this.getDeltaMovement().y, 0.0D);
        this.faceTarget(30.0F);
        if (this.stateTicks >= TopEvokerSkill.CHARGE_WINDUP.lengthTicks()) {
            this.chargeDirection = this.flatAim();
            this.chargeVictims.clear();
            this.chargeTicks = 0;
            // The merged ChargeGoal.start(): hand the navigator the target at speed 2.0.
            LivingEntity target = this.getTarget();
            if (target != null) {
                this.getNavigation().moveTo(target, TopEvokerCharge.NAVIGATION_SPEED);
            }
            this.setSkill(TopEvokerSkill.CHARGE);
        }
    }

    /**
     * 07_斗士冲撞_冲撞期间 — the merged charge, one tick of it.
     *
     * <p>Every tick does what the expansion's {@code ChargeGoal.tick()} did — look at the target at
     * 30, re-path to it at speed 2.0, and land the blow once it is inside
     * {@link TopEvokerCharge#HIT_DISTANCE_SQR} — on top of the dash impulse the R27 clip needs for
     * its forward drive. The blow ends the charge, which puts the boss into the recovery clip and
     * arms the {@link TopEvokerCharge#CHARGE_COOLDOWN} window.
     */
    private void tickCharge() {
        LivingEntity target = this.getTarget();
        if (target == null || !target.isAlive()) {
            this.endCharge();
            return;
        }
        this.getLookControl().setLookAt(target, TopEvokerCharge.LOOK_SPEED, TopEvokerCharge.LOOK_SPEED);
        this.getNavigation().moveTo(target, TopEvokerCharge.NAVIGATION_SPEED);

        this.chargeTicks++;
        Vec3 motion = this.chargeDirection.scale(CHARGE_SPEED);
        this.setDeltaMovement(motion.x, this.getDeltaMovement().y, motion.z);
        this.hasImpulse = true;
        float yaw = (float) (Mth.atan2(this.chargeDirection.z, this.chargeDirection.x) * 180.0D / Math.PI) - 90.0F;
        this.setYRot(yaw);
        this.setYHeadRot(yaw);
        this.yBodyRot = yaw;
        boolean connected = this.hurtTouchedDuringCharge(target);
        if (connected || this.chargeTicks >= TopEvokerCharge.CHARGE_TICKS || this.horizontalCollision) {
            this.endCharge();
        }
    }

    private void tickChargeRecover() {
        this.getNavigation().stop();
        this.setDeltaMovement(0.0D, this.getDeltaMovement().y, 0.0D);
        this.finishWhenDone(TopEvokerSkill.CHARGE_RECOVER);
    }

    /**
     * Ends the charge and starts the cooldown, exactly where the expansion's {@code stop()} did.
     *
     * <p>The recovery clip plays first, but the cooldown clock starts now, so a charge is followed
     * by {@link TopEvokerCharge#CHARGE_COOLDOWN} ticks without another one.
     */
    private void endCharge() {
        this.chargeCooldown = TopEvokerCharge.CHARGE_COOLDOWN;
        this.setSkill(TopEvokerSkill.CHARGE_RECOVER);
    }

    /**
     * Which cast to run next: the four ranged openings, rotating so the same one never repeats.
     *
     * <p>R29: the charge is deliberately <em>not</em> in this pool any more. It is triggered by its
     * own distance window in {@link #tickIdle()} (and gated by the 70-tick cooldown), so there is
     * exactly one way a charge can start.
     */
    private TopEvokerSkill pickSkill() {
        List<TopEvokerSkill> pool = new ArrayList<>(List.of(
                TopEvokerSkill.CROSSBOW, TopEvokerSkill.RING, TopEvokerSkill.LINE, TopEvokerSkill.VEX));
        TopEvokerSkill choice = pool.get(this.random.nextInt(pool.size()));
        if (choice == this.lastCast && pool.size() > 1) {
            choice = pool.get((pool.indexOf(choice) + 1) % pool.size());
        }
        this.lastCast = choice;
        return choice;
    }

    private int cooldownForPhase() {
        float ratio = this.getHealth() / this.getMaxHealth();
        if (ratio <= 0.25F) {
            return COOLDOWN_DESPERATE;
        }
        if (ratio <= 0.5F) {
            return COOLDOWN_BLOODIED;
        }
        return COOLDOWN_OPEN;
    }

    // ------------------------------------------------------------------ 02_弩箭大师

    private void fireVolley() {
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        Vec3 direction = this.aimDirection();
        Vec3 lateral = new Vec3(-direction.z, 0.0D, direction.x);
        if (lateral.lengthSqr() < 1.0E-6D) {
            lateral = new Vec3(1.0D, 0.0D, 0.0D);
        }
        lateral = lateral.normalize();
        Vec3 muzzle = this.getEyePosition().add(direction.scale(1.2D));
        for (int i = 0; i < BOLTS_PER_VOLLEY; i++) {
            double offset = (i - 1) * BOLT_SPREAD;
            Vec3 spawn = muzzle.add(lateral.scale(offset));
            Arrow arrow = new Arrow(level, this, new ItemStack(Items.ARROW), null);
            arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
            arrow.setPos(spawn.x, spawn.y, spawn.z);
            arrow.setBaseDamage(BOLT_DAMAGE);
            arrow.shoot(direction.x, direction.y, direction.z, BOLT_SPEED, 0.4F);
            level.addFreshEntity(arrow);
        }
        level.playSound(null, this.blockPosition(), SoundEvents.CROSSBOW_SHOOT,
                SoundSource.HOSTILE, 1.4F, 0.9F);
    }

    // ------------------------------------------------------------------ 03 / 04 尖刺

    private void castRing(ServerLevel level) {
        for (int i = 0; i < RING_FANGS; i++) {
            double angle = i * (Math.PI * 2.0D / RING_FANGS);
            double x = this.getX() + Math.cos(angle) * RING_RADIUS;
            double z = this.getZ() + Math.sin(angle) * RING_RADIUS;
            this.spawnFang(level, x, z, (float) angle, i % 2);
        }
        this.playCast(level, SoundEvents.EVOKER_PREPARE_ATTACK, 0.8F);
    }

    private void castLine(ServerLevel level) {
        Vec3 flat = this.flatAim();
        float angle = (float) Mth.atan2(flat.z, flat.x);
        for (int i = 0; i < LINE_FANGS; i++) {
            double distance = LINE_FIRST + i * LINE_STEP;
            this.spawnFang(level, this.getX() + flat.x * distance, this.getZ() + flat.z * distance,
                    angle, i * LINE_STAGGER);
        }
        this.playCast(level, SoundEvents.EVOKER_PREPARE_ATTACK, 0.6F);
    }

    /**
     * Drops one fang on the first solid floor under {@code (x, z)}.
     *
     * <p>The descent is vanilla's own {@code Evoker.EvokerAttackSpellGoal#createSpellEntity}
     * routine, so the spikes sit on the chamber floor exactly like the vanilla evoker's do instead
     * of hovering at the boss's feet.
     */
    private void spawnFang(ServerLevel level, double x, double z, float yRot, int extraWarmup) {
        double minY = this.getY();
        double maxY = this.getY() + 1.0D;
        BlockPos pos = BlockPos.containing(x, maxY, z);
        double offset = 0.0D;
        while (true) {
            BlockPos below = pos.below();
            BlockState state = level.getBlockState(below);
            if (state.isFaceSturdy(level, below, Direction.UP)) {
                if (!level.isEmptyBlock(pos)) {
                    VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
                    if (!shape.isEmpty()) {
                        offset = shape.max(Direction.Axis.Y);
                    }
                }
                level.addFreshEntity(new EvokerFangs(level, x, pos.getY() + offset, z, yRot,
                        22 + extraWarmup, this));
                level.gameEvent(GameEvent.ENTITY_PLACE, new Vec3(x, pos.getY() + offset, z),
                        GameEvent.Context.of(this));
                return;
            }
            pos = pos.below();
            if (pos.getY() < Mth.floor(minY) - 1) {
                return;
            }
        }
    }

    private void playCast(ServerLevel level, net.minecraft.sounds.SoundEvent sound, float pitch) {
        level.playSound(null, this.blockPosition(), sound, SoundSource.HOSTILE, 1.6F, pitch);
    }

    // ------------------------------------------------------------------ 05_恼鬼释放

    private void castVexes(ServerLevel level) {
        int count = 2 + this.random.nextInt(3);
        for (int i = 0; i < count; i++) {
            BlockPos pos = this.blockPosition().offset(
                    -3 + this.random.nextInt(7), 1, -3 + this.random.nextInt(7));
            Vex vex = EntityType.VEX.create(level);
            if (vex == null) {
                continue;
            }
            vex.moveTo(pos, 0.0F, 0.0F);
            vex.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.MOB_SUMMONED, null);
            vex.setOwner(this);
            vex.setBoundOrigin(pos);
            vex.setLimitedLife(20 * (20 + this.random.nextInt(20)));
            level.addFreshEntity(vex);
            level.gameEvent(GameEvent.ENTITY_PLACE, pos, GameEvent.Context.of(this));
        }
        this.playCast(level, SoundEvents.EVOKER_PREPARE_SUMMON, 1.0F);
    }

    // ------------------------------------------------------------------ 06 / 07 / 08_冲撞

    /**
     * The landing blow of the charge.
     *
     * <p>R29 replaced the old bounding-box sweep with the expansion's criterion — anyone the boss has
     * closed to within {@link TopEvokerCharge#HIT_DISTANCE_SQR} (≈2.45 blocks) takes the hit — while
     * keeping R27's handling of the victims: once per charge, with the shoulder-charge shove. The
     * ravager attack is the expansion's landing sound.
     *
     * @param target the boss's current target
     * @return whether the target itself was hit, which ends the charge just as the expansion's
     *         {@code ChargeGoal} did when {@code distanceToSqr(target) < 6.0}
     */
    private boolean hurtTouchedDuringCharge(LivingEntity target) {
        if (!(this.level() instanceof ServerLevel level)) {
            return false;
        }
        boolean hitTarget = false;
        AABB reach = this.getBoundingBox().inflate(TopEvokerCharge.HIT_DISTANCE_SQR);
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, reach)) {
            if (victim == this || !victim.isAlive() || victim.isSpectator()) {
                continue;
            }
            if (victim instanceof Vex vex && vex.getOwner() == this) {
                continue;
            }
            if (!TopEvokerCharge.hits(this.distanceToSqr(victim))) {
                continue;
            }
            if (victim == target) {
                hitTarget = true;
            }
            if (!this.chargeVictims.add(victim.getUUID())) {
                continue;
            }
            this.doHurtTarget(victim);
            victim.push(this.chargeDirection.x * 1.6D, 0.42D, this.chargeDirection.z * 1.6D);
            victim.hurtMarked = true;
            level.playSound(null, victim.blockPosition(), SoundEvents.RAVAGER_ATTACK,
                    SoundSource.HOSTILE, TopEvokerCharge.HIT_SOUND_VOLUME, TopEvokerCharge.HIT_SOUND_PITCH);
        }
        return hitTarget;
    }

    // ------------------------------------------------------------------ aiming helpers

    private void faceTarget(float speed) {
        LivingEntity target = this.getTarget();
        if (target != null) {
            this.getLookControl().setLookAt(target, speed, speed);
        }
    }

    /** Unit vector from the boss's eyes to its target, or its gaze when it has none. */
    private Vec3 aimDirection() {
        LivingEntity target = this.getTarget();
        Vec3 aim = target != null
                ? target.getEyePosition().subtract(this.getEyePosition())
                : this.getLookAngle();
        return aim.lengthSqr() < 1.0E-6D ? this.getLookAngle() : aim.normalize();
    }

    /** {@link #aimDirection()} flattened onto the floor plane; used to place fangs and the dash. */
    private Vec3 flatAim() {
        Vec3 aim = this.aimDirection();
        Vec3 flat = new Vec3(aim.x, 0.0D, aim.z);
        if (flat.lengthSqr() < 1.0E-6D) {
            float yaw = this.getYRot() * ((float) Math.PI / 180.0F);
            flat = new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
        }
        return flat.normalize();
    }

    // ------------------------------------------------------------------ persistence

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("StealInDimsSkill", this.skill().name());
        tag.putInt("StealInDimsStateTicks", this.stateTicks);
        tag.putInt("StealInDimsSkillCooldown", this.skillCooldown);
        tag.putInt("StealInDimsChargeCooldown", this.chargeCooldown);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("StealInDimsSkill")) {
            try {
                this.entityData.set(DATA_SKILL,
                        TopEvokerSkill.valueOf(tag.getString("StealInDimsSkill")).ordinal());
            } catch (IllegalArgumentException e) {
                this.entityData.set(DATA_SKILL, TopEvokerSkill.INTRO.ordinal());
            }
        }
        this.stateTicks = tag.getInt("StealInDimsStateTicks");
        this.skillCooldown = tag.getInt("StealInDimsSkillCooldown");
        this.chargeCooldown = tag.getInt("StealInDimsChargeCooldown");
    }

    /** Convenience for the spawn hook: the mob this boss is, or {@code null} if it is not one. */
    public static OverworldWatcher of(Mob mob) {
        return mob instanceof OverworldWatcher watcher ? watcher : null;
    }
}
