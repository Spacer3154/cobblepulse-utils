package fr.cobblepulse.utils.gui;

import fr.cobblepulse.utils.core.Msg;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/**
 * Selecteurs des editeurs : objet (tous les objets du jeu et des mods, avec recherche) et Pokemon (espece, forme,
 * niveau, chromatique, IV, nature, talent, Ball, objet tenu...). Avec le mod cote client, un ecran dedie ;
 * sans le mod, la saisie de l'identifiant ou de la fiche dans le chat.
 */
public final class Pickers {
    private Pickers() {}

    /** Identifiant d'objet choisi (ex. cobblemon:poke_ball). */
    public static void item(ServerPlayer p, String titleKey, Consumer<String> handler, Runnable onCancel) {
        ChatInput.pick(p, "item", titleKey, "general.ask_item_id", "", handler, onCancel);
    }

    /** Fiche de Pokemon (format de la boutique : « espece niveau=… shiny ivs=6 nature=… ... »). */
    public static void pokemon(ServerPlayer p, String titleKey, String initial, Consumer<String> handler, Runnable onCancel) {
        ChatInput.pick(p, "pokemon", titleKey, "shop.ask_pokemon", initial == null ? "" : initial, handler, onCancel);
    }

    static String title(String key) {
        return Msg.formatBare(key);
    }
}
