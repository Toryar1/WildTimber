package com.wildtimber.felling;

import com.wildtimber.WildTimber;
import com.wildtimber.ConsoleColor;
import com.wildtimber.config.BiomeConfig;
import com.wildtimber.config.ConfigManager;
import com.wildtimber.config.ExtraDropEntry;
import com.wildtimber.manager.ActiveTree;
import com.wildtimber.manager.UndoSnapshot;
import com.wildtimber.detection.BlockPos;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;

import java.util.*;

/**
 * Gère l'abattage physique (staged ou instantané) de l'arbre, les drops et la restauration de sol (backfill).
 */
public class TreeFeller {

    private final WildTimber plugin;
    private final ConfigManager configManager;

    private static final int[][] DIRS_6 = {
        {1, 0, 0}, {-1, 0, 0},
        {0, 1, 0}, {0, -1, 0},
        {0, 0, 1}, {0, 0, -1}
    };

    private static final int[][] DIRS_26 = new int[26][3];
    static {
        int idx = 0;
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    DIRS_26[idx++] = new int[]{x, y, z};
                }
            }
        }
    }

    private static final Set<Material> FLOATING_CANDIDATES = new HashSet<>(Arrays.asList(
        Material.STONE, Material.COBBLESTONE, Material.GRAVEL, Material.DIRT, Material.GRASS_BLOCK,
        Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.TUFF, Material.DEEPSLATE,
        Material.SAND
    ));

    public TreeFeller(WildTimber plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
    }

    /**
     * Abat l'arbre.
     *
     * @param tree L'arbre actif à faire tomber.
     * @param lastCutter Le joueur ayant abattu l'arbre.
     */
    public void fell(ActiveTree tree, Player lastCutter) {
        long startTime = System.currentTimeMillis();
        World world = tree.getWorld();

        // 0. Enregistrer le snapshot pour l'annulation (undo) avant toute modification
        if (lastCutter != null) {
            Map<BlockPos, org.bukkit.block.data.BlockData> snapshotBlocks = new HashMap<>();
            for (BlockPos pos : tree.getLogs()) {
                snapshotBlocks.put(pos, world.getBlockAt(pos.x(), pos.y(), pos.z()).getBlockData());
            }
            for (BlockPos pos : tree.getLeaves()) {
                snapshotBlocks.put(pos, world.getBlockAt(pos.x(), pos.y(), pos.z()).getBlockData());
            }
            UndoSnapshot snapshot = new UndoSnapshot(world, snapshotBlocks);
            plugin.getTreeManager().saveUndoSnapshot(lastCutter.getUniqueId(), snapshot);
        }

        String biomeName = tree.getBiomeName();
        BiomeConfig biomeConfig = configManager.getBiomeConfig(biomeName);

        boolean canopyEnabled = biomeConfig != null && biomeConfig.canopyCleanupEnabled() != null
                ? biomeConfig.canopyCleanupEnabled() : configManager.isCanopyCleanupEnabled();

        Set<BlockPos> canopyBlocks = Collections.emptySet();
        if (canopyEnabled) {
            canopyBlocks = identifyCanopyCleanupBlocks(world, tree.getLogs(), tree.getLeaves(), biomeConfig);
        }

        // 1. Détecter si on doit faire une coupe progressive (staged cut)
        boolean stagedEnabled = configManager.isStagedCutEnabled();
        int minStagedBlocks = configManager.getStagedCutMinBlocks();
        int totalBlocks = tree.getLogs().size() + tree.getLeaves().size() + canopyBlocks.size();

        if (stagedEnabled && totalBlocks >= minStagedBlocks) {
            StagedCutScheduler scheduler = new StagedCutScheduler(plugin);
            CutJob job = scheduler.createAndSubmitJob(tree, lastCutter,
                    configManager.getStagedCutSliceHeight(), canopyBlocks);

            if (lastCutter != null) {
                if (job.getStatus() == CutJob.Status.QUEUED) {
                    lastCutter.sendMessage(configManager.getMessage("cut_queued", true)
                            .replace("{position}", plugin.getCutJobManager().getQueueSize() + ""));
                } else {
                    lastCutter.sendMessage(configManager.getMessage("staged_cut_started", true));
                }
            }
            return;
        }

        // --- Coupe instantanée classique ---
        Map<BlockPos, Material> blockCache = new HashMap<>();
        Map<BlockPos, Boolean> resultCache = new HashMap<>();

        Map<Long, FillTarget> heightmap = takeHeightmapSnapshot(world, tree.getLogs(), biomeConfig);

        int fortuneLevel = 0;
        ItemStack tool = null;
        if (lastCutter != null) {
            tool = lastCutter.getInventory().getItemInMainHand();
            fortuneLevel = tool.getEnchantmentLevel(Enchantment.FORTUNE);
        }

        int beltRadius = biomeConfig != null ? biomeConfig.protectionBeltRadius() : 4;

        // Nettoyage des feuilles solitaires dans un rayon X
        boolean leafCleanup = biomeConfig != null && biomeConfig.orphanLeavesCleanup() != null 
                ? biomeConfig.orphanLeavesCleanup() : configManager.isOrphanLeavesCleanupEnabled();
        int cleanupRadius = biomeConfig != null && biomeConfig.orphanLeavesRadius() != null 
                ? biomeConfig.orphanLeavesRadius() : configManager.getOrphanLeavesRadius();
        if (leafCleanup) {
            findOrphanLeaves(world, tree.getLogs(), tree.getLeaves(), cleanupRadius, biomeConfig);
        }

        // Détruire et compter les bûches
        Map<Material, Integer> logCounts = new HashMap<>();
        BlockPos lowestPos = null;

        for (BlockPos pos : tree.getLogs()) {
            org.bukkit.block.Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
            Material mat = block.getType();
            if (mat != Material.AIR) {
                logCounts.put(mat, logCounts.getOrDefault(mat, 0) + 1);
                if (lowestPos == null || pos.y() < lowestPos.y()) {
                    lowestPos = pos;
                }
            }

            // Supprimer la neige/tapis au-dessus avant de détruire le bloc
            cleanBlockAbove(world, pos);

            // physics=true pour déclencher le decay naturel des feuilles restées
            fellBlock(block, Material.AIR, true);
        }

        // Drop des bûches (arrondi supérieur) à la base de l'arbre
        Location dropBaseLoc;
        if (lowestPos != null) {
            dropBaseLoc = new Location(world, lowestPos.x() + 0.5, lowestPos.y() + 0.5, lowestPos.z() + 0.5);
        } else {
            BlockPos firstLog = tree.getLogs().iterator().next();
            dropBaseLoc = new Location(world, firstLog.x() + 0.5, firstLog.y() + 0.5, firstLog.z() + 0.5);
        }

        double logYieldMultiplier = configManager.getLogYieldMultiplier();
        for (Map.Entry<Material, Integer> entry : logCounts.entrySet()) {
            Material mat = entry.getKey();
            if (mat.isItem()) {
                int count = entry.getValue();
                int dropCount = (int) Math.ceil(count * logYieldMultiplier);
                while (dropCount > 0) {
                    int toDrop = Math.min(dropCount, 64);
                    try {
                        world.dropItemNaturally(dropBaseLoc, new ItemStack(mat, toDrop));
                    } catch (Exception e) {
                        // Ignorer
                    }
                    dropCount -= toDrop;
                }
            }
        }

        // Nettoyer et drop les feuilles et attachments
        for (BlockPos pos : tree.getLeaves()) {
            Location loc = new Location(world, pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5);
            org.bukkit.block.Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
            Material mat = block.getType();
            if (mat == Material.AIR) continue;

            // Protection de ceinture configurable autour des troncs étrangers
            if (isNearForeignLog(world, pos, tree.getLogs(), beltRadius, biomeConfig, resultCache, blockCache)) {
                org.bukkit.block.data.BlockData data = block.getBlockData();
                if (data instanceof Leaves leavesData) {
                    if (!leavesData.isPersistent()) {
                        leavesData.setPersistent(true);
                        block.setBlockData(leavesData, false);
                    }
                }
                continue;
            }

            // Supprimer la neige/tapis au-dessus avant de détruire le bloc
            cleanBlockAbove(world, pos);

            // Obtenir les drops de type cisaille/silk touch si applicable
            boolean hasSilkTouchOrShears = false;
            if (tool != null) {
                hasSilkTouchOrShears = tool.getType() == Material.SHEARS || tool.containsEnchantment(Enchantment.SILK_TOUCH);
            }

            if (hasSilkTouchOrShears) {
                Collection<ItemStack> drops = null;
                try {
                    drops = block.getDrops(tool);
                } catch (Exception e) {
                    drops = Collections.emptyList();
                }
                fellBlock(block, Material.AIR, false);
                if (drops != null) {
                    for (ItemStack drop : drops) {
                        if (drop != null && drop.getType() != Material.AIR) {
                            try {
                                world.dropItemNaturally(loc, drop);
                            } catch (Exception e) {
                                // Ignorer
                            }
                        }
                    }
                }
            } else {
                if (isLeafMaterial(mat)) {
                    dropLeaf(block, lastCutter);
                    fellBlock(block, Material.AIR, false);
                } else {
                    block.breakNaturally();
                }
            }

            // Drops additionnels configurés par biome (ex: cacao, petals)
            if (biomeConfig != null && biomeConfig.extraDropsEnabled()) {
                for (ExtraDropEntry entry : biomeConfig.extraDrops()) {
                    if (Math.random() < entry.chance()) {
                        int amount = entry.min() + (int) (Math.random() * (entry.max() - entry.min() + 1));
                        if (amount > 0 && entry.material().isItem()) {
                            try {
                                world.dropItemNaturally(loc, new ItemStack(entry.material(), amount));
                            } catch (Exception e) {
                                // Ignorer
                            }
                        }
                    }
                }
            }
        }

        // Rebouchage intelligent des racines sous terre avec 1 tick de délai
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            backfillRoots(world, tree.getLogs(), biomeConfig, heightmap, null);
        }, 1L);

        // Nettoyage des blocs flottants post-abattage
        cleanupFloatingBlocks(world, tree.getLogs(), tree.getLeaves(), biomeConfig);

        // Nettoyage des bûches orphelines post-abattage
        cleanupIsolatedLogs(world, tree.getLogs(), tree.getLeaves(), biomeConfig);

        // Nettoyage de la canopée post-abattage (si activé)
        if (canopyEnabled) {
            for (BlockPos pos : canopyBlocks) {
                org.bukkit.block.Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
                Material mat = block.getType();
                if (mat != Material.AIR) {
                    cleanBlockAbove(world, pos);
                    if (isLog(mat, biomeConfig)) {
                        fellBlock(block, Material.AIR, true);
                        if (mat.isItem()) {
                            Location loc = new Location(world, pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5);
                            try {
                                world.dropItemNaturally(loc, new ItemStack(mat, 1));
                            } catch (Exception e) { /* ignore */ }
                        }
                    } else {
                        if (isLeafMaterial(mat)) {
                            dropLeaf(block, lastCutter);
                            block.setType(Material.AIR, false);
                        } else {
                            block.breakNaturally();
                        }
                        postFellPhysicsUpdate(block);
                    }
                }
            }
        }

        if (configManager.isDebug()) {
            int totalCut = tree.getLogs().size() + tree.getLeaves().size() + canopyBlocks.size();
            org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Coupe totale (core + canopy): " + totalCut + " blocs — terminée.");
        }

        // Corriger la persistance pour forcer le decay des feuilles persistantes restantes
        updateLeavesPersistence(world, tree.getLogs(), tree.getLeaves(), biomeConfig, resultCache, blockCache);

        if (lastCutter != null && lastCutter.isOnline()) {
            double durationSec = (System.currentTimeMillis() - startTime) / 1000.0;
            sendFellCompletionMessage(
                    lastCutter,
                    tree.getLogs().size(),
                    tree.getLeaves().size() + canopyBlocks.size(),
                    tree.getBiomeName(),
                    durationSec
            );
        }
    }

    private void fellBlock(org.bukkit.block.Block block, Material replacement, boolean physics) {
        org.bukkit.block.data.BlockData blockData = block.getBlockData();
        boolean setWater = blockData instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged();

        if (replacement == Material.AIR) {
            if (setWater) {
                block.setType(Material.WATER, physics);
            } else {
                block.setType(Material.AIR, physics);
            }
        } else {
            block.setType(replacement, physics);
        }

        if (!physics) {
            postFellPhysicsUpdate(block);
        }
    }

    /**
     * Construit une heightmap de référence AVANT la coupe.
     */
    public Map<Long, FillTarget> takeHeightmapSnapshot(World world, Set<BlockPos> logs, BiomeConfig biomeConfig) {
        String fillMode = biomeConfig != null && biomeConfig.fillMode() != null 
                ? biomeConfig.fillMode() : configManager.getFillMode();
        boolean backfillEnabled = biomeConfig != null && biomeConfig.rootReplacementEnabled() != null 
                ? biomeConfig.rootReplacementEnabled() : configManager.isBackfillEnabled();
        if (!backfillEnabled) fillMode = "NONE";

        if (fillMode.equalsIgnoreCase("NONE")) {
            return Collections.emptyMap();
        }

        if (fillMode.equalsIgnoreCase("LEGACY")) {
            // yBase = Y le plus bas de toutes les bûches
            int minY = Integer.MAX_VALUE;
            for (BlockPos pos : logs) {
                if (pos.y() < minY) minY = pos.y();
            }
            int yBase = minY;

            // footprintKeys des logs du tronc principal uniquement (exclure les branches)
            Set<Long> footprintKeys = new HashSet<>();
            for (BlockPos log : logs) {
                // Est-ce que cette colonne (log.x(), log.z()) fait partie du tronc ?
                // Une colonne est "tronc" si elle contient au moins 1 bûche à Y < Y_base + 5
                int lowestYInCol = Integer.MAX_VALUE;
                for (BlockPos other : logs) {
                    if (other.x() == log.x() && other.z() == log.z()) {
                        if (other.y() < lowestYInCol) {
                            lowestYInCol = other.y();
                        }
                    }
                }

                if (lowestYInCol < yBase + 5) {
                    long key = ((long) log.x() << 32) | (log.z() & 0xFFFFFFFFL);
                    footprintKeys.add(key);
                }
            }

            Map<Long, FillTarget> snapshotHeightmap = new HashMap<>();
            int ySolMin = Integer.MAX_VALUE;
            int ySolMax = Integer.MIN_VALUE;

            for (long key : footprintKeys) {
                int x = (int) (key >> 32);
                int z = (int) key;

                // log_Y_min pour cette colonne
                int log_Y_min = Integer.MAX_VALUE;
                for (BlockPos pos : logs) {
                    if (pos.x() == x && pos.z() == z) {
                        if (pos.y() < log_Y_min) {
                            log_Y_min = pos.y();
                        }
                    }
                }

                int groundY = log_Y_min - 1;
                while (groundY >= world.getMinHeight()) {
                    Material m = world.getBlockAt(x, groundY, z).getType();
                    if (isIgnoreMaterial(m, biomeConfig)) {
                        groundY--;
                        continue;
                    }
                    break;
                }

                if (groundY >= world.getMinHeight()) {
                    Material originalMat = world.getBlockAt(x, groundY, z).getType();
                    
                    // Collecter tous les Y des bûches du tronc dans cette colonne
                    Set<Integer> trunkLogYs = new HashSet<>();
                    for (BlockPos other : logs) {
                        if (other.x() == x && other.z() == z) {
                            trunkLogYs.add(other.y());
                        }
                    }

                    snapshotHeightmap.put(key, new FillTarget(log_Y_min, log_Y_min, groundY, originalMat, 0, trunkLogYs, null, 0.0, Material.DIRT));
                    if (groundY < ySolMin) ySolMin = groundY;
                    if (groundY > ySolMax) ySolMax = groundY;

                    if (configManager.isDebug()) {
                        org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX 
                                + "[ROOT FILL] Phase 1: colonne (" + x + "," + z + ") | logYMin=" + log_Y_min 
                                + " | groundY=" + groundY + " | gap=" + (log_Y_min - groundY) + " blocs");
                    }
                }
            }

            if (configManager.isDebug()) {
                int fillDepth = biomeConfig != null && biomeConfig.maxRootSearchDepth() != null 
                        ? biomeConfig.maxRootSearchDepth() : configManager.getFillDepth();
                org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "[ROOT FILL] Phase 1 snapshot : " 
                        + snapshotHeightmap.size() + " colonnes tronc | Y_sol_min=" 
                        + (ySolMin == Integer.MAX_VALUE ? -1 : ySolMin) 
                        + " Y_sol_max=" + (ySolMax == Integer.MIN_VALUE ? -1 : ySolMax)
                        + " | log_Y_min_global=" + yBase
                        + " | fill-depth=" + fillDepth);
            }

            return snapshotHeightmap;

        } else {
            // ── HOLE_DETECTOR mode ─────────────────────────────────────────────────────
            // Pipeline 6 étapes :
            // 1. Détection du contour + profondeur max (pied d'éléphant)
            // 2. Heightmap locale des voisins (rayon dynamique)
            // 3. IDW 8 voisins + rejet aberrants + mode dégradé
            // 4. Lissage 2 passes (pente max)
            // 5. Validation cohérence 4 voisins orthogonaux
            // 6. Stockage FillTarget avec reconstructedTopY

            int fillDepth = biomeConfig != null && biomeConfig.maxRootSearchDepth() != null
                    ? biomeConfig.maxRootSearchDepth() : configManager.getFillDepth();
            int fillRadiusExtra = biomeConfig != null && biomeConfig.fillRadiusExtra() != null
                    ? biomeConfig.fillRadiusExtra() : configManager.getFillRadiusExtra();
            double elephantFactor = biomeConfig != null && biomeConfig.elephantFactor() != null
                    ? biomeConfig.elephantFactor() : configManager.getElephantFactor();
            int maxSlope = configManager.getMaxSlope();
            int idwK = configManager.getIdwNeighbors();

            // ── Bornes globales des logs ───────────────────────────────────────────────
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            int xMinRaw = Integer.MAX_VALUE, xMaxRaw = Integer.MIN_VALUE;
            int zMinRaw = Integer.MAX_VALUE, zMaxRaw = Integer.MIN_VALUE;
            for (BlockPos pos : logs) {
                if (pos.y() < minY) minY = pos.y();
                if (pos.y() > maxY) maxY = pos.y();
                if (pos.x() < xMinRaw) xMinRaw = pos.x();
                if (pos.x() > xMaxRaw) xMaxRaw = pos.x();
                if (pos.z() < zMinRaw) zMinRaw = pos.z();
                if (pos.z() > zMaxRaw) zMaxRaw = pos.z();
            }
            int yMax = maxY, yMin = minY;

            // Empreinte au sol (avec marge extra)
            int xMin = xMinRaw - fillRadiusExtra;
            int xMax = xMaxRaw + fillRadiusExtra;
            int zMin = zMinRaw - fillRadiusExtra;
            int zMax = zMaxRaw + fillRadiusExtra;

            Set<Long> columnsToScan = new HashSet<>();
            if (fillRadiusExtra > 0) {
                for (int x = xMin; x <= xMax; x++) {
                    for (int z = zMin; z <= zMax; z++) {
                        columnsToScan.add(((long) x << 32) | (z & 0xFFFFFFFFL));
                    }
                }
            } else {
                for (BlockPos pos : logs) {
                    columnsToScan.add(((long) pos.x() << 32) | (pos.z() & 0xFFFFFFFFL));
                }
            }

            int empreinteRadius = Math.max((xMaxRaw - xMinRaw) / 2, (zMaxRaw - zMinRaw) / 2);
            int centerX = (xMinRaw + xMaxRaw) / 2;
            int centerZ = (zMinRaw + zMaxRaw) / 2;

            // Pré-calcul O(N) : Y minimum de log par colonne
            Map<Long, Integer> prebuiltLogMinY = new HashMap<>();
            for (BlockPos pos : logs) {
                long pk = ((long) pos.x() << 32) | (pos.z() & 0xFFFFFFFFL);
                Integer cur = prebuiltLogMinY.get(pk);
                if (cur == null || pos.y() < cur) prebuiltLogMinY.put(pk, pos.y());
            }

            // ── ÉTAPE 1 : Profondeur max par colonne ───────────────────────────────────
            // groundY réel par colonne (premier bloc solide non-arbre sous les logs)
            Map<Long, Integer> colGroundY = new HashMap<>();
            // Y du log le plus bas par colonne
            Map<Long, Integer> colLogMinY = new HashMap<>();
            // Matériau au sol par colonne
            Map<Long, Material> colGroundMat = new HashMap<>();
            // Colonnes en suspension (branche/flare au-dessus du vide) -> hauteur max capée
            Map<Long, Integer> colOverhangingRealGroundY = new HashMap<>();

            for (long key : columnsToScan) {
                int x = (int) (key >> 32);
                int z = (int) key;

                // Y le plus bas des logs dans cette colonne (pré-calculé en O(N))
                Integer logMinInCol = prebuiltLogMinY.get(key);
                if (logMinInCol == null) continue; // pas de log ici
                colLogMinY.put(key, logMinInCol);

                int groundY = logMinInCol;
                Material groundMat = Material.DIRT;

                Block blockBelow = world.getBlockAt(x, Math.max(logMinInCol - 1, world.getMinHeight()), z);
                Material belowMat = blockBelow.getType();

                if (belowMat.isAir() || !belowMat.isSolid() || isLeaf(belowMat, biomeConfig)) {
                    // Le tronc/branche est en suspension. On cherche le vrai sol ferme en dessous.
                    int realGroundY = -1;
                    for (int sy = logMinInCol - 2; sy >= world.getMinHeight(); sy--) {
                        Material m = world.getBlockAt(x, sy, z).getType();
                        if (m.isSolid() && !m.isAir() && !isLog(m, biomeConfig) && !isLeaf(m, biomeConfig)) {
                            realGroundY = sy;
                            groundMat = m;
                            break;
                        }
                    }
                    if (realGroundY != -1) {
                        groundY = realGroundY;
                        colOverhangingRealGroundY.put(key, realGroundY);
                    } else {
                        groundMat = belowMat;
                    }
                } else {
                    groundMat = belowMat;
                }

                colGroundY.put(key, groundY);
                colGroundMat.put(key, groundMat);
            }

            // ── ÉTAPE 2 : Heightmap locale des voisins ────────────────────────────────
            int R = Math.max(empreinteRadius + 6, 12);
            Map<Long, Integer> terrainHeightMap = new HashMap<>();
            Map<Long, Material> terrainSurfaceMat = new HashMap<>();
            int terrainMinY = Integer.MAX_VALUE, terrainMaxY = Integer.MIN_VALUE;
            double terrainSumY = 0;

            for (int nx = centerX - R; nx <= centerX + R; nx++) {
                for (int nz = centerZ - R; nz <= centerZ + R; nz++) {
                    long colKey = ((long) nx << 32) | (nz & 0xFFFFFFFFL);
                    if (columnsToScan.contains(colKey)) continue;

                    int terrainY = -1;
                    for (int y = yMax + 5; y >= world.getMinHeight(); y--) {
                        Block b = world.getBlockAt(nx, y, nz);
                        Material m = b.getType();
                        if (m.isAir() || isLeaf(m, biomeConfig) || isLog(m, biomeConfig)) continue;
                        if (m.isSolid()) { terrainY = y; break; }
                    }
                    if (terrainY != -1) {
                        terrainHeightMap.put(colKey, terrainY);
                        terrainSurfaceMat.put(colKey, world.getBlockAt(nx, terrainY, nz).getType());
                        if (terrainY < terrainMinY) terrainMinY = terrainY;
                        if (terrainY > terrainMaxY) terrainMaxY = terrainY;
                        terrainSumY += terrainY;
                    }
                }
            }

            // Vote majoritaire de matériau de surface + ratio neige
            int snowCount = 0;
            Map<Material, Integer> neighborSurfaceMatCounts = new HashMap<>();
            for (Map.Entry<Long, Integer> entry : terrainHeightMap.entrySet()) {
                Material mat = terrainSurfaceMat.getOrDefault(entry.getKey(), Material.DIRT);
                if (mat == Material.SNOW || mat == Material.SNOW_BLOCK) snowCount++;
                neighborSurfaceMatCounts.put(mat, neighborSurfaceMatCounts.getOrDefault(mat, 0) + 1);
            }
            double snowRatio = terrainHeightMap.isEmpty() ? 0.0 : (double) snowCount / terrainHeightMap.size();
            Material dominantMat = Material.DIRT;
            { int dMax = -1;
              for (Map.Entry<Material, Integer> e : neighborSurfaceMatCounts.entrySet()) {
                  if (e.getValue() > dMax) { dMax = e.getValue(); dominantMat = e.getKey(); }
              }
            }

            int aberrantRejected = 0;

            // ── ÉTAPE 3 : IDW — expectedGroundY par colonne ───────────────────────────
            // Précalcul de la médiane globale des voisins pour le rejet aberrant
            List<Integer> allTerrainHeights = new ArrayList<>(terrainHeightMap.values());
            allTerrainHeights.sort(Comparator.naturalOrder());
            double globalMedian = 0.0;
            if (!allTerrainHeights.isEmpty()) {
                int sz = allTerrainHeights.size();
                globalMedian = (sz % 2 == 1)
                        ? allTerrainHeights.get(sz / 2)
                        : (allTerrainHeights.get(sz / 2 - 1) + allTerrainHeights.get(sz / 2)) / 2.0;
            }

            // expectedGroundY par colonne (avant lissage) — stocké dans une Map intermédiaire
            Map<Long, Integer> expectedYMap = new HashMap<>();
            int degradedFallbackCount = 0;

            for (long key : columnsToScan) {
                if (!colLogMinY.containsKey(key) || !colGroundY.containsKey(key)) continue;
                int x = (int) (key >> 32);
                int z = (int) key;


                // IDW sur les k voisins valides les plus proches
                List<NeighborInfo> candidates = new ArrayList<>();
                for (Map.Entry<Long, Integer> entry : terrainHeightMap.entrySet()) {
                    int nx = (int) (entry.getKey() >> 32);
                    int nz = (int) (long) entry.getKey();
                    double distSq = (double) (nx - x) * (nx - x) + (double) (nz - z) * (nz - z);
                    candidates.add(new NeighborInfo(entry.getKey(), entry.getValue(), distSq));
                }
                candidates.sort(Comparator.comparingDouble(a -> a.distSq));

                // Garder les k plus proches non-aberrants (|y - globalMedian| <= 8)
                List<NeighborInfo> validNeighbors = new ArrayList<>();
                for (NeighborInfo n : candidates) {
                    if (validNeighbors.size() >= idwK) break;
                    if (Math.abs(n.y - globalMedian) <= 8) {
                        validNeighbors.add(n);
                    } else {
                        aberrantRejected++;
                    }
                }

                int expectedGroundY;
                if (validNeighbors.size() >= 2) {
                    double weightSum = 0, weightedSum = 0;
                    for (NeighborInfo n : validNeighbors) {
                        double w = (n.distSq < 1e-6) ? 1e6 : 1.0 / n.distSq;
                        weightSum += w;
                        weightedSum += n.y * w;
                    }
                    expectedGroundY = (int) Math.round(weightedSum / weightSum);
                } else if (validNeighbors.size() == 1) {
                    expectedGroundY = validNeighbors.get(0).y;
                } else {
                    // Mode dégradé : scan vertical local
                    degradedFallbackCount++;
                    expectedGroundY = -1;
                    for (int y = yMax + 5; y >= yMin - 15; y--) {
                        Material m = world.getBlockAt(x, y, z).getType();
                        if (!m.isAir() && !isLeaf(m, biomeConfig) && !isLog(m, biomeConfig) && m.isSolid()) {
                            expectedGroundY = y;
                            break;
                        }
                    }
                    if (expectedGroundY == -1) continue;
                }
                 expectedYMap.put(key, expectedGroundY);
            }

            // Appliquer le cap de hauteur pour les colonnes en suspension (branche/flare)
            // afin d'éviter la création de buttes ou d'amas de terre artificiels sur les pentes.
            for (Map.Entry<Long, Integer> entry : colOverhangingRealGroundY.entrySet()) {
                long key = entry.getKey();
                int realGroundY = entry.getValue();
                Integer expectedY = expectedYMap.get(key);
                if (expectedY != null && expectedY > realGroundY) {
                    expectedYMap.put(key, realGroundY);
                }
            }

            // ── ÉTAPE 4 : Lissage 2 passes (pente max) ───────────────────────────────
            // reconstructedYMap commence comme copie de expectedYMap
            Map<Long, Integer> reconstructedYMap = new HashMap<>(expectedYMap);
            int penteLissee = 0;

            for (int pass = 0; pass < 2; pass++) {
                Map<Long, Integer> nextMap = new HashMap<>(reconstructedYMap);
                for (long key : columnsToScan) {
                    Integer myY = reconstructedYMap.get(key);
                    if (myY == null) continue;
                    int x = (int) (key >> 32);
                    int z = (int) key;
                    for (int[] dir : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        long nKey = ((long) (x + dir[0]) << 32) | ((z + dir[1]) & 0xFFFFFFFFL);
                        Integer nY = reconstructedYMap.get(nKey);
                        if (nY == null) continue;
                        if (myY - nY > maxSlope) {
                            // Rabaisser pour respecter la pente max
                            nextMap.put(key, nY + maxSlope);
                            penteLissee++;
                        }
                    }
                }
                reconstructedYMap = nextMap;
            }

            // ── ÉTAPE 5 : Validation cohérence 4 voisins orthogonaux ─────────────────
            int coherenceFixed = 0;
            for (long key : columnsToScan) {
                Integer myY = reconstructedYMap.get(key);
                if (myY == null) continue;
                int x = (int) (key >> 32);
                int z = (int) key;

                int incoherentCount = 0;
                for (int[] dir : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    long nKey = ((long) (x + dir[0]) << 32) | ((z + dir[1]) & 0xFFFFFFFFL);
                    Integer nY = reconstructedYMap.get(nKey);
                    if (nY == null) continue;
                    if (Math.abs(myY - nY) > maxSlope + 1) incoherentCount++;
                }

                if (incoherentCount >= 2) {
                    // Fallback : scan vertical local
                    int localY = -1;
                    for (int y = yMax + 5; y >= yMin - 15; y--) {
                        Material m = world.getBlockAt(x, y, z).getType();
                        if (!m.isAir() && !isLeaf(m, biomeConfig) && !isLog(m, biomeConfig) && m.isSolid()) {
                            localY = y;
                            break;
                        }
                    }
                    if (localY != -1) {
                        reconstructedYMap.put(key, localY);
                        coherenceFixed++;
                    }
                }
            }

            // ── ÉTAPE 6 : Stockage FillTarget avec reconstructedTopY ─────────────────
            Map<Long, FillTarget> snapshotHeightmap = new HashMap<>();
            int maxGap = 0;
            double sumGap = 0;
            int colsWithGap = 0;
            int profileCount = 0, noNeighborCount = 0;
            // depthTarget removed — fill now covers full column scan (no depth cap)

            // Clonage de colonne voisine (matériaux)
            for (long key : columnsToScan) {
                Integer rawIdwY = expectedYMap.get(key);
                Integer reconY = reconstructedYMap.get(key);
                Integer groundY = colGroundY.get(key);
                Material groundMat = colGroundMat.getOrDefault(key, Material.DIRT);
                if (rawIdwY == null || reconY == null || groundY == null) continue;

                // Use the reconstructed terrain height (reconY) directly as the ceiling.
                // Previously, effectiveReconY was set to Math.max(reconY, logMinInColVal) to avoid IDW underestimation,
                // but this caused the trunk columns (where logMinInColVal is the trunk base, e.g. Y=135, while ground is Y=131)
                // to fill up to the trunk base with dirt, creating giant dirt pillars.
                int effectiveReconY = reconY;

                int gap = effectiveReconY - groundY;
                if (gap <= 0 || gap > fillDepth) continue;

                int x = (int) (key >> 32);
                int z = (int) key;

                // Chercher un profil voisin pour les matériaux
                Material[] columnProfile = null;
                int rayon = 8 + empreinteRadius;


                outerLoop:
                for (int r = 1; r <= rayon; r++) {
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                            int nx = x + dx;
                            int nz = z + dz;
                            Material topMat = world.getBlockAt(nx, reconY, nz).getType();
                            if (topMat.isAir() || isLog(topMat, biomeConfig) || isLeaf(topMat, biomeConfig)) continue;
                            int neighborTopY = getTopSurfaceY(world, nx, nz, yMax, yMin, biomeConfig);
                            if (neighborTopY != -1 && Math.abs(neighborTopY - reconY) <= 5) {
                                // Profil depuis la vraie surface du voisin (neighborTopY), pas depuis reconY.
                                // Ancienne formule (reconY-1-k) capturait du STONE sous-sol au lieu
                                // du matériau de surface → SNOW_BLOCK/DIRT.
                                int profileLen = gap + 1; // +1 pour inclure le bloc de surface
                                columnProfile = new Material[profileLen];
                                for (int k = 0; k < profileLen; k++) {
                                    columnProfile[k] = world.getBlockAt(nx, neighborTopY - k, nz).getType();
                                }
                                break outerLoop;
                            }
                        }
                    }
                }

                if (columnProfile != null) profileCount++; else noNeighborCount++;

                snapshotHeightmap.put(key, new FillTarget(
                        rawIdwY, effectiveReconY, groundY, groundMat, gap,
                        Collections.emptySet(), columnProfile, snowRatio, dominantMat));


                colsWithGap++;
                if (gap > maxGap) maxGap = gap;
                sumGap += gap;
            }

            if (configManager.isDebug()) {
                double avgTerrainY = terrainHeightMap.isEmpty() ? 0.0 : terrainSumY / terrainHeightMap.size();
                org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "[ROOT FILL] Heightmap: "
                        + terrainHeightMap.size() + " colonnes voisines | aberrants_rejetés=" + aberrantRejected
                        + " | terrainY_moy=" + String.format(Locale.US, "%.1f", avgTerrainY));
                org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "[ROOT FILL] IDW: "
                        + expectedYMap.size() + " colonnes interpolées | dégradé=" + degradedFallbackCount
                        + " | pente_lissée=" + penteLissee + " | cohérence_corrigée=" + coherenceFixed);
                double avgGap = colsWithGap == 0 ? 0.0 : sumGap / colsWithGap;
                org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "[ROOT FILL] Baseline: "
                        + columnsToScan.size() + " colonnes scannées | "
                        + colsWithGap + " avec gap | profilés=" + profileCount
                        + " | sans_voisin=" + noNeighborCount
                        + " | gap_max=" + maxGap + " | gap_moy=" + String.format(Locale.US, "%.1f", avgGap));
            }

            return snapshotHeightmap;
        }
    }

    private boolean isStructuralAttachment(Material material, BiomeConfig biomeConfig) {
        if (material == null) return false;
        String name = material.name();
        if (name.contains("FENCE") || name.contains("SLAB") || name.contains("WALL")) {
            return (biomeConfig != null && biomeConfig.attachments().contains(material));
        }
        return false;
    }

    private boolean isLeaf(Material material, BiomeConfig biomeConfig) {
        if (material == null) return false;
        if (isStructuralAttachment(material, biomeConfig)) return true;
        return (biomeConfig != null && biomeConfig.leafBlocks().contains(material))
                || configManager.getLeafWeights().containsKey(material)
                || material.name().endsWith("_LEAVES");
    }

    private boolean isIgnoreMaterial(Material m, BiomeConfig biomeConfig) {
        if (m == null) return true;
        if (m.isAir() || m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR) return true;
        if (m == Material.SNOW || m == Material.WATER || m == Material.LAVA) return true;
        
        String name = m.name();
        if (name.contains("GRASS") || name.contains("FERN") || name.contains("FLOWER") || name.contains("DANDELION") 
                || name.contains("POPPY") || name.contains("DEAD_BUSH") || name.contains("DAISY") || name.contains("SAPLING") 
                || name.contains("MUSHROOM")) {
            return true;
        }
        
        return isLog(m, biomeConfig) || isLeaf(m, biomeConfig);
    }

    private int getTopSurfaceY(World world, int x, int z, int yMax, int yMin, BiomeConfig biomeConfig) {
        // Skip air, leaves, logs, AND non-solid vegetation (SHORT_GRASS, TALL_GRASS, flowers,
        // saplings, etc.) so that profile[0] is always a solid terrain block.
        // The old code returned SHORT_GRASS/vegetation as "surface" → profile[0] = vegetation
        // → not in fillWhitelist → counted as aberrant_mat.
        for (int y = yMax + 5; y >= yMin - 10; y--) {
            Block b = world.getBlockAt(x, y, z);
            Material m = b.getType();
            if (isIgnoreMaterial(m, biomeConfig)) continue;
            if (!m.isSolid()) continue; // skip non-solid plants/vines/etc.
            return y;
        }
        return -1;
    }

    /**
     * Rebouchage des racines basé sur une heightmap pré-calculée.
     * Ne remplit que les blocs AIR qui étaient des logs coupés, vers le bas
     * jusqu'au niveau du sol naturel.
     */
    public void backfillRoots(World world, Set<BlockPos> logs, BiomeConfig biomeConfig, Map<Long, FillTarget> snapshotHeightmap, UUID jobId) {
        String fillMode = biomeConfig != null && biomeConfig.fillMode() != null 
                ? biomeConfig.fillMode() : configManager.getFillMode();
        boolean backfillEnabled = biomeConfig != null && biomeConfig.rootReplacementEnabled() != null 
                ? biomeConfig.rootReplacementEnabled() : configManager.isBackfillEnabled();
        if (!backfillEnabled) fillMode = "NONE";

        if (fillMode.equalsIgnoreCase("NONE")) {
            return;
        }

        if (fillMode.equalsIgnoreCase("LEGACY")) {
            int fillDepth = biomeConfig != null && biomeConfig.maxRootSearchDepth() != null 
                    ? biomeConfig.maxRootSearchDepth() : configManager.getFillDepth();
            if (fillDepth <= 0) return;

            long startTime = System.currentTimeMillis();
            int filledCount = 0;

            for (Map.Entry<Long, FillTarget> entry : snapshotHeightmap.entrySet()) {
                long key = entry.getKey();
                FillTarget target = entry.getValue();
                int groundY = target.groundY();
                Material originalGroundMat = target.originalGroundMat();
                int logYMin = target.logYMin();

                int x = (int) (key >> 32);
                int z = (int) key;

                int startY = logYMin;
                int stopY = groundY - fillDepth;

                // Descendre de startY vers le bas
                for (int y = startY; y >= stopY; y--) {
                    if (y < world.getMinHeight()) break;
                    Block b = world.getBlockAt(x, y, z);
                    Material m = b.getType();

                    boolean isTreeBlock = isLog(m, biomeConfig) || isLeaf(m, biomeConfig);
                    boolean isEmpty = m.isAir() || isTreeBlock;

                    if (isEmpty) {
                        // Déterminer le matériau de remplissage
                        Material fill = (y == groundY) ? originalGroundMat : Material.DIRT;
                        if (!fill.isSolid()) fill = Material.DIRT;
                        b.setType(fill, false);
                        filledCount++;
                    } else if (!m.isAir() && !isTreeBlock) {
                        // Vrai bloc solide non-arbre → sol atteint → stop
                        break;
                    }
                }
            }

            long durationMs = System.currentTimeMillis() - startTime;
            double avgDepth = snapshotHeightmap.isEmpty() ? 0.0 : (double) filledCount / snapshotHeightmap.size();
            String jobIdStr = jobId != null ? jobId.toString().substring(0, 8) : "unique";
            String logMsg = "[ROOT FILL] Phase 2 fill terminé : jobId=" + jobIdStr 
                    + " | colonnes=" + snapshotHeightmap.size()
                    + " | blocs_remplis=" + filledCount 
                    + " | profondeur_moy=" + String.format(Locale.US, "%.1f", avgDepth) 
                    + " | OK";

            if (!snapshotHeightmap.isEmpty() && avgDepth < 1.5) {
                org.bukkit.Bukkit.getLogger().warning(ConsoleColor.WARN_PREFIX + logMsg);
            } else {
                org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + logMsg);
            }
        } else {
            // ── HOLE_DETECTOR mode ─────────────────────────────────────────────────────
            int fillDepth = biomeConfig != null && biomeConfig.maxRootSearchDepth() != null
                    ? biomeConfig.maxRootSearchDepth() : configManager.getFillDepth();
            if (fillDepth <= 0) return;

            long startTime = System.currentTimeMillis();
            int filledCount = 0;
            int cloneCount = 0;
            int fallbackCount = 0;
            int surfaceOverrideCount = 0;
            int skippedExistingCount = 0;
            int aberrantMatCount = 0;

            int colsWithHoles = 0;
            int depthMin = Integer.MAX_VALUE;
            int depthMax = 0;
            int stoneSurfaceFixed = 0;
            Map<Material, Integer> materialCounts = new HashMap<>();

            // Matériaux naturels autorisés pour le fill (whitelist Étape 6)
            Set<Material> fillWhitelist = new HashSet<>(Arrays.asList(
                    Material.DIRT, Material.GRASS_BLOCK, Material.COARSE_DIRT,
                    Material.ROOTED_DIRT, Material.PODZOL, Material.STONE,
                    Material.GRANITE, Material.DIORITE, Material.ANDESITE,
                    Material.GRAVEL, Material.SAND, Material.RED_SAND,
                    Material.SANDSTONE, Material.RED_SANDSTONE,
                    Material.SNOW_BLOCK, Material.ICE, Material.PACKED_ICE,
                    Material.BLUE_ICE, Material.CLAY,
                    Material.MYCELIUM, Material.MUD, Material.MUDDY_MANGROVE_ROOTS,
                    Material.DEEPSLATE, Material.CALCITE, Material.TUFF
            ));

            // Matériaux de type "terre" pour l'override neige
            Set<Material> dirtTypes = new HashSet<>(Arrays.asList(
                    Material.DIRT, Material.GRASS_BLOCK, Material.COARSE_DIRT,
                    Material.ROOTED_DIRT, Material.PODZOL
            ));

            // Matériaux "rocheux" à ne jamais poser en surface (remplacés par dominantMat)
            Set<Material> rockyTypes = new HashSet<>(Arrays.asList(
                    Material.STONE, Material.GRANITE, Material.DIORITE, Material.ANDESITE,
                    Material.DEEPSLATE, Material.TUFF, Material.CALCITE, Material.GRAVEL,
                    Material.COBBLESTONE, Material.MOSSY_COBBLESTONE
            ));

            for (Map.Entry<Long, FillTarget> entry : snapshotHeightmap.entrySet()) {
                long key = entry.getKey();
                FillTarget target = entry.getValue();
                // Utiliser reconstructedTopY comme plafond de fill (Y lissé + validé)
                int expectedTopY = target.reconstructedTopY();
                int expectedGroundY = target.groundY();
                Material expectedMat = target.originalGroundMat();
                Material[] columnProfile = target.columnProfile();

                int x = (int) (key >> 32);
                int z = (int) key;

                // ── Scan complet colonne : remplir TOUS les blocs AIR/feuille/log
                // de expectedGroundY à expectedTopY (inclus), y compris les cavités
                // souterraines ("gruyère") laissées par les grosses racines custom.
                // L'ancien scan actualTopY s'arrêtait au premier bloc solide rencontré
                // depuis le haut, ratant les cavités en dessous.
                int colFilled = 0;

                if (columnProfile != null) {
                    // ── Cas normal : profil cloné depuis le voisin ────────────────────
                    cloneCount++;

                    // ── Détection de la surface réelle après coupe (profileRef) ──────
                    // Problème : sur terrain pentu (slope=3-5+), l'IDW surestime reconY
                    // de 5-15 blocs. L'ancien index k=effectiveReconY-y donnait donc
                    // profile[10]=STONE pour une racine en surface → mauvais matériau.
                    //
                    // Fix : scanner vers le haut depuis groundY après la coupe pour trouver
                    // le 1er bloc solide non-racine (= terrain naturel réel). Ce Y devient
                    // profileRef. profile[0] atterrit sur la vraie surface, profile[1] juste
                    // en dessous, etc. Les blocs solides existants (terrain au-dessus de la
                    // racine) sont déjà skippés par le check isAir/isLeaf/isLog.
                    //
                    // Cas racine en surface (aucun solide au-dessus) : profileRef=groundY+1
                    // → k=0 exactement sur la position de la racine → GRASS_BLOCK. ✅
                    int profileRef;
                    {
                        int found = -1;
                        int scanLimit = Math.min(expectedTopY + 2, world.getMaxHeight() - 1);
                        for (int sy = expectedGroundY + 1; sy <= scanLimit; sy++) {
                            Material sm = world.getBlockAt(x, sy, z).getType();
                            if (!sm.isAir() && !isLeaf(sm, biomeConfig)
                                    && !isLog(sm, biomeConfig) && sm.isSolid()) {
                                found = sy;
                                break;
                            }
                        }
                        // Aucun terrain solide au-dessus → racine en surface ou terrain plat.
                        // Fallback sur expectedTopY (IDW) pour conserver la bonne stratigraphie :
                        // k=0 à y=expectedTopY (surface IDW), k>0 plus profond → DIRT → STONE.
                        // N.B. groundY+1 causait k<0 pour tous les blocs au-dessus → tout GRASS.
                        profileRef = (found > 0) ? found : expectedTopY;
                    }

                    for (int y = expectedGroundY; y <= expectedTopY; y++) {
                        Block b = world.getBlockAt(x, y, z);
                        Material m = b.getType();
                        if (m.isAir() || isLeaf(m, biomeConfig) || isLog(m, biomeConfig)) {
                            // k=0 à la surface réelle détectée, augmente vers le bas
                            int k = profileRef - y;
                            Material mat;
                            if (k < 0 || k >= columnProfile.length) {
                                // k<0 = au-dessus de la surface (déjà solide, ne devrait pas
                                // arriver ici). k≥length = plus profond que le profil.
                                mat = (k <= 0) ? target.dominantMat() : Material.DIRT;
                                if (mat == null) mat = Material.DIRT;
                            } else {
                                mat = columnProfile[k];
                            }

                            // Étape 6 — Validation matériau (whitelist)
                            if (!fillWhitelist.contains(mat)) {
                                if (configManager.isDebug()) {
                                    org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "[ROOT FILL] Rejected material: " + mat);
                                }
                                mat = (k <= 0) ? target.dominantMat() : Material.DIRT;
                                if (mat == null) mat = Material.DIRT;
                                aberrantMatCount++;
                            }

                            // Surface (k == 0) : override DIRT/roche → dominantMat
                            // En utilisant k==0 plutôt que y==expectedTopY, on s'assure
                            // que l'override s'applique sur la vraie surface (pas l'IDW).
                            if (k == 0) {
                                boolean isSurfaceRock = rockyTypes.contains(mat);
                                boolean isSurfaceDirt = dirtTypes.contains(mat);
                                if (isSurfaceRock || isSurfaceDirt) {
                                    Material dom = target.dominantMat();
                                    if (dom != null && fillWhitelist.contains(dom)) {
                                        if (isSurfaceRock) stoneSurfaceFixed++;
                                        mat = dom;
                                        surfaceOverrideCount++;
                                    }
                                }
                            }

                            b.setType(mat, false);
                            filledCount++;
                            colFilled++;
                            materialCounts.put(mat, materialCounts.getOrDefault(mat, 0) + 1);
                        } else {
                            skippedExistingCount++;
                        }
                    }
                } else {
                    // ── Fallback : aucun voisin trouvé → stratigraphie synthétique ────
                    // Anciennement : DIRT pour toute la profondeur (incorrecte en surface
                    // pour les biomes non-enneigés et sans représentation du sous-sol).
                    // Maintenant :
                    //   k=0 (surface)       → dominantMat (GRASS_BLOCK / SNOW_BLOCK)
                    //   k=1–2 (subsurface)  → DIRT
                    //   k≥3 (souterrain)    → groundMat (STONE ou matériau de base du biome)
                    fallbackCount++;
                    Material fbSurface = target.dominantMat();
                    if (fbSurface == null || !fillWhitelist.contains(fbSurface)) fbSurface = Material.GRASS_BLOCK;
                    Material fbUnderground = target.originalGroundMat();
                    if (fbUnderground == null || !fillWhitelist.contains(fbUnderground)) fbUnderground = Material.STONE;

                    for (int y = expectedGroundY; y <= expectedTopY; y++) {
                        Block b = world.getBlockAt(x, y, z);
                        Material m = b.getType();
                        if (m.isAir() || isLeaf(m, biomeConfig) || isLog(m, biomeConfig)) {
                            int k = expectedTopY - y;
                            Material mat;
                            if (k == 0)      mat = fbSurface;       // surface
                            else if (k <= 2) mat = Material.DIRT;   // 2 couches de terre
                            else             mat = fbUnderground;    // roche/sol profond

                            if (!fillWhitelist.contains(mat)) mat = Material.DIRT;

                            b.setType(mat, false);
                            filledCount++;
                            colFilled++;
                            materialCounts.put(mat, materialCounts.getOrDefault(mat, 0) + 1);
                            surfaceOverrideCount += (k == 0) ? 1 : 0;
                        } else {
                            skippedExistingCount++;
                        }
                    }
                }

                // Comptage colonne si au moins un bloc posé (inclut cavités profondes)
                // NOTE : Guard+1 supprimé — sur terrains à forte pente (slope≈1), le bloc
                // à reconY+1 est naturellement AIR (au-dessus du sommet de colline) et
                // le Guard créait des bosses SNOW_BLOCK flottantes. effectiveReconY =
                // max(reconY, logMinY) couvre déjà le cas de sous-estimation IDW d'un bloc.
                if (colFilled > 0) {
                    colsWithHoles++;
                    depthMin = Math.min(depthMin, colFilled);
                    depthMax = Math.max(depthMax, colFilled);
                }
            }



            // ── ÉTAPE 8 : Lissage de surface (Smoothing sur-terrain) ──
            int smoothingFilledCount = 0;
            Set<Long> checkColumns = new HashSet<>();
            for (long key : snapshotHeightmap.keySet()) {
                checkColumns.add(key);
                int x = (int) (key >> 32);
                int z = (int) key;
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        checkColumns.add(((long) (x + dx) << 32) | ((z + dz) & 0xFFFFFFFFL));
                    }
                }
            }

            // Exécuter 3 passes pour permettre la propagation du lissage (remplissage en cascade)
            for (int pass = 0; pass < 3; pass++) {
                int passFilled = 0;
                for (long key : checkColumns) {
                    int x = (int) (key >> 32);
                    int z = (int) key;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

                    int surfaceY = world.getHighestBlockYAt(x, z);
                    for (int y = surfaceY - 1; y <= surfaceY + 2; y++) {
                        if (y <= world.getMinHeight() || y >= world.getMaxHeight()) continue;
                        Block b = world.getBlockAt(x, y, z);
                        if (!b.getType().isAir()) continue; // Seulement si c'est du vide

                        Material mN = world.getBlockAt(x, y, z - 1).getType();
                        Material mS = world.getBlockAt(x, y, z + 1).getType();
                        Material mE = world.getBlockAt(x + 1, y, z).getType();
                        Material mW = world.getBlockAt(x - 1, y, z).getType();

                        int solidCount = 0;
                        Map<Material, Integer> counts = new HashMap<>();

                        if (mN.isSolid() && !isLog(mN, biomeConfig) && !isLeaf(mN, biomeConfig)) {
                            solidCount++;
                            counts.put(mN, counts.getOrDefault(mN, 0) + 1);
                        }
                        if (mS.isSolid() && !isLog(mS, biomeConfig) && !isLeaf(mS, biomeConfig)) {
                            solidCount++;
                            counts.put(mS, counts.getOrDefault(mS, 0) + 1);
                        }
                        if (mE.isSolid() && !isLog(mE, biomeConfig) && !isLeaf(mE, biomeConfig)) {
                            solidCount++;
                            counts.put(mE, counts.getOrDefault(mE, 0) + 1);
                        }
                        if (mW.isSolid() && !isLog(mW, biomeConfig) && !isLeaf(mW, biomeConfig)) {
                            solidCount++;
                            counts.put(mW, counts.getOrDefault(mW, 0) + 1);
                        }

                        // Si entouré par 4 voisins horizontaux solides/terrain
                        if (solidCount >= 4) {
                            Material majorityMat = null;
                            int maxCount = 0;
                            for (Map.Entry<Material, Integer> e : counts.entrySet()) {
                                if (e.getValue() > maxCount) {
                                    maxCount = e.getValue();
                                    majorityMat = e.getKey();
                                }
                            }

                            // Si le matériau majoritaire est un matériau de terrain valide (dans la whitelist)
                            if (majorityMat != null && fillWhitelist.contains(majorityMat)) {
                                b.setType(majorityMat, false);
                                filledCount++;
                                passFilled++;
                                materialCounts.put(majorityMat, materialCounts.getOrDefault(majorityMat, 0) + 1);
                            }
                        }
                    }
                }
                smoothingFilledCount += passFilled;
                if (passFilled == 0) break; // Arrêt anticipé si aucun changement
            }

            if (configManager.isDebug() && smoothingFilledCount > 0) {
                org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "[ROOT FILL] Smoothing: " + smoothingFilledCount + " blocs de vide comblés par majorité.");
            }

            // ── ÉTAPE 9 : Lissage vertical (Y-Axis Y-Smoothing) ──
            int ySmoothingFilledCount = 0;
            Set<BlockPos> yCheckPositions = new HashSet<>();
            for (BlockPos logPos : logs) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            yCheckPositions.add(logPos.add(dx, dy, dz));
                        }
                    }
                }
            }

            for (BlockPos pos : yCheckPositions) {
                int x = pos.x();
                int y = pos.y();
                int z = pos.z();
                if (y <= world.getMinHeight() || y >= world.getMaxHeight() - 1) continue;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

                Block b = world.getBlockAt(x, y, z);
                if (!b.getType().isAir()) continue;

                Block above = world.getBlockAt(x, y + 1, z);
                Block below = world.getBlockAt(x, y - 1, z);

                Material matAbove = above.getType();
                Material matBelow = below.getType();

                boolean isAboveSolid = matAbove.isSolid() && !isLog(matAbove, biomeConfig) && !isLeaf(matAbove, biomeConfig);
                boolean isBelowValid = matBelow.isSolid() && !isLog(matBelow, biomeConfig) && !isLeaf(matBelow, biomeConfig) && fillWhitelist.contains(matBelow);

                if (isAboveSolid && isBelowValid) {
                    b.setType(matBelow, false);
                    filledCount++;
                    ySmoothingFilledCount++;
                    materialCounts.put(matBelow, materialCounts.getOrDefault(matBelow, 0) + 1);
                }
            }

            if (configManager.isDebug() && ySmoothingFilledCount > 0) {
                org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "[ROOT FILL] Lissage Vertical (Y): " + ySmoothingFilledCount + " blocs de vide comblés.");
            }

            if (configManager.isDebug()) {
                StringBuilder matSummary = new StringBuilder();
                for (Map.Entry<Material, Integer> e : materialCounts.entrySet()) {
                    if (matSummary.length() > 0) matSummary.append(" ");
                    matSummary.append(e.getKey().name()).append("×").append(e.getValue());
                }
                double avgDepth = colsWithHoles == 0 ? 0.0 : (double) filledCount / colsWithHoles;
                String depthRange = colsWithHoles == 0 ? "n/a" 
                        : depthMin + "–" + depthMax;
                org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "[ROOT FILL] Fill: "
                        + filledCount + " blocs posés"
                        + " | cols_trou=" + colsWithHoles
                        + " | clone=" + cloneCount + " fallback=" + fallbackCount
                        + " | depth_moy=" + String.format(Locale.US, "%.1f", avgDepth)
                        + " | depth_range=" + depthRange
                        + " | surface_override=" + surfaceOverrideCount
                        + " | stone_surface_fixed=" + stoneSurfaceFixed
                        + " | skipped_existing=" + skippedExistingCount
                        + " | aberrant_mat=" + aberrantMatCount
                        + " | dominant_mat=" + (snapshotHeightmap.isEmpty() ? "n/a" : snapshotHeightmap.values().iterator().next().dominantMat())
                        + " | matériaux: " + (matSummary.length() == 0 ? "aucun" : matSummary.toString())
                        + " | OK");
            }
        }
    }




    /**
     * Surcharge rétro-compatible : construit la heightmap à la volée (post-coupe).
     * Moins précis car les logs sont déjà AIR, mais fonctionne comme fallback.
     */
    public void backfillRoots(World world, Set<BlockPos> logs, BiomeConfig biomeConfig) {
        boolean backfillEnabled = biomeConfig != null && biomeConfig.rootReplacementEnabled() != null 
                ? biomeConfig.rootReplacementEnabled() : configManager.isBackfillEnabled();
        if (!backfillEnabled) return;

        Map<Long, FillTarget> snapshotHeightmap = takeHeightmapSnapshot(world, logs, biomeConfig);
        backfillRoots(world, logs, biomeConfig, snapshotHeightmap, (UUID) null);
    }



    /**
     * Nettoyage des blocs flottants (snow, attachments, fences) post-abattage.
     */
    public void cleanupFloatingBlocks(World world, Set<BlockPos> logs, Set<BlockPos> leaves, BiomeConfig biomeConfig) {
        Set<BlockPos> felledPositions = new HashSet<>(logs);
        felledPositions.addAll(leaves);

        Set<BlockPos> candidates = getBlocksWithinDistance(felledPositions, 2);

        for (BlockPos pos : candidates) {
            org.bukkit.block.Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
            Material mat = block.getType();
            if (mat == Material.AIR) continue;

            boolean isCleanupTarget = configManager.getCleanupFloatingBlocks().contains(mat)
                    || (biomeConfig != null && biomeConfig.attachments().contains(mat));
            if (!isCleanupTarget) continue;

            if (isFloating(world, pos, biomeConfig)) {
                block.breakNaturally();
                postFellPhysicsUpdate(block);
            }
        }
    }

    private boolean isFloating(World world, BlockPos pos, BiomeConfig biomeConfig) {
        for (int[] dir : DIRS_6) {
            BlockPos neighbor = pos.add(dir[0], dir[1], dir[2]);
            Material mat = world.getBlockAt(neighbor.x(), neighbor.y(), neighbor.z()).getType();
            if (mat.isSolid() && !isTreeOrFloatingMaterial(mat, biomeConfig)) {
                return false; // Trouvé un support solide!
            }
        }
        return true;
    }

    private boolean isTreeOrFloatingMaterial(Material mat, BiomeConfig biomeConfig) {
        if (mat == null) return false;
        if (configManager.getCleanupFloatingBlocks().contains(mat)) return true;
        if (biomeConfig != null && biomeConfig.attachments().contains(mat)) return true;
        if (configManager.getLogWeights().containsKey(mat) || configManager.getLeafWeights().containsKey(mat)) return true;
        if (biomeConfig != null && (biomeConfig.logBlocks().contains(mat) || biomeConfig.leafBlocks().contains(mat))) return true;
        return false;
    }

    private void findOrphanLeaves(World world, Set<BlockPos> logs, Set<BlockPos> leaves, int radius, BiomeConfig biomeConfig) {
        Set<BlockPos> candidates = new HashSet<>();
        // Détecter autour des logs coupés
        for (BlockPos logPos : logs) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        BlockPos checkPos = logPos.add(dx, dy, dz);
                        if (logs.contains(checkPos) || leaves.contains(checkPos)) continue;
                        candidates.add(checkPos);
                    }
                }
            }
        }
        // Détecter aussi autour des feuilles d'origine avec un petit rayon (ex: 2) pour les blocs adjacents suspendus
        for (BlockPos leafPos : leaves) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        BlockPos checkPos = leafPos.add(dx, dy, dz);
                        if (logs.contains(checkPos) || leaves.contains(checkPos)) continue;
                        candidates.add(checkPos);
                    }
                }
            }
        }

        for (BlockPos pos : candidates) {
            Material mat = world.getBlockAt(pos.x(), pos.y(), pos.z()).getType();
            if (isLeafOrAttachment(mat, biomeConfig)) {
                boolean nearLivingLog = false;
                outerLoop:
                for (int dx = -4; dx <= 4; dx++) {
                    for (int dy = -4; dy <= 4; dy++) {
                        for (int dz = -4; dz <= 4; dz++) {
                            BlockPos logCheck = pos.add(dx, dy, dz);
                            if (logs.contains(logCheck)) continue;
                            Material checkMat = world.getBlockAt(logCheck.x(), logCheck.y(), logCheck.z()).getType();
                            if (configManager.getLogWeights().containsKey(checkMat) || (biomeConfig != null && biomeConfig.logBlocks().contains(checkMat))) {
                                nearLivingLog = true;
                                break outerLoop;
                            }
                        }
                    }
                }

                if (!nearLivingLog) {
                    leaves.add(pos);
                }
            }
        }
    }

    private boolean isLeafOrAttachment(Material material, BiomeConfig biomeConfig) {
        if (material == null) return false;
        return (biomeConfig != null && biomeConfig.leafBlocks().contains(material))
                || (biomeConfig != null && biomeConfig.attachments().contains(material))
                || configManager.getLeafWeights().containsKey(material);
    }

    private boolean isGroundMaterial(Material material) {
        if (material == null || material.isAir()) return false;
        if (!material.isSolid()) return false;
        String name = material.name();
        return !name.contains("LOG") && !name.contains("WOOD") && !name.contains("LEAVES")
                && !name.contains("STEM") && !name.contains("HYPHAE");
    }



    public void updateLeavesPersistence(World world, Set<BlockPos> logs, Set<BlockPos> leaves, BiomeConfig biomeConfig) {
        updateLeavesPersistence(world, logs, leaves, biomeConfig, null, null);
    }

    public void updateLeavesPersistence(World world, Set<BlockPos> logs, Set<BlockPos> leaves, BiomeConfig biomeConfig,
                                        Map<BlockPos, Boolean> resultCache, Map<BlockPos, Material> blockCache) {
        Set<BlockPos> leavesToUpdate = collectLeavesForPersistenceUpdate(world, logs, leaves, biomeConfig, resultCache, blockCache);
        for (BlockPos pos : leavesToUpdate) {
            org.bukkit.block.Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
            org.bukkit.block.data.BlockData data = block.getBlockData();
            if (data instanceof Leaves leavesData) {
                if (leavesData.isPersistent()) {
                    leavesData.setPersistent(false);
                    leavesData.setDistance(7);
                    block.setBlockData(leavesData, true);
                }
            }
        }
    }

    public boolean isNearForeignLog(World world, BlockPos pos, Set<BlockPos> treeLogs, int radius, BiomeConfig biomeConfig) {
        return isNearForeignLog(world, pos, treeLogs, radius, biomeConfig, null, null);
    }

    public boolean isNearForeignLog(World world, BlockPos pos, Set<BlockPos> treeLogs, int radius, BiomeConfig biomeConfig,
                                    Map<BlockPos, Boolean> resultCache, Map<BlockPos, Material> blockCache) {
        if (resultCache != null && resultCache.containsKey(pos)) {
            return resultCache.get(pos);
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    int nx = pos.x() + dx;
                    int ny = pos.y() + dy;
                    int nz = pos.z() + dz;
                    BlockPos neighbor = new BlockPos(nx, ny, nz);
                    if (treeLogs.contains(neighbor)) continue;
                    Material mat;
                    if (blockCache != null) {
                        mat = blockCache.get(neighbor);
                        if (mat == null) {
                            mat = world.getBlockAt(nx, ny, nz).getType();
                            blockCache.put(neighbor, mat);
                        }
                    } else {
                        mat = world.getBlockAt(nx, ny, nz).getType();
                    }
                    if (configManager.getLogWeights().containsKey(mat) || (biomeConfig != null && biomeConfig.logBlocks().contains(mat))) {
                        if (resultCache != null) resultCache.put(pos, true);
                        return true;
                    }
                }
            }
        }
        if (resultCache != null) resultCache.put(pos, false);
        return false;
    }

    /**
     * Nettoyage des bûches orphelines (petits amas de bûches flottantes) post-abattage.
     * Cette méthode parcourt tout le volume 3D [tronc ± isolated-logs-radius] en X, Y et Z.
     */
    public void cleanupIsolatedLogs(World world, Set<BlockPos> logs, Set<BlockPos> leaves, BiomeConfig biomeConfig) {
        int radius = biomeConfig != null && biomeConfig.isolatedLogsRadius() != null 
                ? biomeConfig.isolatedLogsRadius() : configManager.getIsolatedLogsRadius();
        int maxGroupSize = biomeConfig != null && biomeConfig.isolatedLogMax() != null 
                ? biomeConfig.isolatedLogMax() : configManager.getIsolatedLogMax();

        // Si le rayon ou la taille max est <= 0, on désactive le module
        if (radius <= 0 || maxGroupSize <= 0) return;

        Set<BlockPos> felledBlocks = new HashSet<>(logs);
        felledBlocks.addAll(leaves);

        Set<BlockPos> candidateLogs = new HashSet<>();
        Set<BlockPos> candidates = getBlocksWithinDistance(felledBlocks, radius);

        for (BlockPos checkPos : candidates) {
            Material mat = world.getBlockAt(checkPos.x(), checkPos.y(), checkPos.z()).getType();
            if (isLog(mat, biomeConfig)) {
                candidateLogs.add(checkPos);
            }
        }

        if (candidateLogs.isEmpty()) return;

        // 2. Repérer les groupes isolés (non reliés au sol, sans canopée significative, taille <= maxGroupSize)
        Set<BlockPos> processed = new HashSet<>();
        List<Set<BlockPos>> orphanGroups = new ArrayList<>();

        for (BlockPos start : candidateLogs) {
            if (processed.contains(start)) continue;

            Queue<BlockPos> queue = new LinkedList<>();
            Set<BlockPos> component = new HashSet<>();
            boolean connectedToGround = false;

            queue.add(start);
            component.add(start);

            while (!queue.isEmpty()) {
                BlockPos curr = queue.poll();

                // Vérifier si le bloc en dessous est un sol solide
                BlockPos below = curr.add(0, -1, 0);
                Material belowMat = world.getBlockAt(below.x(), below.y(), below.z()).getType();
                if (isGroundMaterial(belowMat)) {
                    connectedToGround = true;
                }

                // Expansion 26-way sur les bûches
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            BlockPos next = curr.add(dx, dy, dz);
                            if (component.contains(next)) continue;

                            Material mat = world.getBlockAt(next.x(), next.y(), next.z()).getType();
                            if (isLog(mat, biomeConfig)) {
                                component.add(next);
                                queue.add(next);
                            }
                        }
                    }
                }
            }

            processed.addAll(component);

            // Un groupe est orphelin si :
            // - il a une taille ≤ maxGroupSize,
            // - il n'est pas connecté à un tronc enraciné au sol,
            // - il n'a plus de canopée significative au-dessus (gros paquet de feuilles reliées).
            if (component.size() <= maxGroupSize && !connectedToGround) {
                boolean hasCanopy = hasSignificantCanopy(world, component, biomeConfig);
                if (!hasCanopy) {
                    orphanGroups.add(component);
                }
            }
        }

        // 3. Casser les bûches orphelines
        if (!orphanGroups.isEmpty() && configManager.isDebug()) {
            org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Found " + orphanGroups.size() + " orphan log groups to cleanup.");
        }

        for (Set<BlockPos> group : orphanGroups) {
            for (BlockPos pos : group) {
                org.bukkit.block.Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
                Material mat = block.getType();
                if (mat != Material.AIR) {
                    cleanBlockAbove(world, pos);
                    if (isLog(mat, biomeConfig)) {
                        // Bûches orphelines : drop normal
                        fellBlock(block, Material.AIR, true);
                        if (mat.isItem()) {
                            Location loc = new Location(world, pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5);
                            try {
                                world.dropItemNaturally(loc, new ItemStack(mat, 1));
                            } catch (Exception e) { /* ignore */ }
                        }
                    } else {
                        // Feuilles / attachments orphelins
                        dropLeaf(block);
                        block.setType(Material.AIR, false);
                        postFellPhysicsUpdate(block);
                    }
                }
            }
        }
    }

    private boolean hasSignificantCanopy(World world, Set<BlockPos> logGroup, BiomeConfig biomeConfig) {
        // 1. Trouver les feuilles directement adjacentes au groupe de bûches
        Set<BlockPos> startLeaves = new HashSet<>();
        for (BlockPos logPos : logGroup) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos neighbor = logPos.add(dx, dy, dz);
                        if (logGroup.contains(neighbor)) continue;
                        Material mat = world.getBlockAt(neighbor.x(), neighbor.y(), neighbor.z()).getType();
                        if (isLeafOrAttachment(mat, biomeConfig)) {
                            startLeaves.add(neighbor);
                        }
                    }
                }
            }
        }

        if (startLeaves.isEmpty()) {
            return false;
        }

        // 2. BFS sur les feuilles pour mesurer la taille de la canopée connectée
        Queue<BlockPos> queue = new LinkedList<>(startLeaves);
        Set<BlockPos> visitedLeaves = new HashSet<>(startLeaves);
        
        // Si le biome minLeafLike est configuré et > 5, on l'utilise comme seuil, sinon par défaut 15
        int maxCanopySize = (biomeConfig != null && biomeConfig.minLeafLike() > 5) 
                ? biomeConfig.minLeafLike() : 15;

        while (!queue.isEmpty()) {
            BlockPos curr = queue.poll();

            if (visitedLeaves.size() > maxCanopySize) {
                return true; // Trouvé un gros paquet de feuilles reliées !
            }

            // Expansion 26-way pour bien suivre toutes les branches de feuilles connectées
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos neighbor = curr.add(dx, dy, dz);
                        if (visitedLeaves.contains(neighbor)) continue;

                        Material mat = world.getBlockAt(neighbor.x(), neighbor.y(), neighbor.z()).getType();
                        if (isLeafOrAttachment(mat, biomeConfig)) {
                            visitedLeaves.add(neighbor);
                            queue.add(neighbor);
                        }
                    }
                }
            }
        }

        return visitedLeaves.size() > maxCanopySize;
    }

    public boolean isLog(Material material, BiomeConfig biomeConfig) {
        if (material == null) return false;
        return (biomeConfig != null && biomeConfig.logBlocks().contains(material))
                || configManager.getLogWeights().containsKey(material);
    }

    public void postFellPhysicsUpdate(org.bukkit.block.Block block) {
        if (block == null) return;
        org.bukkit.block.Block blockAbove = block.getRelative(org.bukkit.block.BlockFace.UP);
        Material aboveMat = blockAbove.getType();
        if (aboveMat.isAir()) return;

        boolean isGravityBlock = aboveMat == Material.SAND
                || aboveMat == Material.RED_SAND
                || aboveMat == Material.GRAVEL
                || aboveMat == Material.SUSPICIOUS_SAND
                || aboveMat == Material.SUSPICIOUS_GRAVEL
                || aboveMat.name().endsWith("_CONCRETE_POWDER");

        if (isGroundPlant(aboveMat)) {
            // C'est une plante au sol, et le bloc en dessous est maintenant de l'air/eau,
            // donc elle n'a plus de support. On la détruit proprement avec drop.
            blockAbove.breakNaturally();
        } else if (isGravityBlock) {
            // C'est un bloc soumis à la gravité, on déclenche sa chute
            blockAbove.setType(aboveMat, true);
        } else if (!aboveMat.isSolid()) {
            // Autre bloc non-solide (ex: torches, boutons, neiges)
            blockAbove.setType(aboveMat, true);
        }
    }

    private boolean isGroundPlant(Material m) {
        if (m == null || m.isAir()) return false;
        String name = m.name();
        return name.contains("GRASS") || name.contains("FERN") || name.contains("FLOWER")
                || name.contains("DANDELION") || name.contains("POPPY") || name.contains("TULIP")
                || name.contains("DAISY") || name.contains("ALLIUM") || name.contains("ORCHID")
                || name.contains("BLUET") || name.contains("ROSE") || name.contains("PEONY")
                || name.contains("LILAC") || name.contains("SUNFLOWER") || name.contains("MUSHROOM")
                || name.contains("SAPLING") || name.contains("ROOTS") || name.contains("SPROUTS")
                || name.contains("PETALS") || name.contains("AZALEA") || name.contains("CARPET")
                || name.contains("BAMBOO") || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS
                || m == Material.KELP || m == Material.KELP_PLANT || m == Material.SUGAR_CANE;
    }


    void cleanBlockAbove(World world, BlockPos pos) {
        int ax = pos.x();
        int ay = pos.y() + 1;
        int az = pos.z();
        while (ay < world.getMaxHeight()) {
            Material mat = world.getBlockAt(ax, ay, az).getType();
            if (configManager.getCleanupFloatingBlocks().contains(mat)) {
                world.getBlockAt(ax, ay, az).setType(Material.AIR, false);
                ay++;
            } else {
                break;
            }
        }
    }

    /**
     * Identifie les blocs de la canopée et les bûches orphelines à supprimer dans la bounding box.
     */
    public Set<BlockPos> identifyCanopyCleanupBlocks(World world, Set<BlockPos> felledLogs, Set<BlockPos> felledLeaves, BiomeConfig biomeConfig) {
        int padding = biomeConfig != null && biomeConfig.canopyCleanupPadding() != null
                ? biomeConfig.canopyCleanupPadding() : configManager.getCanopyCleanupPadding();
        if (padding < 0) padding = 6;

        Set<BlockPos> felledAll = new HashSet<>(felledLogs);
        felledAll.addAll(felledLeaves);

        if (felledAll.isEmpty()) return Collections.emptySet();

        // 1. Calculer la bounding box
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (BlockPos pos : felledAll) {
            minX = Math.min(minX, pos.x());
            maxX = Math.max(maxX, pos.x());
            minY = Math.min(minY, pos.y());
            maxY = Math.max(maxY, pos.y());
            minZ = Math.min(minZ, pos.z());
            maxZ = Math.max(maxZ, pos.z());
        }

        // 2. Définir la zone avec padding
        int startX = minX - padding;
        int endX = maxX + padding;
        int startY = Math.max(world.getMinHeight(), minY - padding);
        int endY = Math.min(world.getMaxHeight() - 1, maxY + padding);
        int startZ = minZ - padding;
        int endZ = maxZ + padding;

        Set<BlockPos> blocksToCleanup = new HashSet<>();
        Set<BlockPos> processedLogs = new HashSet<>();

        // 3. Scanner la zone
        for (int x = startX; x <= endX; x++) {
            for (int z = startZ; z <= endZ; z++) {
                for (int y = startY; y <= endY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (felledAll.contains(pos)) continue;

                    Material mat = world.getBlockAt(x, y, z).getType();
                    if (mat == Material.AIR) continue;
                    if (mat == Material.SNOW || mat == Material.SNOW_BLOCK || mat == Material.POWDER_SNOW) {
                        continue;
                    }

                    // Si c'est une feuille / accessoire
                    if (isLeafOrAttachment(mat, biomeConfig)) {
                        // Vérifier si elle est connectée à un tronc enraciné
                        if (!isLeafConnectedToRootedTrunk(world, pos, biomeConfig, felledAll)) {
                            blocksToCleanup.add(pos);
                        }
                    }
                    // Si c'est une bûche
                    else if (isLog(mat, biomeConfig)) {
                        if (processedLogs.contains(pos)) continue;

                        // Trouver tout le groupe connecté de bûches
                        Set<BlockPos> component = getConnectedLogGroup(world, pos, biomeConfig, felledAll);
                        processedLogs.addAll(component);

                        int maxGroupSize = biomeConfig != null && biomeConfig.isolatedLogMax() != null
                                ? biomeConfig.isolatedLogMax() : configManager.getIsolatedLogMax();

                        if (component.size() <= maxGroupSize) {
                            // Vérifier si connecté au sol
                            boolean connectedToGround = false;
                            for (BlockPos logBlock : component) {
                                BlockPos below = logBlock.add(0, -1, 0);
                                if (!felledAll.contains(below)) {
                                    Material belowMat = world.getBlockAt(below.x(), below.y(), below.z()).getType();
                                    if (isGroundMaterial(belowMat)) {
                                        connectedToGround = true;
                                        break;
                                    }
                                }
                            }

                            if (!connectedToGround) {
                                blocksToCleanup.addAll(component);
                            }
                        }
                    }
                }
            }
        }

        int leavesCount = 0;
        int logsCount = 0;
        for (BlockPos pos : blocksToCleanup) {
            Material mat = world.getBlockAt(pos.x(), pos.y(), pos.z()).getType();
            if (isLog(mat, biomeConfig)) {
                logsCount++;
            } else {
                leavesCount++;
            }
        }
        if (configManager.isDebug()) {
            org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Canopy cleanup → bounding box: [(" 
                + minX + "," + minY + "," + minZ + ") → (" + maxX + "," + maxY + "," + maxZ + ")]"
                + " | feuilles supprimées: " + leavesCount + " | logs orphelins supprimés: " + logsCount);
        }

        return blocksToCleanup;
    }

    private boolean isLeafConnectedToRootedTrunk(World world, BlockPos leafStart, BiomeConfig biomeConfig, Set<BlockPos> ignoredBlocks) {
        Queue<BlockPos> queue = new LinkedList<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(leafStart);
        visited.add(leafStart);
        
        int maxLeafSearch = 100; // limit leaf search to avoid lag

        while (!queue.isEmpty()) {
            BlockPos curr = queue.poll();
            
            // Check if curr is adjacent to any log block that is connected to ground
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos neighbor = curr.add(dx, dy, dz);
                        if (ignoredBlocks.contains(neighbor)) continue;
                        Material mat = world.getBlockAt(neighbor.x(), neighbor.y(), neighbor.z()).getType();
                        if (isLog(mat, biomeConfig)) {
                            if (isLogConnectedToGround(world, neighbor, biomeConfig, ignoredBlocks)) {
                                return true;
                            }
                        }
                    }
                }
            }

            if (visited.size() > maxLeafSearch) {
                return true;
            }

            boolean diagLeaves = biomeConfig != null && biomeConfig.allowDiagonalLeaves() != null
                    ? biomeConfig.allowDiagonalLeaves() : configManager.isAllowDiagonalLeaves();
            int[][] leafNeighbors = diagLeaves ? DIRS_26 : DIRS_6;

            for (int[] dir : leafNeighbors) {
                BlockPos neighbor = curr.add(dir[0], dir[1], dir[2]);
                if (ignoredBlocks.contains(neighbor)) continue;
                if (visited.contains(neighbor)) continue;
                Material mat = world.getBlockAt(neighbor.x(), neighbor.y(), neighbor.z()).getType();
                if (isLeafOrAttachment(mat, biomeConfig)) {
                    visited.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }
        return false;
    }

    private boolean isLogConnectedToGround(World world, BlockPos logStart, BiomeConfig biomeConfig, Set<BlockPos> ignoredBlocks) {
        Queue<BlockPos> queue = new LinkedList<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(logStart);
        visited.add(logStart);

        int maxLogSearch = 50;

        while (!queue.isEmpty()) {
            BlockPos curr = queue.poll();

            BlockPos below = curr.add(0, -1, 0);
            if (!ignoredBlocks.contains(below)) {
                Material belowMat = world.getBlockAt(below.x(), below.y(), below.z()).getType();
                if (isGroundMaterial(belowMat)) {
                    return true;
                }
            }

            if (visited.size() > maxLogSearch) {
                return true;
            }

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos next = curr.add(dx, dy, dz);
                        if (ignoredBlocks.contains(next)) continue;
                        if (visited.contains(next)) continue;
                        Material mat = world.getBlockAt(next.x(), next.y(), next.z()).getType();
                        if (isLog(mat, biomeConfig)) {
                            visited.add(next);
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return false;
    }

    private Set<BlockPos> getConnectedLogGroup(World world, BlockPos start, BiomeConfig biomeConfig, Set<BlockPos> ignoredBlocks) {
        Queue<BlockPos> queue = new LinkedList<>();
        Set<BlockPos> component = new HashSet<>();
        queue.add(start);
        component.add(start);

        int maxGroupSize = (biomeConfig != null && biomeConfig.isolatedLogMax() != null)
                ? biomeConfig.isolatedLogMax() : configManager.getIsolatedLogMax();
        if (maxGroupSize <= 0) maxGroupSize = 4;

        while (!queue.isEmpty()) {
            BlockPos curr = queue.poll();

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos next = curr.add(dx, dy, dz);
                        if (ignoredBlocks.contains(next)) continue;
                        if (component.contains(next)) continue;

                        Material mat = world.getBlockAt(next.x(), next.y(), next.z()).getType();
                        if (isLog(mat, biomeConfig)) {
                            component.add(next);
                            queue.add(next);
                        }
                    }
                }
            }

            if (component.size() > maxGroupSize * 2) {
                break;
            }
        }
        return component;
    }

    public Set<BlockPos> collectLeavesForPersistenceUpdate(World world, Set<BlockPos> logs, Set<BlockPos> leaves, BiomeConfig biomeConfig) {
        return collectLeavesForPersistenceUpdate(world, logs, leaves, biomeConfig, null, null);
    }

    public Set<BlockPos> collectLeavesForPersistenceUpdate(World world, Set<BlockPos> logs, Set<BlockPos> leaves, BiomeConfig biomeConfig,
                                                           Map<BlockPos, Boolean> resultCache, Map<BlockPos, Material> blockCache) {
        Set<BlockPos> result = new HashSet<>();
        Queue<BlockPos> queue = new LinkedList<>(logs);
        queue.addAll(leaves);
        Set<BlockPos> visited = new HashSet<>(logs);
        visited.addAll(leaves);

        int decayRangeXZ = biomeConfig != null && biomeConfig.leafDecayRangeXZ() != null
                ? biomeConfig.leafDecayRangeXZ() : configManager.getLeafDecayRangeXZ();
        int decayRangeY = biomeConfig != null && biomeConfig.leafDecayRangeY() != null
                ? biomeConfig.leafDecayRangeY() : configManager.getLeafDecayRangeY();

        int scalingLogs = biomeConfig != null && biomeConfig.leafDecayScalingLogs() != null
                ? biomeConfig.leafDecayScalingLogs() : configManager.getLeafDecayScalingLogs();
        int scalingXz = biomeConfig != null && biomeConfig.leafDecayScalingXzBonus() != null
                ? biomeConfig.leafDecayScalingXzBonus() : configManager.getLeafDecayScalingXzBonus();
        int scalingY = biomeConfig != null && biomeConfig.leafDecayScalingYBonus() != null
                ? biomeConfig.leafDecayScalingYBonus() : configManager.getLeafDecayScalingYBonus();

        int logCount = logs.size();
        if (scalingLogs > 0) {
            int multiplier = logCount / scalingLogs;
            int bonusXZ = multiplier * scalingXz;
            int bonusY = multiplier * scalingY;
            if (bonusXZ > 0 || bonusY > 0) {
                decayRangeXZ += bonusXZ;
                decayRangeY += bonusY;
                if (configManager.isDebug()) {
                    org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX 
                            + "[DECAY] Grand arbre detecte (" + logCount + " buches) -> augmentation du rayon de decay de +" 
                            + bonusXZ + " XZ et +" + bonusY + " Y (nouveau rayon: " + decayRangeXZ + " XZ, " + decayRangeY + " Y)");
                }
            }
        }

        boolean diagLeaves = biomeConfig != null && biomeConfig.allowDiagonalLeaves() != null
                ? biomeConfig.allowDiagonalLeaves() : configManager.isAllowDiagonalLeaves();
        int[][] leafNeighbors = diagLeaves ? DIRS_26 : DIRS_6;

        Map<BlockPos, int[]> distances = new HashMap<>();
        for (BlockPos pos : visited) {
            distances.put(pos, new int[]{0, 0});
        }

        while (!queue.isEmpty()) {
            BlockPos curr = queue.poll();
            int[] dist = distances.get(curr);
            if (dist == null) continue;

            for (int[] dir : leafNeighbors) {
                BlockPos neighbor = curr.add(dir[0], dir[1], dir[2]);
                if (visited.contains(neighbor)) continue;

                int nextXZ = dist[0] + Math.abs(dir[0]) + Math.abs(dir[2]);
                int nextY = dist[1] + Math.abs(dir[1]);

                if (nextXZ > decayRangeXZ || nextY > decayRangeY) continue;

                visited.add(neighbor);
                distances.put(neighbor, new int[]{nextXZ, nextY});

                Material mat = world.getBlockAt(neighbor.x(), neighbor.y(), neighbor.z()).getType();
                if (isLeafOrAttachment(mat, biomeConfig)) {
                    int beltRadius = biomeConfig != null ? biomeConfig.protectionBeltRadius() : 4;
                    if (isNearForeignLog(world, neighbor, logs, beltRadius, biomeConfig, resultCache, blockCache)) {
                        continue;
                    }
                    result.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }
        return result;
    }

    private Set<BlockPos> getBlocksWithinDistance(Set<BlockPos> startBlocks, int radius) {
        Set<BlockPos> visited = new HashSet<>(startBlocks);
        Set<BlockPos> currentLayer = new HashSet<>(startBlocks);

        for (int step = 0; step < radius; step++) {
            Set<BlockPos> nextLayer = new HashSet<>();
            for (BlockPos pos : currentLayer) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            BlockPos neighbor = pos.add(dx, dy, dz);
                            if (visited.add(neighbor)) {
                                nextLayer.add(neighbor);
                            }
                        }
                    }
                }
            }
            currentLayer = nextLayer;
            if (currentLayer.isEmpty()) break;
        }

        Set<BlockPos> result = new HashSet<>(visited);
        result.removeAll(startBlocks);
        return result;
    }

    public void sendFellCompletionMessage(Player player, int logsCount, int leavesCount, String biomeName, double durationSec) {
        if (player == null || !player.isOnline()) return;
        boolean godmode = plugin.getTreeManager().isPlayerGodMode(player.getUniqueId());
        
        String prefix = godmode ? "§c[GODMODE]§r" : "";
        String msg = prefix + "§a✔ Arbre abattu : §f" + logsCount + " bûches §7+ §f" + leavesCount + " feuilles\n§7(§e" + biomeName + "§7 | §b" + String.format(Locale.US, "%.1f", durationSec) + "s§7)";
        player.sendMessage(msg);
    }

    public void dropLeaf(org.bukkit.block.Block block) {
        dropLeaf(block, null);
    }

    public void dropLeaf(org.bukkit.block.Block block, Player player) {
        if (!isLeafMaterial(block.getType())) return;

        if (configManager.isVerboseDebug()) {
            org.bukkit.Bukkit.getLogger().info("[WildTimber] [LEAF DROP DEBUG] dropLeaf appelé pour " + block.getType());
        }

        if (!configManager.isLeafDropsEnabled()) return;
        Material leafMat = block.getType();
        Location loc = block.getLocation().add(0.5, 0.5, 0.5);
        World world = block.getWorld();

        // 1. Sapling
        Material saplingMat = getSaplingMaterial(leafMat);
        if (saplingMat != null && Math.random() < configManager.getLeafDropsSaplingChance()) {
            dropItemSafely(world, loc, new ItemStack(saplingMat, 1));
        }

        // 2. Stick
        if (Math.random() < configManager.getLeafDropsStickChance()) {
            dropItemSafely(world, loc, new ItemStack(Material.STICK, 1));
        }

        // 3. Apple (chêne + forêt sombre uniquement)
        if ((leafMat == Material.OAK_LEAVES || leafMat == Material.DARK_OAK_LEAVES) 
                && Math.random() < configManager.getLeafDropsAppleChance()) {
            dropItemSafely(world, loc, new ItemStack(Material.APPLE, 1));
        }
    }

    public boolean isLeafMaterial(Material m) {
        if (m == null) return false;
        return m.name().endsWith("_LEAVES");
    }

    private void dropItemSafely(World world, Location loc, ItemStack item) {
        if (org.bukkit.Bukkit.isPrimaryThread()) {
            world.dropItemNaturally(loc, item);
        } else {
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> world.dropItemNaturally(loc, item));
        }
    }

    private Material getSaplingMaterial(Material leafMat) {
        if (leafMat == null) return null;
        return switch (leafMat) {
            case OAK_LEAVES -> Material.OAK_SAPLING;
            case SPRUCE_LEAVES -> Material.SPRUCE_SAPLING;
            case BIRCH_LEAVES -> Material.BIRCH_SAPLING;
            case JUNGLE_LEAVES -> Material.JUNGLE_SAPLING;
            case ACACIA_LEAVES -> Material.ACACIA_SAPLING;
            case DARK_OAK_LEAVES -> Material.DARK_OAK_SAPLING;
            case MANGROVE_LEAVES -> Material.MANGROVE_PROPAGULE;
            case CHERRY_LEAVES -> Material.CHERRY_SAPLING;
            case AZALEA_LEAVES -> Material.AZALEA;
            case FLOWERING_AZALEA_LEAVES -> Material.FLOWERING_AZALEA;
            default -> null;
        };
    }

    private static class NeighborInfo {
        final long key;
        final int y;
        final double distSq;
        NeighborInfo(long key, int y, double distSq) {
            this.key = key;
            this.y = y;
            this.distSq = distSq;
        }
    }
}
