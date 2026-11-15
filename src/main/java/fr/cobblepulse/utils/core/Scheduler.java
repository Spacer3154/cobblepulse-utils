package fr.cobblepulse.utils.core;

import fr.cobblepulse.utils.CobbleUtils;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Ordonnanceur simple base sur les ticks serveur (thread principal uniquement). */
public final class Scheduler {
    private static final List<Task> TASKS = new ArrayList<>();
    private static final List<Task> PENDING = new ArrayList<>();
    private static long tick;

    private Scheduler() {}

    public static final class Task {
        private final Runnable runnable;
        private long nextRun;
        private final long period;
        private boolean cancelled;

        private Task(Runnable runnable, long nextRun, long period) {
            this.runnable = runnable;
            this.nextRun = nextRun;
            this.period = period;
        }

        public void cancel() {
            cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    public static long currentTick() {
        return tick;
    }

    /** Execute la tache au prochain tick. */
    public static Task next(Runnable r) {
        return later(1, r);
    }

    public static Task later(long delayTicks, Runnable r) {
        Task t = new Task(r, tick + Math.max(1, delayTicks), -1);
        synchronized (PENDING) {
            PENDING.add(t);
        }
        return t;
    }

    public static Task repeat(long delayTicks, long periodTicks, Runnable r) {
        Task t = new Task(r, tick + Math.max(1, delayTicks), Math.max(1, periodTicks));
        synchronized (PENDING) {
            PENDING.add(t);
        }
        return t;
    }

    public static void tick(MinecraftServer server) {
        tick++;
        synchronized (PENDING) {
            TASKS.addAll(PENDING);
            PENDING.clear();
        }
        Iterator<Task> it = TASKS.iterator();
        List<Task> due = new ArrayList<>();
        while (it.hasNext()) {
            Task t = it.next();
            if (t.cancelled) {
                it.remove();
                continue;
            }
            if (t.nextRun <= tick) {
                due.add(t);
                if (t.period > 0) {
                    t.nextRun = tick + t.period;
                } else {
                    it.remove();
                }
            }
        }
        for (Task t : due) {
            if (t.cancelled) continue;
            try {
                t.runnable.run();
            } catch (Throwable e) {
                CobbleUtils.LOG.error("Erreur dans une tache planifiee", e);
            }
        }
    }

    public static void clear() {
        synchronized (PENDING) {
            PENDING.clear();
        }
        TASKS.clear();
    }
}
