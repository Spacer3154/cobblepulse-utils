package fr.cobblepulse.utils.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.core.Json;
import fr.cobblepulse.utils.hub.Hub;
import fr.cobblepulse.utils.wiki.WikiService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/** Enregistrement des paquets et traitement des actions client (toujours re-verifiees cote serveur). */
public final class Net {
    private Net() {}

    public static void registerTypes() {
        PayloadTypeRegistry.playC2S().register(Payloads.Action.TYPE, Payloads.Action.CODEC);
        PayloadTypeRegistry.playS2C().register(Payloads.Data.TYPE, Payloads.Data.CODEC);
    }

    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(Payloads.Action.TYPE, (payload, ctx) -> {
            ServerPlayer p = ctx.player();
            ctx.server().execute(() -> handle(p, payload.action(), payload.json()));
        });
    }

    public static boolean hasClient(ServerPlayer p) {
        try {
            return p.connection != null && ServerPlayNetworking.canSend(p, Payloads.Data.TYPE);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void send(ServerPlayer p, String kind, Object data) {
        if (!hasClient(p)) return;
        ServerPlayNetworking.send(p, new Payloads.Data(kind, data instanceof String s ? s : Json.COMPACT.toJson(data)));
    }

    private static int num(JsonObject o, String k) {
        try {
            return o.has(k) ? o.get(k).getAsInt() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static void handle(ServerPlayer p, String action, String json) {
        JsonObject o;
        try {
            o = json == null || json.isBlank() ? new JsonObject() : JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            o = new JsonObject();
        }
        try {
            switch (action) {
                case "hub_open" -> Hub.open(p);
                case "hub_tile" -> Hub.action(p, o.has("id") ? o.get("id").getAsString() : "");
                case "wiki_species" -> WikiService.sendSpecies(p, o.has("species") ? o.get("species").getAsString() : "");
                case "wiki_bosses" -> WikiService.sendBosses(p);
                case "menu_click" -> fr.cobblepulse.utils.gui.Screens.click(p, num(o, "id"), num(o, "slot"), o.has("kind") ? o.get("kind").getAsString() : "left");
                case "menu_close" -> fr.cobblepulse.utils.gui.Screens.closedByClient(p, num(o, "id"));
                case "prompt_answer" -> fr.cobblepulse.utils.gui.ChatInput.answer(p, o.has("text") ? o.get("text").getAsString() : "",
                        o.has("cancel") && o.get("cancel").getAsBoolean());
                default -> CobbleUtils.LOG.debug("[CobblePulse-Utils] Action inconnue {}", action);
            }
        } catch (Throwable t) {
            CobbleUtils.LOG.error("[CobblePulse-Utils] Erreur sur l'action {} de {}", action, p.getGameProfile().getName(), t);
        }
    }
}
