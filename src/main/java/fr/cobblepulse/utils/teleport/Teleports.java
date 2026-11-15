package fr.cobblepulse.utils.teleport;

import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.core.Loc;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.data.PlayerData;
import fr.cobblepulse.utils.data.PlayerStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Teleportations : compte a rebours (annule si le joueur bouge, contournable avec cobblepulse.teleport.bypassdelay),
 * memorisation de la position precedente (/back), interdiction pendant un combat Pokemon.
 */
public final class Teleports {
    private static final class Pending {
        final Loc dest;
        final Vec3 start;
        final long endMs;
        final String label;
        final Runnable after;
        int lastShown = -1;

        Pending(Loc dest, Vec3 start, long endMs, String label, Runnable after) {
            this.dest = dest;
            this.start = start;
            this.endMs = endMs;
            this.label = label;
            this.after = after;
        }
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private Teleports() {}

    public static boolean inBattle(ServerPlayer p) {
        try {
            return PlayerExtensionsKt.isInBattle(p);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Teleporte le joueur (avec compte a rebours sauf bypass).
     * @param label texte affiche (« au spawn », « vers Steve »...)
     */
    public static void teleport(ServerPlayer p, Loc dest, String label, Runnable after) {
        if (dest == null || dest.level() == null) {
            Msg.send(p, "teleport.invalid_destination");
            return;
        }
        if (inBattle(p)) {
            Msg.send(p, "teleport.in_battle");
            return;
        }
        int delay = Cfg.general.teleport.delaySeconds;
        if (delay <= 0 || Perms.perk(p, Perms.TP_BYPASS_DELAY) || p.isCreative() || p.isSpectator()) {
            perform(p, dest, label, after);
            return;
        }
        PENDING.put(p.getUUID(), new Pending(dest, p.position(), System.currentTimeMillis() + delay * 1000L, label, after));
        Msg.send(p, "teleport.warmup", "seconds", delay, "target", label);
    }

    public static void cancel(UUID uuid) {
        PENDING.remove(uuid);
    }

    public static void tick() {
        if (PENDING.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Pending>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Pending> e = it.next();
            ServerPlayer p = fr.cobblepulse.utils.core.Players.online(e.getKey());
            Pending pd = e.getValue();
            if (p == null) {
                it.remove();
                continue;
            }
            if (Cfg.general.teleport.cancelOnMove && p.position().distanceToSqr(pd.start) > 0.36) {
                it.remove();
                Msg.send(p, "teleport.cancelled_move");
                continue;
            }
            long left = pd.endMs - now;
            if (left <= 0) {
                it.remove();
                perform(p, pd.dest, pd.label, pd.after);
            } else {
                int sec = (int) Math.ceil(left / 1000.0);
                if (sec != pd.lastShown) {
                    pd.lastShown = sec;
                    Msg.actionBar(p, "teleport.countdown", "seconds", sec);
                }
            }
        }
    }

    /** Teleportation immediate (memorise la position pour /back). */
    public static boolean perform(ServerPlayer p, Loc dest, String label, Runnable after) {
        ServerLevel level = dest.level();
        if (level == null) {
            Msg.send(p, "teleport.invalid_destination");
            return false;
        }
        setBack(p, Loc.of(p), false);
        if (p.isPassenger()) p.stopRiding();
        p.teleportTo(level, dest.x, dest.y, dest.z, dest.yaw, dest.pitch);
        p.fallDistance = 0;
        level.playSound(null, BlockPos.containing(dest.x, dest.y, dest.z), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.6f, 1.2f);
        level.sendParticles(ParticleTypes.PORTAL, dest.x, dest.y + 1, dest.z, 30, 0.4, 0.8, 0.4, 0.2);
        if (label != null && !label.isEmpty()) Msg.send(p, "teleport.done", "target", label);
        if (after != null) after.run();
        return true;
    }

    public static void setBack(ServerPlayer p, Loc loc, boolean death) {
        PlayerData d = PlayerStore.get(p);
        d.back = loc;
        d.backIsDeath = death;
        PlayerStore.dirty(p.getUUID());
    }

    /** Verifie (et consomme) un delai de recharge ; bypass avec cobblepulse.teleport.bypasscooldown. */
    public static boolean checkCooldown(ServerPlayer p, String key, int seconds) {
        if (seconds <= 0 || Perms.perk(p, Perms.TP_BYPASS_COOLDOWN)) return true;
        PlayerData d = PlayerStore.get(p);
        long left = d.cooldownLeft(key);
        if (left > 0) {
            Msg.send(p, "teleport.cooldown", "time", fr.cobblepulse.utils.core.TimeFmt.duration(left));
            return false;
        }
        d.setCooldown(key, seconds * 1000L);
        PlayerStore.dirty(p.getUUID());
        return true;
    }

    public static Loc spawn() {
        if (Cfg.warps.spawn != null && Cfg.warps.spawn.level() != null) return Cfg.warps.spawn;
        return Worlds.spawnOf(Worlds.mainLevel());
    }
}
