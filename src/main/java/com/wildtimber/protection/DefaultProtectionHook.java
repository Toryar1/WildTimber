package com.wildtimber.protection;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Implémentation par défaut "no-op" (aucune protection).
 */
public class DefaultProtectionHook implements ProtectionHook {

    @Override
    public boolean isProtected(Player player, Location location) {
        return false;
    }
}
