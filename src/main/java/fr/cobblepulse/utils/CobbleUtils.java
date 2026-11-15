package fr.cobblepulse.utils;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CobbleUtils implements ModInitializer {
    public static final String MOD_ID = "cobblepulse_utils";
    public static final Logger LOG = LoggerFactory.getLogger("CobblePulse-Utils");

    @Override
    public void onInitialize() {
        LOG.info("[CobblePulse-Utils] Initialisation");
    }
}
