package com.homemine.stealindims.client;

import com.homemine.stealindims.menu.TransferCrateMenu;
import com.homemine.stealindims.net.TransferCratePayload;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The transit crate screen: three rows of personal extraction area over six rows of shared storage.
 *
 * <p>Vanilla only ships a six row chest texture, so the slot band is tiled to reach nine rows and the
 * player inventory block is blitted on at the end. When the window is too short for the whole panel
 * the mouse wheel pans it; when it is not, the screen stays centred and ignores the wheel.
 */
public class TransferCrateScreen extends AbstractContainerScreen<TransferCrateMenu> {

    private static final ResourceLocation CONTAINER_BACKGROUND =
            ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final int ROWS = 9;
    /** The title band of the chest texture: 17px tall, above the first slot row. */
    private static final int TITLE_HEIGHT = 17;
    private static final int ROW_HEIGHT = 18;
    private static final int BUTTON_WIDTH = 44;
    private static final int BUTTON_HEIGHT = 12;

    private Button transferButton;
    private int scrollOffset;

    public TransferCrateScreen(TransferCrateMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageHeight = 114 + ROWS * ROW_HEIGHT;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void init() {
        super.init();
        this.transferButton = Button.builder(
                        Component.translatable("gui.stealindims.crate.transfer_all"),
                        button -> PacketDistributor.sendToServer(new TransferCratePayload()))
                .bounds(this.leftPos + 128, this.topPos + 3, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        this.addRenderableWidget(this.transferButton);
        // super.init() re-centres the panel, so the scroll position has to be re-applied afterwards.
        this.applyScroll();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.transferButton.setX(this.leftPos + 128);
        this.transferButton.setY(this.topPos + 3);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(CONTAINER_BACKGROUND, this.leftPos, this.topPos, 0, 0,
                this.imageWidth, TITLE_HEIGHT + 3 * ROW_HEIGHT);
        for (int row = 3; row < ROWS; row++) {
            guiGraphics.blit(CONTAINER_BACKGROUND, this.leftPos, this.topPos + TITLE_HEIGHT + row * ROW_HEIGHT,
                    0, TITLE_HEIGHT, this.imageWidth, ROW_HEIGHT);
        }
        guiGraphics.blit(CONTAINER_BACKGROUND, this.leftPos, this.topPos + TITLE_HEIGHT + ROWS * ROW_HEIGHT,
                0, 126, this.imageWidth, 96);
        // A hairline between the personal area and the shared storage, so the split is obvious.
        guiGraphics.fill(this.leftPos + 7, this.topPos + TITLE_HEIGHT + 3 * ROW_HEIGHT,
                this.leftPos + 169, this.topPos + TITLE_HEIGHT + 3 * ROW_HEIGHT + 1, 0xFF373737);
    }

    @Override
    protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
        List<Component> tooltip = super.getTooltipFromContainerItem(stack);
        if (this.hoveredSlot != null) {
            if (this.hoveredSlot.index < TransferCrateMenu.PERSONAL_SLOTS) {
                tooltip.add(Component.translatable("gui.stealindims.crate.personal")
                        .withStyle(ChatFormatting.GRAY));
            } else if (this.hoveredSlot.index < TransferCrateMenu.CRATE_SLOTS) {
                tooltip.add(Component.translatable("gui.stealindims.crate.public")
                        .withStyle(ChatFormatting.GRAY));
            }
        }
        return tooltip;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // Never pan mid drag: the vanilla slot logic expects the panel to sit still under the cursor.
        if (this.imageHeight <= this.height
                || this.isQuickCrafting || !this.menu.getCarried().isEmpty()) {
            return false;
        }
        this.scrollOffset += (int) (scrollY * ROW_HEIGHT);
        this.applyScroll();
        return true;
    }

    /** Centres the panel when it fits, and otherwise pans it within its own bounds. */
    private void applyScroll() {
        int centred = (this.height - this.imageHeight) / 2;
        if (this.imageHeight <= this.height) {
            this.topPos = centred;
            this.scrollOffset = 0;
            return;
        }
        this.topPos = Mth.clamp(centred + this.scrollOffset, this.height - this.imageHeight, 0);
    }
}
