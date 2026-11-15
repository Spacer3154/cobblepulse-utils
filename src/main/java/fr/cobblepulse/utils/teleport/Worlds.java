package fr.cobblepulse.utils.teleport;

import com.mojang.brigadier.CommandDispatcher;
import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.command.Cmd;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.config.WorldsConfig;
import fr.cobblepulse.utils.core.ItemBuilder;
import fr.cobblepulse.utils.core.Loc;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.core.Reg;
import fr.cobblepulse.utils.core.TextUtil;
import fr.cobblepulse.utils.gui.ChatInput;
import fr.cobblepulse.utils.gui.Gui;
import fr.cobblepulse.utils.gui.Paged;
import fr.cobblepulse.utils.gui.Pickers;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static net.minecraft.commands.Commands.argument;

/**
 * Multimonde et dimensions : nom, icone et regles de chaque monde (worlds.json), spawn par monde, menu Mondes,
 * detection des mondes ajoutes par d'autres mods (dimensions de mods, mondes crees a chaud par un mod multimonde).
 */
public final class Worlds {
    private Worlds() {}

    // ------------------------------------------------------------------ configuration

    public static String id(ServerLevel level) {
        return level.dimension().location().toString();
    }

    public static WorldsConfig.World get(String id) {
        WorldsConfig.World w = Cfg.worlds.worlds.get(id);
        return w != null ? w : defaults(id);
    }

    public static WorldsConfig.World get(ServerLevel level) {
        return get(id(level));
    }

    private static WorldsConfig.World defaults(String id) {
        WorldsConfig.World w = new WorldsConfig.World();
        ResourceLocation rl = ResourceLocation.tryParse(id);
        String path = rl == null ? id : rl.getPath();
        w.display = "&b&l" + pretty(path);
        w.icon = path.contains("nether") ? "minecraft:netherrack" : path.contains("end") ? "minecraft:end_stone" : "minecraft:filled_map";
        if (id.equals(Cfg.worlds.mainWorld)) {
            w.homes = "ALL";
            w.listed = true;
        }
        return w;
    }

    private static String pretty(String path) {
        StringBuilder b = new StringBuilder();
        for (String part : path.replace('/', '_').split("[_\\-]")) {
            if (part.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return b.length() == 0 ? path : b.toString();
    }

    /** Ajoute au fichier un monde charge par le serveur et encore inconnu. */
    public static boolean ensure(ServerLevel level) {
        String id = id(level);
        if (Cfg.worlds.worlds.containsKey(id)) return false;
        Cfg.worlds.worlds.put(id, defaults(id));
        CobbleUtils.LOG.info("[CobblePulse-Utils] Nouveau monde detecte : {} (ajoute a worlds.json)", id);
        return true;
    }

    public static void sync(MinecraftServer server) {
        boolean changed = false;
        for (ServerLevel l : server.getAllLevels()) changed |= ensure(l);
        if (changed) Cfg.save("worlds.json", Cfg.worlds);
    }

    public static String name(String id) {
        return get(id).display;
    }

    public static String plainName(String id) {
        return TextUtil.strip(name(id));
    }

    public static boolean isMain(String id) {
        return Cfg.worlds.mainWorld.equals(id);
    }

    public static ServerLevel level(String id) {
        MinecraftServer s = CobbleUtils.server();
        ResourceLocation rl = ResourceLocation.tryParse(id == null ? "" : id);
        if (s == null || rl == null) return null;
        return s.getLevel(ResourceKey.create(Registries.DIMENSION, rl));
    }

    public static ServerLevel mainLevel() {
        ServerLevel l = level(Cfg.worlds.mainWorld);
        return l != null ? l : CobbleUtils.server().overworld();
    }

    /** Noeud d'acces a un monde reserve : cobblepulse.world.<namespace>.<chemin>. */
    public static String node(String id) {
        return "cobblepulse.world." + id.replace(':', '.').replace('/', '.').toLowerCase(Locale.ROOT);
    }

    public static boolean canAccess(ServerPlayer p, String id) {
        WorldsConfig.World w = get(id);
        return !w.restricted || Perms.perkOrOp(p, node(id)) || Perms.perkOrOp(p, "cobblepulse.world.*");
    }

    // ------------------------------------------------------------------ regles

    /** Homes dans ce monde : ALL, PERMISSION (cobblepulse.home.multidimension) ou NONE. */
    public static boolean homesAllowed(ServerPlayer p, String id) {
        return switch (get(id).homes) {
            case "ALL" -> true;
            case "NONE" -> false;
            default -> Perms.perkOrOp(p, Perms.HOME_MULTIDIM);
        };
    }

    public static boolean tpaInto(String id) {
        return get(id).tpa;
    }

    public static boolean bosses(ServerLevel level) {
        return get(level).bosses;
    }

    public static boolean alerts(ServerLevel level) {
        return get(level).alerts;
    }

    // ------------------------------------------------------------------ points d'apparition

    /** Spawn d'un monde : celui defini par /setspawn, sinon calcule (point d'apparition, plate-forme de l'End, sol sur du Nether). */
    public static Loc spawnOf(ServerLevel level) {
        String id = id(level);
        WorldsConfig.World w = get(id);
        if (w.spawn != null && w.spawn.level() != null) return w.spawn;
        if (isMain(id) && Cfg.warps.spawn != null && id.equals(Cfg.warps.spawn.world)) return Cfg.warps.spawn;
        if (level.dimension() == Level.END) {
            BlockPos p = ServerLevel.END_SPAWN_POINT;
            EndPlatformFeature.createEndPlatform(level, p.below(), true);
            return new Loc(id, p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 90f, 0f);
        }
        BlockPos sp = level.getSharedSpawnPos();
        if (!level.dimensionType().hasCeiling()) {
            BlockPos top = Rtp.safeColumn(level, sp.getX(), sp.getZ());
            if (top != null) return new Loc(id, top.getX() + 0.5, top.getY(), top.getZ() + 0.5, 0f, 0f);
        }
        for (int r = 0; r <= 32; r += 4) {
            for (int a = 0; a < 8; a++) {
                int x = sp.getX() + (int) Math.round(Math.cos(a * Math.PI / 4) * r);
                int z = sp.getZ() + (int) Math.round(Math.sin(a * Math.PI / 4) * r);
                level.getChunk(x >> 4, z >> 4);
                BlockPos feet = Rtp.safeColumn(level, x, z);
                if (feet != null) return new Loc(id, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0f, 0f);
                if (r == 0) break;
            }
        }
        return new Loc(id, sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5, 0f, 0f);
    }

    /** /setspawn : spawn du monde courant ; dans le monde principal, c'est aussi le spawn global. */
    public static void setSpawn(ServerPlayer p) {
        String id = id(p.serverLevel());
        Loc here = Loc.of(p);
        WorldsConfig.World w = Cfg.worlds.worlds.computeIfAbsent(id, Worlds::defaults);
        w.spawn = here;
        Cfg.save("worlds.json", Cfg.worlds);
        if (isMain(id)) {
            Cfg.warps.spawn = here.copy();
            Cfg.save("warps.json", Cfg.warps);
            p.serverLevel().setDefaultSpawnPos(p.blockPosition(), p.getYRot());
        }
        Msg.send(p, "worlds.spawn_set", "world", plainName(id), "coords", here.coords());
    }

    /** Destination de /spawn sans argument. */
    public static Loc spawnFor(ServerPlayer p) {
        if (Cfg.worlds.spawnInCurrentWorld) {
            WorldsConfig.World w = get(id(p.serverLevel()));
            if (w.spawn != null && w.spawn.level() != null) return w.spawn;
        }
        return Teleports.spawn();
    }

    // ------------------------------------------------------------------ menu Mondes

    public static List<String> listed(ServerPlayer p) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, WorldsConfig.World> e : Cfg.worlds.worlds.entrySet()) {
            if (e.getValue().listed && level(e.getKey()) != null && canAccess(p, e.getKey())) out.add(e.getKey());
        }
        out.sort(Comparator.comparingInt((String id) -> get(id).order).thenComparing(id -> id));
        return out;
    }

    public static void open(ServerPlayer p) {
        List<Paged.Entry> entries = new ArrayList<>();
        String here = id(p.serverLevel());
        for (String id : listed(p)) {
            WorldsConfig.World w = get(id);
            ServerLevel l = level(id);
            ItemBuilder b = ItemBuilder.of(Reg.stack(w.icon)).name(w.display);
            b.lore(w.lore);
            b.lore(Msg.format("worlds.icon_players", "count", l == null ? 0 : l.players().size()));
            if (id.equals(here)) b.lore(Msg.format("worlds.icon_here"));
            b.lore("", Msg.format("worlds.icon_click"));
            entries.add(new Paged.Entry(b.hideExtra().build(), (pl, c) -> go(pl, id)));
        }
        Paged.open(p, Msg.format("worlds.title"), entries, 0, WarpMenus::open);
    }

    public static void go(ServerPlayer p, String id) {
        if (!canAccess(p, id) || !get(id).listed && !Perms.admin(p, "worlds")) {
            Msg.send(p, "worlds.denied", "world", plainName(id));
            return;
        }
        ServerLevel l = level(id);
        if (l == null) {
            Msg.send(p, "worlds.unavailable", "world", id);
            return;
        }
        Gui.close(p);
        Teleports.teleport(p, spawnOf(l), plainName(id), null);
    }

    // ------------------------------------------------------------------ editeur staff

    public static void editor(ServerPlayer p) {
        MinecraftServer s = p.getServer();
        sync(s);
        List<Paged.Entry> entries = new ArrayList<>();
        List<String> ids = new ArrayList<>(Cfg.worlds.worlds.keySet());
        ids.sort(Comparator.comparingInt((String id) -> get(id).order).thenComparing(id -> id));
        for (String id : ids) {
            WorldsConfig.World w = get(id);
            ServerLevel l = level(id);
            ItemBuilder b = ItemBuilder.of(Reg.stack(w.icon)).name(w.display);
            b.lore("&8" + id);
            b.lore(Msg.format("worlds.editor_state", "loaded", l != null ? "&achargé" : "&cnon chargé", "listed", yes(w.listed),
                    "homes", homesLabel(w.homes), "rtp", yes(w.rtp)));
            if (isMain(id)) b.lore(Msg.format("worlds.editor_main"));
            b.lore("", Msg.format("worlds.editor_click"));
            entries.add(new Paged.Entry(b.hideExtra().build(), (pl, c) -> edit(pl, id)));
        }
        Paged.open(p, Msg.format("worlds.editor_title"), entries, 0, fr.cobblepulse.utils.staff.StaffPanel::open, gui -> {
            gui.button(47, ItemBuilder.of(Items.COMPASS).name(Msg.format("worlds.editor_spawn_mode",
                    "mode", Cfg.worlds.spawnInCurrentWorld ? Msg.format("worlds.spawn_mode_current") : Msg.format("worlds.spawn_mode_main")))
                    .lore(Msg.format("worlds.editor_spawn_mode_lore")).build(), pl -> {
                Cfg.worlds.spawnInCurrentWorld = !Cfg.worlds.spawnInCurrentWorld;
                Cfg.save("worlds.json", Cfg.worlds);
                editor(pl);
            });
        });
    }

    private static String yes(boolean b) {
        return b ? "&aoui" : "&cnon";
    }

    private static String homesLabel(String rule) {
        return switch (rule) {
            case "ALL" -> Msg.format("worlds.homes_all");
            case "NONE" -> Msg.format("worlds.homes_none");
            default -> Msg.format("worlds.homes_permission");
        };
    }

    public static void edit(ServerPlayer p, String id) {
        WorldsConfig.World w = Cfg.worlds.worlds.computeIfAbsent(id, Worlds::defaults);
        Gui g = new Gui(5, Msg.format("worlds.edit_title", "world", plainName(id)));
        g.fill(Gui.pane());
        g.set(4, ItemBuilder.of(Reg.stack(w.icon)).name(w.display).lore("&8" + id).lore(w.lore).hideExtra().build());
        toggle(g, 10, Items.FILLED_MAP, "worlds.edit_listed", w.listed, pl -> w.listed = !w.listed, id);
        toggle(g, 11, Items.IRON_BARS, "worlds.edit_restricted", w.restricted, pl -> w.restricted = !w.restricted, id,
                Msg.format("worlds.edit_restricted_lore", "node", node(id)));
        g.button(12, ItemBuilder.of(Items.RED_BED).name(Msg.format("worlds.edit_homes", "rule", homesLabel(w.homes)))
                .lore(Msg.format("worlds.edit_homes_lore")).build(), pl -> {
            w.homes = switch (w.homes) {
                case "ALL" -> "PERMISSION";
                case "PERMISSION" -> "NONE";
                default -> "ALL";
            };
            saveAndEdit(pl, id);
        });
        toggle(g, 13, Items.GRASS_BLOCK, "worlds.edit_rtp", w.rtp, pl -> w.rtp = !w.rtp, id,
                Msg.format("worlds.edit_rtp_lore", "min", w.rtpMinRadius, "max", w.rtpMaxRadius));
        toggle(g, 14, Items.ENDER_PEARL, "worlds.edit_tpa", w.tpa, pl -> w.tpa = !w.tpa, id);
        toggle(g, 15, Items.WITHER_SKELETON_SKULL, "worlds.edit_bosses", w.bosses, pl -> w.bosses = !w.bosses, id);
        toggle(g, 16, Items.BELL, "worlds.edit_alerts", w.alerts, pl -> w.alerts = !w.alerts, id);
        g.button(19, ItemBuilder.of(Items.NAME_TAG).name(Msg.format("worlds.edit_name")).build(), pl -> ChatInput.ask(pl, "kits.ask_display", txt -> {
            if (!txt.equals("-")) w.display = txt;
            saveAndEdit(pl, id);
        }, () -> edit(pl, id)));
        g.button(20, ItemBuilder.of(Items.ITEM_FRAME).name(Msg.format("worlds.edit_icon")).build(), pl -> Pickers.item(pl, "worlds.pick_icon", item -> {
            w.icon = item;
            saveAndEdit(pl, id);
        }, () -> edit(pl, id)));
        g.button(21, ItemBuilder.of(Items.WRITABLE_BOOK).name(Msg.format("warps.edit_lore")).build(), pl -> ChatInput.ask(pl, "warps.ask_lore", txt -> {
            w.lore = new ArrayList<>();
            if (!txt.equals("-")) for (String line : txt.split("\\|")) w.lore.add(line.trim());
            saveAndEdit(pl, id);
        }, () -> edit(pl, id)));
        g.button(22, ItemBuilder.of(Items.COMPASS).name(Msg.format("worlds.edit_radius", "min", w.rtpMinRadius, "max", w.rtpMaxRadius)).build(),
                pl -> ChatInput.ask(pl, "worlds.ask_radius", txt -> {
                    String[] parts = txt.trim().split("\\s+");
                    try {
                        w.rtpMinRadius = Integer.parseInt(parts[0]);
                        w.rtpMaxRadius = parts.length > 1 ? Integer.parseInt(parts[1]) : w.rtpMinRadius + 1000;
                        Cfg.worlds.normalize();
                    } catch (Exception e) {
                        Msg.send(pl, "general.bad_number");
                    }
                    saveAndEdit(pl, id);
                }, () -> edit(pl, id)));
        g.button(23, ItemBuilder.of(Items.HOPPER).name(Msg.format("worlds.edit_order", "order", w.order)).build(), pl -> ChatInput.ask(pl, "worlds.ask_order", txt -> {
            try {
                w.order = Integer.parseInt(txt.trim());
            } catch (Exception e) {
                Msg.send(pl, "general.bad_number");
            }
            saveAndEdit(pl, id);
        }, () -> edit(pl, id)));
        g.button(24, ItemBuilder.of(Items.BEACON).name(Msg.format("worlds.edit_spawn_here"))
                .lore(w.spawn == null ? Msg.format("worlds.edit_spawn_none") : Msg.format("worlds.edit_spawn_at", "coords", w.spawn.coords())).build(), pl -> {
            if (!id(pl.serverLevel()).equals(id)) {
                Msg.send(pl, "worlds.edit_spawn_wrong_world", "world", plainName(id));
                return;
            }
            setSpawn(pl);
            edit(pl, id);
        });
        if (w.spawn != null) g.button(25, ItemBuilder.of(Items.BARRIER).name(Msg.format("worlds.edit_spawn_clear")).build(), pl -> {
            w.spawn = null;
            saveAndEdit(pl, id);
        });
        if (!isMain(id)) g.button(31, ItemBuilder.of(Items.NETHER_STAR).name(Msg.format("worlds.edit_make_main")).lore(Msg.format("worlds.edit_make_main_lore")).build(), pl -> {
            Cfg.worlds.mainWorld = id;
            saveAndEdit(pl, id);
        });
        g.button(36, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), Worlds::editor);
        g.button(40, ItemBuilder.of(Items.ENDER_EYE).name(Msg.format("worlds.edit_visit")).build(), pl -> {
            ServerLevel l = level(id);
            if (l == null) {
                Msg.send(pl, "worlds.unavailable", "world", id);
                return;
            }
            Gui.close(pl);
            Teleports.perform(pl, spawnOf(l), plainName(id), null);
        });
        g.open(p);
    }

    private static void toggle(Gui g, int slot, net.minecraft.world.item.Item icon, String key, boolean on,
                               java.util.function.Consumer<ServerPlayer> flip, String id, String... lore) {
        g.button(slot, ItemBuilder.of(icon).name(Msg.format(key, "state", yes(on)))
                .lore(lore).hideExtra().build(), pl -> {
            flip.accept(pl);
            saveAndEdit(pl, id);
        });
    }

    private static void saveAndEdit(ServerPlayer p, String id) {
        Cfg.save("worlds.json", Cfg.worlds);
        edit(p, id);
    }

    // ------------------------------------------------------------------ commandes

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        Cmd.player(d, "worlds", l -> Cmd.lit(l).executes(ctx -> Cmd.run(ctx, Worlds::open))
                .then(argument("monde", DimensionArgument.dimension()).executes(ctx -> Cmd.asPlayer(ctx, p -> {
                    go(p, id(DimensionArgument.getDimension(ctx, "monde")));
                    return 1;
                }))));
    }
}
