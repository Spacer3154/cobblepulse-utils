package fr.cobblepulse.utils.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.net.Payloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Cote client : touche du Hub (H par defaut, configurable), ecrans Hub et Wiki. */
public final class UtilsClient implements ClientModInitializer {
    private static KeyMapping hubKey;
    private static KeyMapping wikiKey;

    @Override
    public void onInitializeClient() {
        hubKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.cobblepulse_utils.hub", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_H, "key.categories.cobblepulse_utils"));
        wikiKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.cobblepulse_utils.wiki", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, "key.categories.cobblepulse_utils"));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (hubKey.consumeClick()) {
                if (mc.player != null && mc.screen == null) Ui.send("hub_open", null);
            }
            while (wikiKey.consumeClick()) {
                if (mc.player != null && mc.screen == null) mc.setScreen(new WikiScreen(null));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Data.TYPE, (payload, ctx) -> ctx.client().execute(() -> receive(payload.kind(), payload.json())));
        // les menus couvrent le chat : les messages du serveur s'affichent dans l'ecran ouvert
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof MenuScreen m) m.notice(message);
            else if (mc.screen instanceof Overlay ov && ov.parentScreen() instanceof MenuScreen m) m.notice(message);
        });
        if ("true".equals(System.getProperty("cobblepulse_utils.livetest"))) ClientSelfTest.install();
        CobbleUtils.LOG.info("[CobblePulse-Utils] Client initialise");
    }

    static void receive(String kind, String json) {
        Minecraft mc = Minecraft.getInstance();
        JsonObject o;
        try {
            o = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            CobbleUtils.LOG.warn("[CobblePulse-Utils] Donnees illisibles ({}) : {}", kind, e.toString());
            return;
        }
        switch (kind) {
            case "hub" -> mc.setScreen(new HubScreen(o));
            case "menu" -> {
                if (mc.screen instanceof MenuScreen m) m.update(o);
                else if (mc.screen instanceof Overlay ov) {
                    if (ov.parentScreen() instanceof MenuScreen m) m.update(o);
                    else ov.setParentScreen(new MenuScreen(o));
                } else mc.setScreen(new MenuScreen(o));
            }
            case "menu_close" -> {
                if (mc.screen instanceof MenuScreen || mc.screen instanceof Overlay) mc.setScreen(null);
            }
            case "pick" -> {
                Component title = Component.literal("");
                try {
                    var conn = mc.getConnection();
                    var c = conn == null ? null : Component.Serializer.fromJson(Ui.str(o, "title", ""), conn.registryAccess());
                    if (c != null) title = c;
                } catch (Exception ignored) {
                }
                Screen parent = mc.screen instanceof MenuScreen ? mc.screen : null;
                String value = Ui.str(o, "value", "");
                if ("pokemon".equals(Ui.str(o, "kind", ""))) mc.setScreen(new PokemonBuilderScreen(parent, title, value));
                else mc.setScreen(new ItemPickerScreen(parent, title, value));
            }
            case "prompt" -> {
                Component text = Component.literal(Ui.str(o, "text", ""));
                try {
                    var conn = mc.getConnection();
                    var c = conn == null ? null : Component.Serializer.fromJson(Ui.str(o, "text", ""), conn.registryAccess());
                    if (c != null) text = c;
                } catch (Exception ignored) {
                }
                Screen parent = mc.screen instanceof MenuScreen ? mc.screen : null;
                mc.setScreen(new PromptScreen(parent, text));
            }
            case "wiki_open" -> {
                String sp = o.has("species") ? o.get("species").getAsString() : null;
                if (mc.screen instanceof WikiScreen w && sp != null) {
                    var s = com.cobblemon.mod.common.api.pokemon.PokemonSpecies.getByIdentifier(net.minecraft.resources.ResourceLocation.parse(sp));
                    if (s != null) w.select(s);
                } else mc.setScreen(new WikiScreen(sp));
            }
            case "wiki_species" -> {
                if (mc.screen instanceof WikiScreen w) w.onSpecies(o);
            }
            case "wiki_bosses" -> {
                if (mc.screen instanceof WikiScreen w) w.onBosses(o);
            }
            default -> {
            }
        }
    }
}
