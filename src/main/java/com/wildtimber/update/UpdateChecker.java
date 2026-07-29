package com.wildtimber.update;

import com.wildtimber.WildTimber;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.InputStream;
import java.net.URI;
import java.util.Scanner;
import java.util.function.Consumer;

/**
 * Vérificateur de mises à jour SpigotMC asynchrone.
 */
public class UpdateChecker implements Listener {

    private final WildTimber plugin;
    private final int resourceId;
    private String latestVersion;

    public UpdateChecker(WildTimber plugin, int resourceId) {
        this.plugin = plugin;
        this.resourceId = resourceId;
    }

    public void checkUpdate(Consumer<String> consumer) {
        if (resourceId <= 0) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (InputStream is = URI.create("https://api.spigotmc.org/legacy/update.php?resource=" + resourceId).toURL().openStream();
                 Scanner scanner = new Scanner(is)) {
                if (scanner.hasNext()) {
                    latestVersion = scanner.next();
                    consumer.accept(latestVersion);
                }
            } catch (Exception e) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[UpdateChecker] Recherche de mise à jour SpigotMC : " + e.getMessage());
                }
            }
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("wildtimber.admin")) return;
        if (latestVersion == null) return;

        String current = plugin.getDescription().getVersion();
        if (!current.equalsIgnoreCase(latestVersion)) {
            player.sendMessage(plugin.getConfigManager().getMessage("update_available", true)
                    .replace("{version}", latestVersion));
        }
    }
}
