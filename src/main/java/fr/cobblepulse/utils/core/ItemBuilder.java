package fr.cobblepulse.utils.core;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.ArrayList;
import java.util.List;

/** Construction d'items d'interface (nom, lore, brillance, tete de joueur). */
public final class ItemBuilder {
    private final ItemStack stack;
    private final List<Component> lore = new ArrayList<>();

    private ItemBuilder(ItemStack stack) {
        this.stack = stack;
    }

    public static ItemBuilder of(Item item) {
        return new ItemBuilder(new ItemStack(item));
    }

    public static ItemBuilder of(ItemStack stack) {
        return new ItemBuilder(stack.copy());
    }

    /** Item depuis un identifiant "modid:item" ; barriere si introuvable. */
    public static ItemBuilder of(String id) {
        return new ItemBuilder(new ItemStack(item(id, Items.BARRIER)));
    }

    public static Item item(String id, Item fallback) {
        ResourceLocation rl = id == null ? null : ResourceLocation.tryParse(id.trim());
        if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) return fallback;
        return BuiltInRegistries.ITEM.get(rl);
    }

    public static boolean exists(String id) {
        ResourceLocation rl = id == null ? null : ResourceLocation.tryParse(id.trim());
        return rl != null && BuiltInRegistries.ITEM.containsKey(rl);
    }

    public ItemBuilder name(String legacy) {
        stack.set(DataComponents.CUSTOM_NAME, TextUtil.item(legacy));
        return this;
    }

    public ItemBuilder name(Component c) {
        stack.set(DataComponents.CUSTOM_NAME, Component.empty().withStyle(s -> s.withItalic(false)).append(c));
        return this;
    }

    public ItemBuilder lore(String... legacyLines) {
        for (String l : legacyLines) {
            if (l == null) continue;
            for (String part : l.split("\n|\\|")) lore.add(TextUtil.item(part));
        }
        return this;
    }

    public ItemBuilder lore(List<String> legacyLines) {
        return lore(legacyLines.toArray(new String[0]));
    }

    public ItemBuilder loreComponent(Component c) {
        lore.add(Component.empty().withStyle(s -> s.withItalic(false)).append(c));
        return this;
    }

    public ItemBuilder glow(boolean glow) {
        if (glow) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return this;
    }

    public ItemBuilder count(int count) {
        stack.setCount(Math.max(1, Math.min(stack.getMaxStackSize(), count)));
        return this;
    }

    public ItemBuilder head(GameProfile profile) {
        if (profile != null) stack.set(DataComponents.PROFILE, new ResolvableProfile(profile));
        return this;
    }

    public ItemBuilder hideExtra() {
        stack.set(DataComponents.HIDE_ADDITIONAL_TOOLTIP, net.minecraft.util.Unit.INSTANCE);
        return this;
    }

    public ItemStack build() {
        if (!lore.isEmpty()) stack.set(DataComponents.LORE, new ItemLore(new ArrayList<>(lore)));
        return stack;
    }
}
