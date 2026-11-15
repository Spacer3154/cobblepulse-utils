package fr.cobblepulse.utils.core;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Acces simples aux registres (objets) par identifiant texte. */
public final class Reg {
    private Reg() {}

    public static ResourceLocation rl(String id, String defaultNs) {
        if (id == null || id.isBlank()) return null;
        String v = id.trim().toLowerCase(java.util.Locale.ROOT);
        return ResourceLocation.tryParse(v.contains(":") ? v : defaultNs + ":" + v);
    }

    /** Objet depuis "modid:item" (ou "item" : minecraft puis cobblemon) ; {@code fallback} si introuvable. */
    public static Item item(String id, Item fallback) {
        if (id == null || id.isBlank()) return fallback;
        ResourceLocation rl = rl(id, "minecraft");
        if (rl != null && BuiltInRegistries.ITEM.containsKey(rl)) return BuiltInRegistries.ITEM.get(rl);
        if (!id.contains(":")) {
            rl = rl(id, "cobblemon");
            if (rl != null && BuiltInRegistries.ITEM.containsKey(rl)) return BuiltInRegistries.ITEM.get(rl);
        }
        return fallback;
    }

    public static boolean itemExists(String id) {
        return item(id, null) != null;
    }

    public static ItemStack stack(String id) {
        return new ItemStack(item(id, Items.BARRIER));
    }

    public static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
