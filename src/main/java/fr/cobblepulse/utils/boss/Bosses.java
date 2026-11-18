package fr.cobblepulse.utils.boss;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.AIBattleActor;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.battles.actor.PokemonBattleActor;
import com.cobblemon.mod.common.battles.ai.StrongBattleAI;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.pokemon.properties.UncatchableProperty;
import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.config.BossConfig;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.config.Reward;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.core.Players;
import fr.cobblepulse.utils.core.Reg;
import fr.cobblepulse.utils.core.Rewards;
import fr.cobblepulse.utils.core.Scheduler;
import fr.cobblepulse.utils.core.TextUtil;
import fr.cobblepulse.utils.core.TimeFmt;
import fr.cobblepulse.utils.data.PlayerData;
import fr.cobblepulse.utils.data.PlayerStore;
import fr.cobblepulse.utils.pokemon.PokemonFactory;
import fr.cobblepulse.utils.pokemon.PokemonText;
import fr.cobblepulse.utils.pokemon.SpeciesLookup;
import fr.cobblepulse.utils.storage.Db;
import kotlin.Unit;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Boss PVE : promotion aleatoire des Pokemon sauvages naturels en Boss de 6 raretes (jusqu'au niveau 140),
 * invulnerables aux degats physiques, incapturables, IA de combat forte, butin editable,
 * objet de forme (Mega-Gemme / Orbe / Globe) garanti a la 1re victoire du joueur puis 10 %.
 */
public final class Bosses {
    public static final String TAG = "cpu_boss";
    private static final Random RND = new Random();
    /** Boss actifs : UUID d'entite -> monde. */
    private static final Map<UUID, ServerLevel> ACTIVE = new ConcurrentHashMap<>();
    private static Field levelField;
    private static Field aiField;

    /** Combat contre un Boss, memorise au debut : a la victoire, l'entite du Boss K.O. n'est souvent plus accessible. */
    private record Fight(String tier, String key, String species, UUID entity, ServerLevel level, long started) {}

    private static final Map<UUID, Fight> FIGHTS = new java.util.concurrent.ConcurrentHashMap<>();
    /** Boss portant le nom court de combat, a re-decorer une fois le combat fini (fuite, defaite du joueur). */
    private static final java.util.Set<UUID> BATTLE_NAMED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Auto-test : victoires contre un Boss traitees. */
    public static volatile int victoriesHandled;

    private Bosses() {}

    static Db.Source db() {
        return Db.of("boss_first_victories");
    }

    public static void init() {
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe(Priority.HIGH, e -> {
            try {
                if (!e.isCanceled()) maybePromote(e.getEntity());
            } catch (Throwable t) {
                CobbleUtils.LOG.warn("[CobblePulse-Utils] Promotion de Boss : {}", t.toString());
            }
            return Unit.INSTANCE;
        });
        CobblemonEvents.THROWN_POKEBALL_HIT.subscribe(Priority.HIGHEST, e -> {
            if (isBoss(e.getPokemon())) {
                e.cancel();
                if (e.getPokeBall().getOwner() instanceof ServerPlayer p) Msg.send(p, "boss.uncatchable");
            }
            return Unit.INSTANCE;
        });
        CobblemonEvents.BATTLE_STARTED_PRE.subscribe(Priority.HIGH, e -> {
            try {
                onBattleStart(e.getBattle(), e);
            } catch (Throwable t) {
                CobbleUtils.LOG.warn("[CobblePulse-Utils] Debut de combat de Boss : {}", t.toString());
            }
            return Unit.INSTANCE;
        });
        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL, e -> {
            try {
                onVictory(e.getBattle(), e.getWinners(), e.getLosers());
            } catch (Throwable t) {
                CobbleUtils.LOG.error("[CobblePulse-Utils] Victoire contre un Boss : {}", t.toString());
            }
            return Unit.INSTANCE;
        });
    }

    // ------------------------------------------------------------------ identification

    public static boolean isBoss(Entity e) {
        return e instanceof PokemonEntity pe && pe.getPokemon() != null && pe.getPokemon().getPersistentData().contains(TAG);
    }

    public static String tierOf(PokemonEntity e) {
        return e.getPokemon().getPersistentData().getCompound(TAG).getString("tier");
    }

    public static int activeCount() {
        ACTIVE.entrySet().removeIf(en -> {
            Entity x = en.getValue().getEntity(en.getKey());
            return x == null || x.isRemoved();
        });
        return ACTIVE.size();
    }

    /** Invulnerabilite aux degats physiques (epees, arcs, explosions, chutes...). /kill reste possible. */
    public static boolean allowDamage(LivingEntity entity, DamageSource source) {
        if (!isBoss(entity)) return true;
        return source.is(DamageTypes.GENERIC_KILL) || source.is(DamageTypes.FELL_OUT_OF_WORLD);
    }

    // ------------------------------------------------------------------ promotion

    private static void maybePromote(PokemonEntity entity) {
        BossConfig c = Cfg.boss;
        if (!c.naturalSpawn || c.promotionChance <= 0 || entity == null) return;
        if (entity.level() instanceof ServerLevel sl && !fr.cobblepulse.utils.teleport.Worlds.bosses(sl)) return;
        Pokemon pk = entity.getPokemon();
        if (pk == null || !pk.isWild()) return;
        if (RND.nextDouble() * 100 >= c.promotionChance) return;
        if (!eligible(pk)) return;
        if (activeCount() >= c.maxActive) return;
        // Mega-Boss : seulement pour une espece qui a une Mega-Evolution ou une Primo-Resurgence
        boolean mega = hasMegaTiers() && megaForm(pk.getSpecies()) != null && RND.nextDouble() * 100 < c.specialFormChance;
        promote(entity, pickTier(mega));
    }

    static boolean eligible(Pokemon pk) {
        if (PokemonText.listed(Cfg.boss.blacklistSpecies, pk)) return false;
        Set<String> l = pk.getSpecies().getLabels();
        return Cfg.boss.promoteLegendaries || !(l.contains("legendary") || l.contains("mythical") || l.contains("ultra_beast") || l.contains("paradox"));
    }

    public static boolean hasMegaTiers() {
        for (BossConfig.Tier t : Cfg.boss.tiers.values()) if (t.mega && t.weight > 0) return true;
        return false;
    }

    public static boolean isMegaTier(String tierId) {
        BossConfig.Tier t = Cfg.boss.tiers.get(tierId);
        return t != null && t.mega;
    }

    /** Palier ordinaire au hasard (selon les poids). */
    public static String pickTier() {
        return pickTier(false);
    }

    /** Tirage parmi les paliers ordinaires, ou parmi les paliers de Mega-Boss. */
    public static String pickTier(boolean mega) {
        double total = 0;
        for (BossConfig.Tier t : Cfg.boss.tiers.values()) if (t.mega == mega) total += Math.max(0, t.weight);
        double r = RND.nextDouble() * total;
        for (Map.Entry<String, BossConfig.Tier> e : Cfg.boss.tiers.entrySet()) {
            if (e.getValue().mega != mega) continue;
            r -= Math.max(0, e.getValue().weight);
            if (r <= 0 && e.getValue().weight > 0) return e.getKey();
        }
        for (Map.Entry<String, BossConfig.Tier> e : Cfg.boss.tiers.entrySet()) if (e.getValue().mega == mega) return e.getKey();
        return BossConfig.TIER_ORDER.get(0);
    }

    /** Palier de Mega-Boss associe a un palier ordinaire (epique → mega_epique), sinon le premier ; null s'il n'y en a pas. */
    public static String megaTierFor(String tierId) {
        if (isMegaTier(tierId)) return tierId;
        if (isMegaTier("mega_" + tierId)) return "mega_" + tierId;
        for (Map.Entry<String, BossConfig.Tier> e : Cfg.boss.tiers.entrySet()) if (e.getValue().mega) return e.getKey();
        return null;
    }

    /**
     * Transforme un Pokemon sauvage en Boss du palier donne. Un palier de Mega-Boss impose la Mega-Evolution ou la
     * Primo-Resurgence ; un palier ordinaire peut prendre la forme Originelle.
     * @return false si le palier est inconnu ou si l'espece n'a pas la forme exigee
     */
    public static boolean promote(PokemonEntity entity, String tierId) {
        BossConfig.Tier t = Cfg.boss.tiers.get(tierId);
        if (t == null) return false;
        Pokemon pk = entity.getPokemon();
        String form = "base";
        FormData special = null;
        if (t.mega) {
            special = megaForm(pk.getSpecies());
            if (special == null) return false;
        } else if (RND.nextDouble() * 100 < Cfg.boss.specialFormChance) {
            special = formOf(pk.getSpecies(), Set.of("origin"));
        }
        if (special != null) {
            List<String> errors = new ArrayList<>();
            PokemonFactory.applyForm(pk, pk.getSpecies(), special.getName(), errors);
            if (errors.isEmpty() && pk.getForm() == special) form = formKey(special.getName());
            else if (t.mega) return false;
        }
        int level = t.levelMin + RND.nextInt(t.levelMax - t.levelMin + 1);
        List<Stat> stats = new ArrayList<>(List.of(PokemonText.STATS));
        Collections.shuffle(stats, RND);
        for (int i = 0; i < t.perfectIvs && i < stats.size(); i++) pk.setIV(stats.get(i), 31);
        CompoundTag tag = new CompoundTag();
        tag.putString("tier", tierId);
        tag.putInt("level", level);
        tag.putString("key", key(pk, form));
        tag.putLong("born", System.currentTimeMillis());
        pk.getPersistentData().put(TAG, tag);
        UncatchableProperty.INSTANCE.uncatchable().apply(pk);
        applyLevel(pk, level);
        pk.setScaleModifier(t.scale);
        pk.heal();
        decorate(entity, tierId);
        ACTIVE.put(entity.getUUID(), (ServerLevel) entity.level());
        if (t.announce) {
            Msg.broadcast(Msg.withComponent("boss.announce", "pokemon", fr.cobblepulse.utils.pokemon.SpeciesNames.of(pk.getSpecies()),
                    "tier", t.color + t.name, "level", pk.getLevel(), "biome", "", "x", (int) entity.getX(), "z", (int) entity.getZ()));
        }
        return true;
    }

    /** Re-applique le niveau et l'apparence apres un rechargement de chunk. */
    public static void onLoad(Entity e) {
        if (!(e instanceof PokemonEntity pe) || !isBoss(pe)) return;
        CompoundTag tag = pe.getPokemon().getPersistentData().getCompound(TAG);
        applyLevel(pe.getPokemon(), tag.getInt("level"));
        decorate(pe, tag.getString("tier"));
        ACTIVE.put(pe.getUUID(), (ServerLevel) pe.level());
    }

    private static void decorate(PokemonEntity entity, String tierId) {
        BossConfig.Tier t = Cfg.boss.tiers.get(tierId);
        if (t == null) return;
        Pokemon pk = entity.getPokemon();
        // racine non vide (« %s%s ») : Cobblemon ignore un surnom dont la racine est vide. Surnom pose directement :
        // PokemonEntity.setCustomName l'aplatirait en texte anglais sans couleurs. L'espece reste traduite par chaque client.
        pk.setNickname(Component.translatable("%s%s", TextUtil.legacy(Msg.format("boss.nameplate", "tier", t.color + t.name, "level", pk.getLevel())),
                fr.cobblepulse.utils.pokemon.SpeciesNames.of(pk.getSpecies()).copy().withStyle(TextUtil.styleOf(t.color))));
        BATTLE_NAMED.remove(entity.getUUID());
        entity.setCustomNameVisible(true);
        entity.setGlowingTag(t.glow);
    }

    /** Niveau le plus haut accepte a l'enregistrement d'un Pokemon (voir PokemonLevelCodecMixin) : plafond des paliers. */
    public static final int CODEC_MAX_LEVEL = 250;
    private static volatile Boolean highLevels;

    /**
     * Vrai si un Pokemon au-dela du niveau maximal de Cobblemon peut etre enregistre (sauvegarde du monde, copie).
     * Verifie une fois, par un vrai enregistrement ; sinon les Boss sont plafonnes au niveau maximal, car un Boss
     * non enregistrable ferait echouer la sauvegarde du monde.
     */
    public static boolean highLevelsSupported() {
        Boolean known = highLevels;
        if (known != null) return known;
        boolean ok;
        try {
            PokemonProperties pp = PokemonProperties.Companion.parse("level=5");
            pp.setSpecies("cobblemon:magikarp");
            Pokemon pk = pp.create();
            int target = maxNormalLevel() + 1;
            writeLevel(pk, target);
            CompoundTag saved = pk.saveToNBT(CobbleUtils.server().registryAccess(), new CompoundTag());
            pk.clone(true, CobbleUtils.server().registryAccess());
            ok = saved != null && pk.getLevel() == target;
        } catch (Throwable t) {
            ok = false;
            CobbleUtils.LOG.warn("[CobblePulse-Utils] Cette version de Cobblemon refuse d'enregistrer un Pokemon au-dela du niveau {} ({}) : "
                    + "les Boss seront plafonnes a ce niveau.", maxNormalLevel(), t.getMessage());
        }
        highLevels = ok;
        if (ok) CobbleUtils.LOG.info("[CobblePulse-Utils] Boss au-dela du niveau {} : enregistrement verifie.", maxNormalLevel());
        return ok;
    }

    public static int maxNormalLevel() {
        try {
            return com.cobblemon.mod.common.Cobblemon.INSTANCE.getConfig().getMaxPokemonLevel();
        } catch (Throwable t) {
            return 100;
        }
    }

    private static void writeLevel(Pokemon pk, int level) throws ReflectiveOperationException {
        pk.setLevel(level);
        if (pk.getLevel() != level) {
            if (levelField == null) {
                levelField = Pokemon.class.getDeclaredField("level");
                levelField.setAccessible(true);
            }
            levelField.setInt(pk, level);
        }
    }

    /** Niveau au-dela du maximum de Cobblemon (100) : ecriture directe du champ, le setter etant plafonne. */
    public static void applyLevel(Pokemon pk, int level) {
        try {
            int max = maxNormalLevel();
            if (level > max && !highLevelsSupported()) level = max;
            writeLevel(pk, Math.min(level, CODEC_MAX_LEVEL));
        } catch (Throwable t) {
            CobbleUtils.LOG.warn("[CobblePulse-Utils] Niveau {} du Boss impossible : {}", level, t.toString());
        }
    }

    // ------------------------------------------------------------------ formes speciales et objets

    private static final java.util.regex.Pattern SPECIAL = java.util.regex.Pattern.compile("(?i)^(mega([ _-]?[xyz])?|primal|origin)$");

    /** Forme speciale imposee par un test (mega, primal, origin). */
    private static String forcedKind;

    /** Forme speciale de l'espece parmi ces types (mega, primal, origin), au hasard ; un test peut imposer le type. */
    static FormData formOf(Species s, Set<String> kinds) {
        List<FormData> found = new ArrayList<>();
        for (FormData f : s.getForms()) {
            if (f.getName() == null) continue;
            String kind = specialKind(f.getName());
            if (kind.isEmpty() || !kinds.contains(kind)) continue;
            if (forcedKind != null && !kind.equals(forcedKind)) continue;
            found.add(f);
        }
        return found.isEmpty() ? null : found.get(RND.nextInt(found.size()));
    }

    /** Mega-Evolution ou Primo-Resurgence de l'espece (au hasard s'il y en a plusieurs), ou null. */
    public static FormData megaForm(Species s) {
        return formOf(s, Set.of("mega", "primal"));
    }

    /** Type de forme speciale : mega, primal, origin, ou "" (forme ordinaire). */
    public static String specialKind(String formName) {
        if (formName == null) return "";
        String n = formName.trim();
        if (!SPECIAL.matcher(n).matches()) return "";
        String l = n.toLowerCase(Locale.ROOT);
        return l.startsWith("mega") ? "mega" : l;
    }

    /** Nom de forme en francais pour le chat : Mega-X → Méga-X, Primal → Primo, Origin → Originelle. */
    public static String formLabel(String form) {
        if (form == null) return "";
        String f = form.replace('_', '-');
        String low = f.toLowerCase(java.util.Locale.ROOT);
        if (low.startsWith("mega")) return "Méga" + f.substring(4);
        if (low.equals("primal")) return "Primo";
        if (low.equals("origin")) return "Originelle";
        return f;
    }

    /** Objet de forme d'une espece et d'un nom de forme (Mega-Gemme, Orbe, Globe...), ou null. */
    public static String formItemFor(Species s, String formName) {
        return formItem(s.getResourceIdentifier().getPath() + ":" + formKey(formName));
    }

    public static String formKey(String formName) {
        return formName.trim().toLowerCase(Locale.ROOT).replaceAll("[ -]", "_");
    }

    static String key(Pokemon pk, String form) {
        return pk.getSpecies().getResourceIdentifier().getPath() + ":" + form;
    }

    private static final net.minecraft.tags.TagKey<net.minecraft.world.item.Item> MEGA_STONE_TAG = net.minecraft.tags.TagKey.create(
            net.minecraft.core.registries.Registries.ITEM, ResourceLocation.fromNamespaceAndPath("mega_showdown", "mega_stone"));

    /**
     * Objet de forme : config (espece:forme), sinon Mega-Gemme declaree dans le registre de Mega Showdown (tous les mods),
     * sinon deduite du nom des objets.
     */
    public static String formItem(String bossKey) {
        String v = Cfg.boss.formItems.get(bossKey);
        if (v != null) return Reg.itemExists(v) ? v : null;
        String[] parts = bossKey.split(":");
        if (parts.length < 2 || !parts[1].startsWith("mega")) return null;
        // registre de Mega Showdown : gemmes de tous les mods et datapacks
        String declared = MegaStones.find(parts[0], parts[1]);
        if (declared != null) return declared;
        String species = parts[0].replaceAll("[^a-z]", "");
        String suffix = parts[1].length() > 4 ? parts[1].substring(parts[1].length() - 1) : "";
        String best = null;
        int bestLen = 0;
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            // repli par le nom : objets de Mega Showdown et objets marques Mega-Gemme (#mega_showdown:mega_stone)
            if (!"mega_showdown".equals(id.getNamespace()) && !BuiltInRegistries.ITEM.get(id).builtInRegistryHolder().is(MEGA_STONE_TAG)) continue;
            String path = id.getPath();
            String sfx = "";
            if (path.matches(".*ite_[xyz]$")) {
                sfx = path.substring(path.length() - 1);
                path = path.substring(0, path.length() - 2);
            }
            if (!path.endsWith("ite") || !sfx.equals(suffix)) continue;
            for (String stem : new String[]{path.replaceAll("inite$", ""), path.replaceAll("nite$", ""), path.replaceAll("ite$", "")}) {
                if (stem.length() < 4) continue;
                if (species.startsWith(stem) || stem.startsWith(species)) {
                    int len = Math.min(stem.length(), species.length());
                    if (len > bestLen) {
                        bestLen = len;
                        best = id.toString();
                    }
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ combats

    /**
     * Entite du Boss d'un combat. Pour un Pokemon sauvage, Cobblemon ne renseigne pas l'etat « actif » :
     * PokemonBattleActor.getEntity() vaut toujours null. On passe donc par les donnees du Pokemon puis par l'entite suivie.
     */
    private static PokemonEntity bossOf(PokemonBattle battle) {
        for (BattleActor a : battle.getActors()) {
            if (!(a instanceof PokemonBattleActor pa)) continue;
            if (pa.getEntity() != null && isBoss(pa.getEntity())) return pa.getEntity();
            Pokemon pk = bossPokemon(a);
            if (pk != null) {
                PokemonEntity e = entityOf(pk);
                if (e != null) return e;
            }
        }
        return null;
    }

    private static PokemonBattleActor bossActor(PokemonBattle battle) {
        for (BattleActor a : battle.getActors()) if (bossPokemon(a) != null) return (PokemonBattleActor) a;
        return null;
    }

    /** Entite portant ce Pokemon (Boss suivis, puis tous les mondes). */
    private static PokemonEntity entityOf(Pokemon pk) {
        if (pk.getEntity() != null) return pk.getEntity();
        for (Map.Entry<UUID, ServerLevel> en : ACTIVE.entrySet()) {
            if (en.getValue().getEntity(en.getKey()) instanceof PokemonEntity pe && pe.getPokemon() == pk) return pe;
        }
        for (Map.Entry<UUID, ServerLevel> en : ACTIVE.entrySet()) {
            if (en.getValue().getEntity(en.getKey()) instanceof PokemonEntity pe && pe.getPokemon().getUuid().equals(pk.getUuid())) return pe;
        }
        return null;
    }

    /** Pokemon Boss d'un acteur sauvage (par ses donnees, l'entite pouvant deja avoir disparu). */
    private static Pokemon bossPokemon(BattleActor a) {
        if (!(a instanceof PokemonBattleActor pa) || pa.getPokemon() == null) return null;
        for (Pokemon pk : new Pokemon[]{pa.getPokemon().getOriginalPokemon(), pa.getPokemon().getEffectedPokemon()}) {
            if (pk != null && pk.getPersistentData().contains(TAG)) return pk;
        }
        return null;
    }

    private static void onBattleStart(PokemonBattle battle, com.cobblemon.mod.common.api.events.battles.BattleStartedEvent.Pre event) {
        PokemonEntity boss = bossOf(battle);
        if (boss == null) return;
        String tierId = tierOf(boss);
        BossConfig.Tier t = Cfg.boss.tiers.get(tierId);
        if (t == null) return;
        long cd = t.engageCooldownSeconds * 1000;
        List<ServerPlayer> players = new ArrayList<>();
        for (BattleActor a : battle.getActors()) {
            if (a instanceof PlayerBattleActor) for (UUID u : a.getPlayerUUIDs()) {
                ServerPlayer p = Players.online(u);
                if (p != null) players.add(p);
            }
        }
        if (cd > 0) {
            for (ServerPlayer p : players) {
                if (Perms.perk(p, Perms.BOSS_BYPASS)) continue;
                long left = PlayerStore.get(p).cooldownLeft("boss:" + tierId);
                if (left > 0) {
                    event.cancel();
                    event.setReason(Msg.get("boss.cooldown", "time", TimeFmt.duration(left), "tier", t.color + t.name));
                    Msg.send(p, "boss.cooldown", "time", TimeFmt.duration(left), "tier", t.color + t.name);
                    return;
                }
            }
            for (ServerPlayer p : players) {
                PlayerData d = PlayerStore.get(p);
                d.setCooldown("boss:" + tierId, cd);
                PlayerStore.dirty(p.getUUID());
            }
        }
        FIGHTS.put(battle.getBattleId(), new Fight(tierId, boss.getPokemon().getPersistentData().getCompound(TAG).getString("key"),
                fr.cobblepulse.utils.pokemon.SpeciesNames.text(boss.getPokemon().getSpecies()), boss.getUUID(), (ServerLevel) boss.level(), System.currentTimeMillis()));
        // pendant le combat, le nom d'espece seul, a la couleur du palier : la fenetre de combat de Cobblemon affiche deja
        // le niveau, n'a pas la place pour le palier et sa police n'a pas les symboles. Plaque complete retablie apres.
        boss.getPokemon().setNickname(Component.translatable("%s", fr.cobblepulse.utils.pokemon.SpeciesNames.of(boss.getPokemon().getSpecies()).copy().withStyle(TextUtil.styleOf(t.color))));
        BATTLE_NAMED.add(boss.getUUID());
        // IA de combat forte pour le Boss
        PokemonBattleActor actor = bossActor(battle);
        if (actor != null) setAi(actor, t.aiSkill);
        CobbleUtils.LOG.info("[CobblePulse-Utils] Combat contre le Boss {} ({}) : {}", boss.getPokemon().getSpecies().getName(), tierId,
                players.stream().map(x -> x.getGameProfile().getName()).toList());
        for (ServerPlayer p : players) Msg.send(p, Msg.withComponent("boss.engaged", "pokemon", fr.cobblepulse.utils.pokemon.SpeciesNames.of(boss.getPokemon().getSpecies()), "tier", t.color + t.name));
    }

    private static void setAi(AIBattleActor actor, int skill) {
        try {
            if (aiField == null) {
                aiField = AIBattleActor.class.getDeclaredField("battleAI");
                aiField.setAccessible(true);
            }
            aiField.set(actor, new StrongBattleAI(Math.max(0, Math.min(5, skill))));
        } catch (Throwable t) {
            CobbleUtils.LOG.debug("[CobblePulse-Utils] IA du Boss non appliquee : {}", t.toString());
        }
    }

    private static void onVictory(PokemonBattle battle, List<BattleActor> winners, List<BattleActor> losers) {
        Fight fight = FIGHTS.remove(battle.getBattleId());
        // le Boss a-t-il perdu ? (identifie par les donnees du Pokemon : son entite K.O. n'est plus forcement liee)
        Pokemon lost = null;
        PokemonEntity entity = null;
        for (BattleActor a : losers) {
            Pokemon pk = bossPokemon(a);
            if (pk != null) {
                lost = pk;
                entity = ((PokemonBattleActor) a).getEntity();
                if (entity == null) entity = entityOf(pk);
            }
        }
        if (lost == null) return;
        CompoundTag tag = lost.getPersistentData().getCompound(TAG);
        String tierId = fight != null ? fight.tier() : tag.getString("tier");
        String bossKey = fight != null && !fight.key().isEmpty() ? fight.key() : tag.getString("key");
        String species = fr.cobblepulse.utils.pokemon.SpeciesNames.text(lost.getSpecies());
        net.minecraft.network.chat.Component speciesName = fr.cobblepulse.utils.pokemon.SpeciesNames.of(lost.getSpecies());
        BossConfig.Tier t = Cfg.boss.tiers.get(tierId);
        List<ServerPlayer> players = new ArrayList<>();
        for (BattleActor a : winners) {
            if (a instanceof PlayerBattleActor) for (UUID u : a.getPlayerUUIDs()) {
                ServerPlayer p = Players.online(u);
                if (p != null && !players.contains(p)) players.add(p);
            }
        }
        if (entity == null && fight != null && fight.level() != null && fight.level().getEntity(fight.entity()) instanceof PokemonEntity pe) entity = pe;
        PokemonEntity finalBoss = entity;
        if (finalBoss != null) Scheduler.later(20, () -> despawn(finalBoss, true));
        if (fight != null) ACTIVE.remove(fight.entity());
        victoriesHandled++;
        if (t == null) {
            CobbleUtils.LOG.warn("[CobblePulse-Utils] Boss vaincu mais palier inconnu : {}", tierId);
            return;
        }
        CobbleUtils.LOG.info("[CobblePulse-Utils] Boss {} ({}) vaincu par {}", species, tierId, players.stream().map(x -> x.getGameProfile().getName()).toList());
        for (ServerPlayer p : players) reward(p, t, tierId, bossKey, speciesName);
        if (t.announce && !players.isEmpty()) {
            Msg.broadcast(Msg.withComponent("boss.defeated_broadcast", "pokemon", speciesName, "player", players.get(0).getGameProfile().getName(),
                    "tier", t.color + t.name));
        }
    }

    /** Butin du palier + objet de forme (1re victoire 100 %, ensuite 10 %). */
    public static void reward(ServerPlayer p, BossConfig.Tier t, String tierId, String bossKey, String species) {
        reward(p, t, tierId, bossKey, net.minecraft.network.chat.Component.literal(species));
    }

    /** Le nom de l'espece est un composant : traduit par le client du joueur (Magicarpe et non Magikarp). */
    public static void reward(ServerPlayer p, BossConfig.Tier t, String tierId, String bossKey, net.minecraft.network.chat.Component species) {
        List<String> got = new ArrayList<>();
        // objets proposes dans la fenetre de butin (recuperer, lacher ou jeter) ; argent, BP et commandes : remis tout de suite
        boolean window = Cfg.boss.lootWindow;
        List<net.minecraft.world.item.ItemStack> items = new ArrayList<>();
        for (Reward r : t.loot) {
            if (r.chance < 100 && Math.random() * 100 >= r.chance) continue;
            if (window && Rewards.isItem(r)) {
                net.minecraft.world.item.ItemStack st = fr.cobblepulse.utils.core.ItemSerial.fromSnbt(r.item);
                if (st.isEmpty()) continue;
                items.add(st);
                got.add(Rewards.describe(r));
            } else if (Rewards.give(p, r)) got.add(Rewards.describe(r));
        }
        String item = formItem(bossKey);
        UUID u = p.getUUID();
        db().txAsync(c -> {
            // atomique meme entre plusieurs serveurs : l'insertion echoue sans erreur si la ligne existe deja
            boolean first = Db.update(c, db().insertIgnore() + db().table("boss_victories") + " (uuid, boss_key, first_at, wins) VALUES (?,?,?,1)",
                    u.toString(), bossKey, System.currentTimeMillis()) == 1;
            if (!first) {
                Db.update(c, "UPDATE " + db().table("boss_victories") + " SET wins=wins+1 WHERE uuid=? AND boss_key=?", u.toString(), bossKey);
            }
            return first;
        }).whenComplete((first, err) -> Scheduler.next(() -> {
            if (err != null) CobbleUtils.LOG.error("[CobblePulse-Utils] Suivi des victoires de Boss : {}", err.toString());
            boolean isFirst = Boolean.TRUE.equals(first);
            if (item != null && err == null) {
                double chance = isFirst ? Cfg.boss.formItemFirstChance : Cfg.boss.formItemRepeatChance;
                if (RND.nextDouble() * 100 < chance) {
                    Reward r = Reward.item(item, 100);
                    String label = "&d" + Rewards.describe(r) + (isFirst ? " " + Msg.format("boss.first_win") : "");
                    net.minecraft.world.item.ItemStack st = fr.cobblepulse.utils.core.ItemSerial.fromSnbt(r.item);
                    if (window && !st.isEmpty()) {
                        items.add(0, st);
                        got.add(label);
                    } else if (Rewards.give(p, r)) got.add(label);
                }
            }
            Msg.send(p, Msg.withComponent("boss.victory", "pokemon", species, "tier", t.color + t.name, "rewards", got.isEmpty() ? "-" : String.join("&7, ", got)));
            if (!items.isEmpty()) BossLoot.offer(p, Msg.withComponent("boss.loot_title", "pokemon", species, "tier", t.color + t.name), items);
            Players.saveNow(p);
        }));
    }

    public static void despawn(PokemonEntity e, boolean fx) {
        if (e == null || e.isRemoved()) return;
        if (fx && e.level() instanceof ServerLevel sl) {
            Vec3 pos = e.position();
            sl.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y + 1, pos.z, 1, 0, 0, 0, 0);
            sl.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, pos.x, pos.y + 1, pos.z, 60, 0.6, 1, 0.6, 0.3);
        }
        ACTIVE.remove(e.getUUID());
        e.discard();
    }

    /** Toutes les 20 s : retrait des Boss expires (hors combat). */
    public static void tick() {
        FIGHTS.values().removeIf(f -> System.currentTimeMillis() - f.started() > 3_600_000L);
        long life = Cfg.boss.lifetimeMinutes * 60_000L;
        for (Map.Entry<UUID, ServerLevel> en : new ArrayList<>(ACTIVE.entrySet())) {
            Entity x = en.getValue().getEntity(en.getKey());
            if (!(x instanceof PokemonEntity pe) || x.isRemoved()) {
                ACTIVE.remove(en.getKey());
                continue;
            }
            if (!pe.isBattling() && BATTLE_NAMED.contains(pe.getUUID())) decorate(pe, tierOf(pe));
            long born = pe.getPokemon().getPersistentData().getCompound(TAG).getLong("born");
            if (!pe.isBattling() && born > 0 && System.currentTimeMillis() - born > life) despawn(pe, false);
        }
    }

    public static int clearAll() {
        int n = 0;
        for (ServerLevel level : CobbleUtils.server().getAllLevels()) {
            List<PokemonEntity> list = new ArrayList<>();
            for (Entity e : level.getAllEntities()) if (e instanceof PokemonEntity pe && isBoss(pe)) list.add(pe);
            for (PokemonEntity pe : list) {
                despawn(pe, false);
                n++;
            }
        }
        ACTIVE.clear();
        return n;
    }

    /** /cp boss spawn <palier> [espece] : fait apparaitre un Boss a la position du staff. */
    public static PokemonEntity spawnAt(ServerPlayer p, String tierId, String speciesName) {
        return spawnAt(p, tierId, speciesName, "auto", 3);
    }

    /**
     * Boss de test devant le staff.
     * <p>Un palier de Mega-Boss donne toujours une Mega-Evolution ou une Primo-Resurgence. Demander « mega » ou
     * « primal » sur un palier ordinaire fait apparaitre le Mega-Boss du palier correspondant.</p>
     * @param form auto (tirage normal), base, mega, primal ou origin
     * @return null si le palier est inconnu ou si l'espece n'a pas la forme exigee
     */
    public static PokemonEntity spawnAt(ServerPlayer p, String tierId, String speciesName, String form, double distance) {
        String f = form == null ? "auto" : form.toLowerCase(Locale.ROOT);
        boolean wantsMega = f.equals("mega") || f.equals("primal");
        if (wantsMega && !isMegaTier(tierId)) {
            String m = megaTierFor(tierId);
            if (m == null) return null;
            tierId = m;
        }
        BossConfig.Tier t = Cfg.boss.tiers.get(tierId);
        if (t == null) return null;
        // types de forme acceptes : Mega-Boss = mega et/ou primal ; palier ordinaire = origin seulement
        Set<String> kinds = t.mega ? (wantsMega ? Set.of(f) : Set.of("mega", "primal")) : (f.equals("origin") ? Set.of("origin") : Set.of());
        Species s = speciesName == null || speciesName.isBlank() || speciesName.equals("*") ? null : SpeciesLookup.resolve(speciesName);
        if (s == null) {
            List<Species> pool = new ArrayList<>(com.cobblemon.mod.common.api.pokemon.PokemonSpecies.getImplemented());
            if (kinds.isEmpty()) pool.removeIf(x -> x.getLabels().contains("legendary") || x.getLabels().contains("mythical"));
            // forme exigee : especes qui la possedent (Mega, Primo, Originelle)
            else pool.removeIf(x -> x.getForms().stream().noneMatch(fd -> kinds.contains(specialKind(fd.getName()))));
            if (pool.isEmpty()) return null;
            s = pool.get(RND.nextInt(pool.size()));
        }
        PokemonProperties pp = PokemonProperties.Companion.parse("level=50");
        pp.setSpecies(s.getResourceIdentifier().toString());
        Pokemon pk = pp.create();
        Vec3 look = p.getLookAngle();
        Vec3 pos = p.position().add(new Vec3(look.x, 0, look.z).normalize().scale(distance));
        PokemonEntity e = new PokemonEntity(p.serverLevel(), pk, com.cobblemon.mod.common.CobblemonEntities.POKEMON);
        e.moveTo(pos.x, p.getY(), pos.z, p.getYRot() + 180, 0);
        double old = Cfg.boss.specialFormChance;
        // palier ordinaire : forme Originelle seulement si elle est demandee (auto = tirage normal)
        if (!t.mega && !f.equals("auto")) Cfg.boss.specialFormChance = f.equals("origin") ? 100 : 0;
        boolean ok;
        try {
            if (wantsMega || f.equals("origin") && !t.mega) forcedKind = f;
            ok = promote(e, tierId);
        } finally {
            Cfg.boss.specialFormChance = old;
            forcedKind = null;
        }
        if (!ok) return null;
        p.serverLevel().addFreshEntity(e);
        return e;
    }

    /** Combat de test : le staff affronte immediatement le Boss avec son equipe. */
    public static String startBattle(ServerPlayer p, PokemonEntity boss) {
        var party = fr.cobblepulse.utils.pokemon.PokemonText.party(p);
        Pokemon lead = null;
        for (Pokemon pk : party) if (pk != null && !pk.isFainted()) {
            lead = pk;
            break;
        }
        if (lead == null) return "boss.test_no_pokemon";
        try {
            var res = com.cobblemon.mod.common.battles.BattleBuilder.INSTANCE.pve(p, boss, lead.getUuid(),
                    com.cobblemon.mod.common.battles.BattleFormat.Companion.getGEN_9_SINGLES(), false, false);
            return res instanceof com.cobblemon.mod.common.battles.SuccessfulBattleStart ? null : "boss.test_battle_failed";
        } catch (Throwable t) {
            CobbleUtils.LOG.warn("[CobblePulse-Utils] Combat de test : {}", t.toString());
            return "boss.test_battle_failed";
        }
    }

    public static boolean megaShowdown() {
        return FabricLoader.getInstance().isModLoaded("mega_showdown");
    }
}
