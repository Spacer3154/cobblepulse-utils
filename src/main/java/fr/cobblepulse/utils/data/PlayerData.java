package fr.cobblepulse.utils.data;

import fr.cobblepulse.utils.core.Loc;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Donnees persistantes d'un joueur (table players, colonne data en JSON). */
public final class PlayerData {
    public String name = "";
    public Map<String, Loc> homes = new LinkedHashMap<>();
    public String nick = "";
    /** Derniere position avant teleportation ou mort (/back). */
    public Loc back;
    public boolean backIsDeath;
    /** Prochaine utilisation autorisee (ms epoch) par cle : rtp, back, pokeheal, wt, tpa, kit:<nom>, boss:<palier>. */
    public Map<String, Long> cooldowns = new LinkedHashMap<>();
    /** Kits a usage unique deja recuperes. */
    public Set<String> kitsClaimed = new HashSet<>();
    public Set<UUID> ignored = new HashSet<>();
    /** true : refuse les demandes /tpa. */
    public boolean tpToggle;
    /** 0 = pas muet, -1 = definitif, sinon fin (ms epoch). */
    public long mutedUntil;
    public String muteReason = "";
    public boolean vanished;
    public boolean god;
    /** Paliers de Dex Rewards reclames. */
    public Set<String> dexClaimed = new HashSet<>();
    public boolean ecImported;
    /** Temps de jeu cumule (ticks) connu a la derniere deconnexion. */
    public long playtimeTicks;
    /** Temps de jeu actif (hors AFK) en secondes, pour les recompenses de temps de jeu. */
    public long activeSeconds;
    /** Initialise depuis la statistique vanilla a la premiere prise en compte. */
    public boolean activeSeeded;
    /** Progression vers la prochaine recompense repetable (secondes actives). */
    public long repeatSeconds;
    /** Paliers de temps de jeu reclames / deja signales. */
    public Set<String> playtimeClaimed = new HashSet<>();
    public Set<String> playtimeNotified = new HashSet<>();
    public long firstJoin;
    public long lastSeen;

    public void normalize() {
        if (name == null) name = "";
        if (homes == null) homes = new LinkedHashMap<>();
        if (nick == null) nick = "";
        if (cooldowns == null) cooldowns = new LinkedHashMap<>();
        if (kitsClaimed == null) kitsClaimed = new HashSet<>();
        if (ignored == null) ignored = new HashSet<>();
        if (muteReason == null) muteReason = "";
        if (dexClaimed == null) dexClaimed = new HashSet<>();
        if (playtimeClaimed == null) playtimeClaimed = new HashSet<>();
        if (playtimeNotified == null) playtimeNotified = new HashSet<>();
    }

    public long cooldownLeft(String key) {
        Long until = cooldowns.get(key);
        if (until == null) return 0;
        long left = until - System.currentTimeMillis();
        if (left <= 0) {
            cooldowns.remove(key);
            return 0;
        }
        return left;
    }

    public void setCooldown(String key, long ms) {
        if (ms <= 0) cooldowns.remove(key);
        else cooldowns.put(key, System.currentTimeMillis() + ms);
    }

    public boolean muted() {
        if (mutedUntil == 0) return false;
        if (mutedUntil > 0 && mutedUntil < System.currentTimeMillis()) {
            mutedUntil = 0;
            muteReason = "";
            return false;
        }
        return true;
    }
}
