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
                default -> CobbleUtils.LOG.debug("[CobblePulse-Utils] Action inconnue {}", action);
            }
        } catch (Throwable t) {
            CobbleUtils.LOG.error("[CobblePulse-Utils] Erreur sur l'action {} de {}", action, p.getGameProfile().getName(), t);
        }
    }
}
