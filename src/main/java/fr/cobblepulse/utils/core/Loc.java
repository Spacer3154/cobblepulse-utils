package fr.cobblepulse.utils.core;

import fr.cobblepulse.utils.CobbleUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** Position serialisable (monde + coordonnees + orientation). */
public final class Loc {
    public String world = "minecraft:overworld";
    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;

    public Loc() {}

    public Loc(String world, double x, double y, double z, float yaw, float pitch) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public static Loc of(ServerPlayer p) {
        return new Loc(p.level().dimension().location().toString(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
    }

    public ServerLevel level() {
        MinecraftServer s = CobbleUtils.server();
        if (s == null) return null;
        ResourceLocation rl = ResourceLocation.tryParse(world);
        if (rl == null) return null;
        return s.getLevel(ResourceKey.create(Registries.DIMENSION, rl));
    }

    public boolean isOverworld() {
        return Level.OVERWORLD.location().toString().equals(world);
    }

    public String shortWorld() {
        String w = world.startsWith("minecraft:") ? world.substring(10) : world;
        return switch (w) {
            case "overworld" -> "Monde";
            case "the_nether" -> "Nether";
            case "the_end" -> "End";
            default -> w;
        };
    }

    public String coords() {
        return (int) Math.floor(x) + " " + (int) Math.floor(y) + " " + (int) Math.floor(z);
    }

    public Loc copy() {
        return new Loc(world, x, y, z, yaw, pitch);
    }
}
