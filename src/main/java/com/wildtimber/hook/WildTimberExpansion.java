package com.wildtimber.hook;

import com.wildtimber.WildTimber;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Extension PlaceholderAPI pour WildTimber.
 */
public class WildTimberExpansion extends PlaceholderExpansion {

    private final WildTimber plugin;

    public WildTimberExpansion(WildTimber plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "wildtimber";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Toryar1";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (params.equalsIgnoreCase("status")) {
            return plugin.getConfigManager().isPluginEnabled() ? "ENABLED" : "DISABLED";
        }
        if (params.equalsIgnoreCase("active_trees")) {
            return String.valueOf(plugin.getTreeManager().getActiveTrees().size());
        }
        if (params.equalsIgnoreCase("version")) {
            return plugin.getDescription().getVersion();
        }

        if (player != null && player.getUniqueId() != null) {
            if (params.equalsIgnoreCase("godmode")) {
                return plugin.getTreeManager().isPlayerGodMode(player.getUniqueId()) ? "ENABLED" : "DISABLED";
            }
            if (params.equalsIgnoreCase("disabled")) {
                return plugin.getTreeManager().isPlayerDisabled(player.getUniqueId()) ? "YES" : "NO";
            }
        }

        return null;
    }
}
