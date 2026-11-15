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
        promote(entity, pickTier());
    }

    static boolean eligible(Pokemon pk) {
        if (PokemonText.listed(Cfg.boss.blacklistSpecies, pk)) return false;
        Set<String> l = pk.getSpecies().getLabels();
        return Cfg.boss.promoteLegendaries || !(l.contains("legendary") || l.contains("mythical") || l.contains("ultra_beast") || l.contains("paradox"));
    }

    public static String pickTier() {
        double total = 0;
        for (BossConfig.Tier t : Cfg.boss.tiers.values()) total += Math.max(0, t.weight);
        double r = RND.nextDouble() * total;
        for (Map.Entry<String, BossConfig.Tier> e : Cfg.boss.tiers.entrySet()) {
            r -= Math.max(0, e.getValue().weight);
            if (r <= 0) return e.getKey();
        }
        return BossConfig.TIER_ORDER.get(0);
    }

    /** Transforme un Pokemon sauvage en Boss du palier donne. */
    public static void promote(PokemonEntity entity, String tierId) {
        BossConfig.Tier t = Cfg.boss.tiers.get(tierId);
        if (t == null) return;
        Pokemon pk = entity.getPokemon();
        String form = "base";
        if (RND.nextDouble() * 100 < Cfg.boss.specialFormChance) {
            FormData special = specialForm(pk.getSpecies());
            if (special != null) {
                List<String> errors = new ArrayList<>();
                PokemonFactory.applyForm(pk, pk.getSpecies(), special.getName(), errors);
                if (errors.isEmpty() && pk.getForm() == special) form = formKey(special.getName());
            }
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
            Msg.broadcast(Msg.withComponent("boss.announce", "pokemon", pk.getSpecies().getTranslatedName(),
                    "tier", t.color + t.name, "level", level, "biome", "", "x", (int) entity.getX(), "z", (int) entity.getZ()));
        }
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
        Component name = Component.empty().append(TextUtil.legacy(Msg.format("boss.nameplate", "tier", t.color + t.name, "level", pk.getLevel())))
                .append(pk.getSpecies().getTranslatedName().copy().withStyle(TextUtil.styleOf(t.color)));
        entity.setCustomName(name);
        entity.setCustomNameVisible(true);
        entity.setGlowingTag(t.glow);
    }

    /** Niveau au-dela du maximum de Cobblemon (100) : ecriture directe du champ, le setter etant plafonne. */
    public static void applyLevel(Pokemon pk, int level) {
        try {
            pk.setLevel(level);
            if (pk.getLevel() != level) {
                if (levelField == null) {
                    levelField = Pokemon.class.getDeclaredField("level");
                    levelField.setAccessible(true);
                }
                levelField.setInt(pk, level);
            }
        } catch (Throwable t) {
            CobbleUtils.LOG.warn("[CobblePulse-Utils] Niveau {} du Boss impossible : {}", level, t.toString());
        }
    }

    // ------------------------------------------------------------------ formes speciales et objets

    private static final java.util.regex.Pattern SPECIAL = java.util.regex.Pattern.compile("(?i)^(mega([ _-]?[xyz])?|primal|origin)$");

    /** Forme speciale imposee par un test (mega, primal, origin). */
    private static String forcedKind;

    static FormData specialForm(Species s) {
        List<FormData> found = new ArrayList<>();
        for (FormData f : s.getForms()) {
            if (f.getName() == null || !SPECIAL.matcher(f.getName().trim()).matches()) continue;
            if (forcedKind != null && !specialKind(f.getName()).equals(forcedKind)) continue;
            found.add(f);
        }
        return found.isEmpty() ? null : found.get(RND.nextInt(found.size()));
    }

    /** Type de forme speciale : mega, primal, origin, ou "" (forme ordinaire). */
    public static String specialKind(String formName) {
        if (formName == null) return "";
        String n = formName.trim();
        if (!SPECIAL.matcher(n).matches()) return "";
        String l = n.toLowerCase(Locale.ROOT);
        return l.startsWith("mega") ? "mega" : l;
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

    /** Objet de forme : config (espece:forme), sinon Mega-Gemme deduite des objets de Mega Showdown. */
    public static String formItem(String bossKey) {
        String v = Cfg.boss.formItems.get(bossKey);
        if (v != null) return Reg.itemExists(v) ? v : null;
        String[] parts = bossKey.split(":");
        if (parts.length < 2 || !parts[1].startsWith("mega")) return null;
        String species = parts[0].replaceAll("[^a-z]", "");
        String suffix = parts[1].length() > 4 ? parts[1].substring(parts[1].length() - 1) : "";
        String best = null;
        int bestLen = 0;
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            if (!"mega_showdown".equals(id.getNamespace())) continue;
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

    private static PokemonEntity bossOf(PokemonBattle battle) {
        for (BattleActor a : battle.getActors()) {
            if (a instanceof PokemonBattleActor pa && pa.getEntity() != null && isBoss(pa.getEntity())) return pa.getEntity();
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
        FIGHTS.put(battle.getBattleId(), new Fight(tierId, boss.getPokemon().getPersistentData().getCompound(TAG).getString("key"),
                boss.getPokemon().getSpecies().getTranslatedName().getString(), boss.getUUID(), (ServerLevel) boss.level(), System.currentTimeMillis()));
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
        // IA de combat forte pour le Boss
        for (BattleActor a : battle.getActors()) {
            if (a instanceof PokemonBattleActor pa && pa.getEntity() == boss) setAi(pa, t.aiSkill);
        }
        for (ServerPlayer p : players) Msg.send(p, "boss.engaged", "tier", t.color + t.name, "pokemon", boss.getPokemon().getSpecies().getTranslatedName().getString());
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
            }
        }
        if (lost == null) return;
        CompoundTag tag = lost.getPersistentData().getCompound(TAG);
        String tierId = fight != null ? fight.tier() : tag.getString("tier");
        String bossKey = fight != null && !fight.key().isEmpty() ? fight.key() : tag.getString("key");
        String species = lost.getSpecies().getTranslatedName().getString();
        net.minecraft.network.chat.Component speciesName = lost.getSpecies().getTranslatedName();
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
        for (Reward r : t.loot) {
            String s = Rewards.roll(p, r);
            if (s != null) got.add(s);
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
                    if (Rewards.give(p, r)) got.add("&d" + Rewards.describe(r) + (isFirst ? " " + Msg.format("boss.first_win") : ""));
                }
            }
            Players.saveNow(p);
            Msg.send(p, Msg.withComponent("boss.victory", "pokemon", species, "tier", t.color + t.name, "rewards", got.isEmpty() ? "-" : String.join("&7, ", got)));
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
     * @param form auto (tirage normal), base, mega, primal ou origin
     */
    public static PokemonEntity spawnAt(ServerPlayer p, String tierId, String speciesName, String form, double distance) {
        String f = form == null ? "auto" : form.toLowerCase(Locale.ROOT);
        Species s = speciesName == null || speciesName.isBlank() || speciesName.equals("*") ? null : SpeciesLookup.resolve(speciesName);
        if (s == null) {
            List<Species> pool = new ArrayList<>(com.cobblemon.mod.common.api.pokemon.PokemonSpecies.getImplemented());
            if (f.equals("auto") || f.equals("base")) pool.removeIf(x -> x.getLabels().contains("legendary") || x.getLabels().contains("mythical"));
            // forme demandee : especes qui la possedent (Mega, Primo, Originelle)
            else pool.removeIf(x -> x.getForms().stream().noneMatch(fd -> specialKind(fd.getName()).equals(f)));
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
        if (!f.equals("auto")) Cfg.boss.specialFormChance = f.equals("base") ? 0 : 100;
        try {
            if (!f.equals("auto") && !f.equals("base")) forcedKind = f;
            promote(e, tierId);
        } finally {
            Cfg.boss.specialFormChance = old;
            forcedKind = null;
        }
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
