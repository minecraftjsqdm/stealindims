package com.homemine.stealindims.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gate on R29's charge merge.
 *
 * <p>{@link TopEvokerCharge} is the only place the charge is described after R29: the map-expansion
 * build's {@code ChargeGoal} was not copied over, its numbers were, and
 * {@link OverworldWatcher} consults them from inside its animation state machine. These cases pin
 * both halves of that claim — the numbers, and the fact that the watcher really does route through
 * them instead of growing a second charge next to the first.
 */
class TopEvokerChargeTest {

    /** Squared distance at which the window opens: 3 blocks. */
    private static final double THREE_BLOCKS = 3.0D * 3.0D;

    /** Squared distance at which the window closes: 14 blocks. */
    private static final double FOURTEEN_BLOCKS = 14.0D * 14.0D;

    // ------------------------------------------------------------------ the numbers

    @Test
    @DisplayName("the merged pacing constants are the expansion's")
    void parametersAreTheExpansionValues() {
        assertEquals(1, TopEvokerCharge.GOAL_PRIORITY, "the expansion installed the goal at priority 1");
        assertEquals(40, TopEvokerCharge.CHARGE_TICKS);
        assertEquals(70, TopEvokerCharge.CHARGE_COOLDOWN);
        assertEquals(9.0D, TopEvokerCharge.MIN_DISTANCE_SQR, 1.0E-9D, "3 blocks, squared");
        assertEquals(196.0D, TopEvokerCharge.MAX_DISTANCE_SQR, 1.0E-9D, "14 blocks, squared");
        assertEquals(6.0D, TopEvokerCharge.HIT_DISTANCE_SQR, 1.0E-9D);
        assertEquals(2.0D, TopEvokerCharge.NAVIGATION_SPEED, 1.0E-9D, "navigation.moveTo(target, 2.0)");
        assertEquals(30.0F, TopEvokerCharge.LOOK_SPEED, 1.0E-6F, "lookControl.setLookAt(target, 30, 30)");
    }

    // ------------------------------------------------------------------ the window

    @Test
    @DisplayName("the charge window is 3–14 blocks, both ends exclusive")
    void windowIsThreeToFourteenBlocks() {
        assertFalse(TopEvokerCharge.withinWindow(THREE_BLOCKS),
                "exactly 3 blocks is outside: the expansion tested distance > 9.0");
        assertFalse(TopEvokerCharge.withinWindow(FOURTEEN_BLOCKS),
                "exactly 14 blocks is outside: the expansion tested distance < 196.0");

        assertFalse(TopEvokerCharge.withinWindow(0.0D), "a target in the boss's face is not a charge");
        assertFalse(TopEvokerCharge.withinWindow(THREE_BLOCKS - 0.01D));
        assertTrue(TopEvokerCharge.withinWindow(THREE_BLOCKS + 0.01D), "just over 3 blocks");
        assertTrue(TopEvokerCharge.withinWindow(4.0D * 4.0D), "4 blocks is inside");
        assertTrue(TopEvokerCharge.withinWindow(13.0D * 13.0D), "13 blocks is inside");
        assertTrue(TopEvokerCharge.withinWindow(FOURTEEN_BLOCKS - 0.01D), "just under 14 blocks");
        assertFalse(TopEvokerCharge.withinWindow(FOURTEEN_BLOCKS + 0.01D), "beyond 14 blocks the boss walks");
        assertFalse(TopEvokerCharge.withinWindow(Double.MAX_VALUE));
    }

    @Test
    @DisplayName("every leg of the trigger is required")
    void triggerNeedsTargetGroundCooldownAndWindow() {
        double inWindow = 10.0D * 10.0D;
        assertTrue(TopEvokerCharge.canTrigger(inWindow, true, true, 0), "the plain case must fire");
        assertTrue(TopEvokerCharge.canTrigger(inWindow, true, true, -5), "a negative clock is still ready");

        assertFalse(TopEvokerCharge.canTrigger(inWindow, false, true, 0), "no living target, no charge");
        assertFalse(TopEvokerCharge.canTrigger(inWindow, true, false, 0), "the expansion required onGround()");
        assertFalse(TopEvokerCharge.canTrigger(inWindow, true, true, 1), "1 tick of cooldown left");
        assertFalse(TopEvokerCharge.canTrigger(inWindow, true, true, 70), "the full cooldown blocks it");

        assertFalse(TopEvokerCharge.canTrigger(THREE_BLOCKS, true, true, 0), "too close");
        assertFalse(TopEvokerCharge.canTrigger(FOURTEEN_BLOCKS, true, true, 0), "too far");
    }

    @Test
    @DisplayName("the landing blow connects below 6.0 squared")
    void hitThresholdIsSixSquared() {
        assertTrue(TopEvokerCharge.hits(0.0D));
        assertTrue(TopEvokerCharge.hits(5.99D));
        assertFalse(TopEvokerCharge.hits(6.0D), "exactly 6.0 is not a hit: the expansion tested < 6.0");
        assertFalse(TopEvokerCharge.hits(6.01D));
        assertFalse(TopEvokerCharge.hits(TopEvokerCharge.MIN_DISTANCE_SQR),
                "the blow must land well inside the range that starts a charge");
    }

    // ------------------------------------------------------------------ the merge itself

    @Test
    @DisplayName("the three charge states are the three charge clips, in order")
    void chargeStatesDriveTheThreeClips() {
        assertEquals("06_斗士冲撞_前摇", TopEvokerSkill.CHARGE_WINDUP.animation());
        assertEquals("07_斗士冲撞_冲撞期间", TopEvokerSkill.CHARGE.animation());
        assertEquals("08_斗士冲撞_后摇", TopEvokerSkill.CHARGE_RECOVER.animation());
    }

    @Test
    @DisplayName("the watcher consults the merged parameters and keeps exactly one charge")
    void watcherHasASingleChargePath() throws IOException {
        String source = readProjectFile(
                "src/main/java/com/homemine/stealindims/entity/OverworldWatcher.java");

        // It really uses the merged criteria and pacing, not a private copy of them.
        assertTrue(source.contains("TopEvokerCharge.canTrigger"),
                "the trigger must come from the merged predicate");
        assertTrue(source.contains("TopEvokerCharge.NAVIGATION_SPEED"), "moveTo(target, 2.0) went missing");
        assertTrue(source.contains("TopEvokerCharge.LOOK_SPEED"), "setLookAt(target, 30, 30) went missing");
        assertTrue(source.contains("TopEvokerCharge.HIT_DISTANCE_SQR"), "the 6.0 landing reach went missing");
        assertTrue(source.contains("TopEvokerCharge.CHARGE_TICKS"), "the 40-tick charge length went missing");
        assertTrue(source.contains("TopEvokerCharge.CHARGE_COOLDOWN"), "the 70-tick cooldown went missing");
        assertTrue(source.contains("SoundEvents.RAVAGER_ATTACK"), "the landing sound went missing");

        // ... and it did not grow the expansion's goal next to the state machine. (The javadoc still
        // names ChargeGoal, because that is where the numbers came from; what must not come back is
        // the goal itself — a Goal subclass racing the nine states.)
        assertFalse(source.contains("extends Goal"),
                "a second charge goal would run in parallel with the CHARGE_WINDUP/CHARGE/CHARGE_RECOVER run");
        assertFalse(source.contains("class ChargeGoal"), "the expansion's goal class must not be copied in");
        assertFalse(source.contains("new ChargeGoal"), "the expansion's goal must not be instantiated");
        assertEquals(1, count(source, "setSkill(TopEvokerSkill.CHARGE_WINDUP)"),
                "there must be exactly one way a charge starts");
        assertEquals(1, count(source, "private void endCharge()"),
                "there must be exactly one way a charge ends");
        assertFalse(source.contains("CHARGE_MAX_TICKS"), "the old private charge length should be gone");
        assertFalse(source.contains("CHARGE_REACH"), "the old bounding-box reach should be gone");
    }

    // ------------------------------------------------------------------ helpers

    private static int count(String text, String needle) {
        int total = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            total++;
            index = text.indexOf(needle, index + needle.length());
        }
        return total;
    }

    private static String readProjectFile(String relative) throws IOException {
        Path file = projectDir().resolve(relative);
        assertTrue(Files.isReadable(file), "cannot read " + file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static Path projectDir() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("build.gradle"))) {
            dir = dir.getParent();
        }
        assertNotNull(dir, "cannot locate the project directory");
        return dir;
    }
}
