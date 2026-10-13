package com.homemine.stealindims.raid;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * One zone of the transit crate: either a member's personal extraction area or the shared public
 * storage.
 *
 * <p>Unlike {@link RaidStash} this is an in-memory container that never touches disk, so it takes its
 * size from the constructor and has no changed listener. Plain {@link Container} slots are used so the
 * vanilla container sync packets keep every open screen in step.
 */
public class RaidCrateZone implements Container {

    private final int size;
    private final NonNullList<ItemStack> items;

    public RaidCrateZone(int size) {
        this.size = size;
        this.items = NonNullList.withSize(size, ItemStack.EMPTY);
    }

    @Override
    public int getContainerSize() {
        return this.size;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : this.items) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return this.items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return ContainerHelper.removeItem(this.items, slot, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(this.items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        this.items.set(slot, stack);
    }

    @Override
    public void setChanged() {
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        this.items.clear();
    }

    /**
     * Copies every non empty stack out in slot order. Callers (settlement, the result screen, the
     * threshold check) work on the snapshot so the live container is never handed out.
     */
    public List<ItemStack> snapshot() {
        List<ItemStack> stacks = new ArrayList<>(this.size);
        for (ItemStack stack : this.items) {
            if (!stack.isEmpty()) {
                stacks.add(stack.copy());
            }
        }
        return stacks;
    }

    /**
     * Merges {@code stack} into matching non full stacks first, then drops what is left into the first
     * empty slot.
     *
     * <p>All or nothing: if the zone cannot hold the whole stack nothing is touched, which is what lets
     * the caller decide between the personal area and the public storage without a rollback.
     *
     * @return true when the whole stack was stored
     */
    public boolean insert(ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        int room = 0;
        for (ItemStack existing : this.items) {
            if (existing.isEmpty()) {
                room += stack.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(existing, stack)) {
                room += existing.getMaxStackSize() - existing.getCount();
            }
            if (room >= stack.getCount()) {
                break;
            }
        }
        if (room < stack.getCount()) {
            return false;
        }

        int remaining = stack.getCount();
        for (int slot = 0; slot < this.size && remaining > 0; slot++) {
            ItemStack existing = this.items.get(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) {
                continue;
            }
            int space = existing.getMaxStackSize() - existing.getCount();
            if (space <= 0) {
                continue;
            }
            int moved = Math.min(space, remaining);
            existing.grow(moved);
            remaining -= moved;
        }
        for (int slot = 0; slot < this.size && remaining > 0; slot++) {
            if (!this.items.get(slot).isEmpty()) {
                continue;
            }
            int moved = Math.min(stack.getMaxStackSize(), remaining);
            ItemStack placed = stack.copy();
            placed.setCount(moved);
            this.items.set(slot, placed);
            remaining -= moved;
        }
        return remaining == 0;
    }
}
