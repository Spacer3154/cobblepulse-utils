package fr.cobblepulse.utils.pokemon;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.PokemonStore;
import com.cobblemon.mod.common.api.storage.PokemonStoreManager;
import com.cobblemon.mod.common.api.storage.factory.FileBackedPokemonStoreFactory;
import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.core.Players;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;

/**
 * Sauvegarde immediate de l'equipe / du PC d'un joueur apres une operation critique (GTS, STS, Echange Miracle) :
 * reduit la fenetre ou un arret brutal ferait reapparaitre un Pokemon deja enregistre en base.
 */
public final class PokemonStores {
    private static Field factories;
    private static boolean warned;

    private PokemonStores() {}

    public static void saveNow(ServerPlayer p) {
        if (p == null || Players.isTest(p.getUUID())) return;
        try {
            save(PokemonText.party(p));
            save(Cobblemon.INSTANCE.getStorage().getPC(p));
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                CobbleUtils.LOG.warn("[CobblePulse-Utils] Sauvegarde immediate des Pokemon indisponible : {}", t.toString());
            }
        }
    }

    private static void save(PokemonStore<?> store) throws Exception {
        if (factories == null) {
            factories = PokemonStoreManager.class.getDeclaredField("factories");
            factories.setAccessible(true);
        }
        Object list = factories.get(Cobblemon.INSTANCE.getStorage());
        if (!(list instanceof Iterable<?> it)) return;
        for (Object f : it) {
            if (f instanceof FileBackedPokemonStoreFactory<?> fb && fb.isCached(store)) {
                fb.save(store, CobbleUtils.server().registryAccess());
            }
        }
    }
}
