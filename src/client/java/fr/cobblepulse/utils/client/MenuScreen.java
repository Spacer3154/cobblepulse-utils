package fr.cobblepulse.utils.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.locale.Language;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.lwjgl.glfw.GLFW;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Ecran generique des menus CobblePulse (boutique, GTS, STS, warps, homes, kits, editeurs...). Le contenu vient du
 * serveur ; chaque entree est une carte (icone, nom, deux lignes de description, detail complet au survol), les actions
 * de navigation forment la barre du bas. Les listes defilent et proposent une recherche. Clic gauche, clic droit,
 * Maj + clic et clic molette sont transmis tels quels au serveur.
 */
public final class MenuScreen extends Screen {
    record Entry(int slot, ItemStack stack, boolean click, List<Component> lore, String key) {}

    private static final int CARD_W = 172;
    private static final int CARD_H = 40;
    private static final int GAP = 6;
    private static final int HEADER = 28;
    private static final int BAR_H = 20;

    private int id;
    private boolean list;
    private int rows;
    private Component menuTitle = Component.literal("CobblePulse");
    private String titleKey = "";
    private final List<Entry> content = new ArrayList<>();
    private final List<Entry> bar = new ArrayList<>();
    private EditBox search;
    private String query = "";
    private double scroll;
    private int maxScroll;
    private Component notice;
    private long noticeUntil;

    // geometrie calculee a chaque image
    private int px, py, pw, ph, ax, ay, aw, ah, cols, cardW;
    private final List<int[]> boxes = new ArrayList<>(); // {0 = carte / 1 = barre, index, x, y, w, h}
    private Entry hovered;

    MenuScreen(JsonObject o) {
        super(Component.literal("CobblePulse"));
        load(o);
    }

    // ------------------------------------------------------------------ donnees

    void update(JsonObject o) {
        boolean hadSearch = searchable();
        load(o);
        if (minecraft != null && hadSearch != searchable()) rebuildWidgets();
    }

    private void load(JsonObject o) {
        Component t = parse(Ui.str(o, "title", "CobblePulse"));
        boolean same = t.getString().equals(titleKey);
        id = Ui.num(o, "id", 0);
        list = Ui.bool(o, "list", false);
        rows = Ui.num(o, "rows", 6);
        menuTitle = brighten(t);
        titleKey = t.getString();
        content.clear();
        bar.clear();
        for (JsonElement el : Ui.arr(o, "items")) {
            JsonObject it = el.getAsJsonObject();
            ItemStack st = decode(Ui.str(it, "i", ""));
            List<Component> lore = new ArrayList<>();
            ItemLore il = st.get(DataComponents.LORE);
            if (il != null) lore.addAll(il.lines());
            StringBuilder k = new StringBuilder(st.getHoverName().getString());
            for (Component l : lore) k.append(' ').append(l.getString());
            Entry e = new Entry(Ui.num(it, "s", 0), st, Ui.bool(it, "c", false), lore, norm(k.toString()));
            if (Ui.bool(it, "bar", false)) bar.add(e);
            else content.add(e);
        }
        if (!same) {
            scroll = 0;
            query = "";
            if (search != null) search.setValue("");
        }
    }

    private static Component parse(String json) {
        try {
            var conn = Minecraft.getInstance().getConnection();
            MutableComponent c = conn == null ? null : Component.Serializer.fromJson(json, conn.registryAccess());
            return c == null ? Component.literal(json) : c;
        } catch (Exception e) {
            return Component.literal(json);
        }
    }

    static ItemStack decode(String b64) {
        var conn = Minecraft.getInstance().getConnection();
        if (conn == null || b64.isEmpty()) return new ItemStack(Items.BARRIER);
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(Base64.getDecoder().decode(b64)), conn.registryAccess());
        try {
            return ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        } catch (Exception e) {
            return new ItemStack(Items.BARRIER);
        } finally {
            buf.release();
        }
    }

    static String norm(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT);
    }

    /** Les titres sont ecrits pour le fond clair d'un coffre : les couleurs sombres sont eclaircies. */
    static Component brighten(Component c) {
        MutableComponent out = Component.empty();
        c.visit((style, text) -> {
            TextColor col = style.getColor();
            Style s = style;
            if (col == null) s = style.withColor(Ui.TEXT & 0xFFFFFF);
            else {
                int v = col.getValue();
                int r = (v >> 16) & 255, g = (v >> 8) & 255, b = v & 255;
                double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
                // gris fonce / noir (texte principal d'un titre de coffre) -> texte clair ; couleurs sombres -> eclaircies
                if (v == 0x555555 || v == 0) s = style.withColor(Ui.TEXT & 0xFFFFFF);
                else if (lum < 0.35) s = style.withColor(((r + 255) / 2 << 16) | ((g + 255) / 2 << 8) | ((b + 255) / 2));
            }
            out.append(Component.literal(text).withStyle(s));
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    void notice(Component c) {
        notice = c;
        noticeUntil = System.currentTimeMillis() + 5000;
    }

    private boolean searchable() {
        return list && content.size() > 12;
    }

    private List<Entry> visible() {
        if (query.isBlank()) return content;
        String q = norm(query.trim());
        List<Entry> out = new ArrayList<>();
        for (Entry e : content) if (e.key.contains(q)) out.add(e);
        return out;
    }

    @Override
    public Component getTitle() {
        return menuTitle;
    }

    // ------------------------------------------------------------------ mise en page

    @Override
    protected void init() {
        layout();
        search = null;
        if (searchable()) {
            search = new EditBox(font, px + pw - 12 - 150, py + 6, 150, 16, Component.literal("Recherche"));
            search.setHint(Component.literal("Rechercher…").withColor(Ui.MUTED & 0xFFFFFF));
            search.setValue(query);
            search.setResponder(v -> {
                query = v;
                scroll = 0;
            });
            addRenderableWidget(search);
        }
    }

    /** Groupes de cartes : une grille pour les listes, une ligne par rangee du menu sinon. */
    private List<List<Entry>> groups(List<Entry> vis) {
        List<List<Entry>> out = new ArrayList<>();
        if (list) {
            out.add(vis);
            return out;
        }
        int last = -1;
        for (Entry e : vis) {
            int r = e.slot < rows * 9 ? e.slot / 9 : 99;
            if (r != last || out.isEmpty()) {
                out.add(new ArrayList<>());
                last = r;
            }
            out.get(out.size() - 1).add(e);
        }
        return out;
    }

    /** Largeur des cartes d'une rangee : une rangee d'un menu (boutons -64/+64...) tient sur une ligne si possible. */
    private int groupW(int n) {
        if (list || n <= cols) return cardW;
        int w = (aw - 6 - (n - 1) * GAP) / n;
        return w >= 78 ? w : cardW;
    }

    private int perLine(int n) {
        return groupW(n) == cardW ? cols : n;
    }

    private int contentHeight(List<List<Entry>> groups) {
        int h = 0;
        for (List<Entry> g : groups) {
            if (g.isEmpty()) continue;
            int lines = (g.size() + perLine(g.size()) - 1) / perLine(g.size());
            h += lines * (CARD_H + GAP) + (list ? 0 : 4);
        }
        return Math.max(0, h - GAP - (list ? 0 : 4));
    }

    private void layout() {
        pw = Math.min(width - 24, 640);
        px = (width - pw) / 2;
        int inner = pw - 20 - 6;
        cols = Math.max(1, (inner + GAP) / (CARD_W + GAP));
        cardW = (inner - (cols - 1) * GAP) / cols;
        aw = pw - 20;
        int contentH = contentHeight(groups(visible()));
        int barH = bar.isEmpty() ? 0 : BAR_H + 10;
        if (list) ph = height - 16;
        else ph = Math.min(height - 16, HEADER + 8 + Math.max(contentH, 24) + 10 + barH);
        py = (height - ph) / 2;
        ax = px + 10;
        ay = py + HEADER + 8;
        aw = pw - 20;
        ah = ph - HEADER - 8 - 10 - barH;
        maxScroll = Math.max(0, contentH - ah);
        scroll = Mth.clamp(scroll, 0, maxScroll);
    }

    // ------------------------------------------------------------------ rendu

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderTransparentBackground(g);
        layout();
        boxes.clear();
        hovered = null;
        Ui.panel(g, px, py, pw, ph, Ui.PANEL, Ui.BORDER);
        g.fill(px + 1, py + 1, px + pw - 1, py + HEADER, Ui.PANEL2);
        g.fill(px + 1, py + HEADER, px + pw - 1, py + HEADER + 1, Ui.BORDER);
        int titleMax = pw - 24 - (search != null ? 160 : font.width("Échap pour fermer") + 10);
        drawClipped(g, menuTitle, px + 12, py + 10, titleMax, Ui.TEXT);
        if (search == null) {
            String hint = "Échap pour fermer";
            g.drawString(font, hint, px + pw - 12 - font.width(hint), py + 10, Ui.MUTED, false);
        }

        // cartes
        List<Entry> vis = visible();
        List<List<Entry>> groups = groups(vis);
        g.enableScissor(ax, ay, ax + aw, ay + ah);
        int y = ay - (int) scroll;
        for (List<Entry> grp : groups) {
            if (grp.isEmpty()) continue;
            int n = grp.size();
            int w = groupW(n);
            int per = perLine(n);
            int lineW = Math.min(n, per) * w + (Math.min(n, per) - 1) * GAP;
            int x0 = list ? ax : ax + (aw - 6 - lineW) / 2;
            for (int i = 0; i < n; i++) {
                int cx = x0 + (i % per) * (w + GAP);
                int cy = y + (i / per) * (CARD_H + GAP);
                Entry e = grp.get(i);
                if (cy + CARD_H >= ay && cy <= ay + ah) {
                    boolean hov = Ui.in(mx, my, cx, cy, w, CARD_H) && Ui.in(mx, my, ax, ay, aw, ah);
                    if (hov) hovered = e;
                    drawCard(g, e, cx, cy, w, CARD_H, hov);
                    boxes.add(new int[]{0, content.indexOf(e), cx, cy, w, CARD_H});
                }
            }
            y += ((n + per - 1) / per) * (CARD_H + GAP) + (list ? 0 : 4);
        }
        g.disableScissor();
        if (vis.isEmpty()) {
            String empty = query.isBlank() ? "Rien à afficher pour le moment." : "Aucun résultat pour « " + query.trim() + " ».";
            g.drawString(font, empty, ax + (aw - font.width(empty)) / 2, ay + 16, Ui.MUTED, false);
        }
        if (maxScroll > 0) {
            int track = ah;
            int knob = Math.max(16, track * ah / (ah + maxScroll));
            int ky = ay + (int) ((track - knob) * (scroll / maxScroll));
            g.fill(ax + aw - 3, ay, ax + aw, ay + track, 0x40FFFFFF);
            g.fill(ax + aw - 3, ky, ax + aw, ky + knob, Ui.ACCENT);
        }

        // barre d'actions
        if (!bar.isEmpty()) {
            int by = py + ph - 8 - BAR_H;
            List<Integer> widths = new ArrayList<>();
            int total = 0;
            for (Entry e : bar) {
                int w = 24 + Math.min(130, font.width(e.stack.getHoverName())) + 6;
                widths.add(w);
                total += w + 6;
            }
            boolean compact = total - 6 > aw;
            if (compact) total = bar.size() * 26 - 6;
            else total -= 6;
            int bx = px + (pw - total) / 2;
            for (int i = 0; i < bar.size(); i++) {
                Entry e = bar.get(i);
                int w = compact ? 20 : widths.get(i);
                boolean hov = Ui.in(mx, my, bx, by, w, BAR_H);
                if (hov) hovered = e;
                Ui.panel(g, bx, by, w, BAR_H, hov && e.click ? Ui.CARD_ON : Ui.CARD, hov ? (e.click ? Ui.ACCENT : Ui.MUTED) : Ui.BORDER);
                g.renderItem(e.stack, bx + 2, by + 2);
                if (!compact) drawClipped(g, e.stack.getHoverName(), bx + 22, by + 6, w - 26, Ui.TEXT);
                boxes.add(new int[]{1, i, bx, by, w, BAR_H});
                bx += (compact ? 26 : w + 6);
            }
        }

        super.render(g, mx, my, pt);

        // message du serveur (achat reussi, erreur...) : les menus couvrent le chat
        if (notice != null && System.currentTimeMillis() < noticeUntil) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(notice, aw - 16);
            int nh = Math.min(2, lines.size()) * 10 + 8;
            int ny = ay + ah - nh;
            Ui.panel(g, ax, ny, aw, nh, 0xF0122133, Ui.ACCENT);
            for (int i = 0; i < Math.min(2, lines.size()); i++) g.drawString(font, lines.get(i), ax + 8, ny + 5 + i * 10, Ui.TEXT, false);
            g.pose().popPose();
        }

        if (hovered != null) {
            List<Component> tip = new ArrayList<>();
            tip.add(hovered.stack.getHoverName());
            tip.addAll(hovered.lore);
            g.renderComponentTooltip(font, tip, mx, my);
        }
    }

    private void drawCard(GuiGraphics g, Entry e, int x, int y, int w, int h, boolean hov) {
        Ui.panel(g, x, y, w, h, hov && e.click ? Ui.CARD_ON : Ui.CARD, hov ? (e.click ? Ui.ACCENT : Ui.MUTED) : Ui.BORDER);
        if (w < 130) {
            // carte etroite (rangee de boutons) : icone au-dessus du nom, description au survol
            g.renderItem(e.stack, x + (w - 16) / 2, y + 4);
            g.renderItemDecorations(font, e.stack, x + (w - 16) / 2, y + 4);
            Component name = e.stack.getHoverName();
            int nw = Math.min(font.width(name), w - 6);
            drawClipped(g, name, x + (w - nw) / 2, y + 26, w - 6, Ui.TEXT);
            return;
        }
        g.pose().pushPose();
        g.pose().translate(x + 5, y + (h - 24) / 2f, 0);
        g.pose().scale(1.5f, 1.5f, 1f);
        g.renderItem(e.stack, 0, 0);
        g.renderItemDecorations(font, e.stack, 0, 0);
        g.pose().popPose();
        List<Component> lines = new ArrayList<>();
        for (Component l : e.lore) {
            if (lines.size() >= 2) break;
            if (!l.getString().isBlank()) lines.add(l);
        }
        int tx = x + 35;
        int tw = w - 40;
        int ty = y + (h - ((1 + lines.size()) * 10 - 1)) / 2;
        drawClipped(g, e.stack.getHoverName(), tx, ty, tw, Ui.TEXT);
        for (int i = 0; i < lines.size(); i++) drawClipped(g, lines.get(i), tx, ty + 10 * (i + 1), tw, Ui.MUTED);
    }

    private void drawClipped(GuiGraphics g, Component c, int x, int y, int maxW, int color) {
        if (font.width(c) <= maxW) {
            g.drawString(font, c, x, y, color, false);
            return;
        }
        FormattedText cut = font.substrByWidth(c, Math.max(0, maxW - font.width("…")));
        g.drawString(font, Language.getInstance().getVisualOrder(FormattedText.composite(cut, FormattedText.of("…"))), x, y, color, false);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
    }

    // ------------------------------------------------------------------ interactions

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        for (int[] b : boxes) {
            if (!Ui.in(mx, my, b[2], b[3], b[4], b[5])) continue;
            if (b[0] == 0 && !Ui.in(mx, my, ax, ay, aw, ah)) continue;
            Entry e = b[0] == 0 ? content.get(b[1]) : bar.get(b[1]);
            if (!e.click) return true;
            String kind = button == 1 ? "right" : button == 2 ? "middle" : hasShiftDown() ? "shift" : "left";
            click(e.slot, kind);
            return true;
        }
        return false;
    }

    void click(int slot, String kind) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("slot", slot);
        o.addProperty("kind", kind);
        Ui.send("menu_click", o);
        if (minecraft != null) minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (maxScroll > 0) {
            scroll = Mth.clamp(scroll - sy * 26, 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        boolean typing = search != null && search.isFocused();
        if (!typing && minecraft != null && minecraft.options.keyInventory.matches(key, scan)) {
            onClose();
            return true;
        }
        if (key == GLFW.GLFW_KEY_F && (mods & GLFW.GLFW_MOD_CONTROL) != 0 && search != null) {
            setFocused(search);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void onClose() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        Ui.send("menu_close", o);
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ auto-test (client de CI)

    int menuId() {
        return id;
    }

    List<String> entryNames() {
        List<String> out = new ArrayList<>();
        for (Entry e : content) out.add(e.stack.getHoverName().getString());
        return out;
    }

    List<String> barNames() {
        List<String> out = new ArrayList<>();
        for (Entry e : bar) out.add(e.stack.getHoverName().getString());
        return out;
    }

    boolean hasNotice() {
        return notice != null && System.currentTimeMillis() < noticeUntil;
    }

    String noticeText() {
        return notice == null ? "-" : notice.getString();
    }

    int visibleCount() {
        return visible().size();
    }

    boolean hasSearch() {
        return search != null;
    }

    void setQuery(String q) {
        query = q;
        if (search != null) search.setValue(q);
    }

    /** Clique la premiere entree (cartes puis barre) dont le nom contient le texte. */
    boolean clickNamed(String part, String kind) {
        String p = norm(part);
        for (Entry e : content) if (e.click && norm(e.stack.getHoverName().getString()).contains(p)) {
            click(e.slot, kind);
            return true;
        }
        for (Entry e : bar) if (e.click && norm(e.stack.getHoverName().getString()).contains(p)) {
            click(e.slot, kind);
            return true;
        }
        return false;
    }

    boolean clickFirst(String kind) {
        for (Entry e : content) if (e.click) {
            click(e.slot, kind);
            return true;
        }
        return false;
    }
}
