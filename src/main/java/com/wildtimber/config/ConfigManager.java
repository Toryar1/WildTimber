package com.wildtimber.config;

import com.wildtimber.WildTimber;
import com.wildtimber.gui.ConfigGUI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.util.*;
import java.util.logging.Level;

/**
 * Gère le chargement et le rechargement de tous les fichiers de configuration du plugin.
 * Supporte la migration automatique des configs (ajout de nouvelles clés sans écraser les existantes).
 */
public class ConfigManager {

    private final WildTimber plugin;

    // Fichiers de configuration
    private FileConfiguration config;
    private FileConfiguration biomesConfig;
    private FileConfiguration blocksConfig;
    private FileConfiguration langConfig;

    // Paramètres globaux
    private boolean pluginEnabled;
    private volatile boolean debug;
    private boolean verboseDebug;
    private String language = "fr";
    private Set<String> disabledWorlds = new HashSet<>();
    private double baseCoefficient;
    private int inactivityDelaySeconds;
    private boolean regenEnabled;
    private double regenPercentPerStep;
    private int regenStepSeconds;
    private boolean visualTreeHealthEnabled;
    private String triggerMode;
    private boolean sendHintMessage;
    private int hintCooldownSeconds;
    private long clickCooldownMs;

    // Durabilité et Outils
    private Map<Material, Double> toolMultipliers = new HashMap<>();
    private double defaultToolMultiplier;
    private double efficiencyLevelMultiplier;
    private boolean extraLossEnabled;
    private int extraLossPoints;
    private int extraLossChancePercent;

    // Limites
    private String detectionPreset;
    private int maxLogs;
    private int maxBlocks;
    private int maxRadius;
    private int maxRadiusXZ;
    private int maxHeightY;
    private int maxConnectedNonTreeBlocks;
    private int leafDecayRangeXZ;
    private int leafDecayRangeY;
    private int leafDecayScalingLogs;
    private int leafDecayScalingXzBonus;
    private int leafDecayScalingYBonus;
    private boolean allowDiagonalLogs;
    private boolean allowDiagonalLeaves;
    private double logYieldMultiplier;
    private double efficiencyDamage;
    private double sharpnessDamage;
    private int maxBranchDiscoveryIterations;
    private int maxOrphanClusterSize;
    
    // Paramètres globaux (limites)
    private boolean isolatedLogsRule;
    private boolean orphanLeavesCleanupEnabled;
    private volatile boolean blacklistEnabled = true;
    private volatile boolean treeContactRequired = true;
    private int orphanLeavesRadius;
    private boolean rootReplacementEnabled;
    private Material rootReplacementMaterial;
    private int isolatedLogsRadius;
    private int isolatedLogMax;
    private boolean allowNonRootedStart;
    private int maxRootSearchDepth;
    private int sixWayMaxLogs;
    private boolean canopyCleanupEnabled;
    private int canopyCleanupPadding;
    private int leavesPersistenceBatchSize;

    // Cleanup floating blocks
    private Set<Material> cleanupFloatingBlocks = new HashSet<>();

    // Anti-cheat
    private boolean antiCheatEnabled;
    private long antiCheatMinClickIntervalMs;
    private double antiCheatMinLookChangeDegrees;
    private int antiCheatMaxRegularClicks;

    // Partition multi-source (trees)
    private int minLogsRooted;
    private int minLogsIsolated;
    private int maxBridgeSectionSize;
    private double maxBridgeCost;
    private double heightWeightAlpha;
    private int bridgeSearchRadius;

    // Performance (staged cut)
    private boolean stagedCutEnabled;
    private int stagedCutMinBlocks;
    private int stagedCutSliceHeight;
    private int stagedCutIntervalTicks;

    // Roots (backfill)
    private boolean backfillEnabled;
    private Material backfillBlock;
    private int maxBackfillDepth;
    private int rootFillPadding;
    private int rootFillDepthPadding;
    private String fillMode;
    private int fillRadiusExtra;
    private int fillDepth;
    private double elephantFactor;
    private int maxSlope;
    private int idwNeighbors;

    // Fallback
    private boolean fallbackEnabled;
    private int fallbackMaxBlocks;
    private int fallbackTrunkCoreRadius;
    private int fallbackTrunkMinHeight;
    private int fallbackMaxRadius;
    private double fallbackMinDensity;
    private int fallbackRingStep;

    // Leaf drops
    private boolean leafDropsEnabled;
    private double leafDropsSaplingChance;
    private double leafDropsStickChance;
    private double leafDropsAppleChance;

    // Poids et Blacklist
    private Map<Material, Double> logWeights = new HashMap<>();
    private Map<Material, Double> leafWeights = new HashMap<>();
    private Set<Material> blacklist = new HashSet<>();

    // Biomes
    private Map<String, BiomeConfig> biomes = new HashMap<>();
    private BiomeConfig defaultBiomeConfig;

    // Messages
    private String prefix;
    private Map<String, String> messages = new HashMap<>();

    public ConfigManager(WildTimber plugin) {
        this.plugin = plugin;
    }

    /**
     * Charge toutes les configurations depuis le disque.
     */
    public void load() {
        // Self-test of cleanYamlKey
        try {
            String testVal = cleanYamlKey(":TUNDRA_SWAMP_DARK.:enabled");
            if (!testVal.equals("TUNDRA_SWAMP_DARK.enabled")) {
                throw new AssertionError("cleanYamlKey self-test failed: got " + testVal);
            }
        } catch (Throwable t) {
            plugin.getLogger().severe("Assertion failed for cleanYamlKey: " + t.getMessage());
        }

        saveDefaultConfigs();

        config = loadYaml("config.yml");
        biomesConfig = loadYaml("biomes.yml");
        blocksConfig = loadYaml("blocks.yml");
        langConfig = loadYaml("lang.yml");

        parseGlobals();
        parseBlocks();
        parseBiomes();
        parseLang();

        plugin.getLogger().info("=== CONFIGURATION CHARGEE ===");
        plugin.getLogger().info("Statut : " + (pluginEnabled ? "ACTIF" : "DESACTIVE") + " | Mode Debug : " + debug);
        plugin.getLogger().info("Mondes Exclus : " + disabledWorlds);
        plugin.getLogger().info("Bûches enregistrées : " + logWeights.size() + " types de blocs");
        plugin.getLogger().info("Feuilles enregistrées : " + leafWeights.size() + " types de blocs");
        plugin.getLogger().info("Biomes configurés : " + biomes.size() + " biomes (défaut inclus)");
        plugin.getLogger().info("Blacklist : " + blacklist.size() + " blocs interdits");
        plugin.getLogger().info("=============================");
    }

    private void saveDefaultConfigs() {
        plugin.getDataFolder().mkdirs();
        saveResourceIfNotExists("config.yml");
        saveResourceIfNotExists("biomes.yml");
        saveResourceIfNotExists("blocks.yml");

        // Sauvegarder les fichiers de langues dans le sous-dossier lang/
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) langDir.mkdirs();

        for (String langCode : AVAILABLE_LANGUAGES.keySet()) {
            File resFile = new File(langDir, "lang_" + langCode + ".yml");
            if (!resFile.exists()) {
                InputStream is = plugin.getResource("lang/lang_" + langCode + ".yml");
                if (is == null) is = plugin.getResource("lang/" + langCode + ".yml");
                if (is != null) {
                    try (FileOutputStream fos = new FileOutputStream(resFile)) {
                        is.transferTo(fos);
                    } catch (IOException ignored) {}
                }
            }
        }

        // Si lang.yml n'existe pas, copier le fichier correspondant à la langue choisie
        File langFile = new File(plugin.getDataFolder(), "lang.yml");
        if (!langFile.exists()) {
            String selectedLang = "fr";
            File cfgFile = new File(plugin.getDataFolder(), "config.yml");
            if (cfgFile.exists()) {
                FileConfiguration tmpConfig = YamlConfiguration.loadConfiguration(cfgFile);
                selectedLang = tmpConfig.getString("plugin.language", "fr").trim();
            }
            InputStream is = plugin.getResource("lang/lang_" + selectedLang + ".yml");
            if (is == null) is = plugin.getResource("lang/" + selectedLang + ".yml");
            if (is == null) is = plugin.getResource("lang/lang_fr.yml");
            if (is != null) {
                try (FileOutputStream fos = new FileOutputStream(langFile)) {
                    is.transferTo(fos);
                } catch (IOException ignored) {}
            }
        }

        // Migration : injecter les nouvelles clés sans écraser les existantes
        migrateConfig("config.yml");
        migrateConfig("biomes.yml");
        migrateConfig("blocks.yml");
        migrateLangConfig();
    }

    private void saveResourceIfNotExists(String filename) {
        File file = new File(plugin.getDataFolder(), filename);
        if (!file.exists()) {
            plugin.saveResource(filename, false);
        }
    }

    /**
     * Migration de config : charge le fichier par défaut du JAR et le fichier utilisateur,
     * puis injecte les clés manquantes sans écraser les existantes.
     */
    private void migrateConfig(String filename) {
        File userFile = new File(plugin.getDataFolder(), filename);
        if (!userFile.exists()) return;

        // Charger le YAML embarqué dans le JAR
        InputStream defaultStream = plugin.getResource(filename);
        if (defaultStream == null) return;

        FileConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream));
        FileConfiguration userConfig = YamlConfiguration.loadConfiguration(userFile);

        boolean modified = false;
        for (String key : defaultConfig.getKeys(true)) {
            String cleanedKey = cleanYamlKey(key);
            if (!userConfig.contains(cleanedKey)) {
                userConfig.set(cleanedKey, defaultConfig.get(key));
                modified = true;
                if (debug) {
                    plugin.getLogger().info("[Migration] Clé ajoutée dans " + filename + " : " + cleanedKey);
                }
            }
        }

        if (modified) {
            try {
                userConfig.save(userFile);
                plugin.getLogger().info("[Migration] " + filename + " mis à jour avec les nouvelles clés.");
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Impossible de sauvegarder la migration de " + filename, e);
            }
        }
    }

    private void migrateLangConfig() {
        File userFile = new File(plugin.getDataFolder(), "lang.yml");

        // Resolve language code case-insensitively (supports pt_BR, zh_CN, etc.)
        String selectedLang = "fr";
        if (config != null) {
            String raw = config.getString("plugin.language", "fr").trim();
            for (String code : AVAILABLE_LANGUAGES.keySet()) {
                if (code.equalsIgnoreCase(raw)) { selectedLang = code; break; }
            }
        }

        InputStream defaultStream = plugin.getResource("lang/lang_" + selectedLang + ".yml");
        if (defaultStream == null) defaultStream = plugin.getResource("lang/lang_fr.yml");
        if (defaultStream == null) return;

        FileConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(defaultStream, java.nio.charset.StandardCharsets.UTF_8));
        FileConfiguration userConfig = YamlConfiguration.loadConfiguration(userFile);

        // Count keys in JAR vs disk — if JAR has more, the lang file is outdated
        int defaultKeyCount = defaultConfig.getKeys(true).size();
        int userKeyCount = userConfig.getKeys(true).size();

        if (defaultKeyCount > userKeyCount) {
            // Overwrite entire lang.yml with fresh copy from JAR (preserves all new keys)
            try {
                InputStream freshStream = plugin.getResource("lang/lang_" + selectedLang + ".yml");
                if (freshStream == null) freshStream = plugin.getResource("lang/lang_fr.yml");
                if (freshStream != null) {
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(userFile)) {
                        freshStream.transferTo(fos);
                    }
                    plugin.getLogger().info("[Migration] lang.yml remplacé depuis JAR (" + userKeyCount + " → " + defaultKeyCount + " clés) pour la langue " + selectedLang + ".");
                    return;
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Impossible de remplacer lang.yml : " + e.getMessage());
            }
        }

        // Fallback: inject only missing keys
        boolean modified = false;
        for (String key : defaultConfig.getKeys(true)) {
            String cleanedKey = cleanYamlKey(key);
            if (!userConfig.contains(cleanedKey)) {
                userConfig.set(cleanedKey, defaultConfig.get(key));
                modified = true;
            }
        }

        if (modified) {
            try {
                userConfig.save(userFile);
                plugin.getLogger().info("[Migration] lang.yml mis à jour avec les nouvelles clés de " + selectedLang + ".");
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Impossible de sauvegarder la migration de lang.yml", e);
            }
        }
    }

    private FileConfiguration loadYaml(String filename) {
        File file = new File(plugin.getDataFolder(), filename);
        return YamlConfiguration.loadConfiguration(file);
    }

    private void parseGlobals() {
        pluginEnabled = config.getBoolean("plugin.enabled", true);
        // Preserve the exact case of the language code (e.g. pt_BR, zh_CN)
        String rawLang = config.getString("plugin.language", "fr").trim();
        language = "fr"; // default
        for (String availCode : AVAILABLE_LANGUAGES.keySet()) {
            if (availCode.equalsIgnoreCase(rawLang)) {
                language = availCode;
                break;
            }
        }
        debug = config.getBoolean("plugin.debug", false);
        verboseDebug = config.getBoolean("plugin.verbose-debug", false);

        leafDropsEnabled = config.getBoolean("leaf-drops.enabled", true);
        leafDropsSaplingChance = config.getDouble("leaf-drops.sapling-chance", 0.05);
        leafDropsStickChance = config.getDouble("leaf-drops.stick-chance", 0.02);
        leafDropsAppleChance = config.getDouble("leaf-drops.apple-chance", 0.005);

        disabledWorlds = new HashSet<>(config.getStringList("worlds.disabled"));

        baseCoefficient = config.getDouble("health.base-coefficient", 1.0);
        inactivityDelaySeconds = config.getInt("health.inactivity-delay-seconds", 180);
        regenEnabled = config.getBoolean("health.regen.enabled", true);
        regenPercentPerStep = config.getDouble("health.regen.percent-per-step", 10.0);
        regenStepSeconds = config.getInt("health.regen.step-seconds", 60);
        visualTreeHealthEnabled = config.getBoolean("health.visual-tree-health-enabled", true);

        triggerMode = config.getString("trigger.mode", "RIGHT_CLICK_AXE");
        sendHintMessage = config.getBoolean("trigger.send-hint-message", true);
        hintCooldownSeconds = config.getInt("trigger.hint-cooldown-seconds", 60);
        clickCooldownMs = config.getLong("trigger.click-cooldown-ms", 350);

        // Multiplicateurs d'outils
        toolMultipliers.clear();
        ConfigurationSection toolSec = config.getConfigurationSection("durability.multipliers");
        if (toolSec != null) {
            for (String key : toolSec.getKeys(false)) {
                String materialName = key.equalsIgnoreCase("HAND") ? "AIR" : key;
                Material mat = Material.matchMaterial(materialName);
                if (mat != null) {
                    toolMultipliers.put(mat, toolSec.getDouble(key));
                }
            }
        }
        defaultToolMultiplier = toolMultipliers.getOrDefault(Material.AIR, 0.5);

        efficiencyLevelMultiplier = config.getDouble("durability.efficiency-level-multiplier", 0.2);
        efficiencyDamage = config.getDouble("durability.enchantments.efficiency", 0.5);
        sharpnessDamage = config.getDouble("durability.enchantments.sharpness", 0.3);

        extraLossEnabled = config.getBoolean("durability.extra-loss.enabled", true);
        extraLossPoints = config.getInt("durability.extra-loss.points-per-interval", 1);
        extraLossChancePercent = config.getInt("durability.extra-loss.chance-percent", 100);

        detectionPreset = config.getString("limits.preset", "CUSTOM");
        maxLogs = config.getInt("limits.max-logs", 1024);
        maxBlocks = config.getInt("limits.max-blocks", 4096);
        maxRadius = config.getInt("limits.max-radius", 32);

        int fallbackRadius = maxRadius;
        maxRadiusXZ = config.getInt("limits.max-radius-xz", fallbackRadius);
        maxHeightY = config.getInt("limits.max-height-y", fallbackRadius);

        if ("VANILLA".equalsIgnoreCase(detectionPreset)) {
            maxLogs = 200;
            maxBlocks = 800;
            maxRadiusXZ = 8;
            maxHeightY = 24;
        }

        maxConnectedNonTreeBlocks = config.getInt("limits.max-connected-non-tree-blocks", 10);

        // Leaf-decay XZ/Y avec fallback vers l'ancien leaf-decay-range
        int oldDecayRange = config.getInt("limits.leaf-decay-range", 8);
        leafDecayRangeXZ = config.getInt("limits.leaf-decay-range-xz", oldDecayRange);
        leafDecayRangeY = config.getInt("limits.leaf-decay-range-y", oldDecayRange);
        leafDecayScalingLogs = config.getInt("limits.leaf-decay-scaling-logs", 100);
        leafDecayScalingXzBonus = config.getInt("limits.leaf-decay-scaling-xz-bonus", 1);
        leafDecayScalingYBonus = config.getInt("limits.leaf-decay-scaling-y-bonus", 2);
        allowDiagonalLogs = config.getBoolean("limits.allow-diagonal-logs", true);
        allowDiagonalLeaves = config.getBoolean("limits.allow-diagonal-leaves", true);

        logYieldMultiplier = config.getDouble("limits.log-yield-multiplier", 0.75);
        maxBranchDiscoveryIterations = config.getInt("limits.max-branch-discovery-iterations", 5);
        maxOrphanClusterSize = config.getInt("limits.max-orphan-cluster-size", 10);

        isolatedLogsRule = config.getBoolean("limits.isolated-logs-rule", true);
        orphanLeavesCleanupEnabled = config.getBoolean("limits.orphan-leaves-cleanup.enabled", true);
        blacklistEnabled = config.getBoolean("limits.blacklist-enabled", true);
        treeContactRequired = config.getBoolean("limits.tree-contact-required", true);
        orphanLeavesRadius = config.getInt("limits.orphan-leaves-cleanup.radius", 5);
        rootReplacementEnabled = config.getBoolean("limits.root-replacement.enabled", true);
        
        String matStr = config.getString("limits.root-replacement.material", "DIRT");
        rootReplacementMaterial = Material.matchMaterial(matStr);
        if (rootReplacementMaterial == null) {
            rootReplacementMaterial = Material.DIRT;
        }

        isolatedLogsRadius = config.getInt("limits.isolated-logs-radius", 6);
        isolatedLogMax = config.getInt("limits.isolated-log-max", 4);
        allowNonRootedStart = config.getBoolean("limits.allow-non-rooted-start", false);
        maxRootSearchDepth = config.getInt("limits.max-root-search-depth", 16);
        sixWayMaxLogs = config.getInt("limits.6way-max-logs", 2048);
        canopyCleanupEnabled = config.getBoolean("limits.canopy-cleanup-enabled", true);
        canopyCleanupPadding = config.getInt("limits.canopy-cleanup-padding", 6);
        leavesPersistenceBatchSize = config.getInt("limits.leaves-persistence-batch-size", 64);

        // Cleanup floating blocks
        cleanupFloatingBlocks.clear();
        for (String name : config.getStringList("limits.cleanup-floating-blocks")) {
            Material mat = Material.matchMaterial(name);
            if (mat != null) {
                cleanupFloatingBlocks.add(mat);
            }
        }

        // Anti-cheat
        antiCheatEnabled = config.getBoolean("anti-cheat.enabled", true);
        antiCheatMinClickIntervalMs = config.getLong("anti-cheat.min-click-interval-ms", 100);
        antiCheatMinLookChangeDegrees = config.getDouble("anti-cheat.min-look-change-degrees", 0.1);
        antiCheatMaxRegularClicks = config.getInt("anti-cheat.max-regular-clicks", 8);

        // Partition multi-source (trees)
        minLogsRooted = config.getInt("trees.min-logs-rooted", 4);
        minLogsIsolated = config.getInt("trees.min-logs-isolated", 3);
        maxBridgeSectionSize = config.getInt("trees.max-bridge-section-size", 4);
        maxBridgeCost = config.getDouble("trees.max-bridge-cost", 20.0);
        heightWeightAlpha = config.getDouble("trees.height-weight-alpha", 0.1);
        bridgeSearchRadius = config.getInt("trees.bridge-search-radius", 16);

        // Performance (staged cut)
        stagedCutEnabled = config.getBoolean("performance.staged-cut-enabled", true);
        stagedCutMinBlocks = config.getInt("performance.staged-cut-min-blocks", 256);
        stagedCutSliceHeight = config.getInt("performance.staged-cut-slice-height", 4);
        stagedCutIntervalTicks = config.getInt("performance.staged-cut-interval-ticks", 20);

        // Roots (backfill)
        backfillEnabled = config.getBoolean("roots.backfill-enabled",
                config.getBoolean("limits.root-replacement.enabled", true));
        String backfillMatStr = config.getString("roots.backfill-block",
                config.getString("limits.root-replacement.material", "DIRT"));
        backfillBlock = Material.matchMaterial(backfillMatStr);
        if (backfillBlock == null) backfillBlock = Material.DIRT;
        maxBackfillDepth = config.getInt("roots.max-backfill-depth", 6);
        rootFillPadding = config.getInt("roots.root-fill-padding", 2);
        rootFillDepthPadding = config.getInt("roots.root-fill-depth-padding", 2);
        fillMode = config.getString("roots.fill-mode", "HOLE_DETECTOR");
        fillRadiusExtra = config.getInt("roots.fill-radius-extra", 0);
        fillDepth = config.getInt("roots.fill-depth", config.getInt("roots.max-backfill-depth", 16));
        elephantFactor = config.getDouble("roots.elephant-factor", 1.5);
        maxSlope = config.getInt("roots.max-slope", 2);
        idwNeighbors = config.getInt("roots.idw-neighbors", 8);

        // Fallback
        fallbackEnabled = config.getBoolean("fallback.enabled", true);
        fallbackMaxBlocks = config.getInt("fallback.max-blocks", 2048);
        fallbackTrunkCoreRadius = config.getInt("fallback.trunk-core-radius", 1);
        fallbackTrunkMinHeight = config.getInt("fallback.trunk-min-height", 3);
        fallbackMaxRadius = config.getInt("fallback.max-radius", 8);
        fallbackMinDensity = config.getDouble("fallback.min-density", 0.10);
        fallbackRingStep = config.getInt("fallback.ring-step", 1);
    }

    private void parseBlocks() {
        logWeights.clear();
        ConfigurationSection logsSec = blocksConfig.getConfigurationSection("logs");
        if (logsSec != null) {
            for (String key : logsSec.getKeys(false)) {
                Material mat = Material.matchMaterial(key);
                if (mat != null) {
                    logWeights.put(mat, logsSec.getDouble(key));
                }
            }
        }

        leafWeights.clear();
        ConfigurationSection leafSec = blocksConfig.getConfigurationSection("leaf_like");
        if (leafSec != null) {
            for (String key : leafSec.getKeys(false)) {
                Material mat = Material.matchMaterial(key);
                if (mat != null) {
                    leafWeights.put(mat, leafSec.getDouble(key));
                }
            }
        }

        blacklist.clear();
        List<String> blacklistNames = blocksConfig.getStringList("blacklist");
        for (String name : blacklistNames) {
            // Permet de matcher par préfixe ou nom complet
            Material mat = Material.matchMaterial(name);
            if (mat != null) {
                blacklist.add(mat);
            } else {
                // Si ce n'est pas un nom exact de bloc, on vérifie au chargement s'il correspond à plusieurs blocs
                for (Material m : Material.values()) {
                    if (m.name().contains(name.toUpperCase())) {
                        blacklist.add(m);
                    }
                }
            }
        }
    }

    private void parseBiomes() {
        biomes.clear();
        for (String biomeKey : biomesConfig.getKeys(false)) {
            ConfigurationSection sec = biomesConfig.getConfigurationSection(biomeKey);
            if (sec == null) continue;

            boolean enabled = sec.getBoolean("enabled", true);
            int minLogs = sec.getInt("min-logs", 6);
            int minLeafLike = sec.contains("min-leaf-like") ? sec.getInt("min-leaf-like") : sec.getInt("min-leaves", 5);

            Set<Material> logBlocks = parseMaterialSet(sec.getStringList("log-blocks"));
            Set<Material> leafBlocks = parseMaterialSet(sec.getStringList("leaf-blocks"));
            Set<Material> attachments = parseMaterialSet(sec.getStringList("attachments"));

            boolean extraDropsEnabled = sec.getBoolean("extra-drops.enabled", false);
            List<ExtraDropEntry> extraDrops = new ArrayList<>();
            List<Map<?, ?>> entriesList = sec.getMapList("extra-drops.entries");
            for (Map<?, ?> entryMap : entriesList) {
                try {
                    String itemStr = (String) entryMap.get("item");
                    Material mat = Material.matchMaterial(itemStr);
                    if (mat == null) continue;

                    double chance = ((Number) entryMap.get("chance")).doubleValue();
                    int min = ((Number) entryMap.get("min")).intValue();
                    int max = ((Number) entryMap.get("max")).intValue();
                    extraDrops.add(new ExtraDropEntry(mat, chance, min, max));
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Erreur lors de la lecture d'une entrée drop additionnel dans le biome: " + biomeKey, e);
                }
            }

            int protectionBeltRadius = sec.getInt("protection-belt-radius", 4);
            Integer maxLogs = sec.contains("max-logs") ? sec.getInt("max-logs") : null;
            Integer maxBlocks = sec.contains("max-blocks") ? sec.getInt("max-blocks") : null;
            Integer maxRadiusXZ = sec.contains("max-radius-xz") ? sec.getInt("max-radius-xz") : null;
            Integer maxHeightY = sec.contains("max-height-y") ? sec.getInt("max-height-y") : null;

            // Leaf-decay XZ/Y avec fallback vers l'ancien leaf-decay-range et decay-xz/y
            Integer leafDecayRangeXZ = null;
            Integer leafDecayRangeY = null;
            if (sec.contains("leaf-decay-range-xz")) {
                leafDecayRangeXZ = sec.getInt("leaf-decay-range-xz");
            } else if (sec.contains("decay-xz")) {
                leafDecayRangeXZ = sec.getInt("decay-xz");
            } else if (sec.contains("leaf-decay-range")) {
                leafDecayRangeXZ = sec.getInt("leaf-decay-range");
            }
            if (sec.contains("leaf-decay-range-y")) {
                leafDecayRangeY = sec.getInt("leaf-decay-range-y");
            } else if (sec.contains("decay-y")) {
                leafDecayRangeY = sec.getInt("decay-y");
            } else if (sec.contains("leaf-decay-range")) {
                leafDecayRangeY = sec.getInt("leaf-decay-range");
            }

            Boolean isolatedLogsRule = sec.contains("isolated-logs-rule") ? sec.getBoolean("isolated-logs-rule") : null;
            Boolean orphanLeavesCleanup = sec.contains("orphan-leaves-cleanup") ? sec.getBoolean("orphan-leaves-cleanup") : null;
            Integer orphanLeavesRadius = sec.contains("orphan-leaves-radius") ? sec.getInt("orphan-leaves-radius") : null;
            Boolean rootReplacementEnabled = sec.contains("root-replacement-enabled") ? sec.getBoolean("root-replacement-enabled") : null;
            
            Material rootReplacementMaterial = null;
            if (sec.contains("root-replacement-material")) {
                String matName = sec.getString("root-replacement-material");
                if (matName != null) {
                    rootReplacementMaterial = Material.matchMaterial(matName);
                }
            }

            Boolean fallbackEnabled = sec.contains("fallback.enabled") ? sec.getBoolean("fallback.enabled") : (sec.contains("fallback-allowed") ? sec.getBoolean("fallback-allowed") : null);
            Integer fallbackMaxBlocks = sec.contains("fallback.max-blocks") ? sec.getInt("fallback.max-blocks") : null;
            Integer fallbackTrunkCoreRadius = sec.contains("fallback.trunk-core-radius") ? sec.getInt("fallback.trunk-core-radius") : null;
            Integer fallbackTrunkMinHeight = sec.contains("fallback.trunk-min-height") ? sec.getInt("fallback.trunk-min-height") : null;
            Integer fallbackMaxRadius = sec.contains("fallback.max-radius") ? sec.getInt("fallback.max-radius") : null;
            Double fallbackMinDensity = sec.contains("fallback.min-density") ? sec.getDouble("fallback.min-density") : null;
            Integer fallbackRingStep = sec.contains("fallback.ring-step") ? sec.getInt("fallback.ring-step") : null;

            Integer isolatedLogsRadius = sec.contains("isolated-logs-radius") ? sec.getInt("isolated-logs-radius") : null;
            Integer isolatedLogMax = sec.contains("isolated-log-max") ? sec.getInt("isolated-log-max") : null;
            Boolean allowNonRootedStart = sec.contains("allow-non-rooted-start") ? sec.getBoolean("allow-non-rooted-start") : null;
            Integer maxRootSearchDepth = sec.contains("max-root-search-depth") ? sec.getInt("max-root-search-depth") : (sec.contains("fill-depth") ? sec.getInt("fill-depth") : null);
            Integer sixWayMaxLogs = sec.contains("6way-max-logs") ? sec.getInt("6way-max-logs") : null;
            Boolean canopyCleanupEnabled = sec.contains("canopy-cleanup-enabled") ? sec.getBoolean("canopy-cleanup-enabled") : null;
            Integer canopyCleanupPadding = sec.contains("canopy-cleanup-padding") ? sec.getInt("canopy-cleanup-padding") : null;
            String fillMode = sec.contains("fill-mode") ? sec.getString("fill-mode") : null;
            Integer fillRadiusExtra = sec.contains("fill-radius-extra") ? sec.getInt("fill-radius-extra") : null;
            Double elephantFactor = sec.contains("elephant-factor") ? sec.getDouble("elephant-factor") : null;

            Integer leafDecayScalingLogs = sec.contains("leaf-decay-scaling-logs") ? sec.getInt("leaf-decay-scaling-logs") : null;
            Integer leafDecayScalingXzBonus = sec.contains("leaf-decay-scaling-xz-bonus") ? sec.getInt("leaf-decay-scaling-xz-bonus") : null;
            Integer leafDecayScalingYBonus = sec.contains("leaf-decay-scaling-y-bonus") ? sec.getInt("leaf-decay-scaling-y-bonus") : null;
            Boolean allowDiagonalLogs = sec.contains("allow-diagonal-logs") ? sec.getBoolean("allow-diagonal-logs") : null;
            Boolean allowDiagonalLeaves = sec.contains("allow-diagonal-leaves") ? sec.getBoolean("allow-diagonal-leaves") : null;

            BiomeConfig biomeConfig = new BiomeConfig(
                enabled, minLogs, minLeafLike, logBlocks, leafBlocks, attachments, 
                extraDropsEnabled, extraDrops, protectionBeltRadius, 
                maxLogs, maxBlocks, maxRadiusXZ, maxHeightY,
                leafDecayRangeXZ, leafDecayRangeY,
                isolatedLogsRule, orphanLeavesCleanup, orphanLeavesRadius,
                rootReplacementEnabled, rootReplacementMaterial,
                fallbackEnabled, fallbackMaxBlocks, fallbackTrunkCoreRadius,
                fallbackTrunkMinHeight, fallbackMaxRadius, fallbackMinDensity,
                fallbackRingStep,
                isolatedLogsRadius, isolatedLogMax, allowNonRootedStart, maxRootSearchDepth,
                sixWayMaxLogs, canopyCleanupEnabled, canopyCleanupPadding,
                fillMode, fillRadiusExtra, elephantFactor,
                leafDecayScalingLogs, leafDecayScalingXzBonus, leafDecayScalingYBonus,
                allowDiagonalLogs, allowDiagonalLeaves
            );

            if (biomeKey.equalsIgnoreCase("DEFAULT")) {
                defaultBiomeConfig = biomeConfig;
            } else {
                // Support multi-biome keys (virgule-séparés)
                String[] biomeNames = biomeKey.split(",");
                for (String name : biomeNames) {
                    String trimmed = name.trim().toUpperCase()
                            .replace("'", "").replace("\"", "");
                    if (!trimmed.isEmpty()) {
                        biomes.put(trimmed, biomeConfig);
                    }
                }
            }
        }

        // Deuxième passe pour propager les valeurs par défaut
        for (Map.Entry<String, BiomeConfig> entry : biomes.entrySet()) {
            BiomeConfig bc = entry.getValue();
            Set<Material> logs = bc.logBlocks();
            if (logs.isEmpty() && defaultBiomeConfig != null) {
                logs = defaultBiomeConfig.logBlocks();
            }
            Set<Material> leaves = bc.leafBlocks();
            if (leaves.isEmpty() && defaultBiomeConfig != null) {
                leaves = defaultBiomeConfig.leafBlocks();
            }
            Set<Material> attachments = bc.attachments();
            if (attachments.isEmpty() && defaultBiomeConfig != null) {
                attachments = defaultBiomeConfig.attachments();
            }

            Integer scalingLogs = bc.leafDecayScalingLogs();
            if (scalingLogs == null && defaultBiomeConfig != null) {
                scalingLogs = defaultBiomeConfig.leafDecayScalingLogs();
            }
            Integer scalingXz = bc.leafDecayScalingXzBonus();
            if (scalingXz == null && defaultBiomeConfig != null) {
                scalingXz = defaultBiomeConfig.leafDecayScalingXzBonus();
            }
            Integer scalingY = bc.leafDecayScalingYBonus();
            if (scalingY == null && defaultBiomeConfig != null) {
                scalingY = defaultBiomeConfig.leafDecayScalingYBonus();
            }
            Boolean diagLogs = bc.allowDiagonalLogs();
            if (diagLogs == null && defaultBiomeConfig != null) {
                diagLogs = defaultBiomeConfig.allowDiagonalLogs();
            }
            Boolean diagLeaves = bc.allowDiagonalLeaves();
            if (diagLeaves == null && defaultBiomeConfig != null) {
                diagLeaves = defaultBiomeConfig.allowDiagonalLeaves();
            }

            entry.setValue(new BiomeConfig(
                bc.enabled(), bc.minLogs(), bc.minLeafLike(), logs, leaves, attachments,
                bc.extraDropsEnabled(), bc.extraDrops(), bc.protectionBeltRadius(),
                bc.maxLogs(), bc.maxBlocks(), bc.maxRadiusXZ(), bc.maxHeightY(),
                bc.leafDecayRangeXZ(), bc.leafDecayRangeY(),
                bc.isolatedLogsRule(), bc.orphanLeavesCleanup(), bc.orphanLeavesRadius(),
                bc.rootReplacementEnabled(), bc.rootReplacementMaterial(),
                bc.fallbackEnabled(), bc.fallbackMaxBlocks(), bc.fallbackTrunkCoreRadius(),
                bc.fallbackTrunkMinHeight(), bc.fallbackMaxRadius(), bc.fallbackMinDensity(),
                bc.fallbackRingStep(),
                bc.isolatedLogsRadius(), bc.isolatedLogMax(), bc.allowNonRootedStart(), bc.maxRootSearchDepth(),
                bc.sixWayMaxLogs(), bc.canopyCleanupEnabled(), bc.canopyCleanupPadding(),
                bc.fillMode(), bc.fillRadiusExtra(), bc.elephantFactor(),
                scalingLogs, scalingXz, scalingY,
                diagLogs, diagLeaves
            ));
        }

        if (defaultBiomeConfig == null) {
            // Créer une config par défaut minimale si absente
            defaultBiomeConfig = new BiomeConfig(
                true, 6, 5, new HashSet<>(logWeights.keySet()), new HashSet<>(leafWeights.keySet()), Collections.emptySet(), 
                false, Collections.emptyList(), 4, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null,
                null, null, null,
                null, null, null,
                null, null, null,
                null, null
            );
        }

        // Sweep global pour s'assurer que toutes les variantes de barrières, portiques et dalles
        // sont ajoutées aux attachments pour toutes les configs (y compris DEFAULT)
        if (defaultBiomeConfig != null) {
            defaultBiomeConfig = addFencesSlabsToAttachments(defaultBiomeConfig);
        }
        for (Map.Entry<String, BiomeConfig> entry : biomes.entrySet()) {
            entry.setValue(addFencesSlabsToAttachments(entry.getValue()));
        }
    }

    private BiomeConfig addFencesSlabsToAttachments(BiomeConfig bc) {
        Set<Material> attachments = new HashSet<>(bc.attachments());
        for (Material m : Material.values()) {
            String name = m.name();
            if (name.endsWith("_FENCE") || name.endsWith("_FENCE_GATE") || name.endsWith("_SLAB")) {
                attachments.add(m);
            }
        }
        return new BiomeConfig(
            bc.enabled(), bc.minLogs(), bc.minLeafLike(), bc.logBlocks(), bc.leafBlocks(), attachments,
            bc.extraDropsEnabled(), bc.extraDrops(), bc.protectionBeltRadius(),
            bc.maxLogs(), bc.maxBlocks(), bc.maxRadiusXZ(), bc.maxHeightY(),
            bc.leafDecayRangeXZ(), bc.leafDecayRangeY(),
            bc.isolatedLogsRule(), bc.orphanLeavesCleanup(), bc.orphanLeavesRadius(),
            bc.rootReplacementEnabled(), bc.rootReplacementMaterial(),
            bc.fallbackEnabled(), bc.fallbackMaxBlocks(), bc.fallbackTrunkCoreRadius(),
            bc.fallbackTrunkMinHeight(), bc.fallbackMaxRadius(), bc.fallbackMinDensity(),
            bc.fallbackRingStep(),
            bc.isolatedLogsRadius(), bc.isolatedLogMax(), bc.allowNonRootedStart(), bc.maxRootSearchDepth(),
            bc.sixWayMaxLogs(), bc.canopyCleanupEnabled(), bc.canopyCleanupPadding(),
            bc.fillMode(), bc.fillRadiusExtra(), bc.elephantFactor(),
            bc.leafDecayScalingLogs(), bc.leafDecayScalingXzBonus(), bc.leafDecayScalingYBonus(),
            bc.allowDiagonalLogs(), bc.allowDiagonalLeaves()
        );
    }

    private Set<Material> parseMaterialSet(List<String> list) {
        Set<Material> set = new HashSet<>();
        for (String name : list) {
            Material mat = Material.matchMaterial(name);
            if (mat != null) {
                set.add(mat);
            }
        }
        return set;
    }

    private void parseLang() {
        prefix = translateColor(langConfig.getString("prefix", "§6[WildTimber]§r "));
        messages.clear();

        ConfigurationSection msgSec = langConfig.getConfigurationSection("messages");
        if (msgSec != null) {
            for (String key : msgSec.getKeys(true)) {
                if (msgSec.isString(key)) {
                    messages.put(key, translateColor(msgSec.getString(key)));
                }
            }
        }

        for (String key : langConfig.getKeys(true)) {
            if (langConfig.isString(key) && !key.startsWith("prefix")) {
                String cleanKey = key.startsWith("messages.") ? key.substring("messages.".length()) : key;
                messages.putIfAbsent(cleanKey, translateColor(langConfig.getString(key)));
            }
        }
    }

    private String translateColor(String text) {
        if (text == null) return "";
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /**
     * Récupère un message configuré et formate sa couleur.
     */
    public String getMessage(String key, boolean includePrefix) {
        String msg = messages.get(key);
        if (msg == null) {
            msg = langConfig.getString("messages." + key);
            if (msg == null) msg = langConfig.getString(key);
            if (msg != null) {
                msg = translateColor(msg);
                messages.put(key, msg);
            } else {
                msg = "§cMissing message: " + key;
            }
        }
        return includePrefix ? prefix + msg : msg;
    }

    // ── Getters de base ─────────────────────────────────────────────────────

    public boolean isPluginEnabled() { return pluginEnabled; }
    public boolean isDebug() { return debug; }
    public void setDebug(boolean debug) { this.debug = debug; }
    public Set<String> getDisabledWorlds() { return disabledWorlds; }
    public boolean isWorldEnabled(String worldName) {
        if (worldName == null) return false;
        return !disabledWorlds.contains(worldName);
    }
    public List<String> getMessageList(String key) {
        List<String> raw = langConfig.getStringList("messages." + key);
        if (raw.isEmpty()) {
            raw = langConfig.getStringList(key);
        }
        if (raw.isEmpty()) {
            String val = langConfig.getString("messages." + key);
            if (val == null) val = langConfig.getString(key);
            if (val != null) {
                return List.of(translateColor(val));
            }
            return List.of("§cMissing message list: " + key);
        }
        List<String> translated = new ArrayList<>();
        for (String s : raw) {
            translated.add(translateColor(s));
        }
        return translated;
    }
    public double getBaseCoefficient() { return baseCoefficient; }
    public int getInactivityDelaySeconds() { return inactivityDelaySeconds; }
    public boolean isRegenEnabled() { return regenEnabled; }
    public double getRegenPercentPerStep() { return regenPercentPerStep; }
    public int getRegenStepSeconds() { return regenStepSeconds; }
    public boolean isVisualTreeHealthEnabled() { return visualTreeHealthEnabled; }
    public String getTriggerMode() { return triggerMode; }
    public boolean isSendHintMessage() { return sendHintMessage; }
    public int getHintCooldownSeconds() { return hintCooldownSeconds; }
    public long getClickCooldownMs() { return clickCooldownMs; }

    public double getToolMultiplier(Material material) {
        return toolMultipliers.getOrDefault(material, defaultToolMultiplier);
    }
    public double getEfficiencyLevelMultiplier() { return efficiencyLevelMultiplier; }
    public boolean isExtraLossEnabled() { return extraLossEnabled; }
    public int getExtraLossPoints() { return extraLossPoints; }
    public int getExtraLossChancePercent() { return extraLossChancePercent; }

    // ── Getters limites ─────────────────────────────────────────────────────

    public String getDetectionPreset() { return detectionPreset; }
    public int getMaxLogs() { return maxLogs; }
    public int getMaxBlocks() { return maxBlocks; }
    public int getMaxRadius() { return maxRadius; }
    public int getMaxRadiusXZ() { return maxRadiusXZ; }
    public int getMaxHeightY() { return maxHeightY; }
    public int getMaxConnectedNonTreeBlocks() { return maxConnectedNonTreeBlocks; }
    public int getLeafDecayRangeXZ() { return leafDecayRangeXZ; }
    public int getLeafDecayRangeY() { return leafDecayRangeY; }
    public int getLeafDecayScalingLogs() { return leafDecayScalingLogs; }
    public int getLeafDecayScalingXzBonus() { return leafDecayScalingXzBonus; }
    public int getLeafDecayScalingYBonus() { return leafDecayScalingYBonus; }
    public boolean isAllowDiagonalLogs() { return allowDiagonalLogs; }
    public boolean isAllowDiagonalLeaves() { return allowDiagonalLeaves; }
    /** @deprecated Utiliser getLeafDecayRangeXZ() et getLeafDecayRangeY() */
    @Deprecated
    public int getLeafDecayRange() { return Math.max(leafDecayRangeXZ, leafDecayRangeY); }
    public double getLogYieldMultiplier() { return logYieldMultiplier; }
    public double getEfficiencyDamage() { return efficiencyDamage; }
    public double getSharpnessDamage() { return sharpnessDamage; }
    public int getMaxBranchDiscoveryIterations() { return maxBranchDiscoveryIterations; }
    public int getMaxOrphanClusterSize() { return maxOrphanClusterSize; }

    public boolean isIsolatedLogsRule() { return isolatedLogsRule; }
    public boolean isOrphanLeavesCleanupEnabled() { return orphanLeavesCleanupEnabled; }
    public int getOrphanLeavesRadius() { return orphanLeavesRadius; }
    public boolean isRootReplacementEnabled() { return rootReplacementEnabled; }
    public Material getRootReplacementMaterial() { return rootReplacementMaterial; }

    // ── Getters cleanup floating blocks ─────────────────────────────────────

    public Set<Material> getCleanupFloatingBlocks() { return cleanupFloatingBlocks; }

    // ── Getters anti-cheat ──────────────────────────────────────────────────

    public boolean isAntiCheatEnabled() { return antiCheatEnabled; }
    public long getAntiCheatMinClickIntervalMs() { return antiCheatMinClickIntervalMs; }
    public double getAntiCheatMinLookChangeDegrees() { return antiCheatMinLookChangeDegrees; }
    public int getAntiCheatMaxRegularClicks() { return antiCheatMaxRegularClicks; }

    // ── Getters partition multi-source (trees) ──────────────────────────────

    public int getMinLogsRooted() { return minLogsRooted; }
    public int getMinLogsIsolated() { return minLogsIsolated; }
    public int getMaxBridgeSectionSize() { return maxBridgeSectionSize; }
    public double getMaxBridgeCost() { return maxBridgeCost; }
    public double getHeightWeightAlpha() { return heightWeightAlpha; }
    public int getBridgeSearchRadius() { return bridgeSearchRadius; }

    // ── Getters performance (staged cut) ────────────────────────────────────

    public boolean isStagedCutEnabled() { return stagedCutEnabled; }
    public int getStagedCutMinBlocks() { return stagedCutMinBlocks; }
    public int getStagedCutSliceHeight() { return stagedCutSliceHeight; }
    public int getStagedCutIntervalTicks() { return stagedCutIntervalTicks; }

    // ── Getters roots (backfill) ────────────────────────────────────────────

    public boolean isBackfillEnabled() { return backfillEnabled; }
    public Material getBackfillBlock() { return backfillBlock; }
    public int getMaxBackfillDepth() { return maxBackfillDepth; }
    public int getRootFillPadding() { return rootFillPadding; }
    public int getRootFillDepthPadding() { return rootFillDepthPadding; }

    public int getIsolatedLogsRadius() { return isolatedLogsRadius; }
    public int getIsolatedLogMax() { return isolatedLogMax; }
    public boolean isAllowNonRootedStart() { return allowNonRootedStart; }
    public int getMaxRootSearchDepth() { return maxRootSearchDepth; }
    public int getSixWayMaxLogs() { return sixWayMaxLogs; }
    public boolean isCanopyCleanupEnabled() { return canopyCleanupEnabled; }
    public int getCanopyCleanupPadding() { return canopyCleanupPadding; }
    public int getLeavesPersistenceBatchSize() { return leavesPersistenceBatchSize; }

    // ── Getters fallback ────────────────────────────────────────────────────

    public boolean isFallbackEnabled() { return fallbackEnabled; }
    public int getFallbackMaxBlocks() { return fallbackMaxBlocks; }
    public int getFallbackTrunkCoreRadius() { return fallbackTrunkCoreRadius; }
    public int getFallbackTrunkMinHeight() { return fallbackTrunkMinHeight; }
    public int getFallbackMaxRadius() { return fallbackMaxRadius; }
    public double getFallbackMinDensity() { return fallbackMinDensity; }
    public int getFallbackRingStep() { return fallbackRingStep; }

    // ── Getters poids et blacklist ──────────────────────────────────────────

    public Map<Material, Double> getLogWeights() { return logWeights; }
    public Map<Material, Double> getLeafWeights() { return leafWeights; }
    public Set<Material> getBlacklist() { return blacklist; }
    public boolean isBlacklistEnabled() { return blacklistEnabled; }
    public void setBlacklistEnabled(boolean enabled) { this.blacklistEnabled = enabled; }

    /**
     * Récupère la configuration d'un biome par son nom, ou retourne DEFAULT si non défini.
     * Supporte :
     * - Matching exact
     * - Stripping des suffixes numériques (RAINFOREST1 → RAINFOREST)
     * - Fallback vers DEFAULT
     */
    public BiomeConfig getBiomeConfig(String biomeName) {
        if (biomeName == null) return defaultBiomeConfig;
        String key = biomeName.toUpperCase();
        if (key.contains(":")) {
            key = key.substring(key.indexOf(":") + 1);
        }

        // 1. Matching exact
        BiomeConfig exact = biomes.get(key);
        if (exact != null) return exact;

        // 2. Strip les chiffres de fin et réessayer
        String stripped = key.replaceAll("\\d+$", "");
        if (!stripped.equals(key)) {
            BiomeConfig strippedConfig = biomes.get(stripped);
            if (strippedConfig != null) return strippedConfig;
        }

        // 3. Drop suffixes separated by underscores
        String temp = key;
        while (temp.contains("_")) {
            temp = temp.substring(0, temp.lastIndexOf("_"));
            BiomeConfig match = biomes.get(temp);
            if (match != null) return match;
        }

        // 4. Fallback vers DEFAULT
        return defaultBiomeConfig;
    }

    public boolean hasExactBiomeConfig(String biomeName) {
        if (biomeName == null) return false;
        String key = biomeName.toUpperCase();
        if (key.contains(":")) {
            key = key.substring(key.indexOf(":") + 1);
        }
        if (biomes.containsKey(key)) return true;
        String stripped = key.replaceAll("\\d+$", "");
        if (biomes.containsKey(stripped)) return true;

        String temp = key;
        while (temp.contains("_")) {
            temp = temp.substring(0, temp.lastIndexOf("_"));
            if (biomes.containsKey(temp)) return true;
        }
        return false;
    }

    public static String cleanYamlKey(String key) {
        if (key == null) return "";
        String[] parts = key.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            while (part.startsWith(":")) {
                part = part.substring(1).trim();
            }
            parts[i] = part;
        }
        String cleaned = String.join(".", parts);
        assert !cleaned.startsWith(":") && !cleaned.contains(".:") : "Invalid key generated: " + cleaned;
        return cleaned;
    }

    public boolean isLeafDropsEnabled() { return leafDropsEnabled; }
    public double getLeafDropsSaplingChance() { return leafDropsSaplingChance; }
    public double getLeafDropsStickChance() { return leafDropsStickChance; }
    public double getLeafDropsAppleChance() { return leafDropsAppleChance; }
    public boolean isVerboseDebug() { return verboseDebug; }
    public String getFillMode() { return fillMode; }
    public int getFillRadiusExtra() { return fillRadiusExtra; }
    public int getFillDepth() { return fillDepth; }
    public double getElephantFactor() { return elephantFactor; }
    public int getMaxSlope() { return maxSlope; }
    public int getIdwNeighbors() { return idwNeighbors; }

    public boolean isTreeContactRequired() { return treeContactRequired; }
    public void setTreeContactRequired(boolean required) { this.treeContactRequired = required; }

    public FileConfiguration getConfig() { return config; }
    public FileConfiguration getBiomesConfig() { return biomesConfig; }

    public static final Map<String, String> AVAILABLE_LANGUAGES;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("fr", "Français");
        m.put("en", "English");
        m.put("de", "Deutsch");
        m.put("es", "Español");
        m.put("pt_BR", "Português Brasileiro");
        m.put("nl", "Nederlands");
        m.put("pl", "Polski");
        m.put("ru", "Русский");
        m.put("zh_CN", "简体中文");
        m.put("it", "Italiano");
        AVAILABLE_LANGUAGES = Collections.unmodifiableMap(m);
    }

    public void saveConfig() {
        try {
            config.save(new File(plugin.getDataFolder(), "config.yml"));
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible de sauvegarder config.yml", e);
        }
    }

    public void saveBiomes() {
        try {
            biomesConfig.save(new File(plugin.getDataFolder(), "biomes.yml"));
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible de sauvegarder biomes.yml", e);
        }
    }

    public String getLanguage() { return language; }

    public void setLanguage(String newLang) {
        if (newLang == null) return;
        String trimmed = newLang.trim();

        // Find the matching key case-insensitively (to support pt_BR, zh_CN etc.)
        String matched = null;
        for (String availCode : AVAILABLE_LANGUAGES.keySet()) {
            if (availCode.equalsIgnoreCase(trimmed)) {
                matched = availCode;
                break;
            }
        }
        if (matched == null) return;

        this.language = matched;
        config.set("plugin.language", matched);
        saveConfig();

        File targetLang = new File(plugin.getDataFolder(), "lang.yml");
        String resourcePath = "lang/lang_" + matched + ".yml";
        InputStream is = plugin.getResource(resourcePath);
        if (is != null) {
            try (FileOutputStream fos = new FileOutputStream(targetLang)) {
                is.transferTo(fos);
            } catch (IOException e) {
                plugin.getLogger().warning("Erreur lors de la mise à jour du fichier lang.yml : " + e.getMessage());
            }
        }
        load();

        // Rafraîchit les interfaces GUI de tous les joueurs ayant un menu ouvert
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory() != null && p.getOpenInventory().getTopInventory() != null) {
                if (p.getOpenInventory().getTopInventory().getHolder() instanceof ConfigGUI gui) {
                    p.openInventory(new ConfigGUI(plugin, p, gui.getMenuType(), gui.getBiomeName(), gui.getListType(), gui.getPage()).getInventory());
                }
            }
        }
    }
}
