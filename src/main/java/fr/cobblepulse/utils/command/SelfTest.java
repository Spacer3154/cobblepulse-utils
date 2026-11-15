package fr.cobblepulse.utils.command;

import net.minecraft.commands.CommandSourceStack;

/** Auto-test (CI) : remplace plus loin. */
public final class SelfTest {
    private SelfTest() {}

    public static boolean enabled() {
        return "true".equals(System.getProperty("cobblepulse_utils.selftest"));
    }

    public static int run(CommandSourceStack src) {
        fr.cobblepulse.utils.CobbleUtils.LOG.info("[CobblePulse-Utils][selftest] SELFTEST SUMMARY: 0 pass, 0 fail");
        fr.cobblepulse.utils.CobbleUtils.LOG.info("[CobblePulse-Utils][selftest] SELFTEST DONE");
        return 0;
    }

    public static int persisted(CommandSourceStack src) {
        return run(src);
    }

    public static int report(CommandSourceStack src) {
        return 0;
    }
}
