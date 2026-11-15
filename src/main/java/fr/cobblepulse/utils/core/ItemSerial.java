package fr.cobblepulse.utils.core;

import fr.cobblepulse.utils.CobbleUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Serialisation d'objets :
 * <ul>
 *   <li>listes / inventaires : NBT compresse en Base64 (base de donnees) ;</li>
 *   <li>objet seul : SNBT lisible ({@code {id:"minecraft:diamond",count:3,components:{...}}}) pour les fichiers JSON,
 *   NBT complet conserve (enchantements, noms, donnees de mods).</li>
 * </ul>
 * A appeler sur le thread principal (acces aux registres).
 */
public final class ItemSerial {
    private ItemSerial() {}

    private static HolderLookup.Provider registries() {
        return CobbleUtils.server().registryAccess();
    }

    public static String encode(List<ItemStack> items) {
        try {
            NonNullList<ItemStack> list = NonNullList.withSize(items.size(), ItemStack.EMPTY);
            for (int i = 0; i < items.size(); i++) list.set(i, items.get(i) == null ? ItemStack.EMPTY : items.get(i));
            CompoundTag tag = new CompoundTag();
            tag.putInt("Size", items.size());
            ContainerHelper.saveAllItems(tag, list, registries());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            CobbleUtils.LOG.error("[CobblePulse-Utils] Serialisation d'objets impossible", e);
            return "";
        }
    }

    public static List<ItemStack> decode(String data) {
        List<ItemStack> out = new ArrayList<>();
        if (data == null || data.isEmpty()) return out;
        try {
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(Base64.getDecoder().decode(data)), NbtAccounter.unlimitedHeap());
            int size = tag.getInt("Size");
            NonNullList<ItemStack> list = NonNullList.withSize(size, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(tag, list, registries());
            out.addAll(list);
        } catch (Exception e) {
            CobbleUtils.LOG.error("[CobblePulse-Utils] Deserialisation d'objets impossible", e);
        }
        return out;
    }

    public static String encode(SimpleContainer c) {
        List<ItemStack> l = new ArrayList<>();
        for (int i = 0; i < c.getContainerSize(); i++) l.add(c.getItem(i));
        return encode(l);
    }

    public static void decodeInto(String data, SimpleContainer c) {
        List<ItemStack> l = decode(data);
        for (int i = 0; i < c.getContainerSize(); i++) {
            c.setItem(i, i < l.size() ? l.get(i) : ItemStack.EMPTY);
        }
    }

    /** Objet seul en Base64 (gros stacks acceptes). */
    public static String encodeOne(ItemStack stack) {
        List<ItemStack> l = new ArrayList<>();
        l.add(stack);
        return encode(l);
    }

    public static ItemStack decodeOne(String data) {
        List<ItemStack> l = decode(data);
        return l.isEmpty() ? ItemStack.EMPTY : l.get(0);
    }

    /** SNBT lisible et editable a la main. */
    public static String toSnbt(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        try {
            int count = stack.getCount();
            Tag t = stack.copyWithCount(Math.max(1, Math.min(99, count))).save(registries());
            if (t instanceof CompoundTag ct && count > 99) ct.putInt("count", count);
            return t.toString();
        } catch (Exception e) {
            CobbleUtils.LOG.error("[CobblePulse-Utils] SNBT impossible", e);
            return "";
        }
    }

    /**
     * Objet depuis du SNBT, ou depuis un simple identifiant ("minecraft:diamond", "cobblemon:rare_candy 5").
     */
    public static ItemStack fromSnbt(String s) {
        if (s == null || s.isBlank()) return ItemStack.EMPTY;
        String v = s.trim();
        try {
            if (v.startsWith("{")) {
                CompoundTag tag = TagParser.parseTag(v);
                int count = tag.contains("count") ? tag.getInt("count") : 1;
                if (count > 99) tag.putInt("count", 99);
                ItemStack st = ItemStack.parseOptional(registries(), tag);
                if (!st.isEmpty() && count > 99) st.setCount(count);
                return st;
            }
            String[] parts = v.split("\\s+");
            ItemStack st = new ItemStack(Reg.item(parts[0], net.minecraft.world.item.Items.AIR));
            if (parts.length > 1 && !st.isEmpty()) {
                try {
                    st.setCount(Math.max(1, Integer.parseInt(parts[1])));
                } catch (NumberFormatException ignored) {
                }
            }
            return st;
        } catch (Exception e) {
            CobbleUtils.LOG.warn("[CobblePulse-Utils] Objet invalide dans la configuration : {} ({})", v, e.toString());
            return ItemStack.EMPTY;
        }
    }

    public static List<ItemStack> nonEmpty(List<ItemStack> in) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : in) if (s != null && !s.isEmpty()) out.add(s);
        return out;
    }
}
