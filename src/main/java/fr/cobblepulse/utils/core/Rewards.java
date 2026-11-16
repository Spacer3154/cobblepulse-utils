package fr.cobblepulse.utils.core;

import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.config.Reward;
import fr.cobblepulse.utils.integration.Economy;
import fr.cobblepulse.utils.pokemon.PokemonFactory;
import fr.cobblepulse.utils.pokemon.PokemonText;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Remise des recompenses generiques (Dex Rewards, butin des Boss). */
public final class Rewards {
    private Rewards() {}

    /** Libelle d'apercu (lore). */
    public static String describe(Reward r) {
        if (r.display != null && !r.display.isBlank()) return r.display;
        return switch (r.type) {
            case "money" -> "&a" + Economy.format(r.amount);
            case "bp" -> "&6" + r.amount + " BP &7(Tour de Combat)";
            case "command" -> "&7Commande : &f/" + (r.commands.isEmpty() ? "?" : r.commands.get(0));
            case "pokemon" -> r.pokemon == null ? "&cPokémon ?" : "&ePokémon : &f" + r.pokemon.species + " &7Nv." + r.pokemon.level + (r.pokemon.shiny ? " &e★" : "");
            default -> {
                ItemStack st = ItemSerial.fromSnbt(r.item);
                yield st.isEmpty() ? "&c" + r.item : "&f" + st.getCount() + " × " + st.getHoverName().getString();
            }
        };
    }

    public static ItemStack icon(Reward r) {
        return switch (r.type) {
            case "money" -> new ItemStack(net.minecraft.world.item.Items.GOLD_NUGGET);
            case "bp" -> new ItemStack(net.minecraft.world.item.Items.NETHER_STAR);
            case "command" -> new ItemStack(net.minecraft.world.item.Items.COMMAND_BLOCK);
            case "pokemon" -> new ItemStack(net.minecraft.world.item.Items.EGG);
            default -> {
                ItemStack st = ItemSerial.fromSnbt(r.item);
                yield st.isEmpty() ? new ItemStack(net.minecraft.world.item.Items.BARRIER) : st;
            }
        };
    }

    /** Recompense de type objet (tout ce qui n'est ni argent, ni BP, ni commande, ni Pokemon). */
    public static boolean isItem(Reward r) {
        return !"money".equals(r.type) && !"bp".equals(r.type) && !"command".equals(r.type) && !"pokemon".equals(r.type);
    }

    /** Tire la chance puis remet la recompense. @return libelle si obtenue, null sinon. */
    public static String roll(ServerPlayer p, Reward r) {
        if (r.chance < 100 && Math.random() * 100 >= r.chance) return null;
        return give(p, r) ? describe(r) : null;
    }

    public static boolean give(ServerPlayer p, Reward r) {
        try {
            switch (r.type) {
                case "money" -> {
                    return Economy.deposit(p.getUUID(), r.amount);
                }
                case "bp" -> {
                    run(Cfg.boss.bpCommand.replace("%amount%", String.valueOf(r.amount)), p);
                    return true;
                }
                case "command" -> {
                    for (String c : r.commands) run(c, p);
                    return true;
                }
                case "pokemon" -> {
                    if (r.pokemon == null) return false;
                    List<String> errors = new ArrayList<>();
                    Pokemon pk = PokemonFactory.create(r.pokemon, errors);
                    if (pk == null) {
                        CobbleUtils.LOG.warn("[CobblePulse-Utils] Recompense Pokemon invalide : {}", errors);
                        return false;
                    }
                    PokemonText.party(p).add(pk);
                    return true;
                }
                default -> {
                    ItemStack st = ItemSerial.fromSnbt(r.item);
                    if (st.isEmpty()) return false;
                    giveItem(p, st);
                    return true;
                }
            }
        } catch (Throwable t) {
            CobbleUtils.LOG.error("[CobblePulse-Utils] Recompense {} impossible pour {} : {}", r.type, p.getGameProfile().getName(), t.toString());
            return false;
        }
    }

    /** Ajoute a l'inventaire (gros stacks decoupes), le surplus tombe au sol. */
    public static void giveItem(ServerPlayer p, ItemStack st) {
        int left = st.getCount();
        int max = Math.max(1, st.getMaxStackSize());
        while (left > 0) {
            ItemStack part = st.copyWithCount(Math.min(max, left));
            left -= part.getCount();
            if (!p.getInventory().add(part) || !part.isEmpty()) p.drop(part, false);
        }
    }

    public static void run(String cmd, ServerPlayer p) {
        String c = cmd.replace("%player%", p.getGameProfile().getName()).replace("%uuid%", p.getUUID().toString());
        if (c.startsWith("/")) c = c.substring(1);
        CobbleUtils.server().getCommands().performPrefixedCommand(CobbleUtils.server().createCommandSourceStack(), c);
    }
}
