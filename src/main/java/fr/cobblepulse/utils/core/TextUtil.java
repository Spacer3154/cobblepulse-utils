package fr.cobblepulse.utils.core;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/** Conversion des codes couleur legacy (&a, &l, &#RRGGBB) en composants texte. */
public final class TextUtil {
    private TextUtil() {}

    public static MutableComponent legacy(String input) {
        MutableComponent root = Component.empty();
        if (input == null || input.isEmpty()) return root;
        Style style = Style.EMPTY;
        StringBuilder buf = new StringBuilder();
        int i = 0;
        int n = input.length();
        while (i < n) {
            char c = input.charAt(i);
            if (c == '<' && i + 9 <= n && input.charAt(i + 1) == '#' && input.charAt(i + 8) == '>' && isHex(input, i + 2, 6)) {
                flush(root, buf, style);
                style = Style.EMPTY.withColor(TextColor.fromRgb(Integer.parseInt(input.substring(i + 2, i + 8), 16)));
                i += 9;
                continue;
            }
            if (c == '<' && input.startsWith("</#>", i)) {
                flush(root, buf, style);
                style = Style.EMPTY;
                i += 4;
                continue;
            }
            if ((c == '&' || c == '§') && i + 1 < n) {
                char code = Character.toLowerCase(input.charAt(i + 1));
                if (code == '#' && i + 8 <= n && isHex(input, i + 2, 6)) {
                    flush(root, buf, style);
                    int rgb = Integer.parseInt(input.substring(i + 2, i + 8), 16);
                    style = Style.EMPTY.withColor(TextColor.fromRgb(rgb));
                    i += 8;
                    continue;
                }
                ChatFormatting fmt = ChatFormatting.getByCode(code);
                if (fmt != null) {
                    flush(root, buf, style);
                    if (fmt == ChatFormatting.RESET) {
                        style = Style.EMPTY;
                    } else if (fmt.isColor()) {
                        style = Style.EMPTY.withColor(fmt);
                    } else {
                        style = style.applyFormat(fmt);
                    }
                    i += 2;
                    continue;
                }
            }
            buf.append(c);
            i++;
        }
        flush(root, buf, style);
        return root;
    }

    private static boolean isHex(String s, int from, int len) {
        if (from + len > s.length()) return false;
        for (int k = from; k < from + len; k++) {
            if (Character.digit(s.charAt(k), 16) < 0) return false;
        }
        return true;
    }

    private static void flush(MutableComponent root, StringBuilder buf, Style style) {
        if (buf.length() == 0) return;
        root.append(Component.literal(buf.toString()).setStyle(style));
        buf.setLength(0);
    }

    /** Style correspondant a des codes legacy (ex. "&6&l"). */
    public static Style styleOf(String codes) {
        MutableComponent c = legacy((codes == null ? "" : codes) + "x");
        return c.getSiblings().isEmpty() ? Style.EMPTY : c.getSiblings().get(c.getSiblings().size() - 1).getStyle();
    }

    /** Retire les codes couleur d'une chaine. */
    public static String strip(String input) {
        if (input == null) return "";
        return input.replaceAll("(?i)<#[0-9a-f]{6}>|</#>", "").replaceAll("(?i)[&§]#[0-9a-f]{6}", "").replaceAll("(?i)[&§][0-9a-fk-or]", "");
    }

    /** Version non italique (utile pour les noms/lores d'items). */
    public static MutableComponent item(String legacy) {
        return Component.empty().withStyle(s -> s.withItalic(false)).append(legacy(legacy));
    }

    public static MutableComponent button(String legacyLabel, String command, String legacyHover) {
        MutableComponent c = legacy(legacyLabel);
        Style st = Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
        if (legacyHover != null) {
            st = st.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, legacy(legacyHover)));
        }
        return Component.empty().setStyle(st).append(c);
    }
}
