package fr.cobblepulse.utils.gui;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/** Conteneur verrouille : intercepte tous les clics, empeche tout deplacement d'item. */
public final class LockedMenu extends ChestMenu {
    private final Gui gui;

    LockedMenu(int id, Inventory inv, Gui gui) {
        super(type(gui.rows), id, inv, gui.container, gui.rows);
        this.gui = gui;
    }

    public static MenuType<?> type(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (player instanceof ServerPlayer sp && slotId >= 0 && slotId < gui.size()
                && (clickType == ClickType.PICKUP || clickType == ClickType.QUICK_MOVE)) {
            gui.handleClick(sp, slotId, new Gui.Click(clickType, button));
        }
        // Aucun deplacement autorise : resynchronisation complete du client
        sendAllDataToRemote();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, net.minecraft.world.inventory.Slot slot) {
        return false;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (gui.onClose != null && player instanceof ServerPlayer sp) gui.onClose.accept(sp);
    }
}
