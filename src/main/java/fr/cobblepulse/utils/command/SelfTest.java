package fr.cobblepulse.utils.command;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.CobblemonEntities;
import com.cobblemon.mod.common.api.pokedex.PokedexManager;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.tree.CommandNode;
import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.alerts.SpawnAlerts;
import fr.cobblepulse.utils.boss.Bosses;
import fr.cobblepulse.utils.config.BossConfig;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.config.DexConfig;
import fr.cobblepulse.utils.config.KitsConfig;
import fr.cobblepulse.utils.config.Reward;
import fr.cobblepulse.utils.config.ShopConfig;
import fr.cobblepulse.utils.core.ItemSerial;
import fr.cobblepulse.utils.core.Loc;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.core.Players;
import fr.cobblepulse.utils.core.Scheduler;
import fr.cobblepulse.utils.data.PlayerData;
import fr.cobblepulse.utils.data.PlayerStore;
import fr.cobblepulse.utils.dex.DexRewards;
import fr.cobblepulse.utils.enderchest.Enderchests;
import fr.cobblepulse.utils.essentials.ChatFeatures;
import fr.cobblepulse.utils.essentials.EssentialsCommands;
import fr.cobblepulse.utils.essentials.Vanish;
import fr.cobblepulse.utils.gui.Screens;
import fr.cobblepulse.utils.teleport.Worlds;
import fr.cobblepulse.utils.shop.MonSpecParser;
import fr.cobblepulse.utils.core.TextUtil;
import fr.cobblepulse.utils.staff.StaffPanel;
import fr.cobblepulse.utils.boss.BossEditor;
import fr.cobblepulse.utils.shop.ShopMenus;
import fr.cobblepulse.utils.hub.Hub;
import fr.cobblepulse.utils.integration.Economy;
import fr.cobblepulse.utils.integration.LuckPermsBridge;
import fr.cobblepulse.utils.kits.Kits;
import fr.cobblepulse.utils.market.Gts;
import fr.cobblepulse.utils.market.Mailbox;
import fr.cobblepulse.utils.market.Sts;
import fr.cobblepulse.utils.market.WonderTrade;
import fr.cobblepulse.utils.pokemon.PokemonText;
import fr.cobblepulse.utils.shop.ShopService;
import fr.cobblepulse.utils.storage.Db;
import fr.cobblepulse.utils.teleport.HomeLimits;
import fr.cobblepulse.utils.teleport.Teleports;
import fr.cobblepulse.utils.teleport.Tpa;
import fr.cobblepulse.utils.teleport.WarpMenus;
import fr.cobblepulse.utils.wiki.WikiService;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Auto-test (CI, -Dcobblepulse_utils.selftest=true) : vrais joueurs factices, vraie base (SQLite / MySQL), vraie
 * economie CobbleDollars, vrai LuckPerms. /cp utilstest (1er demarrage) puis /cp utilstest persisted (apres redemarrage).
 */
public final class SelfTest {
    private static final List<String> REPORT = new ArrayList<>();
    private static int pass;
    private static int fail;
    private static FakePlayer a;
    private static FakePlayer b;
    private static FakePlayer c;
    private static FakePlayer staff;

    private SelfTest() {}

    public static boolean enabled() {
        return "true".equals(System.getProperty("cobblepulse_utils.selftest"));
    }

    private static void check(String name, boolean ok, Object detail) {
        String d = detail == null ? "" : String.valueOf(detail);
        String line = (ok ? "PASS " : "FAIL ") + name + (d.isEmpty() ? "" : " - " + d);
        REPORT.add(line);
        if (ok) pass++;
        else fail++;
        CobbleUtils.LOG.info("[CobblePulse-Utils][selftest] {}", line);
    }

    private static void info(String s) {
        REPORT.add("INFO " + s);
        CobbleUtils.LOG.info("[CobblePulse-Utils][selftest] INFO {}", s);
    }

    private interface Step {
        void run() throws Exception;
    }

    private static void safe(String name, Step s) {
        try {
            s.run();
        } catch (Throwable t) {
            check(name + "_exception", false, t);
            CobbleUtils.LOG.error("[CobblePulse-Utils][selftest] {}", name, t);
        }
    }

    private static UUID uuid(String n) {
        return UUID.nameUUIDFromBytes(("cpu-" + n).getBytes(StandardCharsets.UTF_8));
    }

    private static FakePlayer fake(ServerLevel level, String name) {
        FakePlayer fp = FakePlayer.get(level, new GameProfile(uuid(name), name));
        Players.registerTest(fp);
        registerInPlayerList(level.getServer(), fp);
        fp.moveTo(0.5, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0), 0.5);
        LuckPermsBridge.ensureLoaded(fp.getUUID(), name);
        // noeuds laisses par une execution precedente (LuckPerms garde ses donnees entre les runs SQLite et MySQL)
        LuckPermsBridge.clearUserNodes(fp.getUUID(), "cobblepulse.");
        PlayerStore.onJoin(fp);
        return fp;
    }

    @SuppressWarnings("unchecked")
    private static void registerInPlayerList(MinecraftServer server, ServerPlayer p) {
        try {
            for (java.lang.reflect.Field f : net.minecraft.server.players.PlayerList.class.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                if (!(f.getGenericType() instanceof java.lang.reflect.ParameterizedType pt)) continue;
                java.lang.reflect.Type[] args = pt.getActualTypeArguments();
                if (args.length == 2 && args[0] == UUID.class && args[1] == ServerPlayer.class) {
                    f.setAccessible(true);
                    ((Map<UUID, ServerPlayer>) f.get(server.getPlayerList())).put(p.getUUID(), p);
                    return;
                }
            }
        } catch (Throwable t) {
            CobbleUtils.LOG.warn("[CobblePulse-Utils][selftest] index des joueurs : {}", t.toString());
        }
    }

    private static int count(ServerPlayer p, Item item) {
        int n = 0;
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(item)) n += inv.getItem(i).getCount();
        return n;
    }

    private static Pokemon mon(String species, int level, boolean shiny) {
        PokemonProperties pp = PokemonProperties.Companion.parse("level=" + level + " shiny=" + (shiny ? "yes" : "no"));
        pp.setSpecies("cobblemon:" + species);
        return pp.create();
    }

    private static void clearParty(ServerPlayer p) {
        var party = PokemonText.party(p);
        List<Pokemon> all = new ArrayList<>();
        for (Pokemon pk : party) if (pk != null) all.add(pk);
        for (Pokemon pk : all) party.remove(pk);
    }

    private static boolean cmd(MinecraftServer s, CommandSourceStack src, String c) {
        try {
            s.getCommands().getDispatcher().execute(c, src);
            return true;
        } catch (Exception e) {
            info("commande /" + c + " : " + e.getMessage());
            return false;
        }
    }

    private static int sqlCount(Db.Source db, String sql, Object... args) {
        try {
            return db.run(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    Db.bind(ps, args);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getInt(1) : -1;
                    }
                }
            });
        } catch (Exception e) {
            return -2;
        }
    }

    // ================================================================== 1er demarrage

    public static int run(CommandSourceStack src) {
        REPORT.clear();
        pass = 0;
        fail = 0;
        MinecraftServer s = src.getServer();
        ServerLevel level = s.overworld();
        info("stockage : " + Db.main().description() + " | economie : " + Economy.name() + " | LuckPerms : " + LuckPermsBridge.present());
        a = fake(level, "TestA");
        b = fake(level, "TestB");
        c = fake(level, "TestC");
        staff = fake(level, "TestStaff");
        s.getPlayerList().op(staff.getGameProfile());
        Economy.setTestBalance(a.getUUID(), 100_000);
        Economy.setTestBalance(c.getUUID(), 100_000);
        Economy.useRealBackend(b.getUUID());

        safe("config", () -> configChecks());
        safe("commandes", () -> commandChecks(s));
        safe("teleport", () -> teleportChecks(level));
        safe("kits", () -> kitChecks());
        safe("enderchest", () -> enderchestChecks());
        safe("essentials", () -> essentialsChecks(s));
        safe("hub", () -> hubChecks());
        safe("shop", () -> shopChecks());
        safe("wiki", () -> wikiChecks());
        safe("alertes", () -> alertChecks(level));
        safe("boss", () -> bossChecks(level));
        safe("dex", () -> dexChecks());
        // operations asynchrones (base de donnees) : enchainees
        Scheduler.later(40, () -> safe("gts", SelfTest::gtsPhase1));
        return 1;
    }

    private static void configChecks() {
        check("fichier_database_json", Files.exists(Cfg.root().resolve("database.json")), Cfg.database.storageType);
        for (String f : new String[]{"general.json", "commands.json", "messages.toml", "warps.json", "kits.json", "dexrewards.json",
                "shop.json", "gts.json", "sts.json", "wondertrade.json", "alerts.json", "boss.json"}) {
            check("fichier_" + f, Files.exists(Cfg.utilsRoot().resolve(f)), null);
        }
        check("shop_categories_exemple", Cfg.SHOP.size() >= 4, Cfg.SHOP.keySet());
        List<String> bad = new ArrayList<>();
        for (ShopConfig.Category cat : Cfg.SHOP.values()) {
            for (ShopConfig.Entry e : cat.entries) if ("item".equals(e.type) && ShopService.stack(e).isEmpty()) bad.add(cat.id + "/" + e.id);
        }
        check("shop_objets_valides", bad.isEmpty(), bad);
        bad.clear();
        for (KitsConfig.Kit k : Cfg.kits.kits) for (String it : k.items) if (ItemSerial.fromSnbt(it).isEmpty()) bad.add(k.name + ":" + it);
        check("kits_objets_valides", bad.isEmpty(), bad);
        bad.clear();
        for (BossConfig.Tier t : Cfg.boss.tiers.values()) for (Reward r : t.loot) if ("item".equals(r.type) && ItemSerial.fromSnbt(r.item).isEmpty()) bad.add(t.name + ":" + r.item);
        for (DexConfig.Tier t : Cfg.dex.tiers) for (Reward r : t.rewards) if ("item".equals(r.type) && ItemSerial.fromSnbt(r.item).isEmpty()) bad.add(t.id + ":" + r.item);
        check("boss_dex_objets_valides", bad.isEmpty(), bad);
        check("boss_6_paliers", Cfg.boss.tiers.keySet().containsAll(BossConfig.TIER_ORDER), Cfg.boss.tiers.keySet());
        check("messages_charges", !Msg.raw("hub.title").equals("hub.title") && !Msg.raw("boss.victory").equals("boss.victory"), null);
        check("economie_cobbledollars", Economy.real() && "CobbleDollars".equals(Economy.name()), Economy.name());
    }

    private static void commandChecks(MinecraftServer s) {
        var root = s.getCommands().getDispatcher().getRoot();
        List<String> missing = new ArrayList<>();
        for (String n : new String[]{"menu", "cp", "cobblepulse", "wiki", "pwiki", "pokedex", "warp", "warps", "home", "homes", "shop", "boutique", "gts", "ah",
                "hdv", "sts", "sellpoke", "wondertrade", "wt", "miracle", "dex", "dexrewards", "dexr", "kit", "kits", "enderchest", "ec", "spawns", "radar",
                "history", "spawn", "sethome", "delhome", "remhome", "tpa", "tpahere", "tpaccept", "tpyes", "tpdeny", "tpno", "tptoggle", "back", "return",
                "rtp", "wild", "top", "jump", "j", "pokeshout", "ps", "pokeshoutall", "psa", "pokeheal", "pcheal", "pc", "pokebox", "fly", "heal", "feed",
                "eat", "hat", "trash", "bin", "poubelle", "repair", "fix", "craft", "wb", "workbench", "anvil", "nick", "msg", "w", "tell", "m", "reply", "r",
                "ignore", "list", "online", "who", "playtime", "pt", "sell", "balance", "bal", "money", "pay", "setspawn", "setwarp", "delwarp", "invsee",
                "endersee", "ecsee", "tp", "tphere", "s", "tpall", "vanish", "v", "god", "tgm", "gamemode", "gm", "speed", "broadcast", "bc", "alert", "sudo",
                "mute", "unmute", "kick", "ban", "tempban"}) {
            if (root.getChild(n) == null) missing.add(n);
        }
        check("commandes_cahier_des_charges", missing.isEmpty(), missing.isEmpty() ? "toutes presentes" : missing);
        CommandNode<CommandSourceStack> cp = root.getChild("cp");
        check("cp_sous_commandes", cp != null && cp.getChild("admin") != null && cp.getChild("reload") != null && cp.getChild("boss") != null
                && cp.getChild("gts") != null && cp.getChild("sts") != null && cp.getChild("shop") != null, null);
        if (FabricLoader.getInstance().isModLoaded("cobblepulse")) {
            CommandNode<CommandSourceStack> cpl = root.getChild("cobblepulse");
            check("cp_fusion_mod_clans", cpl != null && cpl.getChild("season") != null && cpl.getChild("payinfluence") != null, "sous-commandes du mod Clans conservees");
        }
        CommandNode<CommandSourceStack> pd = root.getChild("pokedex");
        check("pokedex_fusion_cobblemon", pd != null && pd.getChildren().size() > 1, pd == null ? "absent" : pd.getChildren().size() + " sous-noeuds");
    }

    private static void teleportChecks(ServerLevel level) {
        int lim = HomeLimits.limit(a);
        check("homes_limite_defaut", lim == Cfg.general.teleport.homeDefaultLimit, lim);
        a.moveTo(10.5, 80, 10.5);
        boolean h1 = HomeLimits.setHome(a, "maison");
        a.moveTo(20.5, 80, 20.5);
        boolean h2 = HomeLimits.setHome(a, "mine");
        a.moveTo(30.5, 80, 30.5);
        boolean h3 = HomeLimits.setHome(a, "ferme");
        boolean h4 = HomeLimits.setHome(a, "quatrieme");
        check("homes_quota", h1 && h2 && h3 && !h4, PlayerStore.get(a).homes.keySet());
        LuckPermsBridge.setUserNode(a.getUUID(), "cobblepulse.home.limit.5", true);
        check("homes_quota_luckperms", HomeLimits.limit(a) == 5, HomeLimits.limit(a));
        check("homes_quota_luckperms_ajout", HomeLimits.setHome(a, "quatrieme"), null);
        // warps
        staff.moveTo(100.5, 90, 100.5);
        var w = WarpMenus.createAt(staff, "arene");
        check("warp_cree", w != null && Cfg.warps.find("arene") != null, null);
        w.restricted = true;
        Cfg.save("warps.json", Cfg.warps);
        check("warp_prive_cache", !WarpMenus.canUse(a, w) && WarpMenus.visible(a).isEmpty(), null);
        LuckPermsBridge.setUserNode(a.getUUID(), "cobblepulse.warp.arene", true);
        check("warp_prive_permission", WarpMenus.canUse(a, w), null);
        // teleportation immediate + /back
        a.moveTo(5.5, 90, 5.5);
        Loc before = Loc.of(a);
        Teleports.perform(a, w.loc, "test", null);
        check("teleport_warp", Math.abs(a.getX() - 100.5) < 0.01, a.position());
        PlayerData d = PlayerStore.get(a);
        check("back_memorise", d.back != null && Math.abs(d.back.x - before.x) < 0.01, null);
        // spawn
        Cfg.warps.spawn = new Loc("minecraft:overworld", 0.5, 100, 0.5, 0, 0);
        check("spawn_defini", Teleports.spawn().y == 100, null);
        // TPA (le delai de 3 s est verifie plus tard)
        b.moveTo(-50.5, 95, -50.5);
        c.moveTo(60.5, 95, 60.5);
        int sent = Tpa.request(c, b, false);
        int acc = Tpa.accept(b, "TestC");
        check("tpa_demande_acceptee", sent == 1 && acc == 1, null);
        // tptoggle
        PlayerStore.get(b).tpToggle = true;
        check("tptoggle_refuse", Tpa.request(a, b, false) == 0, null);
        PlayerStore.get(b).tpToggle = false;
        Scheduler.later(100, () -> check("tpa_teleporte_apres_delai", Math.abs(c.getX() - (-50.5)) < 0.01, c.position()));
    }

    private static void kitChecks() {
        KitsConfig.Kit start = Cfg.kits.find("depart");
        KitsConfig.Kit vip = Cfg.kits.find("vip");
        a.getInventory().clearContent();
        boolean ok = Kits.claim(a, start);
        check("kit_depart_recupere", ok && count(a, BuiltInRegistries.ITEM.get(ResourceLocation.parse("cobblemon:poke_ball"))) == 16, null);
        check("kit_usage_unique", !Kits.claim(a, start), null);
        KitsConfig.Kit daily = Cfg.kits.find("quotidien");
        check("kit_quotidien", Kits.claim(a, daily) && !Kits.claim(a, daily), PlayerStore.get(a).cooldowns.keySet());
        check("kit_vip_refuse_sans_permission", !Kits.canUse(a, vip), null);
        LuckPermsBridge.setUserNode(a.getUUID(), "cobblepulse.kit.vip", true);
        check("kit_vip_permission", Kits.canUse(a, vip) && Kits.claim(a, vip), null);
        LuckPermsBridge.setUserNode(a.getUUID(), "cobblepulse.kit.bypasscooldown", true);
        check("kit_bypass_delai", Kits.claim(a, daily), null);
        // kit edite avec NBT (objet enchante)
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Lame de test"));
        KitsConfig.Kit k = new KitsConfig.Kit();
        k.name = "testnbt";
        k.defaultAccess = true;
        k.cooldownSeconds = 0;
        k.items.add(ItemSerial.toSnbt(sword));
        Cfg.kits.kits.add(k);
        b.getInventory().clearContent();
        Kits.claim(b, k);
        ItemStack got = b.getInventory().getItem(0);
        check("kit_nbt_conserve", got.is(Items.DIAMOND_SWORD) && got.getHoverName().getString().equals("Lame de test"), got.getHoverName().getString());
        Cfg.kits.kits.remove(k);
    }

    private static void enderchestChecks() {
        check("ec_lignes_defaut", Enderchests.rows(a) == Cfg.general.enderchest.defaultRows, Enderchests.rows(a));
        LuckPermsBridge.setUserNode(a.getUUID(), "cobblepulse.ec.rows.6", true);
        LuckPermsBridge.setUserNode(a.getUUID(), "cobblepulse.ec.pages.3", true);
        check("ec_lignes_pages_luckperms", Enderchests.rows(a) == 6 && Enderchests.pages(a) == 3, Enderchests.rows(a) + " lignes, " + Enderchests.pages(a) + " pages");
        Enderchests.testPut(a.getUUID(), 0, 0, new ItemStack(Items.DIAMOND, 7));
        Enderchests.testPut(a.getUUID(), 2, 53, new ItemStack(Items.EMERALD, 3));
        Scheduler.later(60, () -> {
            List<ItemStack> p0 = Enderchests.peek(a.getUUID(), 0);
            List<ItemStack> p2 = Enderchests.peek(a.getUUID(), 2);
            check("ec_sauvegarde_base", p0.size() == 54 && p0.get(0).is(Items.DIAMOND) && p0.get(0).getCount() == 7
                    && p2.size() == 54 && p2.get(53).is(Items.EMERALD), null);
        });
    }

    private static void essentialsChecks(MinecraftServer s) {
        CommandSourceStack console = s.createCommandSourceStack();
        // nick
        ChatFeatures.nick(staff, a, "&bAlpha");
        check("nick_affiche", a.getDisplayName().getString().contains("Alpha"), a.getDisplayName().getString());
        // mute
        cmd(s, console, "mute TestA 10m spam");
        check("mute_bloque_chat", !ChatFeatures.allowChat(a, "bonjour"), null);
        cmd(s, console, "unmute TestA");
        check("unmute", ChatFeatures.allowChat(a, "bonjour"), null);
        // ignore
        PlayerStore.get(a).ignored.add(b.getUUID());
        check("ignore_chat_public", ChatFeatures.ignores(a, b.getUUID()), null);
        PlayerStore.get(a).ignored.clear();
        // fly / heal / feed par la console
        a.setHealth(5f);
        a.getFoodData().setFoodLevel(3);
        LuckPermsBridge.setUserNode(a.getUUID(), "cobblepulse.command.fly", true);
        LuckPermsBridge.setUserNode(a.getUUID(), "cobblepulse.command.heal", true);
        cmd(s, a.createCommandSourceStack(), "fly");
        cmd(s, a.createCommandSourceStack(), "heal");
        check("fly_heal_permission_luckperms", a.getAbilities().mayfly && a.getHealth() >= a.getMaxHealth() && a.getFoodData().getFoodLevel() == 20, null);
        cmd(s, a.createCommandSourceStack(), "fly");
        check("fly_desactive", !a.getAbilities().mayfly, null);
        // repair : permission LuckPerms du joueur
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        pick.setDamageValue(100);
        a.getInventory().setItem(a.getInventory().selected, pick);
        boolean denied = !Perms.command(a, "repair");
        LuckPermsBridge.setUserNode(a.getUUID(), "cobblepulse.command.repair", true);
        boolean ran = cmd(s, a.createCommandSourceStack(), "repair");
        check("repair_permission_luckperms", denied && ran && a.getMainHandItem().getDamageValue() == 0, a.getMainHandItem().getDamageValue());
        // god, vanish
        EssentialsCommands.isGod(staff);
        PlayerStore.get(staff).god = true;
        check("god", EssentialsCommands.isGod(staff), null);
        PlayerStore.get(staff).god = false;
        Vanish.set(staff, true);
        check("vanish_cache_aux_joueurs", Vanish.hiddenFrom(staff, a) && !Vanish.hiddenFrom(staff, staff), null);
        Vanish.set(staff, false);
        // tempban
        GameProfile gp = new GameProfile(uuid("BanTest"), "BanTest");
        s.getProfileCache().add(gp);
        cmd(s, console, "tempban BanTest 2h triche");
        var entry = s.getPlayerList().getBans().get(gp);
        check("tempban_expiration", entry != null && entry.getExpires() != null && entry.getExpires().getTime() > System.currentTimeMillis(), null);
        s.getPlayerList().getBans().remove(gp);
        // solde d'un autre joueur (nom hors ligne / factice)
        check("balance_autre_joueur", cmd(s, console, "balance TestC"), null);
        // pokeshout / pokeheal : equipe
        clearParty(a);
        Pokemon pk = mon("pikachu", 25, false);
        PokemonText.party(a).add(pk);
        check("pokeshout_texte", PokemonText.hover(pk).getString().contains("IV"), null);
    }

    private static void hubChecks() {
        List<String> ids = new ArrayList<>();
        for (Hub.Tile t : Hub.tiles(a)) ids.add(t.id);
        check("hub_tuiles_joueur", ids.containsAll(List.of("wiki", "warps", "shop", "gts", "sts", "wondertrade", "kits", "enderchest", "dex", "spawns")) && !ids.contains("staff"), ids);
        List<String> st = new ArrayList<>();
        for (Hub.Tile t : Hub.tiles(staff)) st.add(t.id);
        check("hub_panneau_staff", st.contains("staff"), null);
        boolean tower = !FabricLoader.getInstance().isModLoaded("cobblepulse_battletower") || ids.contains("gw:battletower");
        boolean crates = !FabricLoader.getInstance().isModLoaded("cobblepulse_crates") || (ids.contains("gw:crates") && ids.contains("gw:voeux_adn"));
        boolean clans = !FabricLoader.getInstance().isModLoaded("cobblepulse") || ids.contains("gw:clans");
        check("hub_passerelles", tower && crates && clans, ids);
        check("hub_passerelle_absente_masquee", FabricLoader.getInstance().isModLoaded("cobblepulse_battlepass") || !ids.contains("gw:battlepass"), null);
    }

    private static void shopChecks() {
        ShopConfig.Category balls = Cfg.SHOP.get("pokeballs");
        ShopConfig.Entry pb = balls.entries.get(0);
        // B : vraie economie CobbleDollars
        Economy.deposit(b.getUUID(), 50_000);
        long before = Economy.balance(b.getUUID());
        check("cobbledollars_depot", before >= 50_000, before);
        b.getInventory().clearContent();
        boolean bought = ShopService.buy(b, balls, pb, 10);
        long after = Economy.balance(b.getUUID());
        check("shop_achat_groupe", bought && after == before - 10 * pb.buy && count(b, ShopService.stack(pb).getItem()) == 10, before + " -> " + after);
        long sold = ShopService.sell(b, balls, pb, 4, true);
        check("shop_vente_groupee", sold == 4 * pb.sell && count(b, ShopService.stack(pb).getItem()) == 6, sold);
        b.getInventory().setItem(b.getInventory().selected, new ItemStack(ShopService.stack(pb).getItem(), 6));
        long bal = Economy.balance(b.getUUID());
        ShopService.sellCommand(b, false);
        check("sell_hand", Economy.balance(b.getUUID()) > bal, Economy.balance(b.getUUID()) - bal);
        // promotions et reduction de grade
        balls.discount = 50;
        long promo = ShopService.unitBuy(b, balls, pb);
        balls.discount = 0;
        LuckPermsBridge.setUserNode(b.getUUID(), "cobblepulse.shop.discount.10", true);
        long perso = ShopService.unitBuy(b, balls, pb);
        check("shop_promo_et_reduction_grade", promo == pb.buy / 2 && perso == Math.round(pb.buy * 0.9), promo + " / " + perso);
        // categorie fermee
        balls.enabled = false;
        check("shop_categorie_fermee", !ShopService.buy(b, balls, pb, 1), null);
        balls.enabled = true;
        // Pokemon personnalise et commande
        ShopConfig.Category special = Cfg.SHOP.get("speciaux");
        ShopConfig.Entry eevee = special.entries.stream().filter(e -> "pokemon".equals(e.type)).findFirst().orElse(null);
        clearParty(b);
        Economy.deposit(b.getUUID(), 300_000);
        boolean mon = eevee != null && ShopService.buy(b, special, eevee, 1);
        Pokemon got = PokemonText.party(b).get(0);
        check("shop_pokemon_personnalise", mon && got != null && got.getShiny() && PokemonText.perfectIvs(got) == 6 && got.getLevel() == 10,
                got == null ? "aucun" : PokemonText.title(got).getString());
        ShopConfig.Entry xp = special.entries.stream().filter(e -> "command".equals(e.type)).findFirst().orElse(null);
        long beforeCmd = Economy.balance(b.getUUID());
        long unit = xp == null ? -1 : ShopService.unitBuy(b, special, xp);
        boolean cmdOk = xp != null && ShopService.buy(b, special, xp, 1);
        check("shop_commande", cmdOk && Economy.balance(b.getUUID()) == beforeCmd - unit, beforeCmd - Economy.balance(b.getUUID()));
        check("shop_fonds_insuffisants", !ShopService.buy(c, special, eevee, 1), null);
    }

    private static void wikiChecks() {
        Species charmander = PokemonSpecies.getByIdentifier(ResourceLocation.parse("cobblemon:charmander"));
        JsonObject d = WikiService.spawnData(charmander);
        JsonArray spawns = d.getAsJsonArray("spawns");
        int biomes = 0;
        double maxPct = 0;
        for (var e : spawns) {
            for (var m : e.getAsJsonObject().getAsJsonObject("biomes").entrySet()) {
                for (var bb : m.getValue().getAsJsonArray()) {
                    biomes++;
                    maxPct = Math.max(maxPct, bb.getAsJsonObject().get("percent").getAsDouble());
                }
            }
        }
        check("wiki_spawns_biomes", spawns.size() > 0 && biomes > 0 && maxPct > 0, spawns.size() + " entrees, " + biomes + " biomes, max " + maxPct + " %");
        String mods = spawns.size() > 0 ? spawns.get(0).getAsJsonObject().getAsJsonObject("biomes").keySet().toString() : "";
        check("wiki_biomes_par_mod", mods.contains("Minecraft"), mods);
        try {
            java.lang.reflect.Method build = Class.forName("fr.cobblepulse.utils.wiki.SpeciesInfo").getDeclaredMethod("build", Species.class);
            build.setAccessible(true);
            JsonObject info = (JsonObject) build.invoke(null, charmander);
            JsonObject base = info.getAsJsonObject("base");
            boolean evo = base.getAsJsonArray("evolutions").size() > 0;
            boolean hidden = false;
            for (var ab : base.getAsJsonArray("abilities")) if (ab.getAsJsonObject().get("hidden").getAsBoolean()) hidden = true;
            boolean moves = base.getAsJsonObject("moves").getAsJsonArray("level").size() > 5;
            check("wiki_fiche_technique", evo && hidden && moves && info.get("dex").getAsInt() == 4, "evolutions " + evo + ", talent cache " + hidden + ", capacites " + moves);
        } catch (Throwable t) {
            check("wiki_fiche_technique", false, t);
        }
    }

    private static PokemonEntity wild(ServerLevel level, String species, boolean shiny, double dx, double dz) {
        Pokemon pk = mon(species, 30, shiny);
        PokemonEntity e = new PokemonEntity(level, pk, CobblemonEntities.POKEMON);
        var sp = level.getSharedSpawnPos();
        int x = sp.getX() + (int) dx;
        int z = sp.getZ() + (int) dz;
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
        e.moveTo(x + 0.5, y, z + 0.5, 0, 0);
        level.addFreshEntity(e);
        return e;
    }

    private static void alertChecks(ServerLevel level) {
        PokemonEntity e1 = wild(level, "eevee", true, 3, 3);
        check("alertes_categories", SpawnAlerts.categories(e1.getPokemon()).contains("shiny"), null);
        SpawnAlerts.record(e1, SpawnAlerts.categories(e1.getPokemon()));
        var h = SpawnAlerts.history("shiny");
        check("alertes_historique", !h.isEmpty() && "ACTIVE".equals(h.get(0).status) && h.get(0).biome != null, h.isEmpty() ? "vide" : h.get(0).biome);
        PokemonEntity e2 = wild(level, "mewtwo", false, 6, 6);
        SpawnAlerts.record(e2, SpawnAlerts.categories(e2.getPokemon()));
        SpawnAlerts.testCapture(e2.getPokemon(), a);
        e2.discard();
        e1.discard();
        Scheduler.later(80, () -> {
            var sh = SpawnAlerts.history("shiny");
            var lg = SpawnAlerts.history("legendary");
            check("alertes_statut_despawn", !sh.isEmpty() && "DESPAWN".equals(sh.get(0).status), sh.isEmpty() ? "-" : sh.get(0).status);
            check("alertes_statut_capture", !lg.isEmpty() && "CAPTURED".equals(lg.get(0).status) && "TestA".equals(lg.get(0).captor),
                    lg.isEmpty() ? "-" : lg.get(0).status + " " + lg.get(0).captor);
        });
        // un Boss n'est jamais annonce
        PokemonEntity e3 = wild(level, "eevee", true, 9, 9);
        Bosses.promote(e3, "rare");
        int before = SpawnAlerts.history("shiny").size();
        check("alertes_boss_exclus", Bosses.isBoss(e3), null);
        Scheduler.next(() -> {
            check("alertes_boss_non_enregistre", SpawnAlerts.history("shiny").size() == before, null);
            Bosses.despawn(e3, false);
        });
    }

    private static void bossChecks(ServerLevel level) {
        var sp = level.getSharedSpawnPos();
        staff.moveTo(sp.getX() + 4.5, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, sp.getX() + 4, sp.getZ() + 4), sp.getZ() + 4.5);
        PokemonEntity boss = Bosses.spawnAt(staff, "ultime", "charizard");
        check("boss_apparu", boss != null && Bosses.isBoss(boss), null);
        if (boss == null) return;
        Pokemon pk = boss.getPokemon();
        check("boss_niveau_superieur_100", pk.getLevel() >= 110 && pk.getLevel() <= 140, pk.getLevel());
        check("boss_incapturable", pk.isUncatchable(), null);
        check("boss_ivs_parfaits", PokemonText.perfectIvs(pk) >= 6, PokemonText.perfectIvs(pk));
        check("boss_nom_palier", boss.getCustomName() != null && boss.getCustomName().getString().contains("Ultime"), boss.getCustomName() == null ? "-" : boss.getCustomName().getString());
        float hp = boss.getHealth();
        boss.hurt(level.damageSources().playerAttack(a), 50f);
        boss.hurt(level.damageSources().explosion(null, null), 50f);
        boss.hurt(level.damageSources().fall(), 50f);
        check("boss_invulnerable_physique", boss.getHealth() == hp && !boss.isRemoved(), boss.getHealth() + " / " + hp);
        check("boss_objet_forme_config", Cfg.boss.formItems.containsKey("kyogre:primal"), null);
        // butin + suivi de la 1re victoire
        BossConfig.Tier t = Cfg.boss.tiers.get("ultime");
        long before = Economy.balance(a.getUUID());
        Bosses.reward(a, t, "ultime", "charizard:mega_x", "Dracaufeu");
        Bosses.reward(a, t, "ultime", "charizard:mega_x", "Dracaufeu");
        Scheduler.later(60, () -> {
            long gained = Economy.balance(a.getUUID()) - before;
            check("boss_butin_argent", gained >= 2 * 25_000, gained);
            int wins = sqlCount(Db.of("boss_first_victories"), "SELECT wins FROM " + Db.of("boss_first_victories").table("boss_victories")
                    + " WHERE uuid=? AND boss_key=?", a.getUUID().toString(), "charizard:mega_x");
            check("boss_premieres_victoires_base", wins == 2, wins);
        });
        int cleared = Bosses.clearAll();
        check("boss_clear", cleared >= 1 && boss.isRemoved(), cleared);
        // promotion de spawn naturel (100 %)
        PokemonEntity wildOne = wild(level, "rattata", false, 12, 12);
        Bosses.promote(wildOne, Bosses.pickTier());
        check("boss_promotion_palier", Bosses.isBoss(wildOne) && Cfg.boss.tiers.containsKey(Bosses.tierOf(wildOne)), Bosses.tierOf(wildOne));
        Bosses.despawn(wildOne, false);
    }

    private static void dexChecks() {
        PokedexManager dex = Cobblemon.INSTANCE.getPlayerDataManager().getPokedexData(c);
        int total = DexRewards.total();
        int need = (int) Math.ceil(total * 0.05) + 1;
        int n = 0;
        for (Species sp : PokemonSpecies.getImplemented()) {
            if (n >= need) break;
            try {
                Pokemon pk = new Pokemon();
                pk.setSpecies(sp);
                dex.getClass().getMethod("catch", Pokemon.class).invoke(dex, pk);
                n++;
            } catch (Throwable ignored) {
            }
        }
        int caught = DexRewards.caught(c);
        check("dex_progression", caught >= need - 1, caught + "/" + total);
        DexConfig.Tier p5 = Cfg.dex.tiers.get(0);
        long before = Economy.balance(c.getUUID());
        boolean ok = DexRewards.claim(c, p5);
        check("dex_palier_reclame", ok && Economy.balance(c.getUUID()) > before && !DexRewards.claim(c, p5), null);
        check("dex_palier_verrouille", !DexRewards.claim(c, Cfg.dex.tiers.get(Cfg.dex.tiers.size() - 1)), null);
    }

    // ------------------------------------------------------------------ GTS / STS / Echange Miracle (asynchrone)

    private static void gtsPhase1() {
        // A met en vente 3 diamants a 1000 ; B (CobbleDollars) achete
        a.getInventory().clearContent();
        a.getInventory().setItem(a.getInventory().selected, new ItemStack(Items.DIAMOND, 3));
        Gts.listHand(a, 1000, ok -> {
            check("gts_annonce_objet", ok && a.getMainHandItem().isEmpty(), null);
            Gts.active().thenAccept(list -> Scheduler.next(() -> safe("gts", () -> gtsBuy(list))));
        });
    }

    private static void gtsBuy(List<Gts.Listing> list) {
        Gts.Listing l = list.stream().filter(x -> x.seller().equals(a.getUUID()) && !x.pokemon()).findFirst().orElse(null);
        check("gts_annonce_listee", l != null, list.size());
        if (l == null) {
            gtsPhase2();
            return;
        }
        b.getInventory().clearContent();
        long sellerBefore = Economy.balance(a.getUUID());
        long buyerBefore = Economy.balance(b.getUUID());
        AtomicInteger done = new AtomicInteger();
        AtomicInteger okCount = new AtomicInteger();
        // achat simultane par B et C : une seule vente possible
        for (ServerPlayer buyer : new ServerPlayer[]{b, c}) {
            Gts.buy(buyer, l, ok -> {
                if (ok) okCount.incrementAndGet();
                if (done.incrementAndGet() == 2) {
                    Scheduler.later(60, () -> {
                        boolean bGot = count(b, Items.DIAMOND) == 3;
                        boolean cGot = count(c, Items.DIAMOND) == 3;
                        check("gts_achat_unique_concurrence", okCount.get() == 1 && (bGot ^ cGot), "reussis " + okCount.get() + ", B " + bGot + ", C " + cGot);
                        if (bGot) check("gts_cobbledollars_debite", Economy.balance(b.getUUID()) == buyerBefore - 1000, Economy.balance(b.getUUID()));
                        long expected = 1000 - 1000 * Cfg.gts.defaultTax / 100;
                        check("gts_vendeur_paye_taxe", Economy.balance(a.getUUID()) == sellerBefore + expected,
                                (Economy.balance(a.getUUID()) - sellerBefore) + " / " + expected);
                        gtsPhase2();
                    });
                }
            });
        }
    }

    private static void gtsPhase2() {
        // Pokemon : plancher, vente, achat
        clearParty(a);
        PokemonText.party(a).add(mon("bulbasaur", 12, false));
        PokemonText.party(a).add(mon("squirtle", 14, true));
        Gts.listPokemon(a, 1, 100, okLow -> {
            check("gts_prix_plancher_shiny", !okLow, null);
            Gts.listPokemon(a, 0, 500, ok -> {
                check("gts_annonce_pokemon", ok && PokemonText.party(a).occupied() == 1, null);
                Gts.active().thenAccept(list -> Scheduler.next(() -> safe("gts_pokemon", () -> {
                    Gts.Listing l = list.stream().filter(x -> x.seller().equals(a.getUUID()) && x.pokemon()).findFirst().orElse(null);
                    if (l == null) {
                        check("gts_pokemon_listee", false, null);
                        gtsPhase3();
                        return;
                    }
                    clearParty(c);
                    Gts.buy(c, l, okBuy -> Scheduler.later(40, () -> {
                        Pokemon got = PokemonText.party(c).get(0);
                        check("gts_achat_pokemon", okBuy && got != null && got.getSpecies().getResourceIdentifier().getPath().equals("bulbasaur"),
                                got == null ? "aucun" : got.getSpecies().getName());
                        gtsPhase3();
                    }));
                })));
            });
        });
    }

    private static void gtsPhase3() {
        // limite d'annonces par grade + retrait + expiration
        LuckPermsBridge.setUserNode(a.getUUID(), "cobblepulse.gts.limit.1", true);
        a.getInventory().setItem(a.getInventory().selected, new ItemStack(Items.GOLD_INGOT, 5));
        Gts.listHand(a, 200, ok1 -> {
            a.getInventory().setItem(a.getInventory().selected, new ItemStack(Items.IRON_INGOT, 5));
            Gts.listHand(a, 200, ok2 -> {
                check("gts_limite_grade", ok1 && !ok2, ok1 + "/" + ok2);
                Gts.mine(a.getUUID()).thenAccept(mine -> Scheduler.next(() -> safe("gts_retrait", () -> {
                    if (mine.isEmpty()) {
                        check("gts_retrait", false, "aucune annonce");
                        gtsExpire();
                        return;
                    }
                    a.getInventory().clearContent();
                    Gts.cancel(a, mine.get(0), false, ok -> Scheduler.later(40, () -> {
                        check("gts_retrait_rend_objet", ok && count(a, Items.GOLD_INGOT) == 5, count(a, Items.GOLD_INGOT));
                        gtsExpire();
                    }));
                })));
            });
        });
    }

    private static void gtsExpire() {
        Db.Source db = Db.of("gts");
        String data = ItemSerial.encodeOne(new ItemStack(Items.EMERALD, 2));
        db.txAsync(conn -> Db.insert(conn, "INSERT INTO " + db.table("gts_listings")
                        + " (seller, seller_name, kind, data, summary, price, category, created, expires, status, server, tax) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                a.getUUID().toString(), "TestA", "ITEM", data, "", 50, "items", 0L, 1L, "ACTIVE", "test", 5))
                .thenAccept(id -> Scheduler.next(() -> {
                    Gts.expireTick();
                    Scheduler.later(40, () -> Mailbox.list(a.getUUID()).thenAccept(mails -> Scheduler.next(() -> {
                        boolean found = mails.stream().anyMatch(m -> "ITEM".equals(m.kind()) && ItemSerial.decodeOne(m.data()).is(Items.EMERALD));
                        check("gts_expiration_boite_reception", found, mails.size());
                        a.getInventory().clearContent();
                        Gts.claimAllNow(a, () -> {
                            check("gts_boite_reception_recuperee", count(a, Items.EMERALD) == 2, count(a, Items.EMERALD));
                            stsPhase();
                        });
                    })));
                }));
    }

    private static void stsPhase() {
        clearParty(b);
        PokemonText.party(b).add(mon("rattata", 20, false));
        PokemonText.party(b).add(mon("pidgey", 20, false));
        PokemonText.party(b).add(mon("caterpie", 5, false));
        long price = Sts.price(PokemonText.party(b).get(0));
        long before = Economy.balance(b.getUUID());
        LuckPermsBridge.setUserNode(b.getUUID(), "cobblepulse.sts.limit.1", true);
        Sts.sell(b, 0, ok -> {
            check("sts_rachat", ok && Economy.balance(b.getUUID()) == before + price && PokemonText.party(b).occupied() == 2, price);
            Sts.sell(b, 0, ok2 -> {
                check("sts_quota_grade", !ok2 && PokemonText.party(b).occupied() == 2, null);
                wtPhase();
            });
        });
    }

    private static void wtPhase() {
        WonderTrade.refill();
        Scheduler.later(100, () -> {
            clearParty(c);
            Pokemon given = mon("zubat", 15, false);
            PokemonText.party(c).add(given);
            PokemonText.party(c).add(mon("geodude", 15, false));
            int poolBefore = sqlCount(Db.of("wondertrade_pool"), "SELECT COUNT(*) FROM " + Db.of("wondertrade_pool").table("wt_pool"));
            WonderTrade.trade(c, 0, received -> Scheduler.later(40, () -> {
                int poolAfter = sqlCount(Db.of("wondertrade_pool"), "SELECT COUNT(*) FROM " + Db.of("wondertrade_pool").table("wt_pool"));
                boolean inParty = false;
                for (Pokemon pk : PokemonText.party(c)) if (pk != null && received != null && pk.getUuid().equals(received.getUuid())) inParty = true;
                check("wt_echange", received != null && inParty && poolAfter == poolBefore && poolBefore >= Cfg.wt.poolSize, poolBefore + " -> " + poolAfter);
                WonderTrade.trade(c, 0, again -> {
                    check("wt_delai", again == null, null);
                    mailboxPhase();
                });
            }));
        });
    }

    private static void mailboxPhase() {
        Db.Source db = Db.of("gts");
        long before = Economy.balance(b.getUUID());
        db.txAsync(conn -> {
            Mailbox.add(conn, b.getUUID(), "MONEY", "", 777, "test reseau");
            return null;
        }).thenAccept(v -> Scheduler.next(() -> {
            Mailbox.autoClaimMoney(b, false);
            Scheduler.later(40, () -> {
                check("boite_reception_argent_reseau", Economy.balance(b.getUUID()) == before + 777, Economy.balance(b.getUUID()) - before);
                safe("ecrans", SelfTest::screensPhase);
            });
        }));
    }

    // ------------------------------------------------------------------ menus en ecrans client (paquets captures)

    private static int menuItems(JsonObject o) {
        return o == null || !o.has("items") ? -1 : o.getAsJsonArray("items").size();
    }

    private static String menuTitle(ServerPlayer p) {
        JsonObject o = Screens.last(p.getUUID());
        if (o == null || !o.has("title")) return "";
        var c = net.minecraft.network.chat.Component.Serializer.fromJson(o.get("title").getAsString(), p.registryAccess());
        return c == null ? "" : c.getString();
    }

    private static void screensPhase() {
        UUID ua = a.getUUID();
        Screens.capture(ua, true);
        Screens.capture(staff.getUUID(), true);
        // chaque menu du Hub s'ouvre en ecran (aucun coffre) ; certains chargent leurs donnees en base (asynchrone)
        String[][] menus = {{"warps", "Téléportation"}, {"shop", "Boutique"}, {"sts", "STS"}, {"wondertrade", "Miracle"}, {"kits", "Kits"},
                {"dex", "Pokédex"}, {"spawns", "Radar"}, {"gts", "GTS"}};
        screensMenus(menus, 0, new ArrayList<>());
    }

    private static void screensMenus(String[][] menus, int i, List<String> bad) {
        UUID ua = a.getUUID();
        if (i > 0) {
            String[] m = menus[i - 1];
            JsonObject o = Screens.last(ua);
            boolean ok = "menu".equals(Screens.lastKind(ua)) && menuTitle(a).contains(m[1]) && menuItems(o) > 0 && a.containerMenu == a.inventoryMenu;
            if (!ok) bad.add(m[0] + "=" + menuTitle(a) + "/" + menuItems(o));
        }
        if (i < menus.length) {
            Hub.action(a, menus[i][0]);
            Scheduler.later(20, () -> safe("ecrans_menus", () -> screensMenus(menus, i + 1, bad)));
            return;
        }
        StaffPanel.open(staff);
        if (!menuTitle(staff).contains("Staff") || menuItems(Screens.last(staff.getUUID())) < 5) bad.add("staff=" + menuTitle(staff));
        BossEditor.open(staff);
        if (menuItems(Screens.last(staff.getUUID())) < 6) bad.add("boss_editor=" + menuItems(Screens.last(staff.getUUID())));
        ShopMenus.editor(staff);
        if (menuItems(Screens.last(staff.getUUID())) < 4) bad.add("shop_editor=" + menuItems(Screens.last(staff.getUUID())));
        Kits.editor(staff);
        if (menuItems(Screens.last(staff.getUUID())) < 3) bad.add("kits_editor=" + menuItems(Screens.last(staff.getUUID())));
        check("ecrans_tous_les_menus", bad.isEmpty(), bad.isEmpty() ? (menus.length + 4) + " menus" : bad);
        safe("ecrans_contenu", SelfTest::screensContent);
    }

    private static void screensContent() {
        UUID ua = a.getUUID();
        // pas de vitres de decoration, barre d'actions marquee, objets fideles (aller-retour reseau)
        ShopMenus.open(a);
        JsonObject o = Screens.last(ua);
        boolean noPane = true;
        boolean bar = false;
        boolean roundTrip = true;
        for (var el : o.getAsJsonArray("items")) {
            JsonObject it = el.getAsJsonObject();
            ItemStack st = Screens.decodeStack(a, it.get("i").getAsString());
            if (st.getItem().toString().contains("glass_pane")) noPane = false;
            if (it.has("bar")) bar = true;
            if (st.isEmpty()) roundTrip = false;
        }
        check("ecran_contenu_propre", noPane && bar && roundTrip, null);
        ItemStack poke = PokemonText.icon(mon("pikachu", 30, true));
        ItemStack back = Screens.decodeStack(a, fr.cobblepulse.utils.gui.Screens.encodeForTest(a, poke));
        check("ecran_objet_pokemon_fidele", ItemStack.isSameItemSameComponents(poke, back), back.getHoverName().getString());
        // navigation : clic sur une categorie -> nouvel ecran
        int cat = Screens.slotNamed(ua, "Poké Balls");
        int id = Screens.sessionId(ua);
        Screens.click(a, id, cat, "left");
        Scheduler.later(5, () -> safe("ecrans_2", () -> {
            check("ecran_clic_navigation", cat >= 0 && menuTitle(a).contains("Poké Balls") && Screens.sessionId(ua) != id, menuTitle(a));
            // clic perime (ancien ecran) ignore
            int before = Screens.sessionId(ua);
            Screens.click(a, id, cat, "left");
            Scheduler.later(5, () -> safe("ecrans_3", () -> {
                check("ecran_clic_perime_ignore", Screens.sessionId(ua) == before, null);
                screensPrompt();
            }));
        }));
    }

    private static void screensPrompt() {
        UUID ua = a.getUUID();
        WarpMenus.homes(a);
        int set = Screens.slotNamed(ua, "Nouveau home");
        Screens.click(a, Screens.sessionId(ua), set, "left");
        Scheduler.later(5, () -> safe("ecrans_saisie", () -> {
            check("ecran_fenetre_saisie", "prompt".equals(Screens.lastKind(ua)) && Screens.hasSession(ua), Screens.lastKind(ua));
            int homes = PlayerStore.get(a).homes.size();
            fr.cobblepulse.utils.gui.ChatInput.answer(a, "annule", true);
            Scheduler.later(5, () -> safe("ecrans_annulation", () -> {
                check("ecran_saisie_annulee", PlayerStore.get(a).homes.size() == homes && Screens.hasSession(ua), null);
                Screens.click(a, Screens.sessionId(ua), Screens.slotNamed(ua, "Nouveau home"), "left");
                Scheduler.later(5, () -> {
                    fr.cobblepulse.utils.gui.ChatInput.answer(a, "ecran", false);
                    Scheduler.later(5, () -> safe("ecrans_validation", () -> {
                        boolean saved = PlayerStore.get(a).homes.containsKey("ecran");
                        boolean listed = Screens.names(ua).stream().anyMatch(n -> n.contains("ecran"));
                        check("ecran_saisie_validee", saved && listed && menuTitle(a).contains("Mes Homes"), Screens.names(ua));
                        screensMisc();
                    }));
                });
            }));
        }));
    }

    private static void screensMisc() {
        UUID ua = a.getUUID();
        // liste longue : pas de pagination a l'ecran
        List<fr.cobblepulse.utils.gui.Paged.Entry> many = new ArrayList<>();
        for (int i = 0; i < 70; i++) many.add(new fr.cobblepulse.utils.gui.Paged.Entry(new ItemStack(Items.PAPER).copyWithCount(1 + i % 60), (pl, cl) -> {}));
        fr.cobblepulse.utils.gui.Paged.open(a, "&8Liste de test", many, 0, null);
        JsonObject o = Screens.last(ua);
        int content = 0;
        for (var el : o.getAsJsonArray("items")) if (!el.getAsJsonObject().has("bar")) content++;
        check("ecran_liste_complete", content == 70 && o.get("list").getAsBoolean() && !menuTitle(a).contains("1/2"), content);
        // modification du menu ouvert -> renvoi automatique
        fr.cobblepulse.utils.gui.Gui g = new fr.cobblepulse.utils.gui.Gui(3, "&8Rafraichissement");
        g.set(13, new ItemStack(Items.APPLE));
        g.open(a);
        int n1 = menuItems(Screens.last(ua));
        g.set(14, new ItemStack(Items.CARROT));
        Scheduler.later(3, () -> safe("ecrans_refresh", () -> {
            check("ecran_rafraichi", n1 == 1 && menuItems(Screens.last(ua)) == 2, n1 + " -> " + menuItems(Screens.last(ua)));
            fr.cobblepulse.utils.gui.Gui.close(a);
            check("ecran_ferme_par_serveur", "menu_close".equals(Screens.lastKind(ua)) && !Screens.hasSession(ua), null);
            Screens.capture(ua, false);
            Screens.capture(staff.getUUID(), false);
            // sans le mod cote client (joueur factice ordinaire) : menu coffre classique
            ShopMenus.open(b);
            check("coffre_pour_client_sans_mod", !Screens.enabled(b) && !Screens.hasSession(b.getUUID()), null);
            safe("mondes", SelfTest::worldsPhase);
        }));
    }

    // ================================================================== ameliorations : mondes, temps de jeu, GTS, boutique, Mega

    private static void worldsPhase() {
        MinecraftServer s = a.getServer();
        // chaque monde charge est decrit dans worlds.json
        List<String> missing = new ArrayList<>();
        for (ServerLevel l : s.getAllLevels()) if (!Cfg.worlds.worlds.containsKey(Worlds.id(l))) missing.add(Worlds.id(l));
        check("mondes_detectes", missing.isEmpty() && Cfg.worlds.worlds.size() >= 3, missing.isEmpty() ? Cfg.worlds.worlds.keySet() : missing);
        check("mondes_noms", new fr.cobblepulse.utils.core.Loc("minecraft:the_nether", 0, 64, 0, 0, 0).shortWorld().equals("Nether"), null);
        ServerLevel nether = s.getLevel(net.minecraft.world.level.Level.NETHER);
        ServerLevel end = s.getLevel(net.minecraft.world.level.Level.END);
        fr.cobblepulse.utils.core.Loc ns = Worlds.spawnOf(nether);
        fr.cobblepulse.utils.core.Loc es = Worlds.spawnOf(end);
        check("mondes_spawn_calcule", ns.level() == nether && es.level() == end && Math.abs(es.x - 100.5) < 0.01, ns.coords() + " / " + es.coords());
        // homes : regle par monde (TestStaff n'a pas encore de home)
        Teleports.perform(staff, ns, null, null);
        var wn = Cfg.worlds.worlds.get("minecraft:the_nether");
        String oldHomes = wn.homes;
        wn.homes = "NONE";
        boolean denied = !HomeLimits.setHome(staff, "enfer");
        wn.homes = "ALL";
        boolean allowed = HomeLimits.setHome(staff, "enfer");
        wn.homes = oldHomes;
        PlayerStore.get(staff).homes.remove("enfer");
        check("mondes_homes_par_monde", denied && allowed, staff.level().dimension().location() + " " + PlayerStore.get(staff).homes.keySet());
        // spawn par monde : /setspawn dans le Nether ne touche pas au spawn global
        fr.cobblepulse.utils.core.Loc global = Teleports.spawn().copy();
        staff.teleportTo(nether, ns.x, ns.y, ns.z, 0, 0);
        Worlds.setSpawn(staff);
        check("mondes_setspawn_par_monde", wn.spawn != null && "minecraft:the_nether".equals(wn.spawn.world) && Teleports.spawn().world.equals(global.world), null);
        // acces reserve
        wn.restricted = true;
        wn.listed = true;
        boolean blocked = !Worlds.canAccess(b, "minecraft:the_nether");
        LuckPermsBridge.setUserNode(b.getUUID(), Worlds.node("minecraft:the_nether"), true);
        boolean granted = Worlds.canAccess(b, "minecraft:the_nether");
        check("mondes_acces_reserve", blocked && granted, Worlds.node("minecraft:the_nether"));
        // TPA bloquee vers un monde
        wn.tpa = false;
        b.teleportTo(nether, ns.x, ns.y, ns.z, 0, 0);
        c.teleportTo(s.overworld(), 0.5, 100, 0.5, 0, 0);
        Tpa.request(c, b, false);
        check("mondes_tpa_bloquee", Tpa.accept(b, "TestC") == 0, null);
        wn.tpa = true;
        // RTP : rayon propre au monde
        var wo = Cfg.worlds.worlds.get("minecraft:overworld");
        int oldMin = wo.rtpMinRadius, oldMax = wo.rtpMaxRadius;
        wo.rtpMinRadius = 8;
        wo.rtpMaxRadius = 24;
        fr.cobblepulse.utils.core.Loc r = null;
        for (int k = 0; k < 30 && r == null; k++) r = fr.cobblepulse.utils.teleport.Rtp.tryOnce(s.overworld(), s.overworld().getRandom());
        var sp = s.overworld().getSharedSpawnPos();
        double dist = r == null ? -1 : Math.hypot(r.x - sp.getX(), r.z - sp.getZ());
        check("mondes_rtp_rayon", r != null && dist <= 26, dist);
        wo.rtpMinRadius = oldMin;
        wo.rtpMaxRadius = oldMax;
        // menu Mondes et commande /mondes <monde>
        Screens.capture(b.getUUID(), true);
        Worlds.open(b);
        check("mondes_menu", Screens.names(b.getUUID()).stream().anyMatch(n -> n.contains("Nether")), Screens.names(b.getUUID()));
        Screens.capture(b.getUUID(), false);
        b.teleportTo(s.overworld(), 0.5, 100, 0.5, 0, 0);
        LuckPermsBridge.setUserNode(b.getUUID(), "cobblepulse.teleport.bypassdelay", true);
        cmd(s, b.createCommandSourceStack(), "mondes minecraft:the_nether");
        check("mondes_commande_teleporte", b.level() == nether, b.level().dimension().location());
        wn.restricted = false;
        wn.listed = false;
        Cfg.save("worlds.json", Cfg.worlds);
        check("mondes_regles_boss_alertes", Worlds.bosses(s.overworld()) && Worlds.alerts(s.overworld()), null);
        a.teleportTo(s.overworld(), 0.5, 100, 0.5, 0, 0);
        b.teleportTo(s.overworld(), 0.5, 100, 0.5, 0, 0);
        staff.teleportTo(s.overworld(), 4.5, 100, 4.5, 0, 0);
        safe("temps_de_jeu", SelfTest::playtimePhase);
    }

    private static void playtimePhase() {
        var cfg = Cfg.playtime;
        cfg.autoClaim = false;
        PlayerData da = PlayerStore.get(a);
        check("temps_de_jeu_initialise", fr.cobblepulse.utils.playtime.PlaytimeRewards.activeSeconds(a) >= PlayerStore.playtimeTicks(a) / 20, da.activeSeconds);
        long before = Economy.balance(a.getUUID());
        fr.cobblepulse.utils.playtime.PlaytimeRewards.addActive(a, 3600);
        int avail = fr.cobblepulse.utils.playtime.PlaytimeRewards.available(a);
        check("temps_de_jeu_palier_atteint", avail >= 1 && da.playtimeNotified.contains("h1"), avail);
        var h1 = cfg.tiers.get(0);
        boolean claimed = fr.cobblepulse.utils.playtime.PlaytimeRewards.claim(a, h1);
        boolean twice = fr.cobblepulse.utils.playtime.PlaytimeRewards.claim(a, h1);
        check("temps_de_jeu_reclamation", claimed && !twice && Economy.balance(a.getUUID()) > before, Economy.balance(a.getUUID()) - before);
        check("temps_de_jeu_palier_verrouille", !fr.cobblepulse.utils.playtime.PlaytimeRewards.claim(a, cfg.tiers.get(cfg.tiers.size() - 1)), null);
        // bonus repetable (remis automatiquement)
        long cBefore = Economy.balance(c.getUUID());
        PlayerStore.get(c).repeatSeconds = 0;
        fr.cobblepulse.utils.playtime.PlaytimeRewards.addActive(c, cfg.repeat.everyMinutes * 60);
        check("temps_de_jeu_bonus_repetable", Economy.balance(c.getUUID()) >= cBefore + 250, Economy.balance(c.getUUID()) - cBefore);
        // remise automatique
        cfg.autoClaim = true;
        long bBefore = Economy.balance(c.getUUID());
        fr.cobblepulse.utils.playtime.PlaytimeRewards.addActive(c, 5 * 3600);
        check("temps_de_jeu_remise_auto", PlayerStore.get(c).playtimeClaimed.contains("h5") && Economy.balance(c.getUUID()) > bBefore, PlayerStore.get(c).playtimeClaimed);
        cfg.autoClaim = false;
        // commande staff
        long bSec = fr.cobblepulse.utils.playtime.PlaytimeRewards.activeSeconds(b);
        cmd(a.getServer(), a.getServer().createCommandSourceStack(), "ptr ajouter TestB 2h");
        check("temps_de_jeu_commande_ajouter", fr.cobblepulse.utils.playtime.PlaytimeRewards.activeSeconds(b) == bSec + 7200, null);
        // menu (ecran) et tuile du Hub
        Screens.capture(a.getUUID(), true);
        fr.cobblepulse.utils.playtime.PlaytimeRewards.open(a);
        check("temps_de_jeu_menu", menuTitle(a).contains("Temps de jeu") && menuItems(Screens.last(a.getUUID())) >= cfg.tiers.size(), menuTitle(a));
        Screens.capture(a.getUUID(), false);
        check("temps_de_jeu_tuile_hub", Hub.tiles(a).stream().anyMatch(t -> t.id.equals("playtime")), null);
        safe("echange_miracle_delai", SelfTest::wtCooldownPhase);
    }

    private static void wtCooldownPhase() {
        // C vient d'echanger : delai visible partout
        String line = WonderTrade.cooldownLine(c);
        check("wt_delai_ligne", line.contains("dans"), TextUtil.strip(line));
        var tile = Hub.tiles(c).stream().filter(t -> t.id.equals("wondertrade")).findFirst().orElse(null);
        check("wt_delai_tuile_hub", tile != null && tile.lore.stream().anyMatch(l -> l.contains("dans")), tile == null ? "-" : tile.lore);
        Screens.capture(c.getUUID(), true);
        fr.cobblepulse.utils.market.WonderTradeMenus.open(c);
        Scheduler.later(30, () -> safe("wt_delai_menu", () -> {
            List<String> names = Screens.names(c.getUUID());
            check("wt_delai_menu", names.stream().anyMatch(n -> n.contains("Prochain échange dans")), names);
            // compte a rebours en direct : le menu est renvoye chaque seconde
            JsonObject first = Screens.last(c.getUUID());
            Scheduler.later(25, () -> safe("wt_delai_direct", () -> {
                check("wt_delai_mise_a_jour", Screens.last(c.getUUID()) != first, null);
                Screens.capture(c.getUUID(), false);
                gtsDurationPhase();
            }));
        }));
    }

    private static void gtsDurationPhase() {
        List<Integer> choices = Gts.durationChoices(a);
        int base = Gts.tax(a);
        check("gts_durees_proposees", choices.containsAll(List.of(24, 48, 72, 168, 336)), choices);
        check("gts_taxe_selon_duree", Gts.taxFor(a, 48) == base && Gts.taxFor(a, 168) == base + 5 && Gts.taxFor(a, 336) == base + 10,
                Gts.taxFor(a, 48) + " / " + Gts.taxFor(a, 168) + " / " + Gts.taxFor(a, 336));
        LuckPermsBridge.clearUserNodes(a.getUUID(), "cobblepulse.gts.");
        a.getInventory().clearContent();
        a.getInventory().setItem(a.getInventory().selected, new ItemStack(Items.LAPIS_LAZULI, 9));
        Gts.listHand(a, 400, 5, bad -> {
            check("gts_duree_invalide_refusee", !bad && count(a, Items.LAPIS_LAZULI) == 9, null);
            Gts.listHand(a, 400, 168, ok -> Gts.mine(a.getUUID()).thenAccept(mine -> Scheduler.next(() -> safe("gts_duree", () -> {
                Gts.Listing l = mine.stream().filter(x -> x.data() != null && ItemSerial.decodeOne(x.data()).is(Items.LAPIS_LAZULI)).findFirst().orElse(null);
                long hours = l == null ? -1 : Math.round((l.expires() - l.created()) / 3_600_000.0);
                check("gts_annonce_longue_taxee", ok && l != null && hours == 168 && l.tax() == Gts.tax(a) + 5, l == null ? "-" : hours + " h, taxe " + l.tax());
                // menu de choix de la duree (ecran)
                Screens.capture(a.getUUID(), true);
                int[] chosen = {-1};
                fr.cobblepulse.utils.market.GtsMenus.chooseDuration(a, 1000, "Test", h -> chosen[0] = h);
                int slot = Screens.slotNamed(a.getUUID(), "7 j");
                Screens.click(a, Screens.sessionId(a.getUUID()), slot, "left");
                Scheduler.later(3, () -> safe("gts_duree_menu", () -> {
                    check("gts_menu_duree", slot >= 0 && chosen[0] == 168, Screens.names(a.getUUID()));
                    Screens.capture(a.getUUID(), false);
                    shopEditorPhase();
                }));
            }))));
        });
    }

    private static void shopEditorPhase() {
        UUID us = staff.getUUID();
        Screens.capture(us, true);
        ShopConfig.Category cat = Cfg.SHOP.get("ressources");
        int before = cat.entries.size();
        ShopMenus.editCategory(staff, cat, 0);
        Screens.click(staff, Screens.sessionId(us), 45, "left");
        Scheduler.later(3, () -> safe("boutique_objet", () -> {
            JsonObject pick = Screens.last(us);
            check("boutique_selecteur_objet", "pick".equals(Screens.lastKind(us)) && "item".equals(pick.get("kind").getAsString()), Screens.lastKind(us));
            fr.cobblepulse.utils.gui.ChatInput.answer(staff, "cobblemon:great_ball", false);
            Scheduler.later(3, () -> {
                fr.cobblepulse.utils.gui.ChatInput.answer(staff, "16", false);
                Scheduler.later(3, () -> {
                    fr.cobblepulse.utils.gui.ChatInput.answer(staff, "300 75", false);
                    Scheduler.later(3, () -> safe("boutique_objet_cree", () -> {
                        ShopConfig.Entry e = cat.entries.size() > before ? cat.entries.get(cat.entries.size() - 1) : null;
                        ItemStack st = e == null ? ItemStack.EMPTY : ShopService.stack(e);
                        check("boutique_objet_sans_main", e != null && st.getItem().toString().contains("great_ball") && st.getCount() == 16 && e.buy == 300 && e.sell == 75,
                                e == null ? "aucun" : e.item + " " + e.buy + "/" + e.sell);
                        if (e != null) cat.entries.remove(e);
                        shopPokemonPhase(cat);
                    }));
                });
            });
        }));
    }

    private static void shopPokemonPhase(ShopConfig.Category cat) {
        UUID us = staff.getUUID();
        int before = cat.entries.size();
        ShopMenus.editCategory(staff, cat, 0);
        Screens.click(staff, Screens.sessionId(us), 47, "left");
        Scheduler.later(3, () -> safe("boutique_pokemon", () -> {
            JsonObject pick = Screens.last(us);
            check("boutique_createur_pokemon", "pick".equals(Screens.lastKind(us)) && "pokemon".equals(pick.get("kind").getAsString()), Screens.lastKind(us));
            fr.cobblepulse.utils.gui.ChatInput.answer(staff, "pikachu niveau=20 shiny ivs=31,31,31,31,31,31 nature=timid ball=ultra_ball", false);
            Scheduler.later(3, () -> {
                fr.cobblepulse.utils.gui.ChatInput.answer(staff, "5000 -", false);
                Scheduler.later(3, () -> safe("boutique_pokemon_cree", () -> {
                    ShopConfig.Entry e = cat.entries.size() > before ? cat.entries.get(cat.entries.size() - 1) : null;
                    boolean ok = e != null && "pokemon".equals(e.type) && e.pokemon.species.equals("pikachu") && e.pokemon.level == 20 && e.pokemon.shiny
                            && e.buy == 5000 && e.sell == -1;
                    check("boutique_pokemon_sans_equipe", ok, e == null ? "aucun" : MonSpecParser.format(e.pokemon));
                    // le Pokemon vendu est bien cree
                    if (e != null) {
                        Pokemon pk = fr.cobblepulse.utils.pokemon.PokemonFactory.create(e.pokemon, new ArrayList<>());
                        check("boutique_pokemon_fidele", pk != null && pk.getShiny() && pk.getLevel() == 20 && PokemonText.perfectIvs(pk) == 6
                                && pk.getNature().getName().getPath().equals("timid"), pk == null ? "-" : PokemonText.title(pk).getString());
                        cat.entries.remove(e);
                    }
                    // copie depuis l'equipe (clic droit)
                    clearParty(staff);
                    PokemonText.party(staff).add(mon("eevee", 33, true));
                    ShopMenus.editCategory(staff, cat, 0);
                    Screens.click(staff, Screens.sessionId(us), 47, "right");
                    Scheduler.later(3, () -> safe("boutique_copie", () -> {
                        Screens.click(staff, Screens.sessionId(us), 10, "left");
                        Scheduler.later(3, () -> {
                            fr.cobblepulse.utils.gui.ChatInput.answer(staff, "8000 -", false);
                            Scheduler.later(3, () -> safe("boutique_copie_creee", () -> {
                                ShopConfig.Entry c2 = cat.entries.size() > before ? cat.entries.get(cat.entries.size() - 1) : null;
                                check("boutique_pokemon_copie_equipe", c2 != null && c2.pokemon != null && c2.pokemon.species.equals("eevee")
                                        && c2.pokemon.level == 33 && c2.pokemon.shiny, c2 == null ? "aucun" : MonSpecParser.format(c2.pokemon));
                                if (c2 != null) cat.entries.remove(c2);
                                Cfg.saveShopCategory(cat);
                                String spec = "charizard niveau=50 shiny forme=megax ivs=31,31,31,31,31,31 nature=modest talent=blaze ball=ultra_ball";
                                check("boutique_fiche_aller_retour", MonSpecParser.format(MonSpecParser.parse(spec)).contains("nature=modest"),
                                        MonSpecParser.format(MonSpecParser.parse(spec)));
                                Screens.capture(us, false);
                                safe("mega", SelfTest::megaPhase);
                                finish();
                            }));
                        });
                    }));
                }));
            });
        }));
    }

    private static void megaPhase() {
        Species charizard = PokemonSpecies.getByIdentifier(ResourceLocation.parse("cobblemon:charizard"));
        try {
            java.lang.reflect.Method build = Class.forName("fr.cobblepulse.utils.wiki.SpeciesInfo").getDeclaredMethod("build", Species.class);
            build.setAccessible(true);
            JsonObject info = (JsonObject) build.invoke(null, charizard);
            JsonArray megas = info.getAsJsonArray("megas");
            info("Mega Showdown : " + Bosses.megaShowdown() + " | formes Mega de Dracaufeu : " + megas);
            check("mega_formes_wiki", megas.size() >= 2, megas);
            boolean stones = true;
            for (var f : info.getAsJsonArray("forms")) {
                JsonObject fo = f.getAsJsonObject();
                if (!"mega".equals(fo.has("special") ? fo.get("special").getAsString() : "")) continue;
                if (Bosses.megaShowdown() ? !fo.has("stone") : !fo.has("stone") && !fo.has("stone_hint")) stones = false;
            }
            check("mega_gemmes_identifiees", stones, null);
            String x = Bosses.formItemFor(charizard, "Mega-X");
            if (Bosses.megaShowdown()) check("mega_dracaufite_x", x != null && x.contains("charizardite_x"), x);
        } catch (Throwable t) {
            check("mega_formes_wiki", false, t);
        }
    }

    private static void finish() {
        PlayerStore.flushDirty();
        for (ServerPlayer p : new ServerPlayer[]{a, b, c, staff}) PlayerStore.save(p.getUUID());
        Scheduler.later(40, () -> {
            String summary = "SELFTEST SUMMARY: " + pass + " pass, " + fail + " fail";
            CobbleUtils.LOG.info("[CobblePulse-Utils][selftest] {}", summary);
            CobbleUtils.LOG.info("[CobblePulse-Utils][selftest] SELFTEST DONE");
        });
    }

    // ================================================================== apres redemarrage

    public static int persisted(CommandSourceStack src) {
        REPORT.clear();
        pass = 0;
        fail = 0;
        info("stockage : " + Db.main().description());
        safe("persistance", () -> {
            PlayerData da = PlayerStore.peek(uuid("TestA"));
            check("persist_homes", da.homes.keySet().containsAll(List.of("maison", "mine", "ferme", "quatrieme")), da.homes.keySet());
            check("persist_back", da.back != null, null);
            check("persist_nick", da.nick.contains("Alpha"), da.nick);
            check("persist_kits_unique", da.kitsClaimed.contains("depart"), da.kitsClaimed);
            check("persist_cooldown_kit", da.cooldowns.containsKey("kit:quotidien") || da.cooldowns.containsKey("kit:vip"), da.cooldowns.keySet());
            PlayerData dc = PlayerStore.peek(uuid("TestC"));
            check("persist_dex_reclame", dc.dexClaimed.contains(Cfg.dex.tiers.get(0).id), dc.dexClaimed);
            check("persist_warp", Cfg.warps.find("arene") != null && Cfg.warps.find("arene").restricted, null);
            List<ItemStack> p0 = Enderchests.peek(uuid("TestA"), 0);
            List<ItemStack> p2 = Enderchests.peek(uuid("TestA"), 2);
            check("persist_enderchest_pages", !p0.isEmpty() && p0.get(0).is(Items.DIAMOND) && p0.get(0).getCount() == 7 && p2.size() == 54 && p2.get(53).is(Items.EMERALD), null);
            Db.Source g = Db.of("gts");
            int sold = sqlCount(g, "SELECT COUNT(*) FROM " + g.table("gts_listings") + " WHERE seller=? AND status='SOLD'", uuid("TestA").toString());
            check("persist_gts_historique", sold >= 2, sold);
            int sts = sqlCount(Db.of("sts_logs"), "SELECT COUNT(*) FROM " + Db.of("sts_logs").table("sts_logs") + " WHERE player=?", uuid("TestB").toString());
            check("persist_sts_journal", sts == 1, sts);
            Db.Source w = Db.of("wondertrade_pool");
            int pool = sqlCount(w, "SELECT COUNT(*) FROM " + w.table("wt_pool"));
            int trades = sqlCount(w, "SELECT trades FROM " + w.table("wt_stats") + " WHERE uuid=?", uuid("TestC").toString());
            check("persist_wt_reservoir_stats", pool >= Cfg.wt.poolSize && trades == 1, pool + " / " + trades);
            Db.Source bv = Db.of("boss_first_victories");
            int wins = sqlCount(bv, "SELECT wins FROM " + bv.table("boss_victories") + " WHERE uuid=? AND boss_key=?", uuid("TestA").toString(), "charizard:mega_x");
            check("persist_boss_victoires", wins == 2, wins);
            int mails = sqlCount(g, "SELECT COUNT(*) FROM " + g.table("mailbox") + " WHERE owner=?", uuid("TestB").toString());
            check("persist_boite_reception_vide", mails == 0, mails);
            check("persist_historique_spawns", !SpawnAlerts.history("shiny").isEmpty(), SpawnAlerts.history("shiny").size());
            check("persist_temps_de_jeu", da.activeSeconds >= 3600 && da.playtimeClaimed.contains("h1"), da.activeSeconds + " s " + da.playtimeClaimed);
            check("persist_monde_spawn", Cfg.worlds.worlds.get("minecraft:the_nether").spawn != null, null);
        });
        Scheduler.later(20, () -> {
            String summary = "SELFTEST SUMMARY: " + pass + " pass, " + fail + " fail";
            CobbleUtils.LOG.info("[CobblePulse-Utils][selftest] {}", summary);
            CobbleUtils.LOG.info("[CobblePulse-Utils][selftest] SELFTEST DONE");
        });
        return 1;
    }

    public static int report(CommandSourceStack src) {
        for (String l : REPORT) src.sendSystemMessage(net.minecraft.network.chat.Component.literal(l));
        String summary = "SELFTEST SUMMARY: " + pass + " pass, " + fail + " fail";
        src.sendSystemMessage(net.minecraft.network.chat.Component.literal(summary));
        return fail == 0 ? 1 : 0;
    }
}
