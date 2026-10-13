package com.homemine.stealindims.menu;

import com.homemine.stealindims.ModMenus;
import com.homemine.stealindims.raid.RaidStash;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The transit crate screen opened by an Advanced Warp Device.
 *
 * <p>The layout is byte for byte the vanilla three row chest, which lets the client reuse
 * {@code textures/gui/container/generic_54.png} without a custom texture. The stash is a plain
 * {@link RaidStash} so the vanilla container sync packets keep both sides in step.
 */
public class RaidPortalMenu extends AbstractContainerMenu {

    /** Stash slots come first, so everything below this index belongs to the player. */
    public static final int STASH_SLOTS = RaidStash.SIZE;
    private static final int ROWS = 3;
    /** Mirrors {@code ChestMenu}'s {@code (rows - 4) * 18} offset for the player inventory. */
    private static final int PLAYER_INVENTORY_OFFSET = (ROWS - 4) * 18;

    private final RaidStash stash;

    public RaidPortalMenu(int containerId, Inventory playerInventory, RaidStash stash) {
        super(ModMenus.RAID_PORTAL.get(), containerId);
        this.stash = stash;

        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < 9; column++) {
                this.addSlot(new Slot(stash, column + row * 9, 8 + column * 18, 18 + row * 18));
            }
        }

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                this.addSlot(new Slot(playerInventory, column + row * 9 + 9,
                        8 + column * 18, 103 + row * 18 + PLAYER_INVENTORY_OFFSET));
            }
        }

        for (int column = 0; column < 9; column++) {
            this.addSlot(new Slot(playerInventory, column, 8 + column * 18, 161 + PLAYER_INVENTORY_OFFSET));
        }
    }

    /**
     * Client side constructor invoked through {@link com.homemine.stealindims.ModMenus}'s
     * {@code IContainerFactory}. The server never sends stash contents in the open packet, so the client
     * gets an empty placeholder and the real contents arrive via the usual container sync.
     */
    public RaidPortalMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf data) {
        this(containerId, playerInventory, new RaidStash());
    }

    public RaidStash stash() {
        return this.stash;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return result;
        }

        ItemStack stack = slot.getItem();
        result = stack.copy();
        if (index < STASH_SLOTS) {
            if (!this.moveItemStackTo(stack, STASH_SLOTS, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!this.moveItemStackTo(stack, 0, STASH_SLOTS, false)) {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }

        return result;
    }
}
