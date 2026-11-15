package fr.cobblepulse.utils.teleport;

import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.core.Loc;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Scheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.function.Consumer;

/** /rtp : position aleatoire et sure (sol solide, pas de liquide, deux blocs d'air), un essai par tick. */
public final class Rtp {
    private Rtp() {}

    public static void start(ServerPlayer p, ServerLevel level) {
        Msg.send(p, "teleport.rtp_searching");
        attempt(p, level, Cfg.general.teleport.rtpAttempts, loc -> {
            if (loc == null) {
                Msg.send(p, "teleport.rtp_failed");
                return;
            }
            Teleports.teleport(p, loc, Msg.format("teleport.rtp_label", "x", (int) loc.x, "z", (int) loc.z), null);
        });
    }

    private static void attempt(ServerPlayer p, ServerLevel level, int left, Consumer<Loc> done) {
        if (p.hasDisconnected()) return;
        if (left <= 0) {
            done.accept(null);
            return;
        }
        Loc found = tryOnce(level, level.getRandom());
        if (found != null) {
            done.accept(found);
            return;
        }
        Scheduler.next(() -> attempt(p, level, left - 1, done));
    }

    public static Loc tryOnce(ServerLevel level, RandomSource rnd) {
        var w = Worlds.get(level);
        int min = w.rtpMinRadius;
        int max = Math.max(min + 1, w.rtpMaxRadius);
        BlockPos sp = level.getSharedSpawnPos();
        BlockPos center = new BlockPos(w.rtpCenterX != null ? w.rtpCenterX : sp.getX(), sp.getY(), w.rtpCenterZ != null ? w.rtpCenterZ : sp.getZ());
        double angle = rnd.nextDouble() * Math.PI * 2;
        double dist = min + rnd.nextDouble() * (max - min);
        int x = center.getX() + (int) (Math.cos(angle) * dist);
        int z = center.getZ() + (int) (Math.sin(angle) * dist);
        if (!level.getWorldBorder().isWithinBounds(new BlockPos(x, 64, z))) return null;
        level.getChunk(x >> 4, z >> 4);
        BlockPos pos = safeColumn(level, x, z);
        return pos == null ? null : new Loc(level.dimension().location().toString(), pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, rnd.nextFloat() * 360f - 180f, 0);
    }

    /** Premier emplacement sur dans la colonne (surface, ou sous un plafond pour le Nether). */
    public static BlockPos safeColumn(ServerLevel level, int x, int z) {
        if (level.dimensionType().hasCeiling()) {
            int top = Math.min(level.getMaxBuildHeight() - 2, level.getMinBuildHeight() + level.getLogicalHeight() - 2);
            for (int y = top; y > level.getMinBuildHeight() + 2; y--) {
                BlockPos feet = new BlockPos(x, y, z);
                if (safe(level, feet)) return feet;
            }
            return null;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos feet = new BlockPos(x, y, z);
        return safe(level, feet) ? feet : null;
    }

    public static boolean safe(ServerLevel level, BlockPos feet) {
        BlockState below = level.getBlockState(feet.below());
        BlockState at = level.getBlockState(feet);
        BlockState head = level.getBlockState(feet.above());
        if (!below.isSolidRender(level, feet.below()) && !below.is(BlockTags.LEAVES)) return false;
        if (below.is(Blocks.MAGMA_BLOCK) || below.is(Blocks.CACTUS) || below.is(Blocks.CAMPFIRE) || below.is(Blocks.SOUL_CAMPFIRE)
                || below.is(Blocks.POWDER_SNOW) || below.is(Blocks.BEDROCK) && level.dimensionType().hasCeiling() && feet.getY() > 120) return false;
        if (!at.getCollisionShape(level, feet).isEmpty() || !head.getCollisionShape(level, feet.above()).isEmpty()) return false;
        if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty()) return false;
        if (level.getFluidState(feet.below()).is(FluidTags.LAVA) || level.getFluidState(feet.below()).is(FluidTags.WATER)) return false;
        return !at.is(Blocks.FIRE) && !at.is(Blocks.SOUL_FIRE) && !at.is(Blocks.SWEET_BERRY_BUSH);
    }
}
