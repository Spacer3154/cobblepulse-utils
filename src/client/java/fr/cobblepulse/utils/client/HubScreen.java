package fr.cobblepulse.utils.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.cobblepulse.utils.core.TextUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Hub Central (touche H, /menu, /cp) : outils joueurs, passerelles CobblePulse et panneau Staff. */
public final class HubScreen extends Screen {
    record Tile(String id, int section, Component name, ItemStack icon, List<Component> lore) {}

    private static final String[] SECTIONS = {"", "Outils", "Écosystème CobblePulse", "Staff"};
    private final List<Tile> tiles = new ArrayList<>();
    private final Component sub;
    private final List<int[]> boxes = new ArrayList<>();
    int hovered = -1;
    /** Auto-test : tuile survolee forcee. */
    int debugHover = -1;

    public HubScreen(JsonObject o) {
        super(TextUtil.legacy(Ui.str(o, "title", "CobblePulse")));
        this.sub = TextUtil.legacy("&7" + Ui.str(o, "player", "") + "  &8•  &7Solde : &e" + Ui.str(o, "balance", "-"));
        for (JsonElement e : Ui.arr(o, "tiles")) {
            JsonObject t = e.getAsJsonObject();
            List<Component> lore = new ArrayList<>();
            for (JsonElement l : Ui.arr(t, "lore")) lore.add(TextUtil.legacy(l.getAsString()));
            tiles.add(new Tile(Ui.str(t, "id", ""), Ui.num(t, "section", 1), TextUtil.legacy(Ui.str(t, "name", "?")), Ui.item(Ui.str(t, "icon", "")), lore));
        }
    }

    public List<String> tileIds() {
        List<String> out = new ArrayList<>();
        for (Tile t : tiles) out.add(t.id);
        return out;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int panelW() {
        return Math.min(width - 24, 560);
    }

    private int tileW = 104;

    private int tileW() {
        return tileW;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderTransparentBackground(g);
        int pw = panelW();
        int cols = Math.max(2, (pw - 20) / (104 + 6));
        // les tuiles occupent toute la largeur du panneau
        tileW = (pw - 20 - (cols - 1) * 6) / cols;
        int rowsTotal = 0;
        for (int s = 1; s <= 3; s++) {
            int n = count(s);
            if (n > 0) rowsTotal += (n + cols - 1) / cols;
        }
        int sections = 0;
        for (int s = 1; s <= 3; s++) if (count(s) > 0) sections++;
        int ph = Math.min(height - 16, 46 + sections * 16 + rowsTotal * 52 + 10);
        int px = (width - pw) / 2;
        int py = Math.max(8, (height - ph) / 2);
        Ui.panel(g, px, py, pw, ph, Ui.PANEL, Ui.BORDER);
        g.fill(px + 1, py + 1, px + pw - 1, py + 34, Ui.PANEL2);
        g.drawString(font, title, px + 12, py + 8, Ui.TEXT, false);
        g.drawString(font, sub, px + 12, py + 20, Ui.MUTED, false);
        String hint = "Échap pour fermer";
        g.drawString(font, hint, px + pw - 12 - font.width(hint), py + 8, Ui.MUTED, false);
        boxes.clear();
        hovered = -1;
        int y = py + 42;
        for (int s = 1; s <= 3; s++) {
            if (count(s) == 0) continue;
            g.drawString(font, SECTIONS[s], px + 12, y, s == 3 ? Ui.ERROR : Ui.ACCENT, false);
            g.fill(px + 12 + font.width(SECTIONS[s]) + 6, y + 4, px + pw - 12, y + 5, Ui.BORDER);
            y += 14;
            int i = 0;
            for (int k = 0; k < tiles.size(); k++) {
                Tile t = tiles.get(k);
                if (t.section != s) continue;
                int c = i % cols;
                int r = i / cols;
                int tx = px + 10 + c * (tileW() + 6);
                int ty = y + r * 52;
                boolean hov = Ui.in(mx, my, tx, ty, tileW(), 46) || debugHover == k;
                if (hov) hovered = k;
                Ui.panel(g, tx, ty, tileW(), 46, hov ? Ui.CARD_ON : Ui.CARD, hov ? Ui.ACCENT : Ui.BORDER);
                List<FormattedCharSequence> lines = font.split(t.name, tileW() - 8);
                boolean two = lines.size() > 1;
                float sc = two ? 1.25f : 1.5f;
                g.pose().pushPose();
                g.pose().translate(tx + tileW() / 2f - 8 * sc, ty + (two ? 3 : 4), 0);
                g.pose().scale(sc, sc, 1f);
                g.renderItem(t.icon, 0, 0);
                g.pose().popPose();
                int ly = two ? ty + 25 : ty + 30;
                for (int li = 0; li < Math.min(2, lines.size()); li++) {
                    g.drawString(font, lines.get(li), tx + (tileW() - font.width(lines.get(li))) / 2, ly + li * 10, Ui.TEXT, true);
                }
                boxes.add(new int[]{k, tx, ty, tileW(), 46});
                i++;
            }
            y += ((count(s) + cols - 1) / cols) * 52 + 2;
        }
        super.render(g, mx, my, pt);
        if (hovered >= 0) {
            Tile t = tiles.get(hovered);
            List<Component> tip = new ArrayList<>();
            tip.add(t.name);
            tip.addAll(t.lore);
            g.renderComponentTooltip(font, tip, mx, my);
        }
    }

    private int count(int s) {
        int n = 0;
        for (Tile t : tiles) if (t.section == s) n++;
        return n;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (int[] b : boxes) {
            if (Ui.in(mx, my, b[1], b[2], b[3], b[4])) {
                click(tiles.get(b[0]).id);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    public void click(String id) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        Ui.send("hub_tile", o);
        if (minecraft != null && minecraft.screen == this) onClose();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
    }
}
