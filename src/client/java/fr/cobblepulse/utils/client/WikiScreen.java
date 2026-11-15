package fr.cobblepulse.utils.client;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.api.types.ElementalTypes;
import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget;
import com.cobblemon.mod.common.item.PokemonItem;
import com.cobblemon.mod.common.pokemon.RenderablePokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.cobblepulse.utils.core.TextUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Wiki Cobblemon : recherche par nom ou numero, modele 3D interactif (normal / chromatique, formes),
 * statistiques, talents, evolutions, capacites, spawns par biome (noms traduits, groupes par mod) et Boss.
 * Les especes viennent des registres synchronises par le serveur (Cobblemon + datapacks / fakemons).
 */
public final class WikiScreen extends Screen {
    private static final String[] TABS = {"Infos", "Évolutions", "Capacités", "Spawns", "Boss"};
    private static final String[] MOVE_TABS = {"Niveau", "CT", "Œuf", "Tuteur"};
    private static final String[] MOVE_KEYS = {"level", "tm", "egg", "tutor"};

    private final List<Species> all = new ArrayList<>();
    private final List<Species> filtered = new ArrayList<>();
    private final Map<String, JsonObject> cache = new HashMap<>();
    private EditBox search;
    private int listScroll;
    private Species selected;
    private JsonObject data;
    private JsonObject bosses;
    int tab;
    int moveTab;
    private int contentScroll;
    private int contentHeight;
    private boolean shiny;
    private int formIndex;
    private ModelWidget model;
    private float rotation = 325f;
    private boolean dragging;
    private final List<Runnable> clickables = new ArrayList<>();
    private final List<int[]> clickBoxes = new ArrayList<>();
    private String pendingSelect;
    /** Filtre : seulement les especes avec Mega-Evolution. */
    private boolean megaOnly;
    private static final java.util.regex.Pattern MEGA = java.util.regex.Pattern.compile("(?i)^mega([ _-]?[xyz])?$");
    private final java.util.Map<Species, Boolean> megaCache = new java.util.HashMap<>();

    public WikiScreen(String speciesId) {
        super(Component.literal("Wiki Cobblemon"));
        this.pendingSelect = speciesId;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        all.clear();
        for (Species s : PokemonSpecies.getSpecies()) {
            if (!s.getImplemented() && "cobblemon".equals(s.getResourceIdentifier().getNamespace())) continue;
            all.add(s);
        }
        all.sort(Comparator.comparingInt(Species::getNationalPokedexNumber).thenComparing(s -> s.getResourceIdentifier().toString()));
        String prev = search == null ? "" : search.getValue();
        search = new EditBox(font, 10, 26, listW() - 26, 16, Component.literal("Rechercher"));
        search.setHint(Component.literal("Nom ou n° de Pokédex").withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(prev);
        search.setResponder(v -> {
            listScroll = 0;
            refilter();
        });
        addRenderableWidget(search);
        refilter();
        if (bosses == null) Ui.send("wiki_bosses", null);
        if (pendingSelect != null) {
            Species s = byId(pendingSelect);
            pendingSelect = null;
            if (s != null) select(s);
        } else if (selected == null && !filtered.isEmpty()) {
            select(filtered.get(0));
        } else if (selected != null) {
            buildModel();
        }
    }

    private int listW() {
        return Math.max(130, Math.min(170, width / 4));
    }

    private static Species byId(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? null : PokemonSpecies.getByIdentifier(rl);
    }

    /** L'espece possede une forme Mega (donnees de Cobblemon / Mega Showdown synchronisees). */
    boolean hasMega(Species s) {
        return megaCache.computeIfAbsent(s, sp -> {
            for (var f : sp.getForms()) if (f.getName() != null && MEGA.matcher(f.getName().trim()).matches()) return true;
            return false;
        });
    }

    public void setMegaOnly(boolean v) {
        megaOnly = v;
        listScroll = 0;
        refilter();
    }

    private void refilter() {
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        filtered.clear();
        for (Species s : all) {
            if (megaOnly && !hasMega(s)) continue;
            if (q.isEmpty()) {
                filtered.add(s);
                continue;
            }
            if (q.chars().allMatch(Character::isDigit)) {
                if (String.valueOf(s.getNationalPokedexNumber()).startsWith(q.replaceFirst("^0+(?=.)", ""))) filtered.add(s);
                continue;
            }
            String name = s.getTranslatedName().getString().toLowerCase(Locale.ROOT);
            if (name.contains(q) || s.getResourceIdentifier().getPath().contains(q) || s.getName().toLowerCase(Locale.ROOT).contains(q)) filtered.add(s);
        }
    }

    public void select(Species s) {
        selected = s;
        formIndex = 0;
        shiny = false;
        contentScroll = 0;
        String id = s.getResourceIdentifier().toString();
        data = cache.get(id);
        if (data == null) {
            JsonObject o = new JsonObject();
            o.addProperty("species", id);
            Ui.send("wiki_species", o);
        }
        buildModel();
    }

    public String selectedId() {
        return selected == null ? "" : selected.getResourceIdentifier().toString();
    }

    public boolean hasData() {
        return data != null;
    }

    public void onSpecies(JsonObject o) {
        String id = Ui.str(o, "species", "");
        cache.put(id, o);
        if (selected != null && selected.getResourceIdentifier().toString().equals(id)) data = o;
    }

    public void onBosses(JsonObject o) {
        bosses = o;
    }

    public void setShiny(boolean v) {
        shiny = v;
        buildModel();
    }

    private Set<String> aspects() {
        Set<String> a = new HashSet<>();
        if (shiny) a.add("shiny");
        JsonObject f = currentForm();
        for (JsonElement e : Ui.arr(f, "aspects")) a.add(e.getAsString());
        if (data == null && selected != null && formIndex > 0 && formIndex < selected.getForms().size() + 1) {
            a.addAll(selected.getForms().get(formIndex - 1).getAspects());
        }
        return a;
    }

    private JsonObject currentForm() {
        if (data == null) return new JsonObject();
        JsonObject info = Ui.obj(data, "info");
        JsonArray forms = Ui.arr(info, "forms");
        if (formIndex > 0 && formIndex <= forms.size()) return forms.get(formIndex - 1).getAsJsonObject();
        return Ui.obj(info, "base");
    }

    /** « Méga-Dracaufeu X », « Primo-Groudon », « Forme Originelle »... */
    String formLabel(JsonObject f) {
        String name = Ui.str(f, "name", "?");
        String kind = Ui.str(f, "special", "");
        String sp = selected == null ? "" : selected.getTranslatedName().getString();
        return switch (kind) {
            case "mega" -> {
                String sfx = name.trim().length() > 4 ? name.trim().substring(name.trim().length() - 1).toUpperCase(Locale.ROOT) : "";
                yield "Méga-" + sp + (sfx.matches("[XYZ]") ? " " + sfx : "");
            }
            case "primal" -> "Primo-" + sp;
            case "origin" -> "Forme Originelle";
            default -> name;
        };
    }

    /** Nom de l'objet de forme (Méga-Gemme, Orbe...) : objet du registre, sinon nom deduit de Cobblemon. */
    private Component stoneName(JsonObject f) {
        String id = Ui.str(f, "stone", "");
        if (!id.isEmpty()) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null && net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(rl)) {
                return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl).getDescription();
            }
        }
        String hint = Ui.str(f, "stone_hint", "");
        if (hint.isEmpty()) return Component.literal("?");
        String h = hint.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (h.matches(".*ite[xyz]$")) h = h.substring(0, h.length() - 1) + "_" + h.charAt(h.length() - 1);
        return Component.literal(Ui.pretty(h).replaceAll(" ([xyz])$", " $1").replaceAll(" X$", " X"));
    }

    private ItemStack stoneIcon(JsonObject f) {
        String id = Ui.str(f, "stone", "");
        ItemStack st = id.isEmpty() ? ItemStack.EMPTY : Ui.item(id);
        return st.isEmpty() || st.is(net.minecraft.world.item.Items.PAPER) ? new ItemStack(net.minecraft.world.item.Items.AMETHYST_SHARD) : st;
    }

    /** Explication de la forme speciale affichee (Infos). */
    private int specialBanner(GuiGraphics g, int x, int y, int w, JsonObject f, JsonObject info) {
        String kind = Ui.str(f, "special", "");
        if (kind.isEmpty()) return y;
        Component stone = stoneName(f);
        String text = switch (kind) {
            case "mega" -> "Méga-Évolution : en combat, si le Pokémon tient ";
            case "primal" -> "Primo-Résurgence : au début du combat, si le Pokémon tient ";
            default -> "Forme Originelle : quand le Pokémon tient ";
        };
        MutableComponent c = Component.literal(text).withStyle(ChatFormatting.GRAY).append(stone.copy().withStyle(ChatFormatting.LIGHT_PURPLE));
        if (kind.equals("mega")) c.append(Component.literal(" et que le dresseur porte un Méga-Anneau.").withStyle(ChatFormatting.GRAY));
        else c.append(Component.literal(".").withStyle(ChatFormatting.GRAY));
        List<FormattedCharSequence> lines = font.split(c, w - 30);
        boolean noMod = !Ui.bool(info, "mega_showdown", false);
        int h = 8 + lines.size() * 10 + (noMod ? 10 : 0);
        Ui.panel(g, x, y, w, h, 0xFF2A1E36, Ui.PURPLE);
        g.renderItem(stoneIcon(f), x + 5, y + (h - 16) / 2);
        for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x + 26, y + 5 + i * 10, Ui.TEXT, false);
        if (noMod) g.drawString(font, "Nécessite le mod Mega Showdown sur le serveur.", x + 26, y + 5 + lines.size() * 10, Ui.MUTED, false);
        return y + h + 6;
    }

    private int formIndexOf(String name) {
        JsonArray forms = Ui.arr(Ui.obj(data, "info"), "forms");
        for (int i = 0; i < forms.size(); i++) if (Ui.str(forms.get(i).getAsJsonObject(), "name", "").equals(name)) return i + 1;
        return 0;
    }

    public void setForm(String name) {
        formIndex = formIndexOf(name);
        buildModel();
    }

    public List<String> megaNames() {
        List<String> out = new ArrayList<>();
        if (data != null) for (JsonElement e : Ui.arr(Ui.obj(data, "info"), "megas")) out.add(e.getAsString());
        return out;
    }

    public int formIndex() {
        return formIndex;
    }

    private int formCount() {
        return data == null ? 0 : Ui.arr(Ui.obj(data, "info"), "forms").size();
    }

    private void buildModel() {
        if (selected == null) {
            model = null;
            return;
        }
        int[] m = modelBox();
        try {
            model = new ModelWidget(m[0], m[1], m[2], m[3], new RenderablePokemon(selected, aspects(), ItemStack.EMPTY), 2.0f, rotation, -10.0, false, false, 15);
        } catch (Throwable t) {
            model = null;
        }
    }

    /** x, y, largeur, hauteur de la zone du modele 3D. */
    private int[] modelBox() {
        int x = listW() + 22;
        return new int[]{x, 58, 120, 120};
    }

    private int contentX() {
        return listW() + 152;
    }

    // ------------------------------------------------------------------ rendu

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderTransparentBackground(g);
        clickables.clear();
        clickBoxes.clear();
        Ui.panel(g, 4, 4, width - 8, height - 8, Ui.PANEL, Ui.BORDER);
        Component wikiTitle = Component.literal("Wiki Cobblemon").withStyle(ChatFormatting.BOLD);
        g.drawString(font, wikiTitle, 10, 11, Ui.ACCENT, false);
        g.drawString(font, filtered.size() + " espèce(s)", 10 + font.width(wikiTitle) + 8, 11, Ui.MUTED, false);
        renderList(g, mx, my);
        if (selected != null) renderDetails(g, mx, my, pt);
        super.render(g, mx, my, pt);
        renderTooltips(g, mx, my);
    }

    private void renderList(GuiGraphics g, int mx, int my) {
        int mbx = 10 + listW() - 22;
        boolean mh = Ui.in(mx, my, mbx, 26, 20, 16);
        Ui.panel(g, mbx, 26, 20, 16, megaOnly ? 0xFF4A2A63 : mh ? Ui.CARD_ON : Ui.PANEL2, megaOnly || mh ? Ui.PURPLE : Ui.BORDER);
        g.drawCenteredString(font, "M", mbx + 10, 30, megaOnly ? Ui.PURPLE : Ui.TEXT);
        click(mbx, 26, 20, 16, () -> setMegaOnly(!megaOnly));
        if (mh) megaHint = true;
        int x = 8;
        int y = 46;
        int w = listW();
        int h = height - y - 10;
        Ui.panel(g, x, y, w, h, Ui.CARD, Ui.BORDER);
        int rows = h / 18;
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, filtered.size() - rows)));
        for (int i = 0; i < rows && listScroll + i < filtered.size(); i++) {
            Species s = filtered.get(listScroll + i);
            int ry = y + 1 + i * 18;
            boolean sel = s == selected;
            boolean hov = Ui.in(mx, my, x, ry, w, 18);
            if (sel || hov) g.fill(x + 1, ry, x + w - 1, ry + 18, sel ? Ui.CARD_ON : 0x40FFFFFF);
            try {
                g.renderItem(PokemonItem.Companion.from(s, new String[0]), x + 3, ry + 1);
            } catch (Throwable ignored) {
            }
            String num = String.format("#%04d", s.getNationalPokedexNumber());
            g.drawString(font, num, x + 22, ry + 5, Ui.MUTED, false);
            Component n = s.getTranslatedName();
            boolean mega = hasMega(s);
            g.drawString(font, font.plainSubstrByWidth(n.getString(), w - 54 - 4 - (mega ? 10 : 0)), x + 22 + font.width(num) + 4, ry + 5, sel ? Ui.GOLD : Ui.TEXT, false);
            if (mega) g.drawString(font, "M", x + w - 12, ry + 5, Ui.PURPLE, false);
            final Species fs = s;
            click(x, ry, w, 18, () -> select(fs));
        }
        if (filtered.size() > rows) {
            int bh = Math.max(12, h * rows / filtered.size());
            int by = y + (h - bh) * listScroll / Math.max(1, filtered.size() - rows);
            g.fill(x + w - 3, by, x + w - 1, by + bh, Ui.ACCENT);
        }
    }

    private void renderDetails(GuiGraphics g, int mx, int my, float pt) {
        int x0 = listW() + 16;
        int right = width - 12;
        JsonObject info = data == null ? null : Ui.obj(data, "info");
        // En-tete
        MutableComponent name = selected.getTranslatedName().copy().withStyle(ChatFormatting.BOLD);
        g.drawString(font, name, x0 + 4, 26, Ui.TEXT, false);
        String num = String.format("  #%04d", selected.getNationalPokedexNumber());
        g.drawString(font, num, x0 + 4 + font.width(name), 26, Ui.MUTED, false);
        int tx = x0 + 4;
        JsonObject form = currentForm();
        List<String> types = new ArrayList<>();
        for (JsonElement e : Ui.arr(form, "types")) types.add(e.getAsString());
        if (types.isEmpty()) {
            types.add(selected.getPrimaryType().getName());
            if (selected.getSecondaryType() != null) types.add(selected.getSecondaryType().getName());
        }
        for (String t : types) tx += typeBadge(g, t, tx, 38) + 4;
        if (info != null) {
            for (JsonElement l : Ui.arr(info, "labels")) {
                String lab = switch (l.getAsString()) {
                    case "legendary" -> "§6Légendaire";
                    case "mythical" -> "§dFabuleux";
                    case "ultra_beast" -> "§3Ultra-Chimère";
                    case "paradox" -> "§5Paradoxe";
                    case "starter" -> "§aStarter";
                    case "fossil" -> "§7Fossile";
                    case "baby" -> "§bBébé";
                    default -> null;
                };
                if (lab != null) {
                    g.drawString(font, lab, tx + 2, 40, Ui.TEXT, false);
                    tx += font.width(lab) + 8;
                }
            }
        }
        // Modele 3D
        int[] m = modelBox();
        Ui.panel(g, m[0] - 4, m[1] - 4, m[2] + 8, m[3] + 30, Ui.CARD, Ui.BORDER);
        if (model != null) {
            try {
                model.setRotationY(rotation);
                model.render(g, mx, my, pt);
            } catch (Throwable t) {
                model = null;
            }
        } else {
            g.drawCenteredString(font, "Modèle indisponible", m[0] + m[2] / 2, m[1] + m[3] / 2, Ui.MUTED);
        }
        int by = m[1] + m[3] + 4;
        button(g, mx, my, m[0], by, 58, 16, shiny ? "§e★ Chromatique" : "☆ Normal", () -> setShiny(!shiny));
        int forms = formCount();
        String formLabel = formIndex == 0 ? "Forme de base" : formLabel(currentForm());
        button(g, mx, my, m[0] + 62, by, 58, 16, forms == 0 ? "—" : "◀ ▶", () -> {
            if (forms > 0) {
                formIndex = (formIndex + 1) % (forms + 1);
                buildModel();
            }
        });
        g.drawCenteredString(font, font.plainSubstrByWidth(formLabel, m[2] + 6), m[0] + m[2] / 2, m[1] - 2 + m[3] - 8, Ui.MUTED);
        // Onglets
        int cx = contentX();
        int tabX = cx;
        for (int i = 0; i < TABS.length; i++) {
            int w = font.width(TABS[i]) + 14;
            int ti = i;
            boolean on = tab == i;
            Ui.panel(g, tabX, 54, w, 16, on ? Ui.CARD_ON : Ui.CARD, on ? Ui.ACCENT : Ui.BORDER);
            g.drawString(font, TABS[i], tabX + 7, 58, on ? Ui.GOLD : Ui.TEXT, false);
            click(tabX, 54, w, 16, () -> {
                tab = ti;
                contentScroll = 0;
            });
            tabX += w + 3;
        }
        int ch = height - 84;
        Ui.panel(g, cx, 72, right - cx, ch, Ui.CARD, Ui.BORDER);
        if (data == null && tab != 4) {
            g.drawString(font, "Chargement des données du serveur...", cx + 8, 80, Ui.MUTED, false);
            return;
        }
        g.enableScissor(cx + 1, 73, right - 1, 72 + ch - 1);
        int y = 78 - contentScroll;
        int w = right - cx - 16;
        int start = y;
        switch (tab) {
            case 0 -> y = renderInfos(g, cx + 8, y, w, info, form);
            case 1 -> y = renderEvolutions(g, mx, my, cx + 8, y, w, info, form);
            case 2 -> y = renderMoves(g, mx, my, cx + 8, y, w, form);
            case 3 -> y = renderSpawns(g, cx + 8, y, w);
            default -> y = renderBosses(g, cx + 8, y, w);
        }
        g.disableScissor();
        contentHeight = y - start;
        int view = ch - 10;
        if (contentHeight > view) {
            int bh = Math.max(12, view * view / contentHeight);
            int bpos = 73 + (view - bh) * contentScroll / Math.max(1, contentHeight - view);
            g.fill(right - 4, bpos, right - 2, bpos + bh, Ui.ACCENT);
        }
    }

    private int typeBadge(GuiGraphics g, String type, int x, int y) {
        ElementalType t = ElementalTypes.get(type);
        Component label = t != null ? t.getDisplayName() : Component.literal(type);
        int w = font.width(label) + 8;
        int color = 0xFF000000 | (t != null ? t.getHue() : 0x888888);
        g.fill(x, y - 2, x + w, y + 9, color);
        g.drawString(font, label, x + 4, y, 0xFFFFFFFF, true);
        return w;
    }

    private void button(GuiGraphics g, int mx, int my, int x, int y, int w, int h, String label, Runnable r) {
        boolean hov = Ui.in(mx, my, x, y, w, h);
        Ui.panel(g, x, y, w, h, hov ? Ui.CARD_ON : Ui.PANEL2, hov ? Ui.ACCENT : Ui.BORDER);
        g.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2, Ui.TEXT);
        click(x, y, w, h, r);
    }

    private void click(int x, int y, int w, int h, Runnable r) {
        clickBoxes.add(new int[]{x, y, w, h});
        clickables.add(r);
    }

    private int heading(GuiGraphics g, String text, int x, int y, int w) {
        g.drawString(font, text, x, y, Ui.ACCENT, false);
        g.fill(x + font.width(text) + 6, y + 4, x + w, y + 5, Ui.BORDER);
        return y + 13;
    }

    private int wrap(GuiGraphics g, Component text, int x, int y, int w, int color) {
        for (FormattedCharSequence line : font.split(text, w)) {
            g.drawString(font, line, x, y, color, false);
            y += 10;
        }
        return y;
    }

    private int renderInfos(GuiGraphics g, int x, int y, int w, JsonObject info, JsonObject form) {
        y = specialBanner(g, x, y, w, form, info);
        y = heading(g, "Statistiques de base", x, y, w);
        String[] keys = {"hp", "atk", "def", "spa", "spd", "spe"};
        String[] labels = {"PV", "Attaque", "Défense", "Atq. Spé.", "Déf. Spé.", "Vitesse"};
        JsonObject stats = Ui.obj(form, "stats");
        int total = 0;
        for (int i = 0; i < keys.length; i++) {
            int v = Ui.num(stats, keys[i], 0);
            total += v;
            g.drawString(font, labels[i], x, y, Ui.MUTED, false);
            g.drawString(font, String.valueOf(v), x + 62, y, Ui.TEXT, false);
            int bw = Math.min(w - 96, (w - 96) * v / 200);
            int col = v < 50 ? 0xFFE57373 : v < 80 ? 0xFFFFB74D : v < 110 ? 0xFFFFF176 : v < 140 ? 0xFF81C784 : 0xFF4FC3F7;
            g.fill(x + 88, y + 1, x + 88 + Math.max(2, bw), y + 7, col);
            y += 11;
        }
        g.drawString(font, "Total", x, y, Ui.MUTED, false);
        g.drawString(font, String.valueOf(total), x + 62, y, Ui.GOLD, false);
        y += 16;
        y = heading(g, "Talents", x, y, w);
        for (JsonElement e : Ui.arr(form, "abilities")) {
            JsonObject a = e.getAsJsonObject();
            MutableComponent n = Ui.tr(Ui.str(a, "key", ""), Ui.pretty(Ui.str(a, "key", ""))).copy();
            if (Ui.bool(a, "hidden", false)) n.append(Component.literal(" (caché)").withStyle(ChatFormatting.LIGHT_PURPLE));
            g.drawString(font, n, x, y, Ui.TEXT, false);
            y += 10;
            y = wrap(g, Ui.tr(Ui.str(a, "desc", ""), ""), x + 6, y, w - 6, Ui.MUTED) + 3;
        }
        y += 4;
        y = heading(g, "Données", x, y, w);
        double male = Ui.dbl(info, "male_ratio", -1);
        String gender = male < 0 ? "Asexué" : String.format(Locale.FRANCE, "♂ %.1f %% / ♀ %.1f %%", male * 100, (1 - male) * 100);
        List<String> eggs = new ArrayList<>();
        for (JsonElement e : Ui.arr(info, "egg_groups")) eggs.add(Ui.pretty(e.getAsString()));
        String[][] rows = {{"Taille", String.format(Locale.FRANCE, "%.1f m", Ui.dbl(info, "height", 0))},
                {"Poids", String.format(Locale.FRANCE, "%.1f kg", Ui.dbl(info, "weight", 0))},
                {"Taux de capture", String.valueOf(Ui.num(info, "catch_rate", 0))}, {"Sexe", gender},
                {"Groupes d'œufs", eggs.isEmpty() ? "-" : String.join(", ", eggs)}};
        for (String[] r : rows) {
            g.drawString(font, r[0], x, y, Ui.MUTED, false);
            g.drawString(font, r[1], x + 100, y, Ui.TEXT, false);
            y += 10;
        }
        JsonArray dex = Ui.arr(info, "pokedex");
        if (!dex.isEmpty()) {
            y += 6;
            y = heading(g, "Pokédex", x, y, w);
            for (JsonElement e : dex) y = wrap(g, Ui.tr(e.getAsString(), ""), x, y, w, Ui.TEXT) + 2;
        }
        return y + 6;
    }

    private Component evoMethod(JsonObject e) {
        MutableComponent c = Component.empty();
        switch (Ui.str(e, "method", "")) {
            case "item" -> c.append("Utiliser ").append(Ui.itemName(Ui.str(e, "item", "")));
            case "trade" -> c.append("Échange");
            case "block" -> c.append("Interaction avec un bloc");
            case "level" -> c.append("Montée de niveau");
            default -> c.append("Spécial");
        }
        for (JsonElement r : Ui.arr(e, "requirements")) {
            JsonObject q = r.getAsJsonObject();
            String v = Ui.str(q, "value", "");
            c.append(Component.literal(" • ").withStyle(ChatFormatting.DARK_GRAY));
            switch (Ui.str(q, "type", "")) {
                case "level" -> c.append("Niveau " + v);
                case "friendship" -> c.append("Amitié ≥ " + v);
                case "held_item" -> c.append("En tenant ").append(Ui.itemName(v));
                case "time" -> c.append(time(v));
                case "biome" -> c.append("Biome " + Ui.pretty(v.replaceAll("[^a-z0-9_:/#]", "")));
                case "move" -> c.append("Connaît ").append(Ui.tr("cobblemon.move." + v, Ui.pretty(v)));
                default -> c.append(Ui.pretty(v));
            }
        }
        return c;
    }

    private int renderEvolutions(GuiGraphics g, int mx, int my, int x, int y, int w, JsonObject info, JsonObject form) {
        JsonObject pre = Ui.obj(info, "pre_evolution");
        if (pre.has("species")) {
            y = heading(g, "Pré-évolution", x, y, w);
            y = evoLine(g, x, y, w, Ui.str(pre, "species", ""), Ui.str(pre, "name_key", ""), null);
            y += 6;
        }
        y = heading(g, "Évolutions", x, y, w);
        JsonArray evos = Ui.arr(form, "evolutions");
        if (evos.isEmpty()) {
            g.drawString(font, "Aucune évolution connue.", x, y, Ui.MUTED, false);
            y += 12;
        }
        for (JsonElement el : evos) {
            JsonObject e = el.getAsJsonObject();
            y = evoLine(g, x, y, w, Ui.str(e, "species", ""), Ui.str(e, "name_key", ""), evoMethod(e));
        }
        // Mega-Evolutions, Primo-Resurgence, formes Originelles
        List<JsonObject> specials = new ArrayList<>();
        for (JsonElement el : Ui.arr(info, "forms")) {
            JsonObject f = el.getAsJsonObject();
            if (!Ui.str(f, "special", "").isEmpty()) specials.add(f);
        }
        if (!specials.isEmpty()) {
            y += 6;
            boolean megas = specials.stream().anyMatch(f -> "mega".equals(Ui.str(f, "special", "")));
            y = heading(g, megas ? "Méga-Évolution" : "Formes spéciales", x, y, w);
            for (JsonObject f : specials) {
                Ui.panel(g, x, y, w, 32, 0xFF2A1E36, Ui.PURPLE);
                g.renderItem(stoneIcon(f), x + 3, y + 3);
                g.drawString(font, formLabel(f), x + 24, y + 4, Ui.PURPLE, false);
                int total = 0;
                for (var st : Ui.obj(f, "stats").entrySet()) total += st.getValue().getAsInt();
                List<String> types = new ArrayList<>();
                for (JsonElement t : Ui.arr(f, "types")) {
                    ElementalType et = ElementalTypes.get(t.getAsString());
                    types.add(et == null ? t.getAsString() : et.getDisplayName().getString());
                }
                MutableComponent line = Component.literal("Tient : ").withStyle(ChatFormatting.GRAY).append(stoneName(f).copy().withStyle(ChatFormatting.WHITE))
                        .append(Component.literal("  •  " + String.join(" / ", types) + "  •  Total " + total).withStyle(ChatFormatting.GRAY));
                g.drawString(font, font.substrByWidth(line, w - 30).getString(), x + 24, y + 16, Ui.TEXT, false);
                String fname = Ui.str(f, "name", "");
                click(x, y, w, 32, () -> {
                    setForm(fname);
                    tab = 0;
                    contentScroll = 0;
                });
                y += 36;
            }
            if (!Ui.bool(info, "mega_showdown", false)) {
                g.drawString(font, "Nécessite le mod Mega Showdown sur le serveur.", x, y, Ui.MUTED, false);
                y += 12;
            }
        }
        return y + 6;
    }

    private int evoLine(GuiGraphics g, int x, int y, int w, String id, String nameKey, Component method) {
        Species s = byId(id);
        Ui.panel(g, x, y, w, method == null ? 22 : 32, Ui.PANEL2, Ui.BORDER);
        if (s != null) {
            try {
                g.renderItem(PokemonItem.Companion.from(s, new String[0]), x + 3, y + 3);
            } catch (Throwable ignored) {
            }
            click(x, y, w, method == null ? 22 : 32, () -> select(s));
        }
        g.drawString(font, s != null ? s.getTranslatedName() : Ui.tr(nameKey, Ui.pretty(id)), x + 24, y + 4, Ui.GOLD, false);
        if (method != null) g.drawString(font, font.substrByWidth(method, w - 30).getString(), x + 24, y + 16, Ui.TEXT, false);
        return y + (method == null ? 24 : 34);
    }

    private int renderMoves(GuiGraphics g, int mx, int my, int x, int y, int w, JsonObject form) {
        int bx = x;
        for (int i = 0; i < MOVE_TABS.length; i++) {
            int bw = font.width(MOVE_TABS[i]) + 12;
            int mi = i;
            boolean on = moveTab == i;
            Ui.panel(g, bx, y, bw, 14, on ? Ui.CARD_ON : Ui.PANEL2, on ? Ui.ACCENT : Ui.BORDER);
            g.drawString(font, MOVE_TABS[i], bx + 6, y + 3, on ? Ui.GOLD : Ui.TEXT, false);
            click(bx, y, bw, 14, () -> {
                moveTab = mi;
                contentScroll = 0;
            });
            bx += bw + 3;
        }
        y += 20;
        JsonArray moves = Ui.arr(Ui.obj(form, "moves"), MOVE_KEYS[moveTab]);
        int[] cols = {0, 30, w - 190, w - 125, w - 85, w - 50, w - 20};
        String[] head = {moveTab == 0 ? "Niv." : "", "Capacité", "Type", "Cat.", "Puiss.", "Préc.", "PP"};
        for (int i = 0; i < head.length; i++) g.drawString(font, head[i], x + cols[i], y, Ui.MUTED, false);
        y += 12;
        if (moves.isEmpty()) {
            g.drawString(font, "Aucune capacité.", x, y, Ui.MUTED, false);
            return y + 12;
        }
        for (JsonElement el : moves) {
            JsonObject m = el.getAsJsonObject();
            if (moveTab == 0) g.drawString(font, String.valueOf(Ui.num(m, "level", 0)), x, y, Ui.TEXT, false);
            Component mn = Ui.tr(Ui.str(m, "key", ""), Ui.pretty(Ui.str(m, "name", "")));
            g.drawString(font, font.substrByWidth(mn, cols[2] - cols[1] - 4).getString(), x + cols[1], y, Ui.TEXT, false);
            ElementalType t = ElementalTypes.get(Ui.str(m, "type", ""));
            if (t != null) g.drawString(font, t.getDisplayName(), x + cols[2], y, 0xFF000000 | t.getHue(), false);
            String cat = switch (Ui.str(m, "category", "")) {
                case "physical" -> "Phys.";
                case "special" -> "Spé.";
                default -> "Stat.";
            };
            g.drawString(font, cat, x + cols[3], y, Ui.MUTED, false);
            int pw = Ui.num(m, "power", 0);
            int acc = Ui.num(m, "accuracy", 0);
            g.drawString(font, pw <= 0 ? "-" : String.valueOf(pw), x + cols[4], y, Ui.TEXT, false);
            g.drawString(font, acc <= 0 ? "-" : String.valueOf(acc), x + cols[5], y, Ui.TEXT, false);
            g.drawString(font, String.valueOf(Ui.num(m, "pp", 0)), x + cols[6], y, Ui.TEXT, false);
            y += 11;
        }
        return y + 6;
    }

    private static String time(String t) {
        return switch (t) {
            case "day" -> "Jour";
            case "night" -> "Nuit";
            case "dawn" -> "Aube";
            case "dusk" -> "Crépuscule";
            case "morning" -> "Matin";
            case "noon" -> "Midi";
            case "afternoon" -> "Après-midi";
            case "evening" -> "Soir";
            case "midnight" -> "Minuit";
            case "twilight" -> "Crépuscule";
            case "any" -> "Toute la journée";
            default -> Ui.pretty(t);
        };
    }

    private static String bucket(String b) {
        return switch (b) {
            case "common" -> "Commun";
            case "uncommon" -> "Peu commun";
            case "rare" -> "Rare";
            case "ultra-rare" -> "Très rare";
            default -> Ui.pretty(b);
        };
    }

    private static String context(String c) {
        return switch (c) {
            case "grounded" -> "Au sol";
            case "submerged" -> "Sous l'eau";
            case "surface" -> "À la surface de l'eau";
            case "seafloor" -> "Fond marin";
            case "lavafloor" -> "Dans la lave";
            case "fishing" -> "Pêche";
            default -> Ui.pretty(c);
        };
    }

    /** Pourcentage lisible meme pour les apparitions tres rares (0,0042 %). */
    static String pct(double v) {
        if (v <= 0) return "< 0,0001 %";
        if (v >= 10) return String.format(Locale.FRANCE, "%.1f %%", v);
        if (v >= 0.1) return String.format(Locale.FRANCE, "%.2f %%", v);
        if (v >= 0.001) return String.format(Locale.FRANCE, "%.4f %%", v).replaceAll("0+ %$", " %").replace(", %", " %");
        return "< 0,001 %";
    }

    private int renderSpawns(GuiGraphics g, int x, int y, int w) {
        JsonArray spawns = Ui.arr(data, "spawns");
        if (spawns.isEmpty()) {
            g.drawString(font, "Ce Pokémon n'apparaît pas naturellement sur ce serveur.", x, y, Ui.MUTED, false);
            return y + 12;
        }
        y = wrap(g, Component.literal("Chance approximative qu'une apparition naturelle dans le biome soit ce Pokémon."), x, y, w, Ui.MUTED);
        y += 4;
        for (JsonElement el : spawns) {
            JsonObject s = el.getAsJsonObject();
            String head = bucket(Ui.str(s, "bucket", "")) + " • " + context(Ui.str(s, "context", "")) + " • Nv. " + Ui.str(s, "levels", "?");
            String aspects = Ui.str(s, "aspects", "");
            if (!aspects.isEmpty()) head += " • " + Ui.pretty(aspects);
            y = heading(g, head, x, y, w);
            List<Component> conds = conditions(Ui.obj(s, "conditions"));
            for (Component c : conds) y = wrap(g, c, x + 4, y, w - 4, Ui.TEXT);
            if (!conds.isEmpty()) y += 3;
            JsonObject mods = Ui.obj(s, "biomes");
            for (Map.Entry<String, JsonElement> m : mods.entrySet()) {
                g.drawString(font, m.getKey(), x + 4, y, Ui.GOLD, false);
                y += 11;
                List<JsonObject> biomes = new ArrayList<>();
                for (JsonElement b : m.getValue().getAsJsonArray()) biomes.add(b.getAsJsonObject());
                biomes.sort(Comparator.comparingDouble(b -> -Ui.dbl(b, "percent", 0)));
                for (JsonObject b : biomes) {
                    Component bn = Ui.biome(Ui.str(b, "id", ""));
                    g.drawString(font, font.substrByWidth(bn, w - 70).getString(), x + 12, y, Ui.TEXT, false);
                    String pct = pct(Ui.dbl(b, "percent", 0));
                    g.drawString(font, pct, x + w - font.width(pct), y, Ui.OK, false);
                    y += 10;
                }
                y += 3;
            }
            y += 6;
        }
        return y;
    }

    private List<Component> conditions(JsonObject c) {
        List<Component> out = new ArrayList<>();
        List<String> dims = new ArrayList<>();
        for (JsonElement e : Ui.arr(c, "dimensions")) dims.add(e.getAsString());
        if (!dims.isEmpty()) out.add(Component.literal(dims.size() > 1 ? "Mondes : " : "Monde : ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.join(", ", dims)).withStyle(ChatFormatting.WHITE)));
        List<String> times = new ArrayList<>();
        for (JsonElement e : Ui.arr(c, "time")) times.add(time(e.getAsString()));
        if (!times.isEmpty()) out.add(Component.literal("Moment : ").withStyle(ChatFormatting.GRAY).append(Component.literal(String.join(", ", times)).withStyle(ChatFormatting.WHITE)));
        List<String> weather = new ArrayList<>();
        for (JsonElement e : Ui.arr(c, "weather")) weather.add(switch (e.getAsString()) {
            case "rain" -> "Pluie";
            case "thunder" -> "Orage";
            default -> "Temps clair";
        });
        if (!weather.isEmpty()) out.add(Component.literal("Météo : ").withStyle(ChatFormatting.GRAY).append(Component.literal(String.join(", ", weather)).withStyle(ChatFormatting.WHITE)));
        if (c.has("min_y") || c.has("max_y")) {
            String alt = c.has("min_y") && c.has("max_y") ? "Y " + c.get("min_y").getAsInt() + " à " + c.get("max_y").getAsInt()
                    : c.has("min_y") ? "Y ≥ " + c.get("min_y").getAsInt() : "Y ≤ " + c.get("max_y").getAsInt();
            out.add(Component.literal("Altitude : ").withStyle(ChatFormatting.GRAY).append(Component.literal(alt).withStyle(ChatFormatting.WHITE)));
        }
        if (c.has("sky")) out.add(Component.literal(c.get("sky").getAsBoolean() ? "À ciel ouvert" : "Sous abri / souterrain").withStyle(ChatFormatting.GRAY));
        if (c.has("min_light") || c.has("max_light")) {
            out.add(Component.literal("Lumière : " + (c.has("min_light") ? c.get("min_light").getAsInt() : 0) + " à "
                    + (c.has("max_light") ? c.get("max_light").getAsInt() : 15)).withStyle(ChatFormatting.GRAY));
        }
        JsonArray blocks = Ui.arr(c, "blocks");
        if (!blocks.isEmpty()) {
            MutableComponent b = Component.literal("Blocs : ").withStyle(ChatFormatting.GRAY);
            int i = 0;
            for (JsonElement e : blocks) {
                if (i++ > 0) b.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
                b.append(Ui.block(e.getAsString()).copy().withStyle(ChatFormatting.WHITE));
                if (i >= 8) break;
            }
            out.add(b);
        }
        JsonArray st = Ui.arr(c, "structures");
        if (!st.isEmpty()) {
            List<String> l = new ArrayList<>();
            for (JsonElement e : st) l.add(Ui.pretty(e.getAsString()));
            out.add(Component.literal("Structures : ").withStyle(ChatFormatting.GRAY).append(Component.literal(String.join(", ", l)).withStyle(ChatFormatting.WHITE)));
        }
        return out;
    }

    private int renderBosses(GuiGraphics g, int x, int y, int w) {
        if (bosses == null) {
            g.drawString(font, "Chargement des Boss...", x, y, Ui.MUTED, false);
            return y + 12;
        }
        if (!Ui.bool(bosses, "enabled", true)) {
            g.drawString(font, "Le spawn naturel des Boss est actuellement désactivé.", x, y, Ui.ERROR, false);
            y += 14;
        }
        y = wrap(g, Component.literal(Ui.str(bosses, "form_rule", "")), x, y, w, Ui.PURPLE) + 6;
        for (JsonElement el : Ui.arr(bosses, "tiers")) {
            JsonObject t = el.getAsJsonObject();
            Ui.panel(g, x, y, w, 14, Ui.PANEL2, Ui.BORDER);
            g.renderItem(Ui.item(Ui.str(t, "icon", "")), x + 2, y - 1);
            g.drawString(font, TextUtil.legacy(Ui.str(t, "name", "?")), x + 22, y + 3, Ui.TEXT, false);
            String meta = "Nv. " + Ui.str(t, "levels", "?") + " • " + Ui.num(t, "ivs", 0) + " IV parfaits";
            long cd = Ui.num(t, "cooldown", 0);
            if (cd > 0) meta += " • 1 combat / " + (cd >= 3600 ? cd / 3600 + " h" : cd / 60 + " min");
            g.drawString(font, meta, x + w - font.width(meta) - 4, y + 3, Ui.MUTED, false);
            y += 17;
            for (JsonElement l : Ui.arr(t, "loot")) {
                JsonObject lo = l.getAsJsonObject();
                double ch = Ui.dbl(lo, "chance", 100);
                String line = "• " + Ui.str(lo, "text", "") + (ch < 100 ? String.format(Locale.FRANCE, "  (%.0f %%)", ch) : "");
                g.drawString(font, line, x + 8, y, Ui.TEXT, false);
                y += 10;
            }
            y += 6;
        }
        return y;
    }

    private boolean megaHint;

    private void renderTooltips(GuiGraphics g, int mx, int my) {
        if (megaHint) {
            megaHint = false;
            g.renderTooltip(font, Component.literal(megaOnly ? "Afficher toutes les espèces" : "Seulement les Pokémon avec Méga-Évolution"), mx, my);
            return;
        }
        int[] m = modelBox();
        if (Ui.in(mx, my, m[0], m[1], m[2], m[3]) && selected != null) {
            g.renderTooltip(font, Component.literal("Glissez pour faire tourner").withStyle(ChatFormatting.GRAY), mx, my);
        }
    }

    // ------------------------------------------------------------------ entrees

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (search != null && search.mouseClicked(mx, my, button)) {
            setFocused(search);
            return true;
        }
        int[] m = modelBox();
        if (Ui.in(mx, my, m[0], m[1], m[2], m[3])) {
            dragging = true;
            return true;
        }
        for (int i = clickBoxes.size() - 1; i >= 0; i--) {
            int[] b = clickBoxes.get(i);
            if (Ui.in(mx, my, b[0], b[1], b[2], b[3])) {
                clickables.get(i).run();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging) {
            rotation = (float) ((rotation + dx * 2) % 360);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (mx < listW() + 10) {
            listScroll = Math.max(0, listScroll - (int) Math.signum(sy) * 3);
        } else {
            int view = height - 94;
            contentScroll = Math.max(0, Math.min(Math.max(0, contentHeight - view), contentScroll - (int) (sy * 20)));
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (search != null && search.isFocused() && key != 256) return search.keyPressed(key, scan, mods);
        return super.keyPressed(key, scan, mods);
    }

    /** Auto-test : onglet affiche. */
    public void setTab(int t) {
        tab = t;
        contentScroll = 0;
    }

    public void setMoveTab(int t) {
        moveTab = t;
    }

    public void search(String q) {
        if (search != null) search.setValue(q);
    }

    public List<String> visibleIds() {
        List<String> out = new ArrayList<>();
        for (Species s : filtered) out.add(s.getResourceIdentifier().toString());
        return out;
    }

    static final Map<String, String> UNUSED = new LinkedHashMap<>();
}
