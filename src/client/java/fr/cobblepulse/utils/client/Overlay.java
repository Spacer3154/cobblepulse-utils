package fr.cobblepulse.utils.client;

import net.minecraft.client.gui.screens.Screen;

/** Fenetre affichee par-dessus un menu (saisie, selecteurs) : le menu reste derriere et y revient a l'annulation. */
interface Overlay {
    Screen parentScreen();

    void setParentScreen(Screen s);
}
