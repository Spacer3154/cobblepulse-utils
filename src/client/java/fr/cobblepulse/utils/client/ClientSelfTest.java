package fr.cobblepulse.utils.client;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import fr.cobblepulse.utils.CobbleUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;

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

    static void install() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientSelfTest::tick);
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
                        var s = PokemonSpecies.getByIdentifier(ResourceLocation.parse("cobblemon:charizard"));
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
                    if (t >= base && (t - base) % 50 == 0) {
                        int i = (t - base) / 50;
                        if (i < MENUS.length) command(mc, MENUS[i][0]);
                        else if (i == MENUS.length) {
                            CobbleUtils.LOG.info("[CobblePulse-Utils][livetest] LIVETEST SUMMARY: {} pass, {} fail", pass, fail);
                            mc.stop();
                        }
                    } else if (t >= base && (t - base) % 50 == 30) {
                        int i = (t - base) / 50;
                        if (i < MENUS.length) {
                            boolean ok = mc.screen instanceof AbstractContainerScreen<?> && mc.screen.getTitle().getString().contains(MENUS[i][1]);
                            check("menu_" + MENUS[i][0].replace(' ', '_'), ok, screenName(mc));
                            shot(mc, String.format("live_%02d_%s", 10 + i, MENUS[i][0].replace(' ', '_')));
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
