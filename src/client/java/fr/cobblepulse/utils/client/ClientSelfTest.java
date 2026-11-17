package fr.cobblepulse.utils.client;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import fr.cobblepulse.utils.CobbleUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;
import java.util.List;

/**
 * Test de bout en bout (CI) : le client se connecte au serveur de test, ouvre le Hub, le Wiki (Dracaufeu : chaque onglet)
 * et les principaux menus coffre, verifie qu'ils s'affichent et prend des captures.
 */
final class ClientSelfTest {
    private static int ticks;
    private static int start = -1;
    private static int pass;
    private static int fail;

    private ClientSelfTest() {}

    /** Messages recus (verification du butin des Boss). */
    private static final List<String> MESSAGES = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static boolean autoBattle;
    private static int battleTurns;

    static void install() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientSelfTest::tick);
        ClientTickEvents.END_CLIENT_TICK.register(ClientSelfTest::battleTick);
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((m, overlay) -> {
            if (!overlay) MESSAGES.add(m.getString());
        });
    }

    /** Combat reel : quand le client doit choisir, il joue la premiere capacite utilisable sur le Pokemon adverse. */
    private static void battleTick(Minecraft mc) {
        if (!autoBattle) return;
        var battle = com.cobblemon.mod.common.client.CobblemonClient.INSTANCE.getBattle();
        if (battle == null || !battle.getMustChoose() || mc.player == null) return;
        var me = battle.getParticipatingActor(mc.player.getUUID());
        String target = null;
        for (var side : battle.getSides()) {
            for (var actor : side.getActors()) {
                if (actor == me) continue;
                if (!actor.getActivePokemon().isEmpty()) target = actor.getActivePokemon().get(0).getPNX();
            }
        }
        List<com.cobblemon.mod.common.battles.ShowdownActionResponse> responses = new java.util.ArrayList<>();
        for (var req : battle.getPendingActionRequests()) {
            // capacite offensive la plus puissante (une capacite de statut ne ferait jamais finir le combat)
            String move = null;
            double best = -1;
            if (req.getMoveSet() != null) for (var mv : req.getMoveSet().getMoves()) {
                if (!mv.canBeUsed()) continue;
                // capacites a effet differe ou a deux tours : inutiles pour finir vite
                if (java.util.Set.of("futuresight", "doomdesire", "solarbeam", "skyattack", "skullbash", "razorwind", "hyperbeam", "gigaimpact")
                        .contains(mv.getId())) continue;
                var tpl = com.cobblemon.mod.common.api.moves.Moves.getByName(mv.getId());
                double power = tpl == null ? 0 : tpl.getDamageCategory() == com.cobblemon.mod.common.api.moves.categories.DamageCategories.INSTANCE.getSTATUS() ? 0 : tpl.getPower();
                if (move == null || power > best) {
                    move = mv.getId();
                    best = power;
                }
            }
            responses.add(move == null ? com.cobblemon.mod.common.battles.PassActionResponse.INSTANCE : new com.cobblemon.mod.common.battles.MoveActionResponse(move, target, null));
        }
        if (responses.isEmpty()) return;
        battle.setMustChoose(false);
        battleTurns++;
        CobbleUtils.LOG.info("[CobblePulse-Utils][livetest] tour {} : {}", battleTurns, responses);
        new com.cobblemon.mod.common.net.messages.server.battle.BattleSelectActionsPacket(battle.getBattleId(), responses).sendToServer();
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), c -> CobbleUtils.LOG.info("[CobblePulse-Utils][livetest] capture {}", name));
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) pass++;
        else fail++;
        CobbleUtils.LOG.info("[CobblePulse-Utils][livetest] {} {}{}", ok ? "PASS" : "FAIL", name, detail == null ? "" : " - " + detail);
    }

    private static void command(Minecraft mc, String c) {
        if (mc.screen != null) mc.setScreen(null);
        mc.player.connection.sendCommand(c);
    }

    private static String screenName(Minecraft mc) {
        return mc.screen == null ? "aucun" : mc.screen.getClass().getSimpleName() + " « " + mc.screen.getTitle().getString() + " »";
    }

    private static final String[][] MENUS = {
            {"shop", "Boutique"}, {"warp", "Téléportation"}, {"kit", "Kits"}, {"gts", "GTS"}, {"sts", "STS"},
            {"wondertrade", "Échange Miracle"}, {"dex", "Pokédex"}, {"spawns", "Radar"}, {"ec", "Enderchest"},
            {"homes", "Mes Homes"}, {"cp admin", "Panneau Staff"}, {"shop pokeballs", "Poké Balls"}};

    private static final java.util.List<java.util.function.Consumer<Minecraft>> STEPS = new java.util.ArrayList<>();

    /** Super Bonbons dans l'inventaire du joueur (butin garanti du palier Ultime). */
    private static int candies(Minecraft mc) {
        int n = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var st = inv.getItem(i);
            if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString().equals("cobblemon:rare_candy")) n += st.getCount();
        }
        return n;
    }

    private static MenuScreen menu(Minecraft mc) {
        return mc.screen instanceof MenuScreen m ? m : null;
    }

    private static boolean titled(Minecraft mc, String part) {
        return mc.screen != null && mc.screen.getTitle().getString().contains(part);
    }

    static {
        // chaque menu : ouverture puis verification (ecran CobblePulse ; l'Enderchest reste un vrai coffre de stockage)
        for (int k = 0; k < MENUS.length; k++) {
            final int i = k;
            STEPS.add(mc -> command(mc, MENUS[i][0]));
            STEPS.add(mc -> {
                boolean storage = MENUS[i][0].equals("ec");
                boolean ok = (storage ? mc.screen instanceof AbstractContainerScreen<?> : mc.screen instanceof MenuScreen) && titled(mc, MENUS[i][1]);
                check("menu_" + MENUS[i][0].replace(' ', '_'), ok, screenName(mc));
                shot(mc, String.format("live_%02d_%s", 10 + i, MENUS[i][0].replace(' ', '_')));
            });
        }
        // navigation par clics : boutique -> categorie -> achat (fonds insuffisants : message dans l'ecran)
        STEPS.add(mc -> command(mc, "shop"));
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            check("clic_categorie", m != null && m.clickNamed("Poké Balls", "left"), screenName(mc));
        });
        STEPS.add(mc -> {
            check("ecran_categorie", menu(mc) != null && titled(mc, "Poké Balls"), screenName(mc));
            shot(mc, "live_30_shop_categorie");
            if (menu(mc) != null) menu(mc).clickFirst("left");
        });
        STEPS.add(mc -> {
            check("ecran_achat", menu(mc) != null && titled(mc, "Achat"), screenName(mc));
            if (menu(mc) != null) menu(mc).clickNamed("+10", "left");
        });
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            check("quantite_mise_a_jour", m != null && m.entryNames().stream().anyMatch(n -> n.contains("11")), m == null ? "-" : String.valueOf(m.entryNames()));
            if (m != null) m.clickNamed("Acheter", "left");
        });
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            check("message_dans_ecran", m != null && m.hasNotice(), m == null ? "-" : m.noticeText());
            shot(mc, "live_31_shop_achat");
        });
        // fenetre de saisie : nouveau home
        STEPS.add(mc -> command(mc, "homes"));
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            check("clic_nouveau_home", m != null && m.clickNamed("Nouveau home", "left"), screenName(mc));
        });
        STEPS.add(mc -> {
            boolean ok = mc.screen instanceof PromptScreen;
            check("fenetre_saisie", ok, screenName(mc));
            if (mc.screen instanceof PromptScreen ps) ps.setText("ecranlive");
            shot(mc, "live_32_saisie");
        });
        STEPS.add(mc -> {
            if (mc.screen instanceof PromptScreen ps) ps.submit();
        });
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            check("saisie_enregistree", m != null && titled(mc, "Mes Homes") && m.entryNames().contains("ecranlive"), m == null ? screenName(mc) : String.valueOf(m.entryNames()));
            shot(mc, "live_33_homes");
            if (m != null) m.onClose();
        });
        STEPS.add(mc -> check("echap_ferme", mc.screen == null, screenName(mc)));
        // liste longue : recherche
        STEPS.add(mc -> {
            for (int k = 1; k <= 14; k++) mc.player.connection.sendCommand("setwarp livetest" + k);
        });
        STEPS.add(mc -> command(mc, "cp admin"));
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            check("clic_editeur_warps", m != null && m.clickNamed("Warps", "left"), screenName(mc));
        });
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            check("liste_avec_recherche", m != null && m.hasSearch() && m.visibleCount() >= 14, m == null ? screenName(mc) : m.visibleCount() + " entrees");
            shot(mc, "live_34_liste");
            if (m != null) m.setQuery("livetest1");
        });
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            int n = m == null ? -1 : m.visibleCount();
            check("recherche_filtre", n >= 1 && n <= 6, String.valueOf(n));
            shot(mc, "live_35_recherche");
        });
        STEPS.add(mc -> command(mc, "gts"));
        STEPS.add(mc -> shot(mc, "live_36_gts"));

        // ---------------------------------------------------------------- Mega-Evolutions dans le Wiki
        STEPS.add(mc -> command(mc, "wiki"));
        STEPS.add(mc -> {
            if (mc.screen instanceof WikiScreen w) {
                w.setMegaOnly(true);
                int n = w.visibleIds().size();
                check("wiki_filtre_mega", n > 0 && n < 300 && w.visibleIds().contains("cobblemon:charizard"), n + " especes");
                var s = PokemonSpecies.getByIdentifier(ResourceLocation.parse("cobblemon:charizard"));
                if (s != null) w.select(s);
            } else check("wiki_filtre_mega", false, screenName(mc));
        });
        STEPS.add(mc -> {
            if (mc.screen instanceof WikiScreen w) {
                List<String> megas = w.megaNames();
                check("wiki_formes_mega", megas.size() >= 2, String.valueOf(megas));
                if (!megas.isEmpty()) w.setForm(megas.get(0));
                w.setTab(0);
            }
        });
        STEPS.add(mc -> shot(mc, "live_40_wiki_mega"));
        STEPS.add(mc -> {
            if (mc.screen instanceof WikiScreen w) w.setTab(1);
        });
        STEPS.add(mc -> {
            shot(mc, "live_41_wiki_mega_evolutions");
            if (mc.screen instanceof WikiScreen w) w.setMegaOnly(false);
        });
        // ---------------------------------------------------------------- temps de jeu, Echange Miracle
        STEPS.add(mc -> command(mc, "ptr"));
        STEPS.add(mc -> {
            check("ecran_temps_de_jeu", menu(mc) != null && titled(mc, "Temps de jeu"), screenName(mc));
            shot(mc, "live_42_temps_de_jeu");
        });
        STEPS.add(mc -> command(mc, "wondertrade"));
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            check("wt_etat_delai", m != null && m.barNames().stream().anyMatch(n -> n.contains("change")), m == null ? screenName(mc) : String.valueOf(m.barNames()));
            shot(mc, "live_43_wondertrade");
        });
        // ---------------------------------------------------------------- GTS : duree plus longue, taxe plus forte
        STEPS.add(mc -> {
            mc.player.getInventory().selected = 0;
            mc.player.connection.sendCommand("give @s minecraft:diamond 5");
        });
        STEPS.add(mc -> command(mc, "gts"));
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            check("gts_vendre_objet", m != null && m.clickNamed("Vendre l'objet", "left"), screenName(mc));
        });
        STEPS.add(mc -> {
            if (mc.screen instanceof PromptScreen ps) ps.submit("1500");
            else check("gts_saisie_prix", false, screenName(mc));
        });
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            check("gts_choix_duree", m != null && titled(mc, "Durée") && m.entryNames().stream().anyMatch(n -> n.contains("7 j")), m == null ? screenName(mc) : String.valueOf(m.entryNames()));
            shot(mc, "live_44_gts_duree");
            if (m != null) m.clickNamed("7 j", "left");
        });
        STEPS.add(mc -> command(mc, "gts listings"));
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            check("gts_annonce_7_jours", m != null && m.entryNames().stream().anyMatch(n -> n.toLowerCase().contains("diamant")), m == null ? screenName(mc) : String.valueOf(m.entryNames()));
            shot(mc, "live_45_gts_annonce");
        });
        // ---------------------------------------------------------------- Boutique : objet et Pokemon sans les avoir
        STEPS.add(mc -> command(mc, "cp admin"));
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            if (m != null) m.clickNamed("Shop Admin", "left");
        });
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            if (m != null) m.clickNamed("Ressources", "left");
        });
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            check("editeur_categorie", m != null && titled(mc, "Catégorie"), screenName(mc));
            if (m != null) m.clickNamed("Ajouter un objet", "left");
        });
        STEPS.add(mc -> {
            boolean ok = mc.screen instanceof ItemPickerScreen;
            check("selecteur_objets", ok, screenName(mc));
            if (mc.screen instanceof ItemPickerScreen ip) ip.setQuery("diamant");
        });
        STEPS.add(mc -> {
            if (mc.screen instanceof ItemPickerScreen ip) {
                check("selecteur_recherche", ip.visibleCount() > 0 && ip.visibleCount() < 60, String.valueOf(ip.visibleCount()));
                ip.choose("minecraft:diamond");
            }
            shot(mc, "live_46_selecteur_objets");
        });
        STEPS.add(mc -> {
            if (mc.screen instanceof ItemPickerScreen ip) ip.submit();
        });
        STEPS.add(mc -> {
            if (mc.screen instanceof PromptScreen ps) ps.submit("3");
            else check("saisie_lot", false, screenName(mc));
        });
        STEPS.add(mc -> {
            if (mc.screen instanceof PromptScreen ps) ps.submit("500 100");
            else check("saisie_prix_article", false, screenName(mc));
        });
        STEPS.add(mc -> {
            check("article_objet_cree", menu(mc) != null && titled(mc, "Article"), screenName(mc));
            shot(mc, "live_47_article_objet");
            if (menu(mc) != null) menu(mc).clickNamed("Retour", "left");
        });
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            if (m != null) m.clickNamed("Ajouter un Pokémon", "left");
        });
        STEPS.add(mc -> {
            check("createur_pokemon", mc.screen instanceof PokemonBuilderScreen, screenName(mc));
            if (mc.screen instanceof PokemonBuilderScreen pb) {
                pb.chooseSpecies("charizard");
                pb.setShinyFlag(true);
                pb.setLevel(50);
                pb.setIvsPerfect();
                check("createur_formes", pb.formCount() > 1, String.valueOf(pb.formCount()));
            }
        });
        STEPS.add(mc -> {
            if (mc.screen instanceof PokemonBuilderScreen pb) CobbleUtils.LOG.info("[CobblePulse-Utils][livetest] fiche : {}", pb.spec());
            shot(mc, "live_48_createur_pokemon");
        });
        STEPS.add(mc -> {
            if (mc.screen instanceof PokemonBuilderScreen pb) pb.submit();
        });
        STEPS.add(mc -> {
            if (mc.screen instanceof PromptScreen ps) ps.submit("25000");
            else check("saisie_prix_pokemon", false, screenName(mc));
        });
        STEPS.add(mc -> {
            check("article_pokemon_cree", menu(mc) != null && titled(mc, "Article"), screenName(mc));
            shot(mc, "live_49_article_pokemon");
        });
        // ---------------------------------------------------------------- vrai combat contre un Boss de niveau > 100 :
        // sauvegarde du monde pendant le combat (crash du 16/11), puis fenetre de butin
        STEPS.add(mc -> {
            if (mc.screen != null) mc.setScreen(null);
            mc.player.connection.sendCommand("pokegive mewtwo level=100 nature=modest min_perfect_ivs=6");
        });
        STEPS.add(mc -> {
            MESSAGES.clear();
            autoBattle = true;
            mc.player.connection.sendCommand("cp boss combat ultime magikarp base");
        });
        STEPS.add(mc -> mc.player.connection.sendCommand("save-all"));
        STEPS.add(mc -> {
            var battle = com.cobblemon.mod.common.client.CobblemonClient.INSTANCE.getBattle();
            check("combat_boss_reel_demarre", battle != null, String.valueOf(MESSAGES));
            if (battle != null) {
                // nom et niveau du Boss dans la fenetre de combat : traduit, sans niveau en double, au-dela de 100
                var foes = new java.util.ArrayList<com.cobblemon.mod.common.client.battle.ClientBattlePokemon>();
                for (var side : battle.getSides()) for (var actor : side.getActors()) {
                    if (actor == battle.getParticipatingActor(mc.player.getUUID())) continue;
                    for (var ap : actor.getActivePokemon()) if (ap.getBattlePokemon() != null) foes.add(ap.getBattlePokemon());
                }
                String foe = foes.isEmpty() ? "" : foes.get(0).getDisplayName().getString();
                int foeLevel = foes.isEmpty() ? 0 : foes.get(0).getLevel();
                check("combat_boss_engage", MESSAGES.stream().anyMatch(x -> x.contains("Combat contre le Boss Ultime") && x.contains("Magicarpe")), String.valueOf(MESSAGES));
                check("combat_boss_nom_fenetre", foe.equals("Magicarpe"), foe);
                check("combat_boss_niveau_sup_100", foeLevel >= 110, "Nv." + foeLevel);
            }
            // le serveur a sauvegarde le monde avec un Boss de niveau > 100 present : il repond toujours
            check("sauvegarde_avec_boss_niveau_sup_100", MESSAGES.stream().anyMatch(x -> x.toLowerCase().contains("sauvegard") || x.contains("Saved")),
                    String.valueOf(MESSAGES));
            shot(mc, "live_57_combat_boss");
        });
        for (int k = 0; k < 40; k++) STEPS.add(mc -> {
        });
        STEPS.add(mc -> {
            autoBattle = false;
            boolean ended = com.cobblemon.mod.common.client.CobblemonClient.INSTANCE.getBattle() == null;
            String loot = MESSAGES.stream().filter(x -> x.contains("vaincu !")).findFirst().orElse("");
            check("combat_boss_reel_termine", ended, battleTurns + " tour(s)");
            check("combat_boss_reel_butin", loot.contains("Butin") && !loot.endsWith(": -"), loot.isEmpty() ? String.valueOf(MESSAGES) : loot);
            // nom de l'espece traduit par le client (et non le nom anglais du serveur)
            check("boss_nom_traduit", loot.contains("Magicarpe") && !loot.contains("Magikarp"), loot);
            // fenetre de butin : ouverte d'elle-meme une fois le combat fini, les objets ne sont pas encore dans l'inventaire
            MenuScreen m = menu(mc);
            boolean bar = m != null && m.barNames().stream().anyMatch(n -> n.contains("Tout récupérer")) && m.barNames().stream().anyMatch(n -> n.contains("Tout lâcher"))
                    && m.barNames().stream().anyMatch(n -> n.contains("Tout jeter"));
            check("butin_fenetre_ouverte", bar && titled(mc, "Boss vaincu") && titled(mc, "Magicarpe") && !m.entryNames().isEmpty() && candies(mc) == 0,
                    screenName(mc) + (m == null ? "" : " " + m.entryNames() + " " + m.barNames()));
            // souris hors de la fenetre pour la capture (sinon l'infobulle de l'objet survole masque les boutons)
            org.lwjgl.glfw.GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), 6, 6);
        });
        STEPS.add(mc -> shot(mc, "live_58_butin_boss"));
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            if (m != null) m.clickNamed("Tout récupérer", "left");
        });
        STEPS.add(mc -> {
            check("butin_tout_recupere", menu(mc) == null && candies(mc) >= 10, screenName(mc) + " | Super Bonbons : " + candies(mc));
            if (mc.screen != null) mc.setScreen(null);
        });

        // ---------------------------------------------------------------- outils de test du staff (alerte, Boss Mega)
        STEPS.add(mc -> {
            if (mc.screen != null) mc.setScreen(null);
            mc.player.setYRot(0);
            mc.player.setXRot(10);
            mc.player.connection.sendCommand("cp alertes test shiny pikachu moi");
        });
        STEPS.add(mc -> shot(mc, "live_54_alerte_test"));
        // Fakemon d'un datapack serveur (bug du 17/11) : le client n'a pas sa traduction, il doit afficher son nom
        STEPS.add(mc -> {
            var fake = com.cobblemon.mod.common.api.pokemon.PokemonSpecies.getByIdentifier(net.minecraft.resources.ResourceLocation.parse("cobblemon:etreroch"));
            check("fakemon_synchronise_client", fake != null && !net.minecraft.client.resources.language.I18n.exists(fake.getTranslationKey()),
                    fake == null ? "absent" : "traduction client : " + net.minecraft.client.resources.language.I18n.exists(fake.getTranslationKey()));
            check("fakemon_nom_client", fake != null && "Étreroch".equals(fr.cobblepulse.utils.pokemon.SpeciesNames.text(fake)),
                    fake == null ? "-" : fr.cobblepulse.utils.pokemon.SpeciesNames.text(fake));
            MESSAGES.clear();
            mc.player.connection.sendCommand("cp alertes test shiny etreroch moi");
        });
        STEPS.add(mc -> {
            String alert = MESSAGES.stream().filter(x -> x.contains("Radar")).findFirst().orElse("");
            check("fakemon_alerte_lisible", alert.contains("Étreroch") && MESSAGES.stream().noneMatch(x -> x.contains("species.etreroch")),
                    alert.isEmpty() ? String.valueOf(MESSAGES) : alert);
            shot(mc, "live_60_fakemon_alerte");
            command(mc, "wiki");
        });
        STEPS.add(mc -> {
            var fake = com.cobblemon.mod.common.api.pokemon.PokemonSpecies.getByIdentifier(net.minecraft.resources.ResourceLocation.parse("cobblemon:etreroch"));
            if (mc.screen instanceof WikiScreen w && fake != null) w.select(fake);
            else check("fakemon_wiki", false, screenName(mc));
        });
        STEPS.add(mc -> {
            if (mc.screen instanceof WikiScreen w) check("fakemon_wiki", "cobblemon:etreroch".equals(w.selectedId()), w.selectedId());
            shot(mc, "live_61_fakemon_wiki");
            if (mc.screen != null) mc.setScreen(null);
        });
        STEPS.add(mc -> mc.player.connection.sendCommand("cp boss spawn ultime charizard mega"));
        STEPS.add(mc -> {
        });
        STEPS.add(mc -> {
            // Mega demandee sur le palier Ultime : c'est un Mega-Boss « Méga Ultime » qui apparait
            String spawned = MESSAGES.stream().filter(x -> x.contains("Ultime apparu")).reduce((x, y) -> y).orElse("");
            check("boss_mega_nomme", spawned.contains("Boss Méga Ultime") && spawned.contains("Dracaufeu (Méga-"), spawned.isEmpty() ? String.valueOf(MESSAGES) : spawned);
            // plaque au-dessus du Boss (surnom synchronise) : palier + espece traduite
            String plate = mc.level.getEntitiesOfClass(com.cobblemon.mod.common.entity.pokemon.PokemonEntity.class, mc.player.getBoundingBox().inflate(24))
                    .stream().map(e -> e.getEntityData().get(com.cobblemon.mod.common.entity.pokemon.PokemonEntity.Companion.getNICKNAME()))
                    .filter(java.util.Objects::nonNull).map(net.minecraft.network.chat.Component::getString)
                    .filter(n -> n.contains("Ultime")).findFirst().orElse("");
            check("boss_plaque_nom", plate.contains("[Méga Ultime]") && plate.contains("Dracaufeu"), plate);
            shot(mc, "live_55_boss_mega_test");
        });
        STEPS.add(mc -> command(mc, "cp admin"));
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            if (m != null) m.clickNamed("Boss", "left");
        });
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            check("editeur_boss_tests", m != null && m.entryNames().stream().anyMatch(n -> n.contains("Test")) || m != null && m.barNames().stream().anyMatch(n -> n.contains("Test")),
                    m == null ? screenName(mc) : String.valueOf(m.barNames()));
            check("editeur_boss_paliers_mega", m != null && m.entryNames().stream().filter(n -> n.contains("Méga ")).count() == 3,
                    m == null ? screenName(mc) : String.valueOf(m.entryNames()));
            shot(mc, "live_59_editeur_boss_paliers");
            if (m != null) m.clickNamed("Ultime", "left");
        });
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            check("editeur_palier_tests", m != null && m.entryNames().stream().anyMatch(n -> n.contains("combat immédiat")), m == null ? screenName(mc) : String.valueOf(m.entryNames()));
            shot(mc, "live_56_boss_palier_tests");
        });
        STEPS.add(mc -> mc.player.connection.sendCommand("cp boss clear"));

        // ---------------------------------------------------------------- Mondes et dimensions
        STEPS.add(mc -> command(mc, "cp admin"));
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            if (m != null) m.clickNamed("Mondes", "left");
        });
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            check("editeur_mondes", m != null && titled(mc, "Mondes") && m.entryNames().stream().anyMatch(n -> n.contains("Nether")), m == null ? screenName(mc) : String.valueOf(m.entryNames()));
            shot(mc, "live_50_editeur_mondes");
            if (m != null) m.clickNamed("Nether", "left");
        });
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            check("editeur_monde", m != null && titled(mc, "Nether"), screenName(mc));
            shot(mc, "live_51_editeur_nether");
            if (m != null) m.clickNamed("Menu Mondes", "left");
        });
        STEPS.add(mc -> command(mc, "mondes"));
        STEPS.add(mc -> {
            MenuScreen m = menu(mc);
            check("menu_mondes", m != null && m.entryNames().stream().anyMatch(n -> n.contains("Nether")), m == null ? screenName(mc) : String.valueOf(m.entryNames()));
            shot(mc, "live_52_mondes");
            if (m != null) m.clickNamed("Nether", "left");
        });
        for (int k = 0; k < 4; k++) STEPS.add(mc -> {
        });
        STEPS.add(mc -> {
            boolean nether = mc.level != null && mc.level.dimension() == net.minecraft.world.level.Level.NETHER;
            check("teleportation_nether", nether, mc.level == null ? "-" : mc.level.dimension().location().toString());
            shot(mc, "live_53_nether");
        });
    }

    private static void tick(Minecraft mc) {
        ticks++;
        if (start < 0) {
            if (mc.player != null && mc.level != null && mc.player.connection != null) {
                start = ticks + 200;
                CobbleUtils.LOG.info("[CobblePulse-Utils][livetest] connecte au serveur");
            } else if (ticks > 14000) {
                CobbleUtils.LOG.error("[CobblePulse-Utils][livetest] connexion jamais etablie");
                mc.stop();
            }
            return;
        }
        int t = ticks - start;
        if (t < 0) return;
        // notifications d'autres mods (choix du starter Cobblemon) : masquees pour des captures lisibles
        mc.getToasts().clear();
        try {
            switch (t) {
                case 0 -> {
                    if (mc.screen != null) mc.setScreen(null);
                    Ui.send("hub_open", null);
                }
                case 40 -> {
                    boolean ok = mc.screen instanceof HubScreen;
                    check("hub_ouvert", ok, screenName(mc));
                    if (mc.screen instanceof HubScreen h) {
                        check("hub_tuiles", h.tileIds().contains("wiki") && h.tileIds().contains("shop") && h.tileIds().contains("staff"), String.valueOf(h.tileIds()));
                        check("hub_passerelles", h.tileIds().stream().anyMatch(id -> id.startsWith("gw:")), String.valueOf(h.tileIds()));
                        h.debugHover = 0;
                    }
                }
                case 50 -> shot(mc, "live_01_hub");
                case 60 -> {
                    if (mc.screen instanceof HubScreen h) h.click("wiki");
                }
                case 100 -> {
                    check("wiki_ouvert", mc.screen instanceof WikiScreen, screenName(mc));
                    if (mc.screen instanceof WikiScreen w) {
                        var s = PokemonSpecies.getByIdentifier(ResourceLocation.parse("cobblemon:charmander"));
                        check("wiki_especes_synchronisees", s != null && w.visibleIds().size() > 100, w.visibleIds().size() + " especes");
                        if (s != null) w.select(s);
                    }
                }
                case 160 -> {
                    if (mc.screen instanceof WikiScreen w) {
                        check("wiki_donnees_serveur", w.hasData(), w.selectedId());
                        w.setTab(0);
                    }
                }
                case 170 -> shot(mc, "live_02_wiki_infos");
                case 180 -> {
                    if (mc.screen instanceof WikiScreen w) w.setTab(1);
                }
                case 190 -> shot(mc, "live_03_wiki_evolutions");
                case 200 -> {
                    if (mc.screen instanceof WikiScreen w) w.setTab(2);
                }
                case 210 -> shot(mc, "live_04_wiki_capacites");
                case 220 -> {
                    if (mc.screen instanceof WikiScreen w) w.setTab(3);
                }
                case 230 -> shot(mc, "live_05_wiki_spawns");
                case 240 -> {
                    if (mc.screen instanceof WikiScreen w) w.setTab(4);
                }
                case 250 -> shot(mc, "live_06_wiki_boss");
                case 260 -> {
                    if (mc.screen instanceof WikiScreen w) {
                        w.setShiny(true);
                        w.setTab(0);
                        w.search("pika");
                    }
                }
                case 270 -> {
                    if (mc.screen instanceof WikiScreen w) check("wiki_recherche", w.visibleIds().contains("cobblemon:pikachu"), String.valueOf(w.visibleIds().size()));
                    shot(mc, "live_07_wiki_recherche_shiny");
                }
                default -> {
                    int base = 300;
                    if (t >= base && (t - base) % 30 == 0) {
                        int i = (t - base) / 30;
                        if (i < STEPS.size()) STEPS.get(i).accept(mc);
                        else if (i == STEPS.size()) {
                            CobbleUtils.LOG.info("[CobblePulse-Utils][livetest] LIVETEST SUMMARY: {} pass, {} fail", pass, fail);
                            mc.stop();
                        }
                    }
                }
            }
        } catch (Throwable e) {
            check("exception_t" + t, false, e.toString());
            CobbleUtils.LOG.error("[CobblePulse-Utils][livetest] erreur", e);
        }
    }
}
