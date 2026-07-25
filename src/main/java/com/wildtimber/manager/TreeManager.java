package com.wildtimber.manager;

import com.wildtimber.WildTimber;
import com.wildtimber.config.ConfigManager;
import com.wildtimber.detection.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gère le stockage et le cycle de vie des arbres engagés.
 */
public class TreeManager {

    private final WildTimber plugin;
    private final ConfigManager configManager;

    // Map de ID -> Arbre
    private final Map<UUID, ActiveTree> activeTrees = new ConcurrentHashMap<>();
    // Map de Monde -> (Position -> Arbre)
    private final Map<String, Map<BlockPos, ActiveTree>> blockToTreeLookup = new ConcurrentHashMap<>();

    private BukkitTask regenTask;
    private BukkitTask bossBarTask;

    // Joueurs ayant désactivé WildTimber
    private final Set<UUID> disabledPlayers = ConcurrentHashMap.newKeySet();
    // Joueurs ayant activé le godmode
    private final Set<UUID> godModePlayers = ConcurrentHashMap.newKeySet();

    // Snapshots pour l'annulation (undo)
    private final Map<UUID, UndoSnapshot> lastFelledSnapshots = new ConcurrentHashMap<>();

    public TreeManager(WildTimber plugin) {

        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
    }

    /**
     * Enregistre un nouvel arbre actif.
     */
    public void registerTree(ActiveTree tree) {
        activeTrees.put(tree.getId(), tree);

        String worldName = tree.getWorld().getName();
        Map<BlockPos, ActiveTree> worldMap = blockToTreeLookup.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>());

        for (BlockPos pos : tree.getLogs()) {
            worldMap.put(pos, tree);
        }
        for (BlockPos pos : tree.getLeaves()) {
            worldMap.put(pos, tree);
        }
    }

    /**
     * Désenregistre un arbre et supprime la BossBar.
     */
    public void unregisterTree(ActiveTree tree) {
        activeTrees.remove(tree.getId());

        String worldName = tree.getWorld().getName();
        Map<BlockPos, ActiveTree> worldMap = blockToTreeLookup.get(worldName);
        if (worldMap != null) {
            for (BlockPos pos : tree.getLogs()) {
                worldMap.remove(pos);
            }
            for (BlockPos pos : tree.getLeaves()) {
                worldMap.remove(pos);
            }
        }
        tree.cleanup();
    }

    /**
     * Trouve l'arbre actif contenant le bloc spécifié.
     */
    public ActiveTree getTreeAt(World world, BlockPos pos) {
        Map<BlockPos, ActiveTree> worldMap = blockToTreeLookup.get(world.getName());
        return worldMap != null ? worldMap.get(pos) : null;
    }

    public Collection<ActiveTree> getActiveTrees() {
        return activeTrees.values();
    }

    /**
     * Affiche la bossbar de l'arbre à un joueur en le retirant de toutes les autres bossbars actives.
     */
    public void showTreeToPlayer(ActiveTree tree, Player player) {
        for (ActiveTree active : activeTrees.values()) {
            if (active != tree) {
                active.getBossBar().removePlayer(player);
            }
        }
        tree.showTo(player);
    }

    public void startRegenTask() {
        stopRegenTask();
        if (configManager.isRegenEnabled()) {
            // Tâche de régénération exécutée chaque seconde (20 ticks)
            regenTask = Bukkit.getScheduler().runTaskTimer(plugin, this::handleRegen, 20L, 20L);
        }
        // Nettoyage des bossbars chaque seconde (20 ticks)
        bossBarTask = Bukkit.getScheduler().runTaskTimer(plugin, this::handleBossBarCleanup, 20L, 20L);
    }

    /**
     * Arrête la tâche de régénération et de bossbar.
     */
    public void stopRegenTask() {
        if (regenTask != null) {
            regenTask.cancel();
            regenTask = null;
        }
        if (bossBarTask != null) {
            bossBarTask.cancel();
            bossBarTask = null;
        }
    }

    /**
     * Supprime la BossBar si l'arbre n'a pas été frappé depuis > 5s ou si le joueur est à > 5 blocs.
     */
    private void handleBossBarCleanup() {
        long now = System.currentTimeMillis();
        for (ActiveTree tree : activeTrees.values()) {
            boolean hitRecently = (now - tree.getLastCutTime()) <= 5000L;
            List<Player> toRemove = new ArrayList<>();

            for (Player player : tree.getBossBar().getPlayers()) {
                if (!player.isOnline() || !player.getWorld().equals(tree.getWorld()) || !hitRecently) {
                    toRemove.add(player);
                    continue;
                }
                // Vérifier la distance avec le bloc de bûche le plus proche (rayon de 5 blocs max -> distSq <= 25.0)
                Location pLoc = player.getLocation();
                boolean close = false;
                for (BlockPos pos : tree.getLogs()) {
                    double distSq = pLoc.distanceSquared(new Location(tree.getWorld(), pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5));
                    if (distSq <= 25.0) { // Rayon de 5 blocs max
                        close = true;
                        break;
                    }
                }
                if (!close) {
                    toRemove.add(player);
                }
            }
            for (Player player : toRemove) {
                tree.getBossBar().removePlayer(player);
            }
        }
    }

    /**
     * Effectue un cycle de régénération sur tous les arbres inactifs.
     */
    private void handleRegen() {
        long now = System.currentTimeMillis();
        long delayMs = configManager.getInactivityDelaySeconds() * 1000L;
        long stepMs = configManager.getRegenStepSeconds() * 1000L;
        double percentPerStep = configManager.getRegenPercentPerStep();

        List<ActiveTree> toRemove = new ArrayList<>();

        for (ActiveTree tree : activeTrees.values()) {
            // Vérifier si le délai d'inactivité initial est dépassé
            if (now - tree.getLastCutTime() >= delayMs) {
                // Vérifier si l'intervalle entre deux soins est écoulé
                if (now - tree.getLastHealTime() >= stepMs) {
                    tree.setLastHealTime(now);

                    if (tree.getHealth() < tree.getMaxHealth()) {
                        double healAmount = tree.getMaxHealth() * (percentPerStep / 100.0);
                        tree.heal(healAmount, configManager);

                        // Avertir les joueurs de la régénération
                        if (!tree.isRegenMessageSent()) {
                            for (Player player : tree.getBossBar().getPlayers()) {
                                player.sendMessage(configManager.getMessage("tree_regen", true));
                            }
                            tree.setRegenMessageSent(true);
                        }
                    }

                    // Si les PV sont revenus à 100%, marquer l'arbre pour suppression
                    if (tree.getHealth() >= tree.getMaxHealth()) {
                        toRemove.add(tree);
                    }
                }
            }
        }

        // Nettoyage des arbres entièrement régénérés
        for (ActiveTree tree : toRemove) {
            for (Player player : tree.getBossBar().getPlayers()) {
                player.sendMessage(configManager.getMessage("tree_fully_healed", true));
            }
            unregisterTree(tree);
        }
    }

    /**
     * Nettoie tous les arbres (appelé à l'arrêt du plugin).
     */
    public void cleanupAll() {
        stopRegenTask();
        for (ActiveTree tree : activeTrees.values()) {
            tree.cleanup();
        }
        activeTrees.clear();
        blockToTreeLookup.clear();
    }



    public boolean isPlayerDisabled(UUID uuid) {
        return disabledPlayers.contains(uuid);
    }

    public boolean togglePlayer(UUID uuid) {
        if (disabledPlayers.contains(uuid)) {
            disabledPlayers.remove(uuid);
            return true; // activé
        } else {
            disabledPlayers.add(uuid);
            return false; // désactivé
        }
    }

    public boolean isPlayerGodMode(UUID uuid) {
        return godModePlayers.contains(uuid);
    }

    public boolean togglePlayerGodMode(UUID uuid) {
        if (godModePlayers.contains(uuid)) {
            godModePlayers.remove(uuid);
            return false; // désactivé
        } else {
            godModePlayers.add(uuid);
            return true; // activé
        }
    }

    public void saveUndoSnapshot(UUID playerId, UndoSnapshot snapshot) {
        if (playerId != null && snapshot != null) {
            lastFelledSnapshots.put(playerId, snapshot);
        }
    }

    public UndoSnapshot popUndoSnapshot(UUID playerId) {
        if (playerId == null) return null;
        return lastFelledSnapshots.remove(playerId);
    }

    /**
     * Supprime le snapshot d'annulation d'un joueur (appelé à la déconnexion).
     */
    public void discardUndoSnapshot(java.util.UUID uuid) {
        lastFelledSnapshots.remove(uuid);
    }
}

