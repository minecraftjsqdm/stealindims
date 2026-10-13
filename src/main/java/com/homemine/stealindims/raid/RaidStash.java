package com.homemine.stealindims.raid;

import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The 27 slot transit crate store opened by an Advanced Warp Device.
 *
 * <p>Every device in the same host dimension shares one stash, which is why this lives in a per-level
 * {@link RaidStashData} rather than in the block entity. Plain {@link Container} slots are used on
 * purpose so the vanilla container sync packets handle the client screen.
 */
public class RaidStash implements Container {

    public static final int SIZE = 27;

    private final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    private Runnable changedListener = () -> {
    };

    /** Called whenever the contents change; used by the owning {@link RaidStashData} to mark itself dirty. */
    public void setChangedListener(Runnable changedListener) {
        this.changedListener = changedListener;
    }

    NonNullList<ItemStack> items() {
        return this.items;
    }

    @Override
    public int getContainerSize() {
        return SIZE;
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
        this.changedListener.run();
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        this.items.clear();
    }
}
