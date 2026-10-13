package com.homemine.stealindims.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The resident raid HUD (design §21): a clock, the value already handed to the transit crate and the
 * threshold it has to reach.
 *
 * <p>It covers the whole run, so the same panel doubles as the lobby readout (design §4.2): while
 * waiting it shows how many members have readied up, during the countdown the seconds left, and from
 * the moment the run starts it becomes the clock. The teleport tax and the unlock notice are meant to
 * be event popups, not permanent furniture, so the tax counter only appears once one was actually paid.
 */
public final class RaidHudOverlay {

    private static final int BACKGROUND = 0x80000000;
    private static final int LINE_HEIGHT = 10;
    private static final int PADDING = 4;
    private static final int TOP_MARGIN = 4;
    /** The clock turns red for the last sixteen seconds, just before the dimension collapses (design §6.2). */
    private static final int URGENT_SECONDS = 16;

    private RaidHudOverlay() {
    }

    public static void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.screen != null || !RaidClientState.isActive()) {
            return;
        }

        Font font = minecraft.font;
        List<Line> lines = new ArrayList<>(3);

        switch (RaidClientState.phase()) {
            case RaidClientState.WAITING -> lines.add(new Line(Component.translatable("gui.stealindims.hud.lobby",
                    RaidClientState.readyCount(), RaidClientState.memberCount()),
                    ChatFormatting.YELLOW.getColor()));
            case RaidClientState.COUNTDOWN -> lines.add(new Line(Component.translatable("gui.stealindims.hud.countdown",
                    RaidClientState.countdownSeconds()), ChatFormatting.GREEN.getColor()));
            default -> {
                lines.add(new Line(clockLine(), clockColor()));
                lines.add(new Line(thresholdLine(), thresholdColor()));
                if (RaidClientState.teleportTaxCount() > 0) {
                    lines.add(new Line(Component.translatable("gui.stealindims.hud.tax",
                            RaidClientState.teleportTaxCount()), ChatFormatting.GOLD.getColor()));
                }
            }
        }

        if (RaidClientState.phase() != RaidClientState.STARTED) {
            lines.add(new Line(Component.translatable("gui.stealindims.hud.lobby_hint"),
                    ChatFormatting.GRAY.getColor()));
        }

        int width = 0;
        for (Line line : lines) {
            width = Math.max(width, font.width(line.text()));
        }
        width += PADDING * 2;

        int height = lines.size() * LINE_HEIGHT + PADDING * 2;
        int left = (guiGraphics.guiWidth() - width) / 2;
        int top = TOP_MARGIN;

        guiGraphics.fill(left, top, left + width, top + height, BACKGROUND);
        int textX = left + PADDING;
        int textY = top + PADDING;
        for (Line line : lines) {
            guiGraphics.drawString(font, line.text(), textX, textY, line.colour(), true);
            textY += LINE_HEIGHT;
        }
    }

    /**
     * The clock row. Past zero the count goes negative while anyone is still alive (design §T8), and
     * "remaining -00:05" reads wrong, so the overrun gets its own wording.
     */
    private static Component clockLine() {
        int seconds = RaidClientState.remainingSeconds();
        return Component.translatable(seconds < 0 ? "gui.stealindims.hud.clock_overrun" : "gui.stealindims.hud.clock",
                RaidClientState.formatClock(seconds));
    }

    /**
     * The submitted/threshold readout. With more than one raider the threshold carries the party
     * multiplier, so the number on screen can be traced back to the solo quota (design §20).
     */
    private static Component thresholdLine() {
        String submitted = RaidClientState.formatValue(RaidClientState.submittedValue());
        String required = RaidClientState.formatValue(RaidClientState.requiredValue());
        if (RaidClientState.memberCount() <= 1) {
            return Component.translatable("gui.stealindims.hud.value", submitted, required);
        }
        return Component.translatable("gui.stealindims.hud.value_party", submitted, required,
                RaidClientState.memberCount(), RaidClientState.partyFactor());
    }

    private static int clockColor() {
        ChatFormatting colour = RaidClientState.remainingSeconds() <= URGENT_SECONDS
                ? ChatFormatting.RED
                : ChatFormatting.WHITE;
        return colour.getColor();
    }

    private static int thresholdColor() {
        ChatFormatting colour = RaidClientState.submittedValue() >= RaidClientState.requiredValue()
                ? ChatFormatting.GREEN
                : ChatFormatting.AQUA;
        return colour.getColor();
    }

    /** One rendered row: the text and the colour it is drawn in. */
    private record Line(Component text, int colour) {
    }
}
