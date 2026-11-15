package fr.cobblepulse.utils.teleport;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import fr.cobblepulse.utils.command.Cmd;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.config.WarpsConfig;
import fr.cobblepulse.utils.core.Loc;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.data.PlayerData;
import fr.cobblepulse.utils.data.PlayerStore;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static net.minecraft.commands.Commands.argument;

/** Commandes de teleportation (§4.2) et de staff associees (/setspawn, /setwarp, /delwarp, /tphere, /tpall). */
public final class TeleportCommands {
    private TeleportCommands() {}

    private static final SuggestionProvider<CommandSourceStack> WARPS = (ctx, b) -> {
        List<String> names = new ArrayList<>();
        ServerPlayer p = ctx.getSource().getPlayer();
        for (WarpsConfig.Warp w : Cfg.warps.warps) if (p == null || WarpMenus.canUse(p, w)) names.add(w.name);
        return SharedSuggestionProvider.suggest(names, b);
    };
    private static final SuggestionProvider<CommandSourceStack> ALL_WARPS = (ctx, b) ->
            SharedSuggestionProvider.suggest(Cfg.warps.warps.stream().map(w -> w.name).toList(), b);
    private static final SuggestionProvider<CommandSourceStack> HOMES = (ctx, b) -> {
        ServerPlayer p = ctx.getSource().getPlayer();
        return SharedSuggestionProvider.suggest(p == null ? List.of() : PlayerStore.get(p).homes.keySet(), b);
    };
    private static final SuggestionProvider<CommandSourceStack> TPA_PENDING = (ctx, b) -> {
        ServerPlayer p = ctx.getSource().getPlayer();
        return SharedSuggestionProvider.suggest(p == null ? List.of() : Tpa.pendingNames(p), b);
    };

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        Cmd.player(d, "spawn", l -> Cmd.lit(l).executes(ctx -> Cmd.run(ctx, p ->
                        Teleports.teleport(p, Worlds.spawnFor(p), Msg.format("teleport.spawn_label"), null)))
                .then(argument("monde", DimensionArgument.dimension()).executes(ctx -> Cmd.asPlayer(ctx, p -> {
                    Worlds.go(p, Worlds.id(DimensionArgument.getDimension(ctx, "monde")));
                    return 1;
                }))));
        // spawn du monde courant (et spawn global dans le monde principal)
        Cmd.admin(d, "setspawn", "setspawn", l -> Cmd.lit(l).executes(ctx -> Cmd.run(ctx, Worlds::setSpawn)));

        // Warps
        Cmd.player(d, "warp", l -> Cmd.lit(l).executes(ctx -> Cmd.run(ctx, WarpMenus::open))
                .then(argument("nom", StringArgumentType.word()).suggests(WARPS).executes(ctx -> Cmd.run(ctx, p -> {
                    WarpsConfig.Warp w = Cfg.warps.find(StringArgumentType.getString(ctx, "nom"));
                    if (w == null || !WarpMenus.canUse(p, w)) {
                        Msg.send(p, "warps.unknown", "warp", StringArgumentType.getString(ctx, "nom"));
                        return;
                    }
                    WarpMenus.go(p, w);
                }))));
        Cmd.admin(d, "setwarp", "warps", l -> Cmd.lit(l).executes(ctx -> Cmd.run(ctx, WarpMenus::editor))
                .then(argument("nom", StringArgumentType.word()).suggests(ALL_WARPS).executes(ctx -> Cmd.run(ctx, p -> {
                    WarpsConfig.Warp w = WarpMenus.createAt(p, StringArgumentType.getString(ctx, "nom"));
                    if (w != null) WarpMenus.edit(p, w);
                }))));
        Cmd.admin(d, "delwarp", "warps", l -> Cmd.lit(l)
                .then(argument("nom", StringArgumentType.word()).suggests(ALL_WARPS).executes(ctx -> {
                    String n = StringArgumentType.getString(ctx, "nom");
                    WarpsConfig.Warp w = Cfg.warps.find(n);
                    if (w == null) {
                        Msg.send(ctx.getSource(), "warps.unknown", "warp", n);
                        return 0;
                    }
                    Cfg.warps.warps.remove(w);
                    Cfg.save("warps.json", Cfg.warps);
                    Msg.send(ctx.getSource(), "warps.deleted", "warp", w.name);
                    return 1;
                })));

        // Homes
        Cmd.player(d, "home", l -> Cmd.lit(l).executes(ctx -> Cmd.run(ctx, WarpMenus::homes))
                .then(argument("nom", StringArgumentType.word()).suggests(HOMES).executes(ctx -> Cmd.run(ctx, p -> {
                    String n = StringArgumentType.getString(ctx, "nom").toLowerCase(java.util.Locale.ROOT);
                    Loc loc = PlayerStore.get(p).homes.get(n);
                    if (loc == null) {
                        Msg.send(p, "homes.unknown", "home", n);
                        return;
                    }
                    Teleports.teleport(p, loc, Msg.format("homes.label", "home", n), null);
                }))));
        Cmd.player(d, "sethome", l -> Cmd.lit(l).executes(ctx -> Cmd.run(ctx, p -> HomeLimits.setHome(p, "home")))
                .then(argument("nom", StringArgumentType.word()).executes(ctx -> Cmd.run(ctx, p ->
                        HomeLimits.setHome(p, StringArgumentType.getString(ctx, "nom"))))));
        Cmd.player(d, "delhome", l -> Cmd.lit(l)
                .then(argument("nom", StringArgumentType.word()).suggests(HOMES).executes(ctx -> Cmd.run(ctx, p -> {
                    String n = StringArgumentType.getString(ctx, "nom").toLowerCase(java.util.Locale.ROOT);
                    PlayerData data = PlayerStore.get(p);
                    if (data.homes.remove(n) == null) {
                        Msg.send(p, "homes.unknown", "home", n);
                        return;
                    }
                    PlayerStore.dirty(p.getUUID());
                    Msg.send(p, "homes.deleted", "home", n);
                }))));

        // TPA
        Cmd.player(d, "tpa", l -> Cmd.lit(l).then(argument("joueur", EntityArgument.player()).executes(ctx -> Cmd.asPlayer(ctx, p ->
                Tpa.request(p, EntityArgument.getPlayer(ctx, "joueur"), false)))));
        Cmd.player(d, "tpahere", l -> Cmd.lit(l).then(argument("joueur", EntityArgument.player()).executes(ctx -> Cmd.asPlayer(ctx, p ->
                Tpa.request(p, EntityArgument.getPlayer(ctx, "joueur"), true)))));
        Cmd.player(d, "tpaccept", l -> Cmd.lit(l).executes(ctx -> Cmd.asPlayer(ctx, p -> Tpa.accept(p, null)))
                .then(argument("joueur", StringArgumentType.word()).suggests(TPA_PENDING).executes(ctx -> Cmd.asPlayer(ctx, p ->
                        Tpa.accept(p, StringArgumentType.getString(ctx, "joueur"))))));
        Cmd.player(d, "tpdeny", l -> Cmd.lit(l).executes(ctx -> Cmd.asPlayer(ctx, p -> Tpa.deny(p, null)))
                .then(argument("joueur", StringArgumentType.word()).suggests(TPA_PENDING).executes(ctx -> Cmd.asPlayer(ctx, p ->
                        Tpa.deny(p, StringArgumentType.getString(ctx, "joueur"))))));
        Cmd.player(d, "tptoggle", l -> Cmd.lit(l).executes(ctx -> Cmd.run(ctx, p -> {
            PlayerData data = PlayerStore.get(p);
            data.tpToggle = !data.tpToggle;
            PlayerStore.dirty(p.getUUID());
            Msg.send(p, data.tpToggle ? "tpa.toggle_off" : "tpa.toggle_on");
        })));

        // Back / RTP / Top / Jump
        Cmd.player(d, "back", l -> Cmd.lit(l).executes(ctx -> Cmd.run(ctx, TeleportCommands::back)));
        Cmd.player(d, "rtp", l -> Cmd.lit(l).executes(ctx -> Cmd.run(ctx, p -> rtp(p, null)))
                .then(argument("monde", DimensionArgument.dimension()).executes(ctx -> Cmd.asPlayer(ctx, p -> {
                    rtp(p, DimensionArgument.getDimension(ctx, "monde"));
                    return 1;
                }))));
        Cmd.player(d, "top", l -> Cmd.lit(l).executes(ctx -> Cmd.run(ctx, p -> {
            ServerLevel level = p.serverLevel();
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ());
            Teleports.perform(p, new Loc(level.dimension().location().toString(), p.getBlockX() + 0.5, y, p.getBlockZ() + 0.5, p.getYRot(), p.getXRot()),
                    Msg.format("teleport.top_label"), null);
        })));
        Cmd.player(d, "jump", l -> Cmd.lit(l).executes(ctx -> Cmd.run(ctx, TeleportCommands::jump)));

        // Staff
        Cmd.admin(d, "tphere", "tphere", l -> Cmd.lit(l).then(argument("joueur", EntityArgument.players()).executes(ctx -> Cmd.asPlayer(ctx, p -> {
            int n = 0;
            for (ServerPlayer t : EntityArgument.getPlayers(ctx, "joueur")) {
                if (t == p) continue;
                Teleports.perform(t, Loc.of(p), p.getGameProfile().getName(), null);
                n++;
            }
            Msg.send(p, "teleport.tphere_done", "count", n);
            return n;
        }))));
        Cmd.admin(d, "tpall", "tpall", l -> Cmd.lit(l).executes(ctx -> Cmd.asPlayer(ctx, p -> {
            int n = 0;
            for (ServerPlayer t : new ArrayList<>(p.getServer().getPlayerList().getPlayers())) {
                if (t == p) continue;
                Teleports.perform(t, Loc.of(p), p.getGameProfile().getName(), null);
                n++;
            }
            Msg.send(p, "teleport.tpall_done", "count", n);
            return n;
        })));
        // /tp et /teleport vanilla : accessibles aussi avec cobblepulse.admin.tp
        Cmd.allowAlso(d, "tp", Perms.adminReq("tp").and(CommandSourceStack::isPlayer));
        Cmd.allowAlso(d, "teleport", Perms.adminReq("tp").and(CommandSourceStack::isPlayer));
    }

    static void back(ServerPlayer p) {
        PlayerData d = PlayerStore.get(p);
        if (d.back == null || d.back.level() == null) {
            Msg.send(p, "teleport.no_back");
            return;
        }
        if (d.backIsDeath && !backOnDeathAllowed(p)) {
            Msg.send(p, "teleport.no_back_death");
            return;
        }
        if (!Teleports.checkCooldown(p, "back", Cfg.general.teleport.backCooldownSeconds)) return;
        Teleports.teleport(p, d.back.copy(), Msg.format("teleport.back_label"), null);
    }

    public static boolean backOnDeathAllowed(ServerPlayer p) {
        Boolean v = fr.cobblepulse.utils.integration.LuckPermsBridge.check(p, Perms.BACK_ON_DEATH);
        return v != null ? v : Cfg.general.teleport.backOnDeathDefault || p.hasPermissions(Cfg.general.opLevel);
    }

    static void rtp(ServerPlayer p, ServerLevel requested) {
        ServerLevel level = requested;
        if (level == null) {
            // monde courant s'il autorise le /rtp, sinon monde principal, sinon le premier monde qui l'autorise
            level = p.serverLevel();
            if (!Worlds.get(level).rtp) level = Worlds.mainLevel();
            if (!Worlds.get(level).rtp) {
                for (ServerLevel l : p.getServer().getAllLevels()) {
                    if (Worlds.get(l).rtp) {
                        level = l;
                        break;
                    }
                }
            }
        }
        String id = Worlds.id(level);
        if (!Worlds.get(level).rtp || !Worlds.canAccess(p, id)) {
            Msg.send(p, "teleport.rtp_world_denied", "world", Worlds.plainName(id));
            return;
        }
        if (Teleports.inBattle(p)) {
            Msg.send(p, "teleport.in_battle");
            return;
        }
        if (!Teleports.checkCooldown(p, "rtp", Cfg.general.teleport.rtpCooldownSeconds)) return;
        Rtp.start(p, level);
    }

    static void jump(ServerPlayer p) {
        int max = Cfg.general.teleport.jumpMaxDistance;
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getViewVector(1f).scale(max));
        BlockHitResult hit = p.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        if (hit.getType() != HitResult.Type.BLOCK) {
            Msg.send(p, "teleport.jump_nothing");
            return;
        }
        ServerLevel level = p.serverLevel();
        BlockPos base = hit.getBlockPos();
        for (int dy = 1; dy <= 6; dy++) {
            BlockPos feet = base.above(dy);
            if (Rtp.safe(level, feet)) {
                Teleports.perform(p, new Loc(level.dimension().location().toString(), feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5,
                        p.getYRot(), p.getXRot()), Msg.format("teleport.jump_label"), null);
                return;
            }
        }
        Msg.send(p, "teleport.jump_unsafe");
    }
}
