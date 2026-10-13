package com.homemine.stealindims.client;

import com.homemine.stealindims.net.RaidResultPayload;
import com.homemine.stealindims.raid.LootValueIndex;
import com.homemine.stealindims.raid.RaidDifficulty;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The settlement screen (design §21, modelled on 第五人格's 加页手记): what the run was, what was handed
 * to the transit crate, what actually came out and what it cost.
 *
 * <p>Item values come from {@link LootValueIndex}, which is a data pack table: in single player the
 * integrated server loads it into the same JVM, so the per stack figures here match the server's. On
 * a dedicated server the client has no table of its own and the icons simply carry no value line; the
 * totals, which are what the run is judged by, always travel in the payload.
 */
public class RaidResultScreen extends Screen {

    private static final int PANEL_WIDTH = 348;
    private static final int PANEL_HEIGHT = 190;
    private static final int CELL = 18;
    private static final int COLUMNS = 9;
    private static final int MAX_ROWS = 3;

    private static final int LINE = 11;
    private static final int BAR_WIDTH = 150;
    private static final int BAR_HEIGHT = 8;

    private final RaidResultPayload payload;

    private int panelLeft;
    private int panelTop;

    public RaidResultScreen(RaidResultPayload payload) {
        super(Component.translatable("gui.stealindims.result.title"));
        this.payload = payload;
    }

    @Override
    protected void init() {
        this.panelLeft = (this.width - PANEL_WIDTH) / 2;
        this.panelTop = (this.height - PANEL_HEIGHT) / 2;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), widget -> onClose())
                .bounds(this.width / 2 - 50, this.panelTop + PANEL_HEIGHT - 24, 100, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderPanel(guiGraphics);
        renderSummary(guiGraphics);
        ItemStack hovered = renderColumn(guiGraphics, mouseX, mouseY);
        if (hovered != null) {
            guiGraphics.renderTooltip(this.font, tooltip(hovered), java.util.Optional.empty(), mouseX, mouseY);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void renderPanel(GuiGraphics guiGraphics) {
        guiGraphics.fill(this.panelLeft, this.panelTop, this.panelLeft + PANEL_WIDTH,
                this.panelTop + PANEL_HEIGHT, 0xC0101010);
        guiGraphics.fill(this.panelLeft, this.panelTop, this.panelLeft + PANEL_WIDTH,
                this.panelTop + 1, 0xFFAAAAAA);

        Component headline = Component.translatable(this.payload.success()
                ? "gui.stealindims.result.success"
                : "gui.stealindims.result.failure");
        guiGraphics.drawCenteredString(this.font, headline, this.width / 2, this.panelTop + 7,
                this.payload.success() ? ChatFormatting.GREEN.getColor() : ChatFormatting.RED.getColor());

        guiGraphics.drawCenteredString(this.font, Component.translatable("gui.stealindims.result.route",
                        RaidClientState.dimensionName(this.payload.hostDimension()),
                        this.payload.mapName(), difficultyName()),
                this.width / 2, this.panelTop + 20, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, Component.translatable("gui.stealindims.result.time",
                        RaidClientState.formatClock(this.payload.elapsedSeconds()),
                        RaidClientState.formatClock(this.payload.remainingSeconds())),
                this.width / 2, this.panelTop + 31, ChatFormatting.GRAY.getColor());
        guiGraphics.fill(this.panelLeft + 6, this.panelTop + 43, this.panelLeft + PANEL_WIDTH - 6,
                this.panelTop + 44, 0x66AAAAAA);
    }

    private void renderSummary(GuiGraphics guiGraphics) {
        int x = this.panelLeft + 9;
        int y = this.panelTop + 50;

        guiGraphics.drawString(this.font, Component.translatable("gui.stealindims.result.submitted_value",
                        RaidClientState.formatValue(this.payload.submittedValue()),
                        RaidClientState.formatValue(this.payload.requiredValue())),
                x, y, ChatFormatting.AQUA.getColor(), true);

        int barTop = y + LINE;
        int filled = ratio() <= 0.0F ? 0 : Math.max(1, Math.round(BAR_WIDTH * Math.min(1.0F, ratio())));
        guiGraphics.fill(x, barTop, x + BAR_WIDTH, barTop + BAR_HEIGHT, 0xFF202020);
        guiGraphics.fill(x, barTop, x + filled, barTop + BAR_HEIGHT,
                this.payload.success() ? 0xFF3FBF3F : 0xFFBF3F3F);

        y += LINE * 2 + BAR_HEIGHT - 2;
        guiGraphics.drawString(this.font, Component.translatable("gui.stealindims.result.progress",
                Math.round(ratio() * 100.0F)), x, y, 0xFFFFFF, true);

        y += LINE;
        guiGraphics.drawString(this.font, Component.translatable(this.payload.success()
                        ? "gui.stealindims.result.kept_all"
                        : "gui.stealindims.result.lost_pool"),
                x, y, this.payload.success() ? ChatFormatting.GREEN.getColor() : ChatFormatting.RED.getColor(), true);

        y += LINE;
        guiGraphics.drawString(this.font, Component.translatable("gui.stealindims.result.equipment_lost",
                        this.payload.equipmentLost().size()), x, y, ChatFormatting.RED.getColor(), true);

        y += LINE;
        guiGraphics.drawString(this.font, Component.translatable("gui.stealindims.result.tax",
                        this.payload.teleportTaxCount(),
                        RaidClientState.formatValue(this.payload.teleportTaxValue())),
                x, y, ChatFormatting.GOLD.getColor(), true);

        y += LINE;
        guiGraphics.drawString(this.font, unlockLabel(), x, y, ChatFormatting.YELLOW.getColor(), true);
    }

    /** Paints the three item blocks and returns the stack under the cursor, if any. */
    @Nullable
    private ItemStack renderColumn(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int left = this.panelLeft + 177;
        int top = this.panelTop + 50;
        ItemStack hovered = null;

        hovered = grid(guiGraphics, Component.translatable("gui.stealindims.result.detail"),
                this.payload.submitted(), left, top, mouseX, mouseY, hovered);
        hovered = grid(guiGraphics, Component.translatable("gui.stealindims.result.brought_out"),
                this.payload.broughtOut(), left, top + 69, mouseX, mouseY, hovered);

        int lostLabelTop = this.panelTop + 132;
        guiGraphics.drawString(this.font, Component.translatable("gui.stealindims.result.lost_equipment"),
                this.panelLeft + 9, lostLabelTop, ChatFormatting.RED.getColor(), true);
        return grid(guiGraphics, this.payload.equipmentLost(), this.panelLeft + 9, lostLabelTop + 11,
                mouseX, mouseY, hovered);
    }

    private ItemStack grid(GuiGraphics guiGraphics, Component label, List<ItemStack> stacks,
                           int left, int top, int mouseX, int mouseY, @Nullable ItemStack hovered) {
        guiGraphics.drawString(this.font, label, left, top, ChatFormatting.YELLOW.getColor(), true);
        return grid(guiGraphics, stacks, left, top + LINE, mouseX, mouseY, hovered);
    }

    private ItemStack grid(GuiGraphics guiGraphics, List<ItemStack> stacks, int left, int top,
                           int mouseX, int mouseY, @Nullable ItemStack hovered) {
        if (stacks.isEmpty()) {
            guiGraphics.drawString(this.font, Component.translatable("gui.stealindims.result.none"),
                    left, top, ChatFormatting.DARK_GRAY.getColor(), true);
            return hovered;
        }

        int shown = Math.min(stacks.size(), COLUMNS * MAX_ROWS);
        for (int index = 0; index < shown; index++) {
            ItemStack stack = stacks.get(index);
            if (stack.isEmpty()) {
                continue;
            }
            int cellX = left + (index % COLUMNS) * CELL;
            int cellY = top + (index / COLUMNS) * CELL;
            guiGraphics.renderItem(stack, cellX, cellY);
            guiGraphics.renderItemDecorations(this.font, stack, cellX, cellY);
            if (mouseX >= cellX && mouseX < cellX + CELL && mouseY >= cellY && mouseY < cellY + CELL) {
                hovered = stack;
            }
        }

        if (stacks.size() > shown) {
            guiGraphics.drawString(this.font, Component.translatable("gui.stealindims.result.more",
                    stacks.size() - shown), left, top + MAX_ROWS * CELL, ChatFormatting.GRAY.getColor(), true);
        }
        return hovered;
    }

    private List<Component> tooltip(ItemStack stack) {
        List<Component> lines = new ArrayList<>(2);
        lines.add(stack.getHoverName());
        lines.add(Component.translatable("gui.stealindims.result.entry", stack.getCount(),
                RaidClientState.formatValue(LootValueIndex.valueOf(stack))));
        return lines;
    }

    private float ratio() {
        return this.payload.requiredValue() <= 0L
                ? 1.0F
                : (float) this.payload.submittedValue() / (float) this.payload.requiredValue();
    }

    private Component difficultyName() {
        RaidDifficulty[] ladder = RaidDifficulty.values();
        int ordinal = this.payload.difficultyOrdinal();
        return ordinal >= 0 && ordinal < ladder.length
                ? ladder[ordinal].displayName()
                : Component.literal(String.valueOf(ordinal));
    }

    private Component unlockLabel() {
        RaidDifficulty[] ladder = RaidDifficulty.values();
        int ordinal = this.payload.unlockedOrdinal();
        if (ordinal < 0 || ordinal >= ladder.length) {
            return Component.translatable("gui.stealindims.result.no_unlock");
        }
        return Component.translatable("gui.stealindims.result.unlock", ladder[ordinal].displayName());
    }
}
