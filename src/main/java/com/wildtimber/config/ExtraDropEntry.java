package com.wildtimber.config;

import org.bukkit.Material;

/**
 * Représente une entrée de loot additionnel configurable.
 */
public record ExtraDropEntry(Material material, double chance, int min, int max) {
    public ExtraDropEntry {
        if (min > max) {
            throw new IllegalArgumentException("ExtraDropEntry: min (" + min + ") > max (" + max + ") pour: " + material);
        }
        if (chance < 0.0 || chance > 1.0) {
            throw new IllegalArgumentException("ExtraDropEntry: chance (" + chance + ") hors de [0,1] pour: " + material);
        }
    }
}
