package fr.cobblepulse.utils.essentials;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Vue modifiable de l'inventaire d'un joueur pour /invsee (5 lignes) :
 * lignes 1-3 = inventaire principal, ligne 4 = barre rapide, ligne 5 = armure (casque..bottes) + seconde main.
 */
final class InvseeContainer implements Container {
    private final Inventory inv;

    InvseeContainer(Inventory inv) {
        this.inv = inv;
    }

    /** Index dans l'inventaire du joueur, ou -1 (case inutilisee). */
    private int map(int slot) {
        if (slot < 27) return slot + 9;
        if (slot < 36) return slot - 27;
        int k = slot - 36;
        if (k < 4) return 39 - k;
        if (k == 4) return 40;
        return -1;
    }

    @Override
    public int getContainerSize() {
        return 45;
    }

    @Override
    public boolean isEmpty() {
        return inv.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        int i = map(slot);
        return i < 0 ? ItemStack.EMPTY : inv.getItem(i);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        int i = map(slot);
        return i < 0 ? ItemStack.EMPTY : inv.removeItem(i, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        int i = map(slot);
        return i < 0 ? ItemStack.EMPTY : inv.removeItemNoUpdate(i);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        int i = map(slot);
        if (i >= 0) inv.setItem(i, stack);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return map(slot) >= 0;
    }

    @Override
    public void setChanged() {
        inv.setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
    }
}
