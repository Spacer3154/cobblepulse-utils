package fr.cobblepulse.utils.gui;

import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Scheduler;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Saisie dans le chat pour les editeurs (prix, noms, durees...) : le prochain message du joueur est capture
 * (et non diffuse). « annuler » abandonne. Expire apres 90 secondes.
 */
public final class ChatInput {
    private record Pending(Consumer<String> handler, Runnable onCancel, long expires) {}

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private ChatInput() {}

    public static void ask(ServerPlayer p, String promptKey, Consumer<String> handler, Runnable onCancel, Object... kv) {
        PENDING.put(p.getUUID(), new Pending(handler, onCancel, System.currentTimeMillis() + 90_000));
        if (Screens.enabled(p)) {
            // fenetre de saisie par-dessus l'ecran : le menu reste ouvert en cas d'annulation
            Screens.prompt(p, Msg.formatBare(promptKey, kv));
            return;
        }
        p.closeContainer();
        Msg.send(p, promptKey, kv);
        Msg.send(p, "general.input_hint");
    }

    /**
     * Selecteur (objet, Pokemon) : ecran dedie avec le mod cote client, sinon question dans le chat ({@code chatKey}).
     * La valeur choisie arrive dans {@code handler} comme une reponse saisie.
     */
    static void pick(ServerPlayer p, String kind, String titleKey, String chatKey, String initial, Consumer<String> handler, Runnable onCancel) {
        PENDING.put(p.getUUID(), new Pending(handler, onCancel, System.currentTimeMillis() + 300_000));
        if (Screens.enabled(p)) {
            Screens.pick(p, kind, Pickers.title(titleKey), initial);
            return;
        }
        p.closeContainer();
        Msg.send(p, chatKey);
        Msg.send(p, "general.input_hint");
    }

    /** Reponse saisie dans la fenetre de l'ecran client. */
    public static void answer(ServerPlayer p, String text, boolean cancel) {
        if (!PENDING.containsKey(p.getUUID())) return;
        if (!cancel) Screens.forget(p);
        intercept(p, cancel ? "annuler" : text);
    }

    public static void ask(ServerPlayer p, String promptKey, Consumer<String> handler, Object... kv) {
        ask(p, promptKey, handler, null, kv);
    }

    public static boolean waiting(UUID uuid) {
        return PENDING.containsKey(uuid);
    }

    /** @return true si le message a ete capture (a ne pas diffuser). */
    public static boolean intercept(ServerPlayer p, String text) {
        Pending pd = PENDING.remove(p.getUUID());
        if (pd == null) return false;
        if (pd.expires < System.currentTimeMillis()) return false;
        String t = text == null ? "" : text.trim();
        Scheduler.next(() -> {
            if (p.hasDisconnected()) return;
            if (t.equalsIgnoreCase("annuler") || t.equalsIgnoreCase("cancel")) {
                Msg.send(p, "general.input_cancelled");
                if (pd.onCancel != null) pd.onCancel.run();
                return;
            }
            pd.handler.accept(t);
        });
        return true;
    }

    public static void clear(UUID uuid) {
        PENDING.remove(uuid);
    }

    /** Nombre entier positif saisi (null si invalide ; accepte 1k, 2.5m). */
    public static Long parseAmount(String s) {
        if (s == null) return null;
        String v = s.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "").replace("_", "");
        double mult = 1;
        if (v.endsWith("k")) {
            mult = 1_000;
            v = v.substring(0, v.length() - 1);
        } else if (v.endsWith("m")) {
            mult = 1_000_000;
            v = v.substring(0, v.length() - 1);
        }
        try {
            double d = Double.parseDouble(v.replace(',', '.')) * mult;
            if (d < 0 || d > 9e15) return null;
            return Math.round(d);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Duree saisie : 30s, 10m, 2h, 7d / 7j, ou un nombre de secondes. */
    public static Long parseDurationSeconds(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim().toLowerCase(java.util.Locale.ROOT);
        long total = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*([smhdj]?)").matcher(v);
        boolean any = false;
        while (m.find()) {
            any = true;
            long n = Long.parseLong(m.group(1));
            total += switch (m.group(2)) {
                case "m" -> n * 60;
                case "h" -> n * 3600;
                case "d", "j" -> n * 86400;
                default -> n;
            };
        }
        return any ? total : null;
    }
}
