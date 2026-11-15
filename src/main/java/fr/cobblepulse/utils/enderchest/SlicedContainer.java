package fr.cobblepulse.utils.enderchest;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Vue sur les N premieres cases d'un conteneur (Enderchest de 1 a 6 lignes sur une page de 54 cases). */
final class SlicedContainer implements Container {
    private final Container base;
    private final int size;

    SlicedContainer(Container base, int size) {
        this.base = base;
        this.size = Math.min(size, base.getContainerSize());
    }

    @Override
    public int getContainerSize() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < size; i++) if (!base.getItem(i).isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot < size ? base.getItem(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return slot < size ? base.removeItem(slot, amount) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return slot < size ? base.removeItemNoUpdate(slot) : ItemStack.EMPTY;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < size) base.setItem(slot, stack);
    }

    @Override
    public void setChanged() {
        base.setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < size; i++) base.setItem(i, ItemStack.EMPTY);
    }
}
