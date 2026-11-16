package fr.cobblepulse.utils.mixin;

import com.cobblemon.mod.common.config.CobblemonConfig;
import fr.cobblepulse.utils.boss.Bosses;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Sauvegarde des Boss au-dela du niveau maximal de Cobblemon.
 * <p>Le codec d'enregistrement d'un Pokemon refuse tout niveau superieur a {@code maxPokemonLevel} (100) : un Boss de
 * niveau 135 present lors d'une sauvegarde du monde faisait echouer l'ecriture et arretait le serveur. On releve la
 * borne acceptee par ce codec (et lui seul) ; le niveau des Pokemon des joueurs reste plafonne par Cobblemon.</p>
 * <p>Si cette classe change dans une autre version de Cobblemon, l'injection est simplement ignoree et
 * {@link Bosses#highLevelsSupported()} plafonne alors les Boss au niveau maximal.</p>
 */
@Pseudo
@Mixin(targets = "com.cobblemon.mod.common.util.codec.internal.PokemonP1", remap = false)
public abstract class PokemonLevelCodecMixin {
    @Redirect(method = "*", at = @At(value = "INVOKE", target = "Lcom/cobblemon/mod/common/config/CobblemonConfig;getMaxPokemonLevel()I", remap = false),
            require = 0, remap = false)
    private static int cpu$bossLevels(CobblemonConfig config) {
        return Math.max(config.getMaxPokemonLevel(), Bosses.CODEC_MAX_LEVEL);
    }
}
