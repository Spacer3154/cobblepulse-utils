package fr.cobblepulse.utils.mixin;

import com.cobblemon.mod.common.config.CobblemonConfig;
import fr.cobblepulse.utils.boss.Bosses;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Niveau des Boss dans la fenetre de combat.
 * <p>Le Pokemon adverse est decrit au client par une fiche de proprietes ; en la relisant, Cobblemon ramene le niveau
 * a {@code maxPokemonLevel} : un Boss de niveau 125 s'affichait « Nv.100 » en combat alors qu'il combat bien au
 * niveau 125. On releve la borne de cette lecture. Le niveau reel d'un Pokemon reste plafonne par
 * {@code Pokemon.setLevel} : « /pokegive level=150 » donne toujours un niveau 100.</p>
 */
@Pseudo
@Mixin(targets = "com.cobblemon.mod.common.api.pokemon.PokemonProperties$Companion", remap = false)
public abstract class PokemonPropertiesLevelMixin {
    @Redirect(method = "parse*", at = @At(value = "INVOKE", target = "Lcom/cobblemon/mod/common/config/CobblemonConfig;getMaxPokemonLevel()I", remap = false),
            require = 0, remap = false)
    private int cpu$bossLevels(CobblemonConfig config) {
        return Math.max(config.getMaxPokemonLevel(), Bosses.CODEC_MAX_LEVEL);
    }
}
