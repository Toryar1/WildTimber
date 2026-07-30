package com.wildtimber.util;

import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

/**
 * Helper de compatibilité pour la gestion des enchantements multi-versions (1.19.4 -> 26.2+).
 */
public class EnchantCompat {

    public static Enchantment getEnchant(String keyName, String legacyName) {
        try {
            Enchantment enc = Enchantment.getByKey(NamespacedKey.minecraft(keyName));
            if (enc != null) return enc;
        } catch (Throwable ignored) {}
        try {
            @SuppressWarnings("deprecation")
            Enchantment enc = Enchantment.getByName(legacyName);
            if (enc != null) return enc;
        } catch (Throwable ignored) {}
        return null;
    }

    public static int getLevel(ItemStack item, String keyName, String legacyName) {
        if (item == null) return 0;
        Enchantment enc = getEnchant(keyName, legacyName);
        return enc != null ? item.getEnchantmentLevel(enc) : 0;
    }
}
