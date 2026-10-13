package com.homemine.stealindims.menu;

import com.homemine.stealindims.ModMenus;
import com.homemine.stealindims.raid.RaidCrateZone;
import com.homemine.stealindims.raid.RaidLootMark;
import com.homemine.stealindims.raid.RaidSession;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The transit crate screen: three rows of personal extraction area on top, six rows of shared public
 * storage below, then the player inventory.
 *
 * <p>The vanilla six row chest texture is reused by stacking its pattern rows, so the layout follows
 * {@code ChestMenu} geometry and only marked raid loot may be placed in either crate zone.
 */
public class TransferCrateMenu extends AbstractContainerMenu {

    /** Personal extraction area slots, one row group per member. */
    public static final int PERSONAL_SLOTS = RaidSession.PERSONAL_ZONE_SIZE;
    /** Shared public storage slots. */
    public static final int PUBLIC_SLOTS = RaidSession.PUBLIC_STORAGE_SIZE;
    /** Both crate zones, in slot order: personal first, then public. */
    public static final int CRATE_SLOTS = PERSONAL_SLOTS + PUBLIC_SLOTS;

    private static final int ROWS = 9;
    /** Mirrors {@code ChestMenu}'s {@code (rows - 4) * 18} offset for the player inventory. */
    private static final int PLAYER_INVENTORY_OFFSET = (ROWS - 4) * 18;

    private final RaidCrateZone personal;
    private final RaidCrateZone publicStorage;

    public TransferCrateMenu(int containerId, Inventory playerInventory,
                             RaidCrateZone personal, RaidCrateZone publicStorage) {
        super(ModMenus.TRANSFER_CRATE.get(), containerId);
        this.personal = personal;
        this.publicStorage = publicStorage;

        for (int index = 0; index < PERSONAL_SLOTS; index++) {
            int row = index / 9;
            int column = index % 9;
            this.addSlot(new CrateSlot(personal, index, 8 + column * 18, 18 + row * 18));
        }
        for (int index = 0; index < PUBLIC_SLOTS; index++) {
            int row = index / 9;
            int column = index % 9;
            this.addSlot(new CrateSlot(publicStorage, index, 8 + column * 18, 18 + (row + 3) * 18));
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
     * Client side constructor invoked through {@link ModMenus}'s {@code IContainerFactory}. The server
     * sends no contents in the open packet, so the client gets two empty placeholders and the real
     * contents arrive via the vanilla container sync.
     */
    public TransferCrateMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf data) {
        this(containerId, playerInventory,
                new RaidCrateZone(PERSONAL_SLOTS),
                new RaidCrateZone(PUBLIC_SLOTS));
    }

    public RaidCrateZone personal() {
        return this.personal;
    }

    public RaidCrateZone publicStorage() {
        return this.publicStorage;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = slot.getItem();
        ItemStack result = stack.copy();
        if (index < CRATE_SLOTS) {
            if (!this.moveItemStackTo(stack, CRATE_SLOTS, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (!RaidLootMark.isMarked(stack)) {
                return ItemStack.EMPTY;
            }
            if (!this.moveItemStackTo(stack, 0, PERSONAL_SLOTS, false)
                    && !this.moveItemStackTo(stack, PERSONAL_SLOTS, CRATE_SLOTS, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }

        return result;
    }

    /** A crate zone slot: only marked raid loot may be placed here. */
    private static final class CrateSlot extends Slot {

        private CrateSlot(RaidCrateZone zone, int slot, int x, int y) {
            super(zone, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return RaidLootMark.isMarked(stack);
        }
    }
}
