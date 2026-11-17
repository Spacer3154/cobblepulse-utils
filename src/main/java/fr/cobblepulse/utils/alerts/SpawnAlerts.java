package fr.cobblepulse.utils.alerts;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.item.PokemonItem;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.boss.Bosses;
import fr.cobblepulse.utils.command.Cmd;
import fr.cobblepulse.utils.config.AlertsConfig;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.core.ItemBuilder;
import fr.cobblepulse.utils.core.Json;
import fr.cobblepulse.utils.core.Loc;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.core.Reg;
import fr.cobblepulse.utils.core.Scheduler;
import fr.cobblepulse.utils.core.TextUtil;
import fr.cobblepulse.utils.core.TimeFmt;
import fr.cobblepulse.utils.gui.Gui;
import fr.cobblepulse.utils.hub.Hub;
import fr.cobblepulse.utils.teleport.Teleports;
import kotlin.Unit;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static net.minecraft.commands.Commands.argument;

/**
 * Alertes et historique des spawns rares (Chromatiques, Paradoxes, Ultra-Chimeres, Legendaires, Fabuleux) :
 * annonce globale (Pokemon, biome traduit, joueur le plus proche), coordonnees au survol pour le staff,
 * historique des 10 derniers par categorie avec statut (Actif, Capture, Despawn). Les Boss sont exclus.
 */
public final class SpawnAlerts {
    public static final List<String> CATEGORIES = List.of("shiny", "paradox", "ultra_beast", "legendary", "mythical");
    /** Auto-test : texte de la derniere annonce. */
    public static volatile String lastBroadcast = "";
    /** Auto-test : derniere annonce telle qu'envoyee (nom d'espece compris). */
    public static volatile Component lastBroadcastMessage = Component.empty();
    /** Auto-test : texte de la derniere annonce de capture / victoire / mort. */
    public static volatile String lastOutcome = "";

    /** Entree d'historique (serialisee dans data/spawn_history.json). */
    public static final class Entry {
        public String species;
        public List<String> aspects = new ArrayList<>();
        public String pokemonUuid;
        public String entityUuid;
        public long time;
        public String biome;
        public String nearest = "";
        public String status = "ACTIVE";
        public String captor = "";
        public long statusTime;
        public Loc loc;
        public boolean shiny;
        /** Alerte de test envoyee a un seul membre du staff (UUID) : la suite lui est annoncee a lui seul. */
        public String onlyTo = "";
    }

    private static final Map<String, Deque<Entry>> HISTORY = new LinkedHashMap<>();
    private static final Map<String, List<Entry>> BY_ENTITY = new LinkedHashMap<>();

    private SpawnAlerts() {}

    public static void init() {
        for (String c : CATEGORIES) HISTORY.put(c, new ArrayDeque<>());
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe(Priority.LOWEST, e -> {
            try {
                if (!e.isCanceled()) onSpawn(e.getEntity());
            } catch (Throwable t) {
                CobbleUtils.LOG.warn("[CobblePulse-Utils] Alerte de spawn : {}", t.toString());
            }
            return Unit.INSTANCE;
        });
        CobblemonEvents.POKEMON_CAPTURED.subscribe(Priority.LOWEST, e -> {
            try {
                onCapture(e.getPokemon(), e.getPlayer());
            } catch (Throwable t) {
                CobbleUtils.LOG.warn("[CobblePulse-Utils] Historique (capture) : {}", t.toString());
            }
            return Unit.INSTANCE;
        });
        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.LOWEST, e -> {
            try {
                onVictory(e.getWinners(), e.getLosers());
            } catch (Throwable t) {
                CobbleUtils.LOG.warn("[CobblePulse-Utils] Historique (victoire) : {}", t.toString());
            }
            return Unit.INSTANCE;
        });
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        Cmd.player(d, "spawns", l -> Cmd.lit(l).executes(ctx -> Cmd.run(ctx, p -> openHistory(p, null)))
                .then(argument("categorie", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(CATEGORIES, b))
                        .executes(ctx -> Cmd.run(ctx, p -> openHistory(p, StringArgumentType.getString(ctx, "categorie"))))));
    }

    /** Categories surveillees auxquelles appartient ce Pokemon. */
    public static List<String> categories(Pokemon pk) {
        List<String> out = new ArrayList<>();
        Set<String> labels = pk.getSpecies().getLabels();
        if (pk.getShiny()) out.add("shiny");
        if (labels.contains("paradox")) out.add("paradox");
        if (labels.contains("ultra_beast")) out.add("ultra_beast");
        if (labels.contains("mythical")) out.add("mythical");
        else if (labels.contains("legendary")) out.add("legendary");
        out.removeIf(c -> {
            AlertsConfig.Category cat = Cfg.alerts.categories.get(c);
            return cat == null || !cat.enabled;
        });
        return out;
    }

    private static void onSpawn(PokemonEntity entity) {
        if (!Cfg.alerts.enabled || entity == null) return;
        if (entity.level() instanceof ServerLevel sl && !fr.cobblepulse.utils.teleport.Worlds.alerts(sl)) return;
        Pokemon pk = entity.getPokemon();
        if (pk == null || !pk.isWild()) return;
        List<String> cats = categories(pk);
        if (cats.isEmpty()) return;
        // laisser le Boss eventuel se declarer (promotion au meme tick), puis verifier
        Scheduler.next(() -> {
            if (entity.isRemoved() || Bosses.isBoss(entity)) return;
            record(entity, cats);
        });
    }

    public static void record(PokemonEntity entity, List<String> cats) {
        record(entity, cats, null);
    }

    /**
     * Test staff : un vrai Pokemon sauvage de la categorie apparait devant le joueur et l'annonce part comme pour un
     * spawn naturel (historique, statut Capture / Disparu compris). onlyMe : annonce envoyee au staff seulement.
     */
    public static PokemonEntity test(ServerPlayer p, String cat, String speciesName, boolean onlyMe) {
        if (!CATEGORIES.contains(cat)) {
            Msg.send(p, "alerts.test_unknown", "category", cat, "categories", String.join(", ", CATEGORIES));
            return null;
        }
        com.cobblemon.mod.common.pokemon.Species sp = speciesName == null || speciesName.equals("*") ? null
                : fr.cobblepulse.utils.pokemon.SpeciesLookup.resolve(speciesName);
        if (sp == null) {
            List<com.cobblemon.mod.common.pokemon.Species> pool = new ArrayList<>(com.cobblemon.mod.common.api.pokemon.PokemonSpecies.getImplemented());
            pool.removeIf(x -> cat.equals("shiny") ? x.getLabels().contains("legendary") || x.getLabels().contains("mythical") : !x.getLabels().contains(cat));
            if (pool.isEmpty()) {
                Msg.send(p, "alerts.test_no_species", "category", cat);
                return null;
            }
            sp = pool.get(new java.util.Random().nextInt(pool.size()));
        }
        com.cobblemon.mod.common.api.pokemon.PokemonProperties pp = com.cobblemon.mod.common.api.pokemon.PokemonProperties.Companion
                .parse("level=30 shiny=" + (cat.equals("shiny") ? "yes" : "no"));
        pp.setSpecies(sp.getResourceIdentifier().toString());
        Pokemon pk = pp.create();
        net.minecraft.world.phys.Vec3 look = p.getLookAngle();
        net.minecraft.world.phys.Vec3 pos = p.position().add(new net.minecraft.world.phys.Vec3(look.x, 0, look.z).normalize().scale(4));
        PokemonEntity e = new PokemonEntity(p.serverLevel(), pk, com.cobblemon.mod.common.CobblemonEntities.POKEMON);
        e.moveTo(pos.x, p.getY(), pos.z, p.getYRot() + 180, 0);
        p.serverLevel().addFreshEntity(e);
        List<String> cats = categories(pk);
        if (cats.isEmpty()) {
            // categorie desactivee dans alerts.json : on l'annonce quand meme pour le test
            cats = new ArrayList<>(List.of(cat));
        }
        record(e, cats, onlyMe ? p : null);
        Msg.send(p, Msg.withComponent("alerts.test_done", "pokemon", fr.cobblepulse.utils.pokemon.SpeciesNames.of(sp), "category", label(cats),
                "target", onlyMe ? Msg.format("alerts.test_target_me") : Msg.format("alerts.test_target_all")));
        return e;
    }

    /** Choix de la categorie a tester (clic : annonce a tous, clic droit : au staff seul). */
    public static void testMenu(ServerPlayer p) {
        Gui g = new Gui(3, Msg.format("alerts.test_title"));
        g.fill(Gui.pane());
        int slot = 11;
        for (String c : CATEGORIES) {
            AlertsConfig.Category ac = Cfg.alerts.categories.get(c);
            if (ac == null) continue;
            g.button(slot++, ItemBuilder.of(Reg.stack(ac.icon)).name(ac.color + ac.label).lore(Msg.format("alerts.test_cat_lore")).build(), (pl, click) -> {
                Gui.close(pl);
                test(pl, c, null, click.right());
            });
        }
        g.button(22, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), pl -> openHistory(pl, null));
        g.open(p);
    }

    public static void record(PokemonEntity entity, List<String> cats, ServerPlayer onlyTo) {
        Pokemon pk = entity.getPokemon();
        ServerLevel level = (ServerLevel) entity.level();
        ResourceLocation biome = level.getBiome(entity.blockPosition()).unwrapKey().map(k -> k.location()).orElse(ResourceLocation.withDefaultNamespace("plains"));
        ServerPlayer nearest = null;
        double best = Cfg.alerts.nearestPlayerRadius * Cfg.alerts.nearestPlayerRadius;
        for (ServerPlayer p : level.players()) {
            double dd = p.distanceToSqr(entity);
            if (dd <= best && !p.isSpectator()) {
                best = dd;
                nearest = p;
            }
        }
        Entry en = new Entry();
        en.species = pk.getSpecies().getResourceIdentifier().toString();
        en.aspects = new ArrayList<>(pk.getAspects());
        en.pokemonUuid = pk.getUuid().toString();
        en.entityUuid = entity.getUUID().toString();
        en.time = System.currentTimeMillis();
        en.biome = biome.toString();
        en.nearest = nearest == null ? "" : nearest.getGameProfile().getName();
        en.loc = new Loc(level.dimension().location().toString(), entity.getX(), entity.getY(), entity.getZ(), 0, 0);
        en.shiny = pk.getShiny();
        en.onlyTo = onlyTo == null ? "" : onlyTo.getUUID().toString();
        for (String c : cats) {
            Deque<Entry> dq = HISTORY.computeIfAbsent(c, k -> new ArrayDeque<>());
            dq.addFirst(en);
            while (dq.size() > Cfg.alerts.historySize) dq.removeLast();
        }
        BY_ENTITY.computeIfAbsent(en.entityUuid, k -> new ArrayList<>()).add(en);
        broadcast(pk, cats, en, onlyTo);
    }

    private static Component biomeName(String biome) {
        ResourceLocation rl = ResourceLocation.tryParse(biome);
        if (rl == null) return Component.literal(biome);
        return Component.translatable("biome." + rl.getNamespace() + "." + rl.getPath());
    }

    private static String label(List<String> cats) {
        List<String> parts = new ArrayList<>();
        for (String c : cats) {
            AlertsConfig.Category cat = Cfg.alerts.categories.get(c);
            parts.add(cat.color + cat.label);
        }
        return String.join("&7, ", parts);
    }

    private static void broadcast(Pokemon pk, List<String> cats, Entry en, ServerPlayer onlyTo) {
        AlertsConfig.Category first = Cfg.alerts.categories.get(cats.get(0));
        MutableComponent name = fr.cobblepulse.utils.pokemon.SpeciesNames.of(pk.getSpecies()).copy();
        String world = fr.cobblepulse.utils.teleport.Worlds.isMain(en.loc.world) ? ""
                : Msg.format("alerts.world_suffix", "world", fr.cobblepulse.utils.teleport.Worlds.plainName(en.loc.world));
        String text = Msg.format(en.nearest.isEmpty() ? "alerts.broadcast_nobody" : "alerts.broadcast",
                "categories", label(cats), "player", en.nearest, "color", first.color, "world", world);
        MutableComponent base = Msg.splice(text, "pokemon", name.withStyle(TextUtil.styleOf(first.color)));
        MutableComponent normal = spliceBiome(base, en);
        MutableComponent staff = normal.copy().withStyle(s -> s
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, TextUtil.legacy(Msg.format("alerts.hover_coords",
                        "world", en.loc.shortWorld(), "coords", en.loc.coords()).replace("|", "\n"))))
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/execute in " + en.loc.world + " run tp @s "
                        + (int) en.loc.x + " " + (int) en.loc.y + " " + (int) en.loc.z)));
        lastBroadcast = text;
        lastBroadcastMessage = normal;
        for (ServerPlayer p : onlyTo != null ? List.of(onlyTo) : CobbleUtils.server().getPlayerList().getPlayers()) {
            if (!Msg.reachable(p) || onlyTo == null && !Perms.player(p, Perms.ALERTS_RECEIVE)) continue;
            p.sendSystemMessage(Perms.perkOrOp(p, Perms.ALERTS_COORDS) ? staff : normal);
            if (first.sound) p.playNotifySound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_CHIME.value(), net.minecraft.sounds.SoundSource.MASTER, 0.7f, 1.6f);
        }
    }

    /** Remplace %biome% par le nom traduit (cote client) du biome. */
    private static MutableComponent spliceBiome(MutableComponent in, Entry en) {
        MutableComponent out = Component.empty();
        boolean done = false;
        for (Component part : in.toFlatList()) {
            String s = part.getString();
            int i = s.indexOf("%biome%");
            if (i < 0 || done) {
                out.append(part);
                continue;
            }
            out.append(Component.literal(s.substring(0, i)).setStyle(part.getStyle()));
            out.append(biomeName(en.biome).copy().setStyle(part.getStyle()));
            out.append(Component.literal(s.substring(i + 7)).setStyle(part.getStyle()));
            done = true;
        }
        return out;
    }

    /** Auto-test : capture simulee. */
    public static void testCapture(Pokemon pk, ServerPlayer player) {
        onCapture(pk, player);
    }

    private static void onCapture(Pokemon pk, ServerPlayer player) {
        String name = player == null ? "" : player.getGameProfile().getName();
        for (Entry e : tracked(pk.getUuid().toString())) {
            if (!open(e)) continue;
            outcome(e, "CAPTURED", name, Cfg.alerts.announceCapture && !name.isEmpty() ? "alerts.captured" : null);
        }
    }

    /** Auto-test : victoire en combat simulee. */
    public static void testDefeat(Pokemon pk, ServerPlayer player) {
        defeated(pk.getUuid().toString(), player == null ? "" : player.getGameProfile().getName());
    }

    /** Fin de combat : un Pokemon sauvage suivi a ete mis K.O. par un ou plusieurs joueurs. */
    private static void onVictory(List<com.cobblemon.mod.common.api.battles.model.actor.BattleActor> winners,
                                  List<com.cobblemon.mod.common.api.battles.model.actor.BattleActor> losers) {
        List<String> names = new ArrayList<>();
        for (var a : winners) {
            if (!(a instanceof com.cobblemon.mod.common.battles.actor.PlayerBattleActor)) continue;
            for (UUID u : a.getPlayerUUIDs()) {
                ServerPlayer p = fr.cobblepulse.utils.core.Players.online(u);
                if (p != null && !names.contains(p.getGameProfile().getName())) names.add(p.getGameProfile().getName());
            }
        }
        if (names.isEmpty()) return;
        for (var a : losers) {
            // l'entite d'un Pokemon sauvage K.O. n'est plus liee au combat : on passe par l'identifiant du Pokemon
            if (!(a instanceof com.cobblemon.mod.common.battles.actor.PokemonBattleActor pa) || pa.getPokemon() == null) continue;
            Set<String> ids = new HashSet<>();
            if (pa.getPokemon().getOriginalPokemon() != null) ids.add(pa.getPokemon().getOriginalPokemon().getUuid().toString());
            if (pa.getPokemon().getEffectedPokemon() != null) ids.add(pa.getPokemon().getEffectedPokemon().getUuid().toString());
            for (String id : ids) defeated(id, String.join(", ", names));
        }
    }

    private static void defeated(String pokemonUuid, String players) {
        for (Entry e : tracked(pokemonUuid)) {
            if (!open(e)) continue;
            outcome(e, "DEFEATED", players, Cfg.alerts.announceDefeat && !players.isEmpty() ? "alerts.defeated" : null);
        }
    }

    /** Mort d'un Pokemon suivi hors combat (epee, arc, chute...). En combat, c'est la victoire qui est annoncee. */
    public static void onDeath(Entity entity, net.minecraft.world.damagesource.DamageSource source) {
        if (!(entity instanceof PokemonEntity pe)) return;
        List<Entry> list = BY_ENTITY.get(entity.getUUID().toString());
        if (list == null || pe.isBattling()) return;
        ServerPlayer killer = source != null && source.getEntity() instanceof ServerPlayer sp ? sp : null;
        String name = killer == null ? "" : killer.getGameProfile().getName();
        for (Entry e : new ArrayList<>(list)) {
            if (!open(e)) continue;
            outcome(e, "KILLED", name, Cfg.alerts.announceKill && killer != null ? "alerts.killed" : null);
        }
    }

    /** Entrees d'historique de ce Pokemon (une entree peut figurer dans plusieurs categories). */
    private static List<Entry> tracked(String pokemonUuid) {
        List<Entry> out = new ArrayList<>();
        for (Deque<Entry> dq : HISTORY.values()) for (Entry e : dq) if (pokemonUuid.equals(e.pokemonUuid) && !out.contains(e)) out.add(e);
        return out;
    }

    /**
     * Le sort de ce Pokemon peut-il encore etre fixe ? Oui s'il est actif, ou s'il vient d'etre marque « disparu » /
     * « mort » sans joueur : l'entite est retiree du monde avant que la capture ou la victoire soit signalee.
     */
    private static boolean open(Entry e) {
        if ("ACTIVE".equals(e.status)) return true;
        boolean provisional = ("DESPAWN".equals(e.status) || "KILLED".equals(e.status)) && (e.captor == null || e.captor.isEmpty());
        return provisional && System.currentTimeMillis() - e.statusTime < 60_000L;
    }

    private static List<String> categoriesOf(Entry e) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Deque<Entry>> h : HISTORY.entrySet()) {
            if (h.getValue().contains(e) && Cfg.alerts.categories.get(h.getKey()) != null) out.add(h.getKey());
        }
        return out;
    }

    /** Fixe le sort d'un Pokemon rare et l'annonce (key null : pas d'annonce). */
    private static void outcome(Entry e, String status, String player, String key) {
        e.status = status;
        e.captor = player == null ? "" : player;
        e.statusTime = System.currentTimeMillis();
        List<String> cats = categoriesOf(e);
        if (key == null || cats.isEmpty() || !Cfg.alerts.enabled) return;
        AlertsConfig.Category first = Cfg.alerts.categories.get(cats.get(0));
        Species sp = null;
        try {
            sp = PokemonSpecies.getByIdentifier(ResourceLocation.parse(e.species));
        } catch (Throwable ignored) {
        }
        MutableComponent name = (sp == null ? Component.literal(e.species) : fr.cobblepulse.utils.pokemon.SpeciesNames.of(sp).copy()).withStyle(TextUtil.styleOf(first.color));
        String text = Msg.format(key, "categories", label(cats), "player", e.captor, "color", first.color);
        MutableComponent msg = Msg.splice(text, "pokemon", name);
        lastOutcome = text;
        ServerPlayer only = null;
        if (e.onlyTo != null && !e.onlyTo.isEmpty()) {
            try {
                only = fr.cobblepulse.utils.core.Players.online(UUID.fromString(e.onlyTo));
            } catch (IllegalArgumentException ignored) {
            }
            if (only == null) return;
        }
        for (ServerPlayer p : only != null ? List.of(only) : CobbleUtils.server().getPlayerList().getPlayers()) {
            if (!Msg.reachable(p) || only == null && !Perms.player(p, Perms.ALERTS_RECEIVE)) continue;
            p.sendSystemMessage(msg);
        }
    }

    /** Disparition d'une entite suivie (appele par l'evenement de dechargement Fabric). */
    public static void onUnload(Entity entity) {
        List<Entry> list = BY_ENTITY.get(entity.getUUID().toString());
        if (list == null) return;
        Entity.RemovalReason r = entity.getRemovalReason();
        if (r == null || r == Entity.RemovalReason.UNLOADED_TO_CHUNK || r == Entity.RemovalReason.UNLOADED_WITH_PLAYER
                || r == Entity.RemovalReason.CHANGED_DIMENSION) return;
        boolean killed = r == Entity.RemovalReason.KILLED;
        // la capture ou la victoire est signalee apres la disparition de l'entite : le statut pose ici reste provisoire
        // pendant une minute (voir open) ; on laisse 2 s avant de le poser
        Scheduler.later(40, () -> {
            for (Entry e : list) {
                if ("ACTIVE".equals(e.status)) {
                    e.status = killed ? "KILLED" : "DESPAWN";
                    e.captor = "";
                    e.statusTime = System.currentTimeMillis();
                }
            }
            BY_ENTITY.remove(entity.getUUID().toString());
        });
    }

    // ------------------------------------------------------------------ interface

    private static ItemStack icon(Entry e, boolean staff) {
        ItemStack base;
        try {
            Species s = PokemonSpecies.getByIdentifier(ResourceLocation.parse(e.species));
            base = s == null ? new ItemStack(Items.EGG) : PokemonItem.Companion.from(s, new HashSet<>(e.aspects), 1, null);
        } catch (Throwable t) {
            base = new ItemStack(Items.EGG);
        }
        Species s = PokemonSpecies.getByIdentifier(ResourceLocation.parse(e.species));
        ItemBuilder b = ItemBuilder.of(base).name(Component.empty().append(s == null ? Component.literal(e.species) : fr.cobblepulse.utils.pokemon.SpeciesNames.of(s).copy())
                .append(Component.literal(e.shiny ? " ★" : "")));
        b.lore(Msg.format("alerts.icon_time", "time", TimeFmt.duration(System.currentTimeMillis() - e.time), "date", TimeFmt.date(e.time)));
        b.loreComponent(TextUtil.legacy(Msg.format("alerts.icon_biome")).append(biomeName(e.biome).copy().withStyle(net.minecraft.ChatFormatting.WHITE)));
        if (!e.nearest.isEmpty()) b.lore(Msg.format("alerts.icon_nearest", "player", e.nearest));
        b.lore(switch (e.status) {
            case "CAPTURED" -> Msg.format("alerts.status_captured", "player", e.captor);
            case "DEFEATED" -> Msg.format("alerts.status_defeated", "player", e.captor);
            case "KILLED" -> e.captor == null || e.captor.isEmpty() ? Msg.format("alerts.status_dead") : Msg.format("alerts.status_killed", "player", e.captor);
            case "DESPAWN" -> Msg.format("alerts.status_despawn");
            default -> Msg.format("alerts.status_active");
        });
        if (staff && e.loc != null) b.lore("", Msg.format("alerts.icon_coords", "world", e.loc.shortWorld(), "coords", e.loc.coords()), Msg.format("alerts.icon_tp"));
        return b.build();
    }

    public static void openHistory(ServerPlayer p, String category) {
        if (!Cfg.alerts.enabled) {
            Msg.send(p, "alerts.disabled");
            return;
        }
        boolean staff = Perms.perkOrOp(p, Perms.ALERTS_COORDS);
        String cat = category == null || !CATEGORIES.contains(category) ? null : category;
        Gui g = new Gui(6, Msg.format("alerts.title") + (cat == null ? "" : " &8» " + Cfg.alerts.categories.get(cat).color + Cfg.alerts.categories.get(cat).label));
        for (int i = 0; i < 9; i++) g.set(i, Gui.pane());
        int col = 2;
        for (String c : CATEGORIES) {
            AlertsConfig.Category ac = Cfg.alerts.categories.get(c);
            if (ac == null) continue;
            g.button(col++, ItemBuilder.of(Reg.stack(ac.icon)).name(ac.color + ac.label).lore(Msg.format("alerts.cat_lore", "count", HISTORY.get(c).size()))
                    .glow(c.equals(cat)).build(), pl -> openHistory(pl, c));
        }
        List<Entry> entries = new ArrayList<>();
        if (cat != null) entries.addAll(HISTORY.get(cat));
        else {
            Set<Entry> seen = new HashSet<>();
            for (Deque<Entry> dq : HISTORY.values()) for (Entry e : dq) if (seen.add(e)) entries.add(e);
            entries.sort((a, b) -> Long.compare(b.time, a.time));
        }
        int slot = 9;
        for (Entry e : entries) {
            if (slot >= 45) break;
            g.button(slot++, icon(e, staff), pl -> {
                if (staff && e.loc != null) {
                    fr.cobblepulse.utils.gui.Gui.close(pl);
                    Teleports.perform(pl, e.loc, null, null);
                }
            });
        }
        if (entries.isEmpty()) g.set(31, ItemBuilder.of(Items.BARRIER).name(Msg.format("alerts.none")).build());
        for (int i = 45; i < 54; i++) g.set(i, Gui.pane());
        g.button(49, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), Hub::open);
        if (Perms.admin(p, "alerts")) g.button(51, ItemBuilder.of(Items.BELL).name(Msg.format("alerts.test_button")).lore(Msg.format("alerts.test_button_lore")).build(),
                SpawnAlerts::testMenu);
        g.open(p);
    }

    // ------------------------------------------------------------------ persistance

    private static Path file() {
        return Cfg.utilsRoot().resolve("data").resolve("spawn_history.json");
    }

    public static void save() {
        try {
            Cfg.writeAtomic(file(), Json.COMPACT.toJson(HISTORY));
        } catch (Exception e) {
            CobbleUtils.LOG.warn("[CobblePulse-Utils] Sauvegarde de l'historique des spawns : {}", e.toString());
        }
    }

    public static void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            Map<String, List<Entry>> m = Json.COMPACT.fromJson(Files.readString(f, StandardCharsets.UTF_8), new TypeToken<Map<String, List<Entry>>>() {}.getType());
            if (m == null) return;
            for (Map.Entry<String, List<Entry>> e : m.entrySet()) {
                Deque<Entry> dq = HISTORY.computeIfAbsent(e.getKey(), k -> new ArrayDeque<>());
                dq.clear();
                for (Entry en : e.getValue()) {
                    if ("ACTIVE".equals(en.status)) BY_ENTITY.computeIfAbsent(en.entityUuid, k -> new ArrayList<>()).add(en);
                    dq.addLast(en);
                }
            }
        } catch (Exception e) {
            CobbleUtils.LOG.warn("[CobblePulse-Utils] Lecture de l'historique des spawns : {}", e.toString());
        }
    }

    /** Auto-test : historique d'une categorie. */
    public static List<Entry> history(String cat) {
        return new ArrayList<>(HISTORY.getOrDefault(cat, new ArrayDeque<>()));
    }

    public static UUID entity(Entry e) {
        return UUID.fromString(e.entityUuid);
    }
}
