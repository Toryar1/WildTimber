package com.wildtimber.manager;

import com.wildtimber.config.BiomeConfig;
import com.wildtimber.config.ConfigManager;
import com.wildtimber.detection.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Représente un arbre engagé et en cours d'abattage.
 */
public class ActiveTree {

    private final UUID id;
    private final World world;
    private final Set<BlockPos> logs;
    private final Set<BlockPos> leaves;
    private final double maxHealth;
    private final BiomeConfig biomeConfig;
    private final String biomeName;
    private final String treeName;

    private double health;
    private long lastCutTime;
    private long lastHealTime;
    private BossBar bossBar;
    private boolean regenMessageSent = false;

    public ActiveTree(World world, Set<BlockPos> logs, Set<BlockPos> leaves, double maxHealth, BiomeConfig biomeConfig, String biomeName, ConfigManager configManager) {
        this.id = UUID.randomUUID();
        this.world = world;
        this.logs = logs;
        this.leaves = leaves;
        this.maxHealth = maxHealth;
        this.health = maxHealth;
        this.biomeConfig = biomeConfig;
        this.biomeName = biomeName;
        this.lastCutTime = System.currentTimeMillis();
        this.lastHealTime = System.currentTimeMillis();
        this.treeName = determineTreeName(logs, world, configManager);

        // Création de la BossBar
        String title = configManager.getMessage("bossbar_title", false)
                .replace("{type}", treeName)
                .replace("{percent}", "100")
                .replace("{current}", String.format(Locale.US, "%.0f", health))
                .replace("{max}", String.format(Locale.US, "%.0f", maxHealth));
        this.bossBar = Bukkit.createBossBar(title, BarColor.GREEN, BarStyle.SOLID);
    }

    private String determineTreeName(Set<BlockPos> logs, World world, ConfigManager configManager) {
        if (logs.isEmpty()) return configManager.getMessage("tree_type.unknown", false);
        BlockPos first = logs.iterator().next();
        Material mat = world.getBlockAt(first.x(), first.y(), first.z()).getType();
        String name = mat.name().replace("_LOG", "").replace("_WOOD", "").replace("_", " ");
        return switch (name) {
            case "OAK" -> configManager.getMessage("tree_type.oak", false);
            case "SPRUCE" -> configManager.getMessage("tree_type.spruce", false);
            case "BIRCH" -> configManager.getMessage("tree_type.birch", false);
            case "JUNGLE" -> configManager.getMessage("tree_type.jungle", false);
            case "ACACIA" -> configManager.getMessage("tree_type.acacia", false);
            case "DARK OAK" -> configManager.getMessage("tree_type.dark_oak", false);
            case "MANGROVE" -> configManager.getMessage("tree_type.mangrove", false);
            case "CHERRY" -> configManager.getMessage("tree_type.cherry", false);
            default -> name.substring(0, 1).toUpperCase() + name.substring(1).toLowerCase();
        };
    }

    /**
     * Applique des dégâts à l'arbre.
     */
    public void damage(double amount, ConfigManager configManager) {
        this.health = Math.max(0.0, this.health - amount);
        this.lastCutTime = System.currentTimeMillis();
        updateBossBar(configManager);
    }

    /**
     * Régénère une partie des PV de l'arbre.
     */
    public void heal(double amount, ConfigManager configManager) {
        this.health = Math.min(this.maxHealth, this.health + amount);
        updateBossBar(configManager);
    }

    public void updateBossBar(ConfigManager configManager) {
        double percent = (health / maxHealth) * 100.0;
        String title = configManager.getMessage("bossbar_title", false)
                .replace("{type}", treeName)
                .replace("{percent}", String.format(Locale.US, "%.0f", percent))
                .replace("{current}", String.format(Locale.US, "%.0f", health))
                .replace("{max}", String.format(Locale.US, "%.0f", maxHealth));
        
        bossBar.setTitle(title);
        bossBar.setProgress(Math.clamp(health / maxHealth, 0.0, 1.0));

        // Ajuster la couleur selon les PV
        if (percent > 50) {
            bossBar.setColor(BarColor.GREEN);
        } else if (percent > 20) {
            bossBar.setColor(BarColor.YELLOW);
        } else {
            bossBar.setColor(BarColor.RED);
        }
    }

    /**
     * Ajoute un joueur à la BossBar de l'arbre.
     */
    public void showTo(Player player) {
        if (!bossBar.getPlayers().contains(player)) {
            bossBar.addPlayer(player);
        }
    }

    /**
     * Supprime tous les joueurs de la BossBar et la détruit.
     */
    public void cleanup() {
        bossBar.setVisible(false); // M10: hide before removing all players
        bossBar.removeAll();
    }

    // Getters

    public UUID getId() { return id; }
    public World getWorld() { return world; }
    public Set<BlockPos> getLogs() { return logs; }
    public Set<BlockPos> getLeaves() { return leaves; }
    public double getMaxHealth() { return maxHealth; }
    public double getHealth() { return health; }
    public long getLastCutTime() { return lastCutTime; }
    public long getLastHealTime() { return lastHealTime; }
    public void setLastHealTime(long lastHealTime) { this.lastHealTime = lastHealTime; }
    public BiomeConfig getBiomeConfig() { return biomeConfig; }
    public String getBiomeName() { return biomeName; }
    public String getTreeName() { return treeName; }
    public BossBar getBossBar() { return bossBar; }
    public boolean isRegenMessageSent() { return regenMessageSent; }
    public void setRegenMessageSent(boolean sent) { this.regenMessageSent = sent; }

    public boolean hasBlock(BlockPos pos) {
        return logs.contains(pos) || leaves.contains(pos);
    }
}
