package fr.cobblepulse.utils.client;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Selecteur d'objet des editeurs (boutique, recompenses, icones) : tous les objets du jeu et des mods, recherche par nom
 * ou identifiant, filtre par mod. Clic = selection, double-clic ou Valider = choix.
 */
public final class ItemPickerScreen extends Screen implements Overlay {
    private record Entry(ResourceLocation id, ItemStack stack, String key) {}

    Screen parent;
    private final Component prompt;
    private final List<Entry> all = new ArrayList<>();
    private final List<Entry> visible = new ArrayList<>();
    private final List<String> mods = new ArrayList<>();
    private int modIndex;
    private EditBox search;
    private Button ok;
    private Entry selected;
    private double scroll;
    private int px, py, pw, ph, ax, ay, aw, ah, cols;
    private long lastClick;
    private Entry lastClicked;

    ItemPickerScreen(Screen parent, Component prompt, String initial) {
        super(Component.literal("Choisir un objet"));
        this.parent = parent;
        this.prompt = prompt;
        TreeSet<String> ns = new TreeSet<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            ItemStack st = new ItemStack(item);
            all.add(new Entry(id, st, MenuScreen.norm(st.getHoverName().getString() + " " + id)));
            ns.add(id.getNamespace());
        }
        // Cobblemon puis Minecraft en premier, puis les autres mods
        all.sort((a, b) -> {
            int ra = rank(a.id.getNamespace()), rb = rank(b.id.getNamespace());
            if (ra != rb) return Integer.compare(ra, rb);
            int c = a.id.getNamespace().compareTo(b.id.getNamespace());
            return c != 0 ? c : Integer.compare(BuiltInRegistries.ITEM.getId(a.stack.getItem()), BuiltInRegistries.ITEM.getId(b.stack.getItem()));
        });
        mods.add("");
        if (ns.remove("cobblemon")) mods.add("cobblemon");
        if (ns.remove("minecraft")) mods.add("minecraft");
        mods.addAll(ns);
        ResourceLocation init = ResourceLocation.tryParse(initial == null ? "" : initial.trim().split("\\s+")[0]);
        if (init != null) for (Entry e : all) if (e.id.equals(init)) selected = e;
    }

    private static int rank(String ns) {
        return ns.equals("cobblemon") ? 0 : ns.equals("minecraft") ? 1 : 2;
    }

    private void refilter() {
        visible.clear();
        String q = MenuScreen.norm(search == null ? "" : search.getValue().trim());
        String mod = mods.get(modIndex);
        for (Entry e : all) {
            if (!mod.isEmpty() && !e.id.getNamespace().equals(mod)) continue;
            if (!q.isEmpty() && !e.key.contains(q)) continue;
            visible.add(e);
        }
        scroll = 0;
    }

    @Override
    protected void init() {
        if (parent != null) parent.init(minecraft, width, height);
        pw = Math.min(width - 24, 640);
        ph = height - 16;
        px = (width - pw) / 2;
        py = 8;
        String prev = search == null ? "" : search.getValue();
        search = new EditBox(font, px + pw - 12 - 160, py + 6, 160, 16, Component.literal("Recherche"));
        search.setHint(Component.literal("Nom ou identifiant…").withColor(Ui.MUTED & 0xFFFFFF));
        search.setValue(prev);
        search.setResponder(v -> refilter());
        addRenderableWidget(search);
        setInitialFocus(search);
        addRenderableWidget(Button.builder(Component.literal(modLabel()), b -> {
            modIndex = (modIndex + 1) % mods.size();
            b.setMessage(Component.literal(modLabel()));
            refilter();
        }).bounds(px + pw - 12 - 160 - 6 - 110, py + 5, 110, 18).build());
        int by = py + ph - 26;
        ok = addRenderableWidget(Button.builder(Component.literal("Valider"), b -> submit()).bounds(px + pw - 12 - 200, by, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Annuler"), b -> onClose()).bounds(px + pw - 12 - 98, by, 96, 20).build());
        ax = px + 10;
        ay = py + 32;
        aw = pw - 20;
        ah = ph - 32 - 34;
        cols = Math.max(1, (aw - 6) / 20);
        refilter();
        if (selected != null) {
            int idx = visible.indexOf(selected);
            if (idx >= 0) scroll = Math.max(0, (idx / cols) * 20 - ah / 2);
        }
    }

    private String modLabel() {
        String m = mods.get(modIndex);
        return m.isEmpty() ? "Tous les mods" : Ui.pretty(m);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        if (parent != null) parent.render(g, -1, -1, pt);
        else renderTransparentBackground(g);
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(0, 0, width, height, 0x88000000);
        Ui.panel(g, px, py, pw, ph, Ui.PANEL, Ui.ACCENT);
        g.drawString(font, font.plainSubstrByWidth(prompt.getString(), pw - 320), px + 12, py + 10, Ui.TEXT, false);
        g.pose().popPose();
        // grille (rendu des objets a leur profondeur habituelle, au-dessus du voile)
        int maxScroll = Math.max(0, ((visible.size() + cols - 1) / cols) * 20 - ah);
        scroll = Mth.clamp(scroll, 0, maxScroll);
        g.pose().pushPose();
        g.pose().translate(0, 0, 410);
        g.enableScissor(ax, ay, ax + aw, ay + ah);
        Entry hov = null;
        int first = (int) (scroll / 20) * cols;
        for (int i = first; i < visible.size(); i++) {
            int r = i / cols, c = i % cols;
            int x = ax + c * 20;
            int y = ay + r * 20 - (int) scroll;
            if (y > ay + ah) break;
            Entry e = visible.get(i);
            boolean h = Ui.in(mx, my, x, y, 20, 20) && Ui.in(mx, my, ax, ay, aw, ah);
            if (h) hov = e;
            g.fill(x + 1, y + 1, x + 19, y + 19, e == selected ? 0xFF3B5F7A : h ? 0xFF2C3A4E : 0xFF1A202A);
            g.renderItem(e.stack, x + 2, y + 2);
        }
        g.disableScissor();
        if (maxScroll > 0) {
            int knob = Math.max(16, ah * ah / (ah + maxScroll));
            int ky = ay + (int) ((ah - knob) * (scroll / maxScroll));
            g.fill(ax + aw - 3, ay, ax + aw, ay + ah, 0x40FFFFFF);
            g.fill(ax + aw - 3, ky, ax + aw, ky + knob, Ui.ACCENT);
        }
        // selection
        int fy = py + ph - 26;
        if (selected != null) {
            g.renderItem(selected.stack, px + 12, fy + 2);
            g.drawString(font, selected.stack.getHoverName(), px + 32, fy + 2, Ui.TEXT, false);
            g.drawString(font, selected.id.toString(), px + 32, fy + 12, Ui.MUTED, false);
        } else {
            g.drawString(font, visible.size() + " objet(s) — cliquez pour choisir", px + 12, fy + 6, Ui.MUTED, false);
        }
        g.pose().popPose();
        if (ok != null) ok.active = selected != null;
        g.pose().pushPose();
        g.pose().translate(0, 0, 420);
        super.render(g, mx, my, pt);
        g.pose().popPose();
        if (hov != null) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 500);
            g.renderComponentTooltip(font, List.of(hov.stack.getHoverName(), Component.literal(hov.id.toString()).withColor(Ui.MUTED & 0xFFFFFF)), mx, my);
            g.pose().popPose();
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (!Ui.in(mx, my, ax, ay, aw, ah)) return false;
        int c = (int) ((mx - ax) / 20);
        int r = (int) ((my - ay + scroll) / 20);
        int i = r * cols + c;
        if (c < 0 || c >= cols || i < 0 || i >= visible.size()) return false;
        Entry e = visible.get(i);
        long now = System.currentTimeMillis();
        boolean dbl = e == lastClicked && now - lastClick < 350;
        selected = e;
        lastClicked = e;
        lastClick = now;
        if (dbl) submit();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll -= sy * 24;
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && selected != null) {
            submit();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    void submit() {
        if (selected == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("text", selected.id.toString());
        Ui.send("prompt_answer", o);
        if (minecraft != null) minecraft.setScreen(null);
    }

    @Override
    public void onClose() {
        JsonObject o = new JsonObject();
        o.addProperty("cancel", true);
        Ui.send("prompt_answer", o);
        if (minecraft != null) minecraft.setScreen(parent instanceof MenuScreen ? parent : null);
    }

    @Override
    public Screen parentScreen() {
        return parent;
    }

    @Override
    public void setParentScreen(Screen s) {
        parent = s;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // auto-test
    int visibleCount() {
        return visible.size();
    }

    void setQuery(String q) {
        if (search != null) search.setValue(q);
    }

    boolean choose(String id) {
        for (Entry e : all) if (e.id.toString().equals(id)) {
            selected = e;
            return true;
        }
        return false;
    }
}
