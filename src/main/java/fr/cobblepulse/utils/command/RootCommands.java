package fr.cobblepulse.utils.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.boss.Bosses;
import fr.cobblepulse.utils.config.BossConfig;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.config.CommandsConfig;
import fr.cobblepulse.utils.config.ShopConfig;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.core.TextUtil;
import fr.cobblepulse.utils.hub.Hub;
import fr.cobblepulse.utils.staff.StaffPanel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static net.minecraft.commands.Commands.argument;

/**
 * /menu, /cp, /cobblepulse : Hub + commandes Staff (§4.1, §4.5).
 * Si le mod CobblePulse (Clans, Ligue, Tournois) a deja /cp ou /cobblepulse, ses sous-commandes (season, payinfluence...)
 * sont conservees et /cp reload recharge aussi sa configuration.
 */
public final class RootCommands {
    private RootCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        CommandsConfig.Entry e = Cfg.commands.get("menu");
        if (!e.enabled) return;
        List<String> labels = new ArrayList<>();
        labels.add("menu");
        for (String a : e.aliases) if (a != null && !a.isBlank() && !labels.contains(a.trim().toLowerCase(Locale.ROOT))) labels.add(a.trim().toLowerCase(Locale.ROOT));
        for (String label : labels) {
            CommandNode<CommandSourceStack> old = d.getRoot().getChild(label);
            Command<CommandSourceStack> oldReload = old != null && old.getChild("reload") != null ? old.getChild("reload").getCommand() : null;
            if (oldReload == null && old != null && !label.equals("cobblepulse")) {
                CommandNode<CommandSourceStack> other = d.getRoot().getChild("cobblepulse");
                if (other != null && other.getChild("reload") != null) oldReload = other.getChild("reload").getCommand();
            }
            LiteralArgumentBuilder<CommandSourceStack> tree = tree(label, oldReload).requires(Perms.cmdReq("menu"));
            if (old != null) {
                Cmd.mergeInto(d, label, tree);
                CobbleUtils.LOG.info("[CobblePulse-Utils] /{} fusionne avec la commande existante (sous-commandes conservees)", label);
            } else {
                d.register(tree);
            }
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> tree(String label, Command<CommandSourceStack> previousReload) {
        LiteralArgumentBuilder<CommandSourceStack> root = Cmd.lit(label).executes(ctx -> Cmd.run(ctx, Hub::open));
        root.then(Cmd.lit("admin").requires(src -> !src.isPlayer() || StaffPanel.canOpen(src.getPlayer())).executes(ctx -> Cmd.run(ctx, StaffPanel::open)));
        root.then(Cmd.lit("staff").requires(src -> !src.isPlayer() || StaffPanel.canOpen(src.getPlayer())).executes(ctx -> Cmd.run(ctx, StaffPanel::open)));
        root.then(Cmd.lit("reload").requires(Perms.adminReq("reload")).executes(ctx -> {
            CobbleUtils.reload();
            int extra = 0;
            if (previousReload != null) {
                try {
                    previousReload.run(ctx);
                    extra = 1;
                } catch (Exception ex) {
                    CobbleUtils.LOG.warn("[CobblePulse-Utils] Rechargement du mod CobblePulse : {}", ex.toString());
                }
            }
            Msg.send(ctx.getSource(), "general.reloaded", "shop", Cfg.SHOP.size(), "kits", Cfg.kits.kits.size(), "warps", Cfg.warps.warps.size());
            return 1 + extra;
        }));
        root.then(Cmd.lit("boss")
                .then(Cmd.lit("toggle").requires(Perms.adminReq("boss.toggle")).executes(ctx -> {
                    Cfg.boss.naturalSpawn = !Cfg.boss.naturalSpawn;
                    Cfg.save("boss.json", Cfg.boss);
                    Msg.send(ctx.getSource(), Cfg.boss.naturalSpawn ? "boss.toggle_on" : "boss.toggle_off");
                    return 1;
                }))
                // tests staff : un Boss d'un palier (espece et forme au choix), un de chaque palier, ou un combat immediat
                .then(Cmd.lit("spawn").requires(Perms.adminReq("boss.spawn"))
                        .then(argument("palier", StringArgumentType.word()).suggests(TIERS)
                                .executes(ctx -> spawnBoss(ctx.getSource(), StringArgumentType.getString(ctx, "palier"), null, "auto", false))
                                .then(argument("espece", StringArgumentType.word()).suggests(SPECIES)
                                        .executes(ctx -> spawnBoss(ctx.getSource(), StringArgumentType.getString(ctx, "palier"), StringArgumentType.getString(ctx, "espece"), "auto", false))
                                        .then(argument("forme", StringArgumentType.word()).suggests(FORMS)
                                                .executes(ctx -> spawnBoss(ctx.getSource(), StringArgumentType.getString(ctx, "palier"),
                                                        StringArgumentType.getString(ctx, "espece"), StringArgumentType.getString(ctx, "forme"), false))))))
                .then(Cmd.lit("tous").requires(Perms.adminReq("boss.spawn")).executes(ctx -> spawnAllTiers(ctx.getSource())))
                .then(Cmd.lit("combat").requires(Perms.adminReq("boss.spawn"))
                        .then(argument("palier", StringArgumentType.word()).suggests(TIERS)
                                .executes(ctx -> spawnBoss(ctx.getSource(), StringArgumentType.getString(ctx, "palier"), null, "auto", true))
                                .then(argument("espece", StringArgumentType.word()).suggests(SPECIES)
                                        .executes(ctx -> spawnBoss(ctx.getSource(), StringArgumentType.getString(ctx, "palier"), StringArgumentType.getString(ctx, "espece"), "auto", true))
                                        .then(argument("forme", StringArgumentType.word()).suggests(FORMS)
                                                .executes(ctx -> spawnBoss(ctx.getSource(), StringArgumentType.getString(ctx, "palier"),
                                                        StringArgumentType.getString(ctx, "espece"), StringArgumentType.getString(ctx, "forme"), true))))))
                .then(Cmd.lit("clear").requires(Perms.adminReq("boss.clear")).executes(ctx -> {
                    int n = Bosses.clearAll();
                    Msg.send(ctx.getSource(), "boss.cleared", "count", n);
                    return n;
                }))
                .then(Cmd.lit("editor").requires(Perms.adminReq("boss")).executes(ctx -> Cmd.run(ctx, fr.cobblepulse.utils.boss.BossEditor::open))));
        // test des annonces de spawns rares : vrai Pokemon sauvage + vraie annonce (a tous, ou au staff seul)
        root.then(Cmd.lit("alertes").requires(Perms.adminReq("alerts"))
                .then(Cmd.lit("test")
                        .then(argument("categorie", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(fr.cobblepulse.utils.alerts.SpawnAlerts.CATEGORIES, b))
                                .executes(ctx -> testAlert(ctx.getSource(), StringArgumentType.getString(ctx, "categorie"), null, false))
                                .then(argument("espece", StringArgumentType.word()).suggests(SPECIES)
                                        .executes(ctx -> testAlert(ctx.getSource(), StringArgumentType.getString(ctx, "categorie"), StringArgumentType.getString(ctx, "espece"), false))
                                        .then(Cmd.lit("moi").executes(ctx -> testAlert(ctx.getSource(), StringArgumentType.getString(ctx, "categorie"),
                                                StringArgumentType.getString(ctx, "espece"), true))))
                                .then(Cmd.lit("moi").executes(ctx -> testAlert(ctx.getSource(), StringArgumentType.getString(ctx, "categorie"), null, true))))));
        root.then(Cmd.lit("gts").then(Cmd.lit("toggle").requires(Perms.adminReq("gts.toggle")).executes(ctx -> {
            Cfg.gts.enabled = !Cfg.gts.enabled;
            Cfg.save("gts.json", Cfg.gts);
            Msg.send(ctx.getSource(), Cfg.gts.enabled ? "gts.toggled_open" : "gts.toggled_closed");
            return 1;
        })));
        root.then(Cmd.lit("sts").then(Cmd.lit("toggle").requires(Perms.adminReq("sts.toggle")).executes(ctx -> {
            Cfg.sts.enabled = !Cfg.sts.enabled;
            Cfg.save("sts.json", Cfg.sts);
            Msg.send(ctx.getSource(), Cfg.sts.enabled ? "sts.toggled_open" : "sts.toggled_closed");
            return 1;
        })));
        root.then(Cmd.lit("wondertrade").then(Cmd.lit("toggle").requires(Perms.adminReq("gts.toggle")).executes(ctx -> {
            Cfg.wt.enabled = !Cfg.wt.enabled;
            Cfg.save("wondertrade.json", Cfg.wt);
            Msg.send(ctx.getSource(), Cfg.wt.enabled ? "wt.toggled_open" : "wt.toggled_closed");
            return 1;
        })));
        root.then(Cmd.lit("shop").then(Cmd.lit("toggle").requires(Perms.adminReq("shop.toggle")).executes(ctx -> {
                    Cfg.shop.enabled = !Cfg.shop.enabled;
                    Cfg.save("shop.json", Cfg.shop);
                    Msg.send(ctx.getSource(), Cfg.shop.enabled ? "shop.toggled_open" : "shop.toggled_closed");
                    return 1;
                })
                .then(argument("categorie", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(Cfg.SHOP.keySet(), b)).executes(ctx -> {
                    String id = StringArgumentType.getString(ctx, "categorie").toLowerCase(Locale.ROOT);
                    ShopConfig.Category c = Cfg.SHOP.get(id);
                    if (c == null) {
                        Msg.send(ctx.getSource(), "shop.unknown_category", "category", id);
                        return 0;
                    }
                    c.enabled = !c.enabled;
                    Cfg.saveShopCategory(c);
                    Msg.send(ctx.getSource(), c.enabled ? "shop.cat_toggled_open" : "shop.cat_toggled_closed", "category", TextUtil.strip(c.name));
                    return 1;
                }))));
        if (SelfTest.enabled()) {
            root.then(Cmd.lit("utilstest").requires(src -> src.hasPermission(3))
                    .executes(ctx -> SelfTest.run(ctx.getSource()))
                    .then(Cmd.lit("persisted").executes(ctx -> SelfTest.persisted(ctx.getSource())))
                    .then(Cmd.lit("report").executes(ctx -> SelfTest.report(ctx.getSource()))));
        }
        return root;
    }

    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> TIERS = (c, b) ->
            SharedSuggestionProvider.suggest(Cfg.boss.tiers.keySet(), b);
    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> FORMS = (c, b) ->
            SharedSuggestionProvider.suggest(java.util.List.of("auto", "base", "mega", "primal", "origin"), b);
    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> SPECIES = (c, b) -> {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add("*");
        String rem = b.getRemaining().toLowerCase(Locale.ROOT);
        for (var sp : com.cobblemon.mod.common.api.pokemon.PokemonSpecies.getImplemented()) {
            String n = sp.getResourceIdentifier().getPath();
            if (n.startsWith(rem)) out.add(n);
            if (out.size() > 60) break;
        }
        return SharedSuggestionProvider.suggest(out, b);
    };

    public static int spawnBoss(CommandSourceStack src, String tier, String species, String form, boolean fight) {
        if (!Cfg.boss.tiers.containsKey(tier)) {
            Msg.send(src, "boss.unknown_tier", "tier", tier, "tiers", String.join(", ", Cfg.boss.tiers.keySet()));
            return 0;
        }
        if (!src.isPlayer()) {
            Msg.send(src, "general.players_only");
            return 0;
        }
        String f = form == null ? "auto" : form.toLowerCase(Locale.ROOT);
        if (!java.util.List.of("auto", "base", "mega", "primal", "origin").contains(f)) {
            Msg.send(src, "boss.unknown_form", "form", form);
            return 0;
        }
        ServerPlayer p = src.getPlayer();
        // Mega / Primo demande sur un palier ordinaire : Mega-Boss du palier correspondant
        boolean wantsMega = f.equals("mega") || f.equals("primal");
        String target = wantsMega ? Bosses.megaTierFor(tier) : tier;
        if (target == null) target = tier;
        if (Bosses.isMegaTier(target) && species != null && !species.isBlank() && !species.equals("*")) {
            var sp = fr.cobblepulse.utils.pokemon.SpeciesLookup.resolve(species);
            if (sp != null && Bosses.megaForm(sp) == null) {
                src.sendSystemMessage(Msg.withComponent("boss.no_mega_form", "pokemon", fr.cobblepulse.utils.pokemon.SpeciesNames.of(sp)));
                return 0;
            }
        }
        var e = Bosses.spawnAt(p, tier, species, f, 3);
        if (e == null) {
            Msg.send(src, "boss.spawn_failed");
            return 0;
        }
        var t = Cfg.boss.tiers.get(Bosses.tierOf(e));
        if (!target.equals(tier)) Msg.send(src, "boss.mega_tier_used", "tier", t.color + t.name);
        String formName = e.getPokemon().getForm() == e.getPokemon().getSpecies().getStandardForm() ? "" : " &d(" + fr.cobblepulse.utils.boss.Bosses.formLabel(e.getPokemon().getForm().getName()) + ")";
        src.sendSystemMessage(Msg.withComponent("boss.spawned", "pokemon", fr.cobblepulse.utils.pokemon.SpeciesNames.of(e.getPokemon().getSpecies()).copy()
                .append(fr.cobblepulse.utils.core.TextUtil.legacy(formName)), "tier", t.color + t.name, "level", e.getPokemon().getLevel()));
        if (!f.equals("auto") && !f.equals("base") && formName.isEmpty()) Msg.send(src, "boss.form_unavailable", "form", f);
        if (fight) {
            String err = Bosses.startBattle(p, e);
            if (err != null) Msg.send(src, err);
        }
        return 1;
    }

    /** Un Boss de chaque palier, en arc de cercle devant le staff. */
    public static int spawnAllTiers(CommandSourceStack src) {
        if (!src.isPlayer()) {
            Msg.send(src, "general.players_only");
            return 0;
        }
        ServerPlayer p = src.getPlayer();
        float yaw = p.getYRot();
        int i = 0;
        java.util.List<String> done = new java.util.ArrayList<>();
        for (String tier : Cfg.boss.tiers.keySet()) {
            p.setYRot(yaw - 60 + i * (120f / Math.max(1, Cfg.boss.tiers.size() - 1)));
            var e = Bosses.spawnAt(p, tier, null, "auto", 6);
            if (e != null) done.add(Cfg.boss.tiers.get(tier).color + Cfg.boss.tiers.get(tier).name);
            i++;
        }
        p.setYRot(yaw);
        Msg.send(src, "boss.spawned_all", "count", done.size(), "tiers", String.join("&7, ", done));
        return done.size();
    }

    private static int testAlert(CommandSourceStack src, String cat, String species, boolean onlyMe) {
        if (!src.isPlayer()) {
            Msg.send(src, "general.players_only");
            return 0;
        }
        return fr.cobblepulse.utils.alerts.SpawnAlerts.test(src.getPlayer(), cat, species, onlyMe) != null ? 1 : 0;
    }
}
