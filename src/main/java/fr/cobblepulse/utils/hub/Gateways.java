package fr.cobblepulse.utils.hub;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.core.ItemBuilder;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Reg;
import fr.cobblepulse.utils.gui.Paged;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Passerelles vers les autres mods CobblePulse (sans dependance de compilation). */
public final class Gateways {
    private Gateways() {}

    /** Caisses & Cles : liste des caisses de CobblePulse Crates, clic = apercu (/lootbox preview). */
    public static void crates(ServerPlayer p) {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("cobblepulse_crates").resolve("caisses");
        List<Paged.Entry> entries = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : ds) files.add(f);
        } catch (Exception e) {
            CobbleUtils.LOG.debug("[CobblePulse-Utils] Caisses illisibles : {}", e.toString());
        }
        files.sort(java.util.Comparator.comparing(f -> f.getFileName().toString()));
        for (Path f : files) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                String id = f.getFileName().toString().replace(".json", "");
                String name = str(o, "nom", "&f" + id);
                String key = str(o, "cle", "minecraft:tripwire_hook");
                int needed = (int) num(o, 1, "cles_requises", "clesRequises");
                boolean maintenance = o.has("maintenance") && o.get("maintenance").getAsBoolean();
                ItemBuilder b = ItemBuilder.of(Reg.itemExists(key) ? Reg.stack(key) : new net.minecraft.world.item.ItemStack(Items.CHEST)).name(name);
                String desc = str(o, "description", "");
                if (!desc.isBlank()) b.lore(desc.split("\n"));
                b.lore(Msg.format("hub.crates_key", "count", needed));
                if (maintenance) b.lore(Msg.format("hub.crates_maintenance"));
                b.lore("", Msg.format("hub.crates_click"));
                entries.add(new Paged.Entry(b.hideExtra().build(), (pl, c) -> {
                    fr.cobblepulse.utils.gui.Gui.close(pl);
                    CobbleUtils.server().getCommands().performPrefixedCommand(pl.createCommandSourceStack(), "lootbox preview " + id);
                }));
            } catch (Exception e) {
                CobbleUtils.LOG.debug("[CobblePulse-Utils] Caisse {} illisible : {}", f, e.toString());
            }
        }
        Paged.open(p, Msg.format("hub.crates_title"), entries, 0, Hub::open);
    }

    private static String str(JsonObject o, String k, String def) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? def : e.getAsString();
    }

    private static double num(JsonObject o, double def, String... keys) {
        for (String k : keys) if (o.has(k) && o.get(k).isJsonPrimitive()) return o.get(k).getAsDouble();
        return def;
    }

    /** Clans, Ligue & Tournois : menu principal du mod CobblePulse. */
    public static void clans(ServerPlayer p) {
        try {
            Class<?> k = Class.forName("fr.cobblepulse.menu.MainMenu");
            k.getMethod("open", ServerPlayer.class).invoke(null, p);
        } catch (Throwable t) {
            CobbleUtils.LOG.debug("[CobblePulse-Utils] Menu Clans indisponible : {}", t.toString());
            CobbleUtils.server().getCommands().performPrefixedCommand(p.createCommandSourceStack(), "clan");
        }
    }
}
