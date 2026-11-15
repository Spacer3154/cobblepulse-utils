package fr.cobblepulse.utils;

import fr.cobblepulse.utils.alerts.SpawnAlerts;
import fr.cobblepulse.utils.boss.Bosses;
import fr.cobblepulse.utils.command.Cmd;
import fr.cobblepulse.utils.command.RootCommands;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.core.Loc;
import fr.cobblepulse.utils.core.Players;
import fr.cobblepulse.utils.core.Scheduler;
import fr.cobblepulse.utils.data.PlayerData;
import fr.cobblepulse.utils.data.PlayerStore;
import fr.cobblepulse.utils.dex.DexRewards;
import fr.cobblepulse.utils.enderchest.Enderchests;
import fr.cobblepulse.utils.essentials.ChatFeatures;
import fr.cobblepulse.utils.essentials.EssentialsCommands;
import fr.cobblepulse.utils.essentials.Vanish;
import fr.cobblepulse.utils.gui.ChatInput;
import fr.cobblepulse.utils.integration.Economy;
import fr.cobblepulse.utils.kits.Kits;
import fr.cobblepulse.utils.market.Gts;
import fr.cobblepulse.utils.market.GtsMenus;
import fr.cobblepulse.utils.market.Mailbox;
import fr.cobblepulse.utils.market.Sts;
import fr.cobblepulse.utils.market.WonderTrade;
import fr.cobblepulse.utils.net.Net;
import fr.cobblepulse.utils.shop.ShopMenus;
import fr.cobblepulse.utils.storage.Db;
import fr.cobblepulse.utils.teleport.TeleportCommands;
import fr.cobblepulse.utils.teleport.Teleports;
import fr.cobblepulse.utils.teleport.Tpa;
import fr.cobblepulse.utils.wiki.WikiService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CobblePulse Utils : mod central de l'ecosysteme CobblePulse (Fabric 1.21.1, Cobblemon 1.8.4).
 * Hub, Wiki, teleportation, Shop/GTS/STS/Echange Miracle, kits, enderchests, Dex Rewards, alertes de spawns, Boss PVE.
 */
public final class CobbleUtils implements ModInitializer {
    public static final String MOD_ID = "cobblepulse_utils";
    public static final Logger LOG = LoggerFactory.getLogger("CobblePulse-Utils");
    private static final ResourceLocation LATE = ResourceLocation.fromNamespaceAndPath(MOD_ID, "late");
    private static MinecraftServer server;

    public static MinecraftServer server() {
        return server;
    }

    @Override
    public void onInitialize() {
        Net.registerTypes();
        Net.registerServer();
        SpawnAlerts.init();
        Bosses.init();

        ServerLifecycleEvents.SERVER_STARTING.register(s -> {
            server = s;
            Cfg.loadAll();
            try {
                Db.init();
            } catch (Exception e) {
                LOG.error("[CobblePulse-Utils] Base de donnees indisponible : {}", e.toString(), e);
            }
            Economy.init();
            SpawnAlerts.load();
        });
        // mondes ajoutes par d'autres mods (y compris crees a chaud) : ajoutes a worlds.json
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents.LOAD.register((s, level) -> {
            if (Cfg.worlds != null && fr.cobblepulse.utils.teleport.Worlds.ensure(level) && s.isRunning()) Cfg.save("worlds.json", Cfg.worlds);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            fr.cobblepulse.utils.teleport.Worlds.sync(s);
            Scheduler.repeat(20, 20, fr.cobblepulse.utils.playtime.PlaytimeRewards::tick);
            Scheduler.repeat(1200, 1200, () -> {
                PlayerStore.flushDirty();
                Enderchests.periodicSave();
                Gts.expireTick();
            });
            Scheduler.repeat(600, 600, () -> {
                for (ServerPlayer p : s.getPlayerList().getPlayers()) if (!Players.isTest(p.getUUID())) Mailbox.autoClaimMoney(p, false);
            });
            Scheduler.repeat(400, 400, Bosses::tick);
            Scheduler.repeat(100, 6000, () -> {
                WonderTrade.refill();
                SpawnAlerts.save();
            });
            if (!Cmd.replaced().isEmpty()) LOG.info("[CobblePulse-Utils] Commandes d'autres mods remplacees : {}", Cmd.replaced());
            LOG.info("[CobblePulse-Utils] Pret ({} categories de shop, {} kits, {} warps)", Cfg.SHOP.size(), Cfg.kits.kits.size(), Cfg.warps.warps.size());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            PlayerStore.saveAllSync();
            Enderchests.saveAllSync();
            SpawnAlerts.save();
            Scheduler.clear();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            Db.close();
            PlayerStore.clear();
            server = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            Scheduler.tick(s);
            Teleports.tick();
        });

        // Commandes : phase tardive pour voir (et remplacer / fusionner) les commandes vanilla, Cobblemon et des autres mods
        CommandRegistrationCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, LATE);
        CommandRegistrationCallback.EVENT.register(LATE, (d, ctx, env) -> {
            if (Cfg.general == null || Cfg.commands.commands.isEmpty()) Cfg.loadAll();
            RootCommands.register(d);
            WikiService.register(d);
            TeleportCommands.register(d);
            EssentialsCommands.register(d);
            ChatFeatures.register(d);
            Kits.register(d);
            Enderchests.register(d);
            DexRewards.register(d);
            ShopMenus.register(d);
            GtsMenus.register(d);
            Sts.register(d);
            WonderTrade.register(d);
            SpawnAlerts.register(d);
            fr.cobblepulse.utils.teleport.Worlds.register(d);
            fr.cobblepulse.utils.playtime.PlaytimeRewards.register(d);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            ServerPlayer p = handler.getPlayer();
            boolean first = PlayerStore.get(p).firstJoin == 0;
            PlayerStore.onJoin(p);
            ChatFeatures.onJoin(p);
            Vanish.onJoin(p);
            Scheduler.later(40, () -> {
                if (!p.hasDisconnected()) Mailbox.autoClaimMoney(p, true);
            });
            if (first && Cfg.warps.spawn != null) Scheduler.later(5, () -> {
                if (!p.hasDisconnected()) Teleports.perform(p, Cfg.warps.spawn, null, null);
            });
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            ServerPlayer p = handler.getPlayer();
            Teleports.cancel(p.getUUID());
            Tpa.clear(p.getUUID());
            ChatInput.clear(p.getUUID());
            fr.cobblepulse.utils.gui.Screens.forget(p.getUUID());
            fr.cobblepulse.utils.playtime.PlaytimeRewards.forget(p.getUUID());
            Vanish.onLeave(p);
            ChatFeatures.onLeave(p);
            PlayerStore.onLeave(p);
        });
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            fr.cobblepulse.utils.playtime.PlaytimeRewards.active(sender);
            return ChatFeatures.allowChat(sender, message.signedContent());
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer p && EssentialsCommands.isGod(p)) return false;
            return Bosses.allowDamage(entity, source);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer p) Teleports.setBack(p, Loc.of(p), true);
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> Bosses.onLoad(entity));
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> SpawnAlerts.onUnload(entity));
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp) || !Cfg.general.enderchest.replaceVanilla) return InteractionResult.PASS;
            if (!level.getBlockState(hit.getBlockPos()).is(Blocks.ENDER_CHEST) || sp.isSecondaryUseActive()) return InteractionResult.PASS;
            if (!fr.cobblepulse.utils.core.Perms.command(sp, "enderchest")) return InteractionResult.PASS;
            Enderchests.open(sp, sp.getUUID(), sp.getGameProfile().getName(), -1);
            return InteractionResult.SUCCESS;
        });
    }

    /** /cp reload : configurations et messages (le type de stockage necessite un redemarrage). */
    public static void reload() {
        Cfg.loadAll();
        Economy.init();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PlayerData d = PlayerStore.get(p);
            ChatFeatures.applyNick(p.getUUID(), d.nick, p.getGameProfile().getName());
            server.getCommands().sendCommands(p);
        }
    }
}
