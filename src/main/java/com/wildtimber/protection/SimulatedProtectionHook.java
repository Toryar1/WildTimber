package com.wildtimber.protection;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * Hook de protection universel par simulation de BlockBreakEvent.
 * Compatible à 100% avec WorldGuard, GriefPrevention, Lands, Towny, Residence, Factions, CoreProtect, etc.
 */
public class SimulatedProtectionHook implements ProtectionHook {

    @Override
    public boolean isProtected(Player player, Location location) {
        if (player == null || location == null) return false;
        Block block = location.getBlock();

        // Simule un BlockBreakEvent pour interroger tous les plugins de claim/protection du serveur
        BlockBreakEvent dummyEvent = new BlockBreakEvent(block, player);
        Bukkit.getPluginManager().callEvent(dummyEvent);

        return dummyEvent.isCancelled();
    }
}
