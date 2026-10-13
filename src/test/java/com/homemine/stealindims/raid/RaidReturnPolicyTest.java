package com.homemine.stealindims.raid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/**
 * R26's headless gate: the tag the return point travels in, and the rule that decides whether a
 * player waking up inside a raid dimension has to be sent home.
 *
 * <p>Both halves are pure — {@link CompoundTag} and {@link GlobalPos} need no game bootstrap — so the
 * whole "left the world and came back" policy can be pinned down without a server, a world or a
 * player. The in-game halves (the login hook, the teleport) are covered by the acceptance run.
 */
class RaidReturnPolicyTest {

    private static final ResourceKey<Level> OVERWORLD = Level.OVERWORLD;
    private static final ResourceKey<Level> RAID = RaidDimensions.OVERWORLD_RAID;

    private static GlobalPos pos(ResourceKey<Level> dimension, int x, int y, int z) {
        return GlobalPos.of(dimension, new BlockPos(x, y, z));
    }

    // ---------------------------------------------------------------- persistent data

    @Test
    void returnPointSurvivesAWriteAndReadRoundTrip() {
        CompoundTag data = new CompoundTag();
        GlobalPos written = pos(OVERWORLD, 282, 63, -30);

        RaidReturnPolicy.write(data, written);

        assertTrue(RaidReturnPolicy.isStored(data));
        assertEquals(written, RaidReturnPolicy.read(data));
    }

    @Test
    void readReturnsNullWhenNothingWasEverWritten() {
        assertNull(RaidReturnPolicy.read(new CompoundTag()));
        assertFalse(RaidReturnPolicy.isStored(new CompoundTag()));
    }

    @Test
    void clearRemovesTheReturnPoint() {
        CompoundTag data = new CompoundTag();
        RaidReturnPolicy.write(data, pos(OVERWORLD, 1, 64, 2));

        RaidReturnPolicy.clear(data);

        assertFalse(RaidReturnPolicy.isStored(data));
        assertNull(RaidReturnPolicy.read(data));
    }

    @Test
    void aCorruptReturnPointIsTreatedAsMissingRatherThanThrowing() {
        CompoundTag data = new CompoundTag();
        // A tag of the wrong type, an unparseable dimension and a half written position: each one has
        // to fall back to the vanilla spawn instead of taking the login down with it.
        data.putString(RaidReturnPolicy.TAG_RETURN_POINT, "not a compound");
        assertNull(RaidReturnPolicy.read(data));

        CompoundTag wrongDimension = new CompoundTag();
        wrongDimension.putString("dimension", "not a resource location");
        wrongDimension.putInt("x", 1);
        wrongDimension.putInt("y", 2);
        wrongDimension.putInt("z", 3);
        data.put(RaidReturnPolicy.TAG_RETURN_POINT, wrongDimension);
        assertNull(RaidReturnPolicy.read(data));

        CompoundTag missingY = new CompoundTag();
        missingY.putString("dimension", "minecraft:overworld");
        missingY.putInt("x", 1);
        missingY.putInt("z", 3);
        data.put(RaidReturnPolicy.TAG_RETURN_POINT, missingY);
        assertNull(RaidReturnPolicy.read(data));
    }

    // ---------------------------------------------------------------- the decision

    @Test
    void aLiveSessionIsNeverDisturbed() {
        // 有会话: the ordinary flow of a raider standing in their own run.
        assertEquals(RaidReturnPolicy.Decision.LEAVE_ALONE,
                RaidReturnPolicy.decide(true, true, pos(OVERWORLD, 10, 64, 10)));
    }

    @Test
    void noSessionInsideARaidDimensionMeansAForfeitedReturn() {
        // 无会话: the reloaded run, with the point the persistent data remembers.
        assertEquals(RaidReturnPolicy.Decision.RETURN_TO_RECORDED_POINT,
                RaidReturnPolicy.decide(true, false, pos(OVERWORLD, 10, 64, 10)));
    }

    @Test
    void outsideARaidDimensionNothingHappens() {
        // 非摸金维度: no session and no point is just an ordinary player somewhere else.
        assertEquals(RaidReturnPolicy.Decision.LEAVE_ALONE, RaidReturnPolicy.decide(false, false, null));
        assertEquals(RaidReturnPolicy.Decision.LEAVE_ALONE,
                RaidReturnPolicy.decide(false, false, pos(OVERWORLD, 10, 64, 10)));
    }

    @Test
    void noRecordedPointFallsBackToTheRespawnPoint() {
        // 无持久化点: an old save, or a run entered before R26 ever wrote anything.
        assertEquals(RaidReturnPolicy.Decision.RETURN_TO_FALLBACK,
                RaidReturnPolicy.decide(true, false, null));
    }

    // ---------------------------------------------------------------- the landing spot

    @Test
    void everyDecisionResolvesToTheRightTarget() {
        GlobalPos recorded = pos(OVERWORLD, 282, 63, -30);
        GlobalPos respawn = pos(OVERWORLD, 5, 70, 5);
        GlobalPos shared = pos(OVERWORLD, 0, 64, 0);

        assertNull(RaidReturnPolicy.target(RaidReturnPolicy.Decision.LEAVE_ALONE,
                recorded, respawn, shared));
        assertEquals(recorded, RaidReturnPolicy.target(
                RaidReturnPolicy.Decision.RETURN_TO_RECORDED_POINT, recorded, respawn, shared));
        assertEquals(respawn, RaidReturnPolicy.target(
                RaidReturnPolicy.Decision.RETURN_TO_RECORDED_POINT, null, respawn, shared));
        assertEquals(respawn, RaidReturnPolicy.target(
                RaidReturnPolicy.Decision.RETURN_TO_FALLBACK, null, respawn, shared));
        assertEquals(shared, RaidReturnPolicy.target(
                RaidReturnPolicy.Decision.RETURN_TO_FALLBACK, null, null, shared));
    }

    @Test
    void aTargetPointingBackIntoARaidDimensionIsSkipped() {
        // A corrupt respawn point must not undo the whole exercise: the chain moves on instead.
        GlobalPos recorded = pos(OVERWORLD, 282, 63, -30);
        GlobalPos raidSpawn = pos(RAID, 0, 64, 0);
        GlobalPos shared = pos(OVERWORLD, 0, 64, 0);

        assertFalse(RaidReturnPolicy.usable(raidSpawn));
        assertEquals(recorded, RaidReturnPolicy.target(
                RaidReturnPolicy.Decision.RETURN_TO_RECORDED_POINT, recorded, raidSpawn, shared));
        assertEquals(shared, RaidReturnPolicy.target(
                RaidReturnPolicy.Decision.RETURN_TO_RECORDED_POINT, raidSpawn, raidSpawn, shared));
        assertEquals(shared, RaidReturnPolicy.target(
                RaidReturnPolicy.Decision.RETURN_TO_FALLBACK, null, raidSpawn, shared));
    }

    @Test
    void theRaidDimensionsAreRecognised() {
        assertTrue(RaidDimensions.isRaidDimension(RAID));
        assertTrue(RaidDimensions.isRaidDimension(RaidDimensions.NETHER_RAID));
        assertFalse(RaidDimensions.isRaidDimension(OVERWORLD));
        assertFalse(RaidDimensions.isRaidDimension(
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:the_end"))));
    }
}
