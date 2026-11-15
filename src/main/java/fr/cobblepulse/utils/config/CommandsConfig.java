package fr.cobblepulse.utils.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * utils/commands.json : activation, alias et acces par defaut de chaque commande.
 * « default_access » ne sert que si LuckPerms ne definit pas le noeud cobblepulse.command.<nom>
 * (true : tous les joueurs ; false : operateurs uniquement).
 */
public final class CommandsConfig {
    public Map<String, Entry> commands = new LinkedHashMap<>();

    public static final class Entry {
        public boolean enabled = true;
        public boolean defaultAccess = true;
        public List<String> aliases = new ArrayList<>();

        public Entry() {}

        Entry(boolean def, String... aliases) {
            this.defaultAccess = def;
            this.aliases = new ArrayList<>(List.of(aliases));
        }
    }

    /** Commandes connues : nom -> (acces par defaut, alias). Les commandes admin ont default_access=false. */
    private static Map<String, Entry> defaults() {
        Map<String, Entry> m = new LinkedHashMap<>();
        // 4.1 Interfaces
        m.put("menu", new Entry(true, "cobblepulse", "cp"));
        m.put("wiki", new Entry(true, "pwiki", "pokedex"));
        m.put("warp", new Entry(true, "warps"));
        m.put("home", new Entry(true, "homes"));
        m.put("shop", new Entry(true, "boutique"));
        m.put("gts", new Entry(true, "ah", "hdv"));
        m.put("sts", new Entry(true, "sellpoke"));
        m.put("wondertrade", new Entry(true, "wt", "miracle"));
        m.put("dex", new Entry(true, "dexrewards", "dexr"));
        m.put("kit", new Entry(true, "kits"));
        m.put("enderchest", new Entry(true, "ec"));
        m.put("spawns", new Entry(true, "radar", "history"));
        m.put("playtimerewards", new Entry(true, "ptr", "ptrewards"));
        // 4.2 Teleportation
        m.put("spawn", new Entry(true));
        m.put("sethome", new Entry(true));
        m.put("delhome", new Entry(true, "remhome"));
        m.put("tpa", new Entry(true));
        m.put("tpahere", new Entry(true));
        m.put("tpaccept", new Entry(true, "tpyes"));
        m.put("tpdeny", new Entry(true, "tpno"));
        m.put("tptoggle", new Entry(true));
        m.put("back", new Entry(true, "return"));
        m.put("rtp", new Entry(true, "wild"));
        m.put("worlds", new Entry(true, "mondes", "monde"));
        m.put("top", new Entry(false));
        m.put("jump", new Entry(false, "j"));
        // 4.3 Confort & social
        m.put("pokeshout", new Entry(true, "ps"));
        m.put("pokeshoutall", new Entry(true, "psa"));
        m.put("pokeheal", new Entry(false, "pcheal"));
        m.put("pc", new Entry(false, "pokebox"));
        m.put("fly", new Entry(false));
        m.put("heal", new Entry(false));
        m.put("feed", new Entry(false, "eat"));
        m.put("hat", new Entry(false));
        m.put("trash", new Entry(true, "bin", "poubelle"));
        m.put("repair", new Entry(false, "fix"));
        m.put("craft", new Entry(false, "wb", "workbench"));
        m.put("anvil", new Entry(false));
        m.put("nick", new Entry(false));
        m.put("msg", new Entry(true, "w", "tell", "m"));
        m.put("reply", new Entry(true, "r"));
        m.put("ignore", new Entry(true));
        m.put("list", new Entry(true, "online", "who"));
        m.put("playtime", new Entry(true, "pt"));
        // 4.4 Economie
        m.put("sell", new Entry(true));
        m.put("balance", new Entry(true, "bal", "money"));
        m.put("pay", new Entry(true));
        // 4.5 Staff (noeuds cobblepulse.admin.*)
        m.put("setspawn", new Entry(false));
        m.put("setwarp", new Entry(false));
        m.put("delwarp", new Entry(false));
        m.put("invsee", new Entry(false));
        m.put("endersee", new Entry(false, "ecsee"));
        m.put("tphere", new Entry(false, "s"));
        m.put("tpall", new Entry(false));
        m.put("vanish", new Entry(false, "v"));
        m.put("god", new Entry(false, "tgm"));
        m.put("gamemode", new Entry(false, "gm"));
        m.put("speed", new Entry(false));
        m.put("broadcast", new Entry(false, "bc", "alert"));
        m.put("sudo", new Entry(false));
        m.put("mute", new Entry(false));
        m.put("unmute", new Entry(false));
        m.put("tempban", new Entry(false));
        return m;
    }

    public void normalize() {
        if (commands == null) commands = new LinkedHashMap<>();
        Map<String, Entry> def = defaults();
        Map<String, Entry> out = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> e : def.entrySet()) {
            Entry user = commands.get(e.getKey());
            if (user == null) user = e.getValue();
            if (user.aliases == null) user.aliases = new ArrayList<>();
            out.put(e.getKey(), user);
        }
        commands = out;
    }

    public Entry get(String name) {
        Entry e = commands.get(name);
        return e == null ? new Entry() : e;
    }

    public boolean enabled(String name) {
        return get(name).enabled;
    }

    /** Acces par defaut quand LuckPerms ne definit rien (null si commande inconnue). */
    public Boolean defaultFor(String name) {
        Entry e = commands.get(name);
        return e == null ? null : e.defaultAccess;
    }
}
