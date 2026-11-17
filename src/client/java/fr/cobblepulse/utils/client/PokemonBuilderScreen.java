package fr.cobblepulse.utils.client;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.abilities.PotentialAbility;
import com.cobblemon.mod.common.api.pokeball.PokeBalls;
import com.cobblemon.mod.common.api.pokemon.Natures;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.api.types.ElementalTypes;
import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget;
import com.cobblemon.mod.common.item.PokemonItem;
import com.cobblemon.mod.common.pokeball.PokeBall;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Nature;
import com.cobblemon.mod.common.pokemon.RenderablePokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Createur de Pokemon des editeurs (boutique) : espece choisie dans la liste complete (fakemons compris), forme
 * (Mega comprises), niveau, chromatique, IV, nature, talent (cache compris), genre, Ball, objet tenu, Tera, surnom,
 * capacites. Produit la fiche texte de la boutique ; aucun Pokemon n'est necessaire dans l'equipe.
 */
public final class PokemonBuilderScreen extends Screen implements Overlay {
    /** Champ a choix : valeur ecrite dans la fiche + libelle affiche. */
    private static final class Choice {
        final String label;
        final List<String> values = new ArrayList<>();
        final List<Component> names = new ArrayList<>();
        int index;
        Button button;

        Choice(String label) {
            this.label = label;
        }

        void add(String value, Component name) {
            values.add(value);
            names.add(name);
        }

        String value() {
            return values.isEmpty() ? "" : values.get(Math.min(index, values.size() - 1));
        }

        Component name() {
            return names.isEmpty() ? Component.literal("—") : names.get(Math.min(index, names.size() - 1));
        }

        void select(String v) {
            int i = values.indexOf(v);
            index = Math.max(0, i);
        }
    }

    Screen parent;
    private final Component prompt;
    private final List<Species> all = new ArrayList<>();
    private final List<Species> filtered = new ArrayList<>();
    private Species species;
    private EditBox search, level, held, nick, moves;
    private Button shinyBtn, ok;
    private boolean shiny;
    private final Choice form = new Choice("Forme");
    private final Choice ivs = new Choice("IV");
    private final Choice nature = new Choice("Nature");
    private final Choice ability = new Choice("Talent");
    private final Choice gender = new Choice("Genre");
    private final Choice ball = new Choice("Ball");
    private final Choice tera = new Choice("Tera");
    private final Map<String, String> initial = new LinkedHashMap<>();
    private double listScroll;
    private ModelWidget model;
    private int px, py, pw, ph, lx, ly, lw, lh, fx, fw;

    PokemonBuilderScreen(Screen parent, Component prompt, String initialSpec) {
        super(Component.literal("Créer un Pokémon"));
        this.parent = parent;
        this.prompt = prompt;
        for (Species s : PokemonSpecies.getSpecies()) {
            if (!s.getImplemented() && "cobblemon".equals(s.getResourceIdentifier().getNamespace())) continue;
            all.add(s);
        }
        all.sort(Comparator.comparingInt(Species::getNationalPokedexNumber).thenComparing(s -> s.getResourceIdentifier().toString()));
        ivs.add("", Component.literal("Aléatoires"));
        ivs.add("31,31,31,31,31,31", Component.literal("Tous parfaits (6×31)"));
        ivs.add("31,0,31,31,31,31", Component.literal("Parfaits sauf Attaque (0)"));
        ivs.add("31,31,31,31,31,0", Component.literal("Parfaits sauf Vitesse (0)"));
        nature.add("", Component.literal("Aléatoire"));
        List<Nature> natures = new ArrayList<>(Natures.all());
        natures.sort(Comparator.comparing(n -> Component.translatable(n.getDisplayName()).getString()));
        for (Nature n : natures) nature.add(n.getName().getPath(), Component.translatable(n.getDisplayName()));
        ball.add("", Component.literal("Poké Ball"));
        for (PokeBall b : PokeBalls.all()) {
            if (b.getName().getPath().equals("poke_ball")) continue;
            ball.add(b.getName().getNamespace().equals("cobblemon") ? b.getName().getPath() : b.getName().toString(), b.item().getDescription());
        }
        tera.add("", Component.literal("Par défaut"));
        for (ElementalType t : ElementalTypes.all()) tera.add(t.getName(), t.getDisplayName());
        tera.add("stellar", Component.literal("Stellaire"));
        parse(initialSpec);
        Species init = initial.containsKey("") ? find(initial.get("")) : null;
        selectSpecies(init != null ? init : all.isEmpty() ? null : all.get(0), init != null);
    }

    private void parse(String spec) {
        if (spec == null || spec.isBlank()) return;
        String[] t = spec.trim().split("\\s+");
        initial.put("", t[0]);
        for (int i = 1; i < t.length; i++) {
            String k = t[i].contains("=") ? t[i].substring(0, t[i].indexOf('=')).toLowerCase(Locale.ROOT) : t[i].toLowerCase(Locale.ROOT);
            String v = t[i].contains("=") ? t[i].substring(t[i].indexOf('=') + 1) : "true";
            initial.put(switch (k) {
                case "lvl", "level", "nv" -> "niveau";
                case "chromatique" -> "shiny";
                case "ability" -> "talent";
                case "held", "item" -> "objet";
                case "form" -> "forme";
                case "gender" -> "genre";
                case "nick" -> "surnom";
                case "moves", "capacités" -> "capacites";
                case "iv" -> "ivs";
                default -> k;
            }, v);
        }
    }

    private Species find(String id) {
        String raw = id.toLowerCase(Locale.ROOT);
        ResourceLocation rl = ResourceLocation.tryParse(raw.contains(":") ? raw : "cobblemon:" + raw);
        Species s = rl == null ? null : PokemonSpecies.getByIdentifier(rl);
        if (s != null) return s;
        for (Species sp : all) if (sp.getName().equalsIgnoreCase(raw) || fr.cobblepulse.utils.pokemon.SpeciesNames.text(sp).equalsIgnoreCase(raw)) return sp;
        return null;
    }

    private FormData currentForm() {
        if (species == null) return null;
        String f = form.value();
        if (f.isEmpty()) return species.getStandardForm();
        for (FormData d : species.getForms()) if (d.formOnlyShowdownId().equals(f)) return d;
        return species.getStandardForm();
    }

    private void selectSpecies(Species s, boolean applyInitial) {
        species = s;
        form.values.clear();
        form.names.clear();
        form.index = 0;
        form.add("", Component.literal("Forme de base"));
        if (s != null) {
            for (FormData d : s.getForms()) {
                String n = d.getName();
                String label = n;
                if (n != null && n.toLowerCase(Locale.ROOT).startsWith("mega")) label = "Méga " + n.substring(4).replace("-", " ").trim() + " (combat)";
                form.add(d.formOnlyShowdownId(), Component.literal(label.trim()));
            }
        }
        gender.values.clear();
        gender.names.clear();
        gender.index = 0;
        gender.add("", Component.literal(s != null && s.getMaleRatio() < 0 ? "Asexué" : "Aléatoire"));
        if (s != null && s.getMaleRatio() > 0) gender.add("male", Component.literal("Mâle"));
        if (s != null && s.getMaleRatio() >= 0 && s.getMaleRatio() < 1) gender.add("female", Component.literal("Femelle"));
        if (applyInitial) {
            form.select(initial.getOrDefault("forme", ""));
            gender.select(initial.getOrDefault("genre", ""));
            shiny = initial.containsKey("shiny") && !initial.get("shiny").equalsIgnoreCase("false");
            ivs.select(initial.getOrDefault("ivs", ""));
            nature.select(initial.getOrDefault("nature", ""));
            ball.select(initial.getOrDefault("ball", ""));
            tera.select(initial.getOrDefault("tera", ""));
        }
        refreshAbilities(applyInitial);
        rebuildModel();
        refreshButtons();
    }

    private void refreshAbilities(boolean applyInitial) {
        String prev = ability.value();
        ability.values.clear();
        ability.names.clear();
        ability.index = 0;
        ability.add("", Component.literal("Aléatoire (normal)"));
        FormData f = currentForm();
        if (f != null) {
            Set<String> seen = new HashSet<>();
            for (PotentialAbility a : f.getAbilities()) {
                String n = a.getTemplate().getName();
                if (!seen.add(n)) continue;
                boolean hidden = a.getPriority() == Priority.LOW;
                ability.add(n, Component.translatable(a.getTemplate().getDisplayName()).append(hidden ? " (caché)" : ""));
            }
        }
        ability.select(applyInitial ? initial.getOrDefault("talent", "") : prev);
    }

    private void rebuildModel() {
        model = null;
        if (species == null || minecraft == null) return;
        Set<String> aspects = new HashSet<>();
        if (shiny) aspects.add("shiny");
        FormData f = currentForm();
        if (f != null) aspects.addAll(f.getAspects());
        try {
            model = new ModelWidget(lx + lw + 14, py + 34, 110, 110, new RenderablePokemon(species, aspects, ItemStack.EMPTY), 1.6f, 325f, -10.0, false, false, 15);
        } catch (Throwable t) {
            model = null;
        }
    }

    private void refilter() {
        filtered.clear();
        String q = MenuScreen.norm(search == null ? "" : search.getValue().trim());
        for (Species s : all) {
            if (q.isEmpty() || MenuScreen.norm(fr.cobblepulse.utils.pokemon.SpeciesNames.text(s)).contains(q) || s.getResourceIdentifier().toString().contains(q)
                    || (q.chars().allMatch(Character::isDigit) && String.valueOf(s.getNationalPokedexNumber()).equals(q.replaceFirst("^0+(?=.)", "")))) {
                filtered.add(s);
            }
        }
        listScroll = 0;
    }

    @Override
    protected void init() {
        if (parent != null) parent.init(minecraft, width, height);
        pw = Math.min(width - 24, 640);
        ph = height - 16;
        px = (width - pw) / 2;
        py = 8;
        lx = px + 10;
        lw = 150;
        ly = py + 50;
        lh = ph - 50 - 36;
        fx = lx + lw + 140;
        fw = px + pw - 10 - fx;
        String prevSearch = search == null ? "" : search.getValue();
        search = addRenderableWidget(new EditBox(font, lx, py + 30, lw, 16, Component.literal("Espèce")));
        search.setHint(Component.literal("Espèce ou n°…").withColor(Ui.MUTED & 0xFFFFFF));
        search.setValue(prevSearch);
        search.setResponder(v -> refilter());
        refilter();
        int y = py + 30;
        int cw = fw - 70;
        int cx = fx + 70;
        cycle(form, cx, y, cw, () -> {
            refreshAbilities(false);
            rebuildModel();
        });
        y += 20;
        level = field(cx, y, cw, initialOr(level, "niveau", "5"), "1 à 100");
        y += 20;
        shinyBtn = addRenderableWidget(Button.builder(Component.empty(), b -> {
            shiny = !shiny;
            rebuildModel();
            refreshButtons();
        }).bounds(cx, y, cw, 18).build());
        y += 20;
        cycle(ivs, cx, y, cw, null);
        y += 20;
        cycle(nature, cx, y, cw, null);
        y += 20;
        cycle(ability, cx, y, cw, null);
        y += 20;
        cycle(gender, cx, y, cw, null);
        y += 20;
        cycle(ball, cx, y, cw, null);
        y += 20;
        held = field(cx, y, cw, initialOr(held, "objet", ""), "ex. cobblemon:leftovers");
        y += 20;
        cycle(tera, cx, y, cw, null);
        y += 20;
        nick = field(cx, y, cw, initialOr(nick, "surnom", "").replace('_', ' '), "facultatif");
        y += 20;
        moves = field(cx, y, cw, initialOr(moves, "capacites", ""), "ex. thunderbolt,quick_attack");
        int by = py + ph - 26;
        ok = addRenderableWidget(Button.builder(Component.literal("Valider"), b -> submit()).bounds(px + pw - 12 - 200, by, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Annuler"), b -> onClose()).bounds(px + pw - 12 - 98, by, 96, 20).build());
        rebuildModel();
        refreshButtons();
    }

    private String initialOr(EditBox old, String key, String def) {
        return old != null ? old.getValue() : initial.getOrDefault(key, def);
    }

    private EditBox field(int x, int y, int w, String value, String hint) {
        EditBox b = addRenderableWidget(new EditBox(font, x, y + 1, w, 16, Component.empty()));
        b.setMaxLength(200);
        b.setValue(value);
        b.setHint(Component.literal(hint).withColor(Ui.MUTED & 0xFFFFFF));
        return b;
    }

    private void cycle(Choice c, int x, int y, int w, Runnable after) {
        c.button = addRenderableWidget(Button.builder(Component.empty(), b -> {
            if (!c.values.isEmpty()) c.index = (c.index + (hasShiftDown() ? c.values.size() - 1 : 1)) % c.values.size();
            if (after != null) after.run();
            refreshButtons();
        }).bounds(x, y, w, 18).build());
    }

    private void refreshButtons() {
        for (Choice c : new Choice[]{form, ivs, nature, ability, gender, ball, tera}) {
            if (c.button != null) c.button.setMessage(c.name());
        }
        if (shinyBtn != null) shinyBtn.setMessage(Component.literal(shiny ? "§e★ Chromatique" : "☆ Normal"));
    }

    /** Fiche au format de la boutique. */
    String spec() {
        if (species == null) return "";
        ResourceLocation id = species.getResourceIdentifier();
        StringBuilder b = new StringBuilder(id.getNamespace().equals("cobblemon") ? id.getPath() : id.toString());
        int lv = 5;
        try {
            lv = Mth.clamp(Integer.parseInt(level == null ? "5" : level.getValue().trim()), 1, 250);
        } catch (Exception ignored) {
        }
        b.append(" niveau=").append(lv);
        if (shiny) b.append(" shiny");
        add(b, "forme", form.value());
        add(b, "ivs", ivs.value());
        add(b, "nature", nature.value());
        add(b, "talent", ability.value());
        add(b, "genre", gender.value());
        add(b, "ball", ball.value());
        add(b, "objet", held == null ? "" : held.getValue().trim().replace(' ', '_'));
        add(b, "tera", tera.value());
        add(b, "surnom", nick == null ? "" : nick.getValue().trim().replace(' ', '_'));
        add(b, "capacites", moves == null ? "" : moves.getValue().trim().replace(" ", ""));
        return b.toString();
    }

    private static void add(StringBuilder b, String k, String v) {
        if (v != null && !v.isBlank()) b.append(' ').append(k).append('=').append(v);
    }

    private boolean heldValid() {
        String v = held == null ? "" : held.getValue().trim();
        if (v.isEmpty()) return true;
        ResourceLocation rl = ResourceLocation.tryParse(v.contains(":") ? v : "cobblemon:" + v);
        return rl != null && BuiltInRegistries.ITEM.containsKey(rl);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        if (parent != null) parent.render(g, -1, -1, pt);
        else renderTransparentBackground(g);
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(0, 0, width, height, 0x88000000);
        Ui.panel(g, px, py, pw, ph, Ui.PANEL, Ui.ACCENT);
        g.drawString(font, font.plainSubstrByWidth(prompt.getString(), pw - 24), px + 12, py + 12, Ui.TEXT, false);
        String[] labels = {"Forme", "Niveau", "Chromatique", "IV", "Nature", "Talent", "Genre", "Ball", "Objet tenu", "Tera", "Surnom", "Capacités"};
        for (int i = 0; i < labels.length; i++) g.drawString(font, labels[i], fx, py + 35 + i * 20, Ui.MUTED, false);
        if (!heldValid()) g.drawString(font, "objet inconnu", fx, py + 35 + 8 * 20 + 9, Ui.ERROR, false);
        Ui.panel(g, lx, ly, lw, lh, Ui.CARD, Ui.BORDER);
        Ui.panel(g, lx + lw + 10, py + 30, 118, 118, Ui.CARD, Ui.BORDER);
        if (species != null) {
            g.drawCenteredString(font, fr.cobblepulse.utils.pokemon.SpeciesNames.of(species), lx + lw + 69, py + 154, Ui.GOLD);
            g.drawCenteredString(font, font.plainSubstrByWidth(form.name().getString(), 124), lx + lw + 69, py + 166, Ui.MUTED);
        }
        String sp = spec();
        g.drawString(font, font.plainSubstrByWidth(sp, pw - 230), px + 12, py + ph - 20, Ui.MUTED, false);
        g.pose().popPose();
        // modele et liste (objets/Pokemon au-dessus du voile)
        g.pose().pushPose();
        g.pose().translate(0, 0, 310);
        if (model != null) {
            try {
                model.render(g, mx, my, pt);
            } catch (Throwable t) {
                model = null;
            }
        }
        int rows = lh / 18;
        int maxScroll = Math.max(0, filtered.size() - rows);
        int start = (int) Mth.clamp(listScroll, 0, maxScroll);
        g.enableScissor(lx + 1, ly + 1, lx + lw - 1, ly + lh - 1);
        for (int i = 0; i < rows && start + i < filtered.size(); i++) {
            Species s = filtered.get(start + i);
            int ry = ly + 1 + i * 18;
            boolean hov = Ui.in(mx, my, lx, ry, lw, 18);
            if (s == species || hov) g.fill(lx + 1, ry, lx + lw - 1, ry + 18, s == species ? Ui.CARD_ON : 0x40FFFFFF);
            try {
                g.renderItem(PokemonItem.Companion.from(s, new String[0]), lx + 3, ry + 1);
            } catch (Throwable ignored) {
            }
            g.drawString(font, font.plainSubstrByWidth(fr.cobblepulse.utils.pokemon.SpeciesNames.text(s), lw - 26), lx + 22, ry + 5, s == species ? Ui.GOLD : Ui.TEXT, false);
        }
        g.disableScissor();
        g.pose().popPose();
        if (ok != null) ok.active = species != null && heldValid();
        g.pose().pushPose();
        g.pose().translate(0, 0, 320);
        super.render(g, mx, my, pt);
        g.pose().popPose();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (Ui.in(mx, my, lx, ly, lw, lh)) {
            int i = (int) Mth.clamp(listScroll, 0, Math.max(0, filtered.size() - lh / 18)) + (int) ((my - ly - 1) / 18);
            if (i >= 0 && i < filtered.size()) {
                selectSpecies(filtered.get(i), false);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (Ui.in(mx, my, lx, ly, lw, lh)) {
            listScroll = Math.max(0, listScroll - sy * 3);
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && ok != null && ok.active) {
            submit();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    void submit() {
        if (species == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("text", spec());
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
    void chooseSpecies(String id) {
        Species s = find(id);
        if (s != null) selectSpecies(s, false);
    }

    void setShinyFlag(boolean v) {
        shiny = v;
        rebuildModel();
        refreshButtons();
    }

    void setLevel(int v) {
        if (level != null) level.setValue(String.valueOf(v));
    }

    void setIvsPerfect() {
        ivs.index = 1;
        refreshButtons();
    }

    boolean setFormNamed(String part) {
        for (int i = 0; i < form.names.size(); i++) {
            if (form.names.get(i).getString().toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT))) {
                form.index = i;
                refreshAbilities(false);
                rebuildModel();
                refreshButtons();
                return true;
            }
        }
        return false;
    }

    int formCount() {
        return form.values.size();
    }
}
