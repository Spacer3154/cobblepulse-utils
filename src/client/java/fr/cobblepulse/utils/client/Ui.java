package fr.cobblepulse.utils.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.cobblepulse.utils.core.Json;
import fr.cobblepulse.utils.net.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Couleurs et petites aides de dessin partagees par le Hub et le Wiki. */
public final class Ui {
    public static final int BG = 0xD80B0E13;
    public static final int PANEL = 0xF0141922;
    public static final int PANEL2 = 0xF01B2230;
    public static final int CARD = 0xFF1A202A;
    public static final int CARD_ON = 0xFF263347;
    public static final int BORDER = 0xFF2E3A4C;
    public static final int ACCENT = 0xFF5FD3F3;
    public static final int GOLD = 0xFFFFD24A;
    public static final int TEXT = 0xFFE8ECF2;
    public static final int MUTED = 0xFF8E9AAB;
    public static final int OK = 0xFF6BE07A;
    public static final int ERROR = 0xFFFF6B6B;
    public static final int PURPLE = 0xFFC48CFF;

    private Ui() {}

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    public static void send(String action, JsonObject args) {
        if (!ClientPlayNetworking.canSend(Payloads.Action.TYPE)) return;
        ClientPlayNetworking.send(new Payloads.Action(action, args == null ? "{}" : Json.COMPACT.toJson(args)));
    }

    public static void panel(GuiGraphics g, int x, int y, int w, int h, int fill, int border) {
        g.fill(x, y, x + w, y + h, fill);
        g.fill(x, y, x + w, y + 1, border);
        g.fill(x, y + h - 1, x + w, y + h, border);
        g.fill(x, y, x + 1, y + h, border);
        g.fill(x + w - 1, y, x + w, y + h, border);
    }

    public static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    public static ItemStack item(String id) {
        if (id == null || id.isBlank()) return ItemStack.EMPTY;
        ResourceLocation rl = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) return new ItemStack(Items.PAPER);
        return new ItemStack(BuiltInRegistries.ITEM.get(rl));
    }

    /** Traduction avec repli lisible quand la cle n'existe pas (biomes de mods, fakemons). */
    public static Component tr(String key, String fallback) {
        if (key == null || key.isBlank()) return Component.literal(fallback == null ? "" : fallback);
        if (I18n.exists(key)) return Component.translatable(key);
        return Component.literal(fallback != null && !fallback.isBlank() ? fallback : key);
    }

    public static String pretty(String path) {
        String p = path.contains(":") ? path.substring(path.indexOf(':') + 1) : path;
        p = p.contains("/") ? p.substring(p.lastIndexOf('/') + 1) : p;
        StringBuilder b = new StringBuilder();
        for (String part : p.split("[_\\-]")) {
            if (part.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return b.toString();
    }

    public static Component biome(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return Component.literal(id);
        return tr("biome." + rl.getNamespace() + "." + rl.getPath().replace('/', '.'), pretty(rl.getPath()));
    }

    public static Component block(String id) {
        if (id.startsWith("#")) return Component.literal(pretty(id.substring(1)) + " (tag)");
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return Component.literal(id);
        if (BuiltInRegistries.BLOCK.containsKey(rl)) return BuiltInRegistries.BLOCK.get(rl).getName();
        return Component.literal(pretty(rl.getPath()));
    }

    public static Component itemName(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id == null ? "" : id);
        if (rl != null && BuiltInRegistries.ITEM.containsKey(rl)) return BuiltInRegistries.ITEM.get(rl).getDescription();
        return Component.literal(id == null ? "?" : pretty(id));
    }

    public static String str(JsonObject o, String k, String def) {
        JsonElement e = o == null ? null : o.get(k);
        return e == null || e.isJsonNull() ? def : e.getAsString();
    }

    public static int num(JsonObject o, String k, int def) {
        JsonElement e = o == null ? null : o.get(k);
        return e == null || e.isJsonNull() ? def : e.getAsInt();
    }

    public static double dbl(JsonObject o, String k, double def) {
        JsonElement e = o == null ? null : o.get(k);
        return e == null || e.isJsonNull() ? def : e.getAsDouble();
    }

    public static boolean bool(JsonObject o, String k, boolean def) {
        JsonElement e = o == null ? null : o.get(k);
        return e == null || e.isJsonNull() ? def : e.getAsBoolean();
    }

    public static JsonArray arr(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    public static JsonObject obj(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }
}
