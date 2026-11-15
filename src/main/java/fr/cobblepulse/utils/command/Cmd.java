package fr.cobblepulse.utils.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.config.CommandsConfig;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Enregistrement des commandes depuis commands.json (activation + alias).
 * Chaque alias recoit son propre arbre complet (les redirections Brigadier ne gerent pas les commandes sans argument).
 * Un noeud existant du meme nom (vanilla, Cobblemon, autre mod) est remplace et journalise.
 */
public final class Cmd {
    /** Noeuds remplaces sans avertissement (vanilla / Cobblemon, remplacement voulu). */
    private static final Set<String> EXPECTED = Set.of("msg", "tell", "w", "list", "pc");
    private static final List<String> REPLACED = new ArrayList<>();

    private Cmd() {}

    public static LiteralArgumentBuilder<CommandSourceStack> lit(String n) {
        return Commands.literal(n);
    }

    public static List<String> replaced() {
        return REPLACED;
    }

    /** Commande joueur (noeud cobblepulse.command.<name>). */
    public static void player(CommandDispatcher<CommandSourceStack> d, String name, Function<String, LiteralArgumentBuilder<CommandSourceStack>> tree) {
        register(d, name, Perms.cmdReq(name), tree);
    }

    /** Commande staff (noeud cobblepulse.admin.<node>). */
    public static void admin(CommandDispatcher<CommandSourceStack> d, String name, String node, Function<String, LiteralArgumentBuilder<CommandSourceStack>> tree) {
        register(d, name, Perms.adminReq(node), tree);
    }

    private static void register(CommandDispatcher<CommandSourceStack> d, String name, Predicate<CommandSourceStack> req,
                                 Function<String, LiteralArgumentBuilder<CommandSourceStack>> tree) {
        CommandsConfig.Entry e = Cfg.commands.get(name);
        if (!e.enabled) return;
        List<String> labels = new ArrayList<>();
        labels.add(name);
        for (String a : e.aliases) {
            String al = a == null ? "" : a.trim().toLowerCase(java.util.Locale.ROOT);
            if (!al.isEmpty() && !labels.contains(al)) labels.add(al);
        }
        for (String label : labels) {
            removeExisting(d, label);
            d.register(tree.apply(label).requires(req));
        }
    }

    public static void removeExisting(CommandDispatcher<CommandSourceStack> d, String label) {
        CommandNode<CommandSourceStack> old = d.getRoot().getChild(label);
        if (old == null) return;
        removeChild(d.getRoot(), label);
        if (!EXPECTED.contains(label)) {
            REPLACED.add(label);
            CobbleUtils.LOG.info("[CobblePulse-Utils] Commande /{} existante remplacee (desactivable dans commands.json)", label);
        }
    }

    @SuppressWarnings("unchecked")
    public static void removeChild(CommandNode<CommandSourceStack> parent, String name) {
        for (String f : new String[]{"children", "literals", "arguments"}) {
            try {
                Field field = CommandNode.class.getDeclaredField(f);
                field.setAccessible(true);
                ((Map<String, ?>) field.get(parent)).remove(name);
            } catch (ReflectiveOperationException ex) {
                CobbleUtils.LOG.warn("[CobblePulse-Utils] Retrait de /{} impossible : {}", name, ex.toString());
            }
        }
    }

    /** Ajoute une condition alternative (OU) a un noeud existant : ex. /tp accessible avec cobblepulse.admin.tp. */
    @SuppressWarnings("unchecked")
    public static void allowAlso(CommandDispatcher<CommandSourceStack> d, String label, Predicate<CommandSourceStack> extra) {
        CommandNode<CommandSourceStack> node = d.getRoot().getChild(label);
        if (node == null) return;
        try {
            Field f = CommandNode.class.getDeclaredField("requirement");
            f.setAccessible(true);
            Predicate<CommandSourceStack> old = (Predicate<CommandSourceStack>) f.get(node);
            f.set(node, (Predicate<CommandSourceStack>) src -> old.test(src) || extra.test(src));
        } catch (ReflectiveOperationException ex) {
            CobbleUtils.LOG.warn("[CobblePulse-Utils] Permission supplementaire sur /{} impossible : {}", label, ex.toString());
        }
    }

    /**
     * Fusionne notre racine avec un noeud existant d'un autre mod (ex. /cp du mod Clans) :
     * nos sous-commandes priment, les siennes absentes chez nous sont conservees.
     * @return les sous-commandes d'origine (pour chainer, ex. reload)
     */
    public static Map<String, CommandNode<CommandSourceStack>> mergeInto(CommandDispatcher<CommandSourceStack> d, String label,
                                                                        LiteralArgumentBuilder<CommandSourceStack> ours) {
        CommandNode<CommandSourceStack> old = d.getRoot().getChild(label);
        Map<String, CommandNode<CommandSourceStack>> previous = new java.util.LinkedHashMap<>();
        if (old != null) {
            for (CommandNode<CommandSourceStack> c : old.getChildren()) previous.put(c.getName(), c);
            removeChild(d.getRoot(), label);
        }
        LiteralCommandNode<CommandSourceStack> node = d.register(ours);
        for (Map.Entry<String, CommandNode<CommandSourceStack>> e : previous.entrySet()) {
            if (node.getChild(e.getKey()) == null) node.addChild(e.getValue());
        }
        return previous;
    }

    public interface PlayerAction {
        int run(ServerPlayer p) throws CommandSyntaxException;
    }

    /** Execute une action reservee aux joueurs. */
    public static int asPlayer(CommandContext<CommandSourceStack> ctx, PlayerAction action) throws CommandSyntaxException {
        if (!ctx.getSource().isPlayer()) {
            Msg.send(ctx.getSource(), "general.players_only");
            return 0;
        }
        return action.run(ctx.getSource().getPlayerOrException());
    }

    public static int run(CommandContext<CommandSourceStack> ctx, Consumer<ServerPlayer> action) throws CommandSyntaxException {
        return asPlayer(ctx, p -> {
            action.accept(p);
            return 1;
        });
    }
}
