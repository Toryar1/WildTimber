package com.wildtimber.detection;

import com.wildtimber.WildTimber;
import com.wildtimber.ConsoleColor;
import com.wildtimber.config.BiomeConfig;
import com.wildtimber.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;

import java.util.*;
import java.util.function.Consumer;

/**
 * Moteur de détection d'arbre effectuant des scans BFS asynchrones via ChunkSnapshots.
 *
 * Flux (v2 — partition multi-source) :
 *  1. BFS 26-way sur les logs depuis le bloc frappé → cluster complet de bûches
 *  2. Construction du TreeGraph → racines, composantes, BFS multi-source
 *  3. Tentative de séparation via goulots entre sous-arbres
 *  4. Si échec → FallbackCutter (algorithme cylindrique)
 *  5. Scan des feuilles 6-way avec distances XZ/Y séparées
 *  6. Branches dans la canopée (itérations)
 *  7. Vérification des minimums et construction du résultat
 */
public class TreeDetector {

    private final WildTimber plugin;
    private final ConfigManager configManager;

    // Voisinage 6-way (faces uniquement) pour les feuilles
    private static final int[][] DIRS_6 = {
        {1, 0, 0}, {-1, 0, 0},
        {0, 1, 0}, {0, -1, 0},
        {0, 0, 1}, {0, 0, -1}
    };

    // Voisinage 26-way (inclut diagonales) pour le scan initial des logs
    private static final int[][] NEIGHBORS_26 = new int[26][3];
    static {
        int idx = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    NEIGHBORS_26[idx][0] = dx;
                    NEIGHBORS_26[idx][1] = dy;
                    NEIGHBORS_26[idx][2] = dz;
                    idx++;
                }
            }
        }
    }

    public TreeDetector(WildTimber plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
    }

    /**
     * Lance la détection asynchrone d'un arbre et retourne le résultat via callback sur le main thread.
     */
    public void detectTree(World world, BlockPos startPos, Map<Long, ChunkSnapshot> snapshots, Consumer<TreeDetectionResult> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TreeDetectionResult result = performScan(world, startPos, snapshots);
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
        });
    }

    /**
     * Scan principal — exécuté en async.
     * Suit le pipeline de priorité :
     * 1. 26-way scan.
     * 2. Si 26-way dépasse max-logs ou goulots échouent (tree_too_large ou tree_too_fused) -> tenter 6bis (6-way restreint).
     * 3. Si 6bis dépasse aussi son budget (tree_too_large_6bis ou tree_too_large) -> tenter fallback cylindrique.
     * 4. Si tout échoue -> refuser.
     */
    private void logBiomeConfigUsed(TreeDetectionResult result) {
        if (result == null || !result.success() || !configManager.isDebug()) return;
        BiomeConfig biomeConfig = result.biomeConfig();
        String biomeName = result.biomeName();
        boolean exact = configManager.hasExactBiomeConfig(biomeName);
        String source = exact ? "exact" : "default";
        int maxLogsVal = biomeConfig.maxLogs() != null ? biomeConfig.maxLogs() : configManager.getMaxLogs();
        int intervalVal = configManager.getStagedCutIntervalTicks();
        int fillDepthVal = biomeConfig.maxRootSearchDepth() != null ? biomeConfig.maxRootSearchDepth() : configManager.getMaxRootSearchDepth();
        
        org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Config biome utilisée → biome=" + biomeName 
                + " | source=" + source + " | max-logs=" + maxLogsVal + " | interval=" + intervalVal + " | fill-depth=" + fillDepthVal);
    }

    private TreeDetectionResult performScan(World world, BlockPos startPos, Map<Long, ChunkSnapshot> snapshots) {
        // 1. Scan 26-way
        TreeDetectionResult result26 = performScanInternal(world, startPos, snapshots, false);
        if (result26.success()) {
            logBiomeConfigUsed(result26);
            return result26;
        }

        // Si 26-way dépasse max-logs ou si goulots échouent
        if ("tree_too_large".equals(result26.cancellationReason()) || "tree_too_fused".equals(result26.cancellationReason())) {
            if (configManager.isDebug()) {
                org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "26-way failed (" + result26.cancellationReason() + "). Retrying with 6-way scan (détection 6bis)...");
            }
            
            // 2. Scan 6bis (6-way restreint avec 6way-max-logs)
            TreeDetectionResult result6bis = performScanInternal(world, startPos, snapshots, true);
            if (result6bis.success()) {
                logBiomeConfigUsed(result6bis);
                return result6bis;
            }

            // Fallback automatique si 6bis échoue sur blacklist_soft
            if ("tree_too_fused".equals(result6bis.cancellationReason()) && result6bis.hasBlacklistSoftHit()) {
                boolean fallbackEnabled = result26.biomeConfig() != null && result26.biomeConfig().fallbackEnabled() != null
                        ? result26.biomeConfig().fallbackEnabled() : configManager.isFallbackEnabled();
                if (fallbackEnabled) {
                    if (configManager.isDebug()) {
                        org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "6bis hit blacklist_soft → retry fallback cylindrique");
                    }
                    Set<BlockPos> fallbackAllLogs = result26.logs();
                    if (fallbackAllLogs == null || fallbackAllLogs.isEmpty()) {
                        fallbackAllLogs = result6bis.logs();
                    }
                    SubTreePartition fallback = FallbackCutter.compute(
                            startPos, fallbackAllLogs, result26.biomeConfig(), configManager, snapshots, world);
                    if (fallback != null) {
                        TreeDetectionResult resultFallback = new TreeDetectionResult(true, null, fallback.logs(), fallback.leaves(),
                                fallback.maxHealth(), result26.biomeConfig(), result26.biomeName(), true, true);
                        logBiomeConfigUsed(resultFallback);
                        return resultFallback;
                    }
                }
            }

            // Si 6bis dépasse aussi son budget ou échoue (large, large_6bis ou fused)
            if ("tree_too_large_6bis".equals(result6bis.cancellationReason()) 
                    || "tree_too_large".equals(result6bis.cancellationReason())
                    || "tree_too_fused".equals(result6bis.cancellationReason())) {
                boolean fallbackEnabled = result26.biomeConfig() != null && result26.biomeConfig().fallbackEnabled() != null
                        ? result26.biomeConfig().fallbackEnabled() : configManager.isFallbackEnabled();
                if (fallbackEnabled) {
                    if (configManager.isDebug()) {
                        org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "6bis scan exceeded budget. Retrying with fallback cylindrique...");
                    }
                    Set<BlockPos> fallbackAllLogs = result26.logs();
                    if (fallbackAllLogs == null || fallbackAllLogs.isEmpty()) {
                        fallbackAllLogs = result6bis.logs();
                    }
                    SubTreePartition fallback = FallbackCutter.compute(
                            startPos, fallbackAllLogs, result26.biomeConfig(), configManager, snapshots, world);
                    if (fallback != null) {
                        TreeDetectionResult resultFallback = new TreeDetectionResult(true, null, fallback.logs(), fallback.leaves(),
                                fallback.maxHealth(), result26.biomeConfig(), result26.biomeName(), true, result6bis.hasBlacklistSoftHit());
                        logBiomeConfigUsed(resultFallback);
                        return resultFallback;
                    }
                }
            }
        }

        // Sinon on retourne l'échec initial
        return result26;
    }

    private TreeDetectionResult performScanInternal(World world, BlockPos startPos, Map<Long, ChunkSnapshot> snapshots, boolean use6Way) {
        boolean blacklistSoftHit = false;
        // ── Biome ──
        Biome startBiome = getBlockBiome(startPos, snapshots, world);
        String biomeName = startBiome != null ? startBiome.name() : "DEFAULT";
        if (biomeName.contains(":")) biomeName = biomeName.substring(biomeName.indexOf(':') + 1).toUpperCase();
        BiomeConfig biomeConfig = configManager.getBiomeConfig(biomeName);

        if (!biomeConfig.enabled()) {
            if (configManager.isDebug()) {
                org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Biome '" + biomeName + "' désactivé → ignoré");
            }
            return fail("biome_disabled", biomeConfig, biomeName, blacklistSoftHit);
        }

        Material startMat = getBlockMaterial(startPos, snapshots, world);

        if (configManager.isDebug()) {
            org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "performScanInternal → biome=" + biomeName
                    + " | startBlock=" + startMat + " @ " + startPos + " | use6Way=" + use6Way);
        }

        // ── Vérification du bloc de départ ──
        if (!isLog(startMat, biomeConfig)) {
            if (configManager.isDebug()) {
                org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Bloc de départ non reconnu comme bûche: " + startMat);
            }
            return fail("not_a_tree", biomeConfig, biomeName, blacklistSoftHit);
        }


        // ══════════════════════════════════════════════════════════════════════
        //  ÉTAPE 1 — Scan des logs (BFS 26-way ou 6-way)
        // ══════════════════════════════════════════════════════════════════════

        int maxLogs = biomeConfig.maxLogs() != null ? biomeConfig.maxLogs() : configManager.getMaxLogs();
        int maxBlocks = biomeConfig.maxBlocks() != null ? biomeConfig.maxBlocks() : configManager.getMaxBlocks();
        int maxRadiusXZ = biomeConfig.maxRadiusXZ() != null ? biomeConfig.maxRadiusXZ() : configManager.getMaxRadiusXZ();
        int maxHeightY = biomeConfig.maxHeightY() != null ? biomeConfig.maxHeightY() : configManager.getMaxHeightY();

        Queue<BlockPos> logQueue = new LinkedList<>();
        Set<BlockPos> visitedLogs = new HashSet<>();
        Set<BlockPos> allLogs = new HashSet<>();
        int separatedTreesCount = 0;

        if (use6Way) {
            // 6bis scan : Identifier la colonne centrale du tronc en descendant en Y jusqu'au sol
            BlockPos basePos = startPos;
            for (int scanY = startPos.y() - 1; scanY >= Math.max(startPos.y() - 64, world.getMinHeight()); scanY--) {
                BlockPos below = new BlockPos(startPos.x(), scanY, startPos.z());
                Material belowMat = getBlockMaterial(below, snapshots, world);
                if (isLog(belowMat, biomeConfig)) {
                    basePos = below;
                } else {
                    break;
                }
            }

            // Stocker toute la colonne verticale principale
            BlockPos curCol = basePos;
            while (isLog(getBlockMaterial(curCol, snapshots, world), biomeConfig)) {
                allLogs.add(curCol);
                visitedLogs.add(curCol);
                logQueue.add(curCol);
                curCol = curCol.add(0, 1, 0);
            }
        } else {
            logQueue.add(startPos);
            visitedLogs.add(startPos);
            allLogs.add(startPos);
        }

        int logLimit;
        if (use6Way) {
            logLimit = (int) (maxLogs * 0.75);
            if (configManager.isDebug()) {
                org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "6bis budget: " + logLimit + " (biome=" + biomeName + ", max-logs=" + maxLogs + ")");
            }
        } else {
            logLimit = maxLogs;
        }

        int allowedLimit = use6Way ? (int) (logLimit * 1.05) : logLimit;

        int[][] logNeighbors = use6Way ? DIRS_6 : NEIGHBORS_26;

        while (!logQueue.isEmpty()) {
            BlockPos current = logQueue.poll();

            for (int[] offset : logNeighbors) {
                BlockPos neighbor = current.add(offset[0], offset[1], offset[2]);

                if (Math.abs(neighbor.x() - startPos.x()) > maxRadiusXZ
                        || Math.abs(neighbor.z() - startPos.z()) > maxRadiusXZ
                        || Math.abs(neighbor.y() - startPos.y()) > maxHeightY) continue;

                if (visitedLogs.contains(neighbor)) continue;

                Material mat = getBlockMaterial(neighbor, snapshots, world);

                if (mat == null || mat == Material.AIR
                        || mat == Material.CAVE_AIR || mat == Material.VOID_AIR) {
                    visitedLogs.add(neighbor);
                    continue;
                }

                // Blacklist contact check : seuls les blocs en contact avec log/leaf bloquent
                if (isBlacklisted(mat, biomeConfig)) {
                    if (isBlacklistSoft(mat)) {
                        blacklistSoftHit = true;
                        if (configManager.isDebug()) {
                            org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Blacklist soft hit " + mat + " @ " + neighbor + " → traversé");
                        }
                        visitedLogs.add(neighbor);
                        continue;
                    }

                    // Vérifier si ce bloc est en contact avec un log ou leaf de l'arbre
                    boolean contactWithTree = false;
                    for (int[] dir : DIRS_6) {
                        BlockPos adj = neighbor.add(dir[0], dir[1], dir[2]);
                        if (allLogs.contains(adj)) {
                            contactWithTree = true;
                            break;
                        }
                    }
                    if (contactWithTree) {
                        if (configManager.isDebug()) {
                            org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Blacklist hit: " + mat + " @ " + neighbor);
                        }
                        return fail("has_blacklist", biomeConfig, biomeName, allLogs, blacklistSoftHit);
                    }
                    visitedLogs.add(neighbor);
                    continue;
                }

                if (isLog(mat, biomeConfig) || isStructuralAttachment(mat, biomeConfig)) {
                    boolean isDiagonal = (offset[0] != 0 && offset[2] != 0) || (offset[1] != 0 && (offset[0] != 0 || offset[2] != 0));
                    boolean diagLogs = biomeConfig.allowDiagonalLogs() != null ? biomeConfig.allowDiagonalLogs() : configManager.isAllowDiagonalLogs();
                    if (!use6Way && isDiagonal && !diagLogs) {
                        if (!hasShort6WayPath(world, current, neighbor, biomeConfig, snapshots)) {
                            separatedTreesCount++;
                            continue;
                        }
                    }
                    visitedLogs.add(neighbor);
                    allLogs.add(neighbor);
                    logQueue.add(neighbor);
                } else {
                    visitedLogs.add(neighbor);
                }

                if (allLogs.size() > allowedLimit) {
                    if (configManager.isDebug()) {
                        org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Trop de bûches: " + allLogs.size() + " (limite avec tolérance: " + allowedLimit + ")");
                    }
                    return fail(use6Way ? "tree_too_large_6bis" : "tree_too_large", biomeConfig, biomeName, allLogs, blacklistSoftHit);
                }
            }
        }

        if (!use6Way && separatedTreesCount > 0) {
            if (configManager.isDebug()) {
                org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Arbres séparés détectés : 2 groupes distincts → coupe limitée au groupe du startBlock (" + allLogs.size() + " logs)");
            }
        }

        // ── Vérification de l'enracinement global du cluster ──
        if (configManager.isTreeContactRequired()) {
            boolean clusterRooted = false;
            Map<Long, Integer> minColY = new HashMap<>();
            for (BlockPos pos : allLogs) {
                long colKey = ((long) pos.x() << 32) | (pos.z() & 0xFFFFFFFFL);
                minColY.merge(colKey, pos.y(), Math::min);
            }

            for (Map.Entry<Long, Integer> entry : minColY.entrySet()) {
                int cx = (int) (entry.getKey() >> 32);
                int cz = (int) (entry.getKey() & 0xFFFFFFFFL);
                int cy = entry.getValue();
                BlockPos lowestLog = new BlockPos(cx, cy, cz);
                if (isRooted(lowestLog, biomeConfig, snapshots, world)) {
                    clusterRooted = true;
                    break;
                }
            }

            if (!clusterRooted) {
                if (configManager.isDebug()) {
                    org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Aucune partie du cluster d'arbres n'est en contact direct avec le sol → ignoré.");
                }
                return fail("not_rooted", biomeConfig, biomeName, allLogs, blacklistSoftHit);
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        //  ÉTAPE 2 — TreeGraph : racines, BFS multi-source, goulots
        // ══════════════════════════════════════════════════════════════════════

        TreeGraph graph = null;
        Set<BlockPos> targetLogs;

        if (use6Way) {
            targetLogs = new HashSet<>(allLogs);
            if (configManager.isDebug()) {
                org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "6bis scan → core logs collectés: " + targetLogs.size());
                if (allLogs.size() > logLimit) {
                    org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "6bis scan : " + allLogs.size() + " bûches (dans la tolérance de 5%, budget nominal: " + logLimit + ")");
                }
            }
        } else {
            graph = TreeGraph.build(allLogs, biomeConfig, configManager, snapshots, world, use6Way);
            graph.computeMultiSourceBFS();

            if (graph.getRootCount() <= 1) {
                // Un seul tronc → tout est à couper
                targetLogs = new HashSet<>(allLogs);
                if (configManager.isDebug()) {
                    org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Tronc unique détecté → tous les logs inclus ("
                            + allLogs.size() + ")");
                }
            } else {
                // Plusieurs troncs → essayer de séparer via goulots
                int sMax = configManager.getMaxBridgeSectionSize();
                double cMax = configManager.getMaxBridgeCost();
                double alpha = configManager.getHeightWeightAlpha();

                targetLogs = graph.extractSubTree(startPos, sMax, cMax, alpha);

                if (targetLogs == null) {
                    // Goulots insuffisants → retourner un échec tree_too_fused pour essayer 6bis
                    if (configManager.isDebug()) {
                        org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Arbres trop fusionnés, aucune séparation possible via goulots");
                    }
                    return fail("tree_too_fused", biomeConfig, biomeName, allLogs, blacklistSoftHit);
                }

                if (configManager.isDebug()) {
                    org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Partition multi-source: " + graph.getRootCount()
                            + " troncs, sous-arbre ciblé = " + targetLogs.size() + " logs");
                }
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        //  ÉTAPE 3 — Scan des feuilles (BFS 6-way ou 26-way avec distances XZ/Y séparées)
        // ══════════════════════════════════════════════════════════════════════

        int decayRangeXZ = biomeConfig.leafDecayRangeXZ() != null
                ? biomeConfig.leafDecayRangeXZ() : configManager.getLeafDecayRangeXZ();
        int decayRangeY = biomeConfig.leafDecayRangeY() != null
                ? biomeConfig.leafDecayRangeY() : configManager.getLeafDecayRangeY();

        boolean diagLeaves = biomeConfig.allowDiagonalLeaves() != null ? biomeConfig.allowDiagonalLeaves() : configManager.isAllowDiagonalLeaves();
        int[][] leafNeighbors = diagLeaves ? NEIGHBORS_26 : DIRS_6;

        Set<BlockPos> leaves = new HashSet<>();
        Map<BlockPos, int[]> leafDepths = new HashMap<>(); // [distXZ, distY, manhattanDist]
        Queue<BlockPos> leafQueue = new LinkedList<>();

        for (BlockPos logPos : targetLogs) {
            leafDepths.put(logPos, new int[]{0, 0, 0});
            leafQueue.add(logPos);
        }

        while (!leafQueue.isEmpty()) {
            BlockPos current = leafQueue.poll();
            int[] currentDepth = leafDepths.get(current);

            for (int[] dir : leafNeighbors) {
                BlockPos neighbor = current.add(dir[0], dir[1], dir[2]);
                if (leafDepths.containsKey(neighbor)) continue;

                int newXZ = currentDepth[0] + Math.abs(dir[0]) + Math.abs(dir[2]);
                int newY = currentDepth[1] + Math.abs(dir[1]);
                int newManhattan = currentDepth[2] + Math.abs(dir[0]) + Math.abs(dir[1]) + Math.abs(dir[2]);

                if (newXZ > decayRangeXZ || newY > decayRangeY) continue;

                Material mat = getBlockMaterial(neighbor, snapshots, world);

                if (mat == null || mat == Material.AIR
                        || mat == Material.CAVE_AIR || mat == Material.VOID_AIR) continue;

                // Blacklist contact check pour les feuilles
                if (isBlacklisted(mat, biomeConfig)) {
                    if (isBlacklistSoft(mat)) {
                        blacklistSoftHit = true;
                        continue; // traversé
                    }
                    // Vérifier contact direct avec log/leaf de l'arbre
                    Material currentMat = getBlockMaterial(current, snapshots, world);
                    if (isLog(currentMat, biomeConfig) || isLeaf(currentMat, biomeConfig)
                            || isLeafOrAttachment(currentMat, biomeConfig)) {
                        if (configManager.isDebug()) {
                            org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Blacklist hit in leaf scan: " + mat
                                    + " @ " + neighbor + " (touche " + currentMat + ")");
                        }
                        return fail("has_blacklist", biomeConfig, biomeName, blacklistSoftHit);
                    }
                    continue;
                }

                if (isLeafOrAttachment(mat, biomeConfig)) {
                    // Prévention de la propagation aux arbres voisins en comparant avec le tag de distance Minecraft
                    if (diagLeaves) {
                        org.bukkit.block.data.BlockData data = getBlockData(neighbor, snapshots, world);
                        if (data instanceof org.bukkit.block.data.type.Leaves leavesData) {
                            if (!leavesData.isPersistent()) {
                                int leafDistance = leavesData.getDistance();
                                if (newManhattan > leafDistance) {
                                    // La feuille est plus proche d'un autre tronc, on l'ignore pour stopper la cascade
                                    continue;
                                }
                            }
                        }
                    }

                    // Vérifier que la feuille n'est pas adjacente à un log d'un AUTRE sous-arbre
                    boolean adjacentToForeignLog = false;
                    for (int[] checkDir : DIRS_6) {
                        BlockPos adjPos = neighbor.add(checkDir[0], checkDir[1], checkDir[2]);
                        Material adjMat = getBlockMaterial(adjPos, snapshots, world);
                        if (isLog(adjMat, biomeConfig) && !targetLogs.contains(adjPos)) {
                            adjacentToForeignLog = true;
                            break;
                        }
                    }

                    if (adjacentToForeignLog) {
                        leafDepths.put(neighbor, new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE});
                        continue;
                    }

                    leafDepths.put(neighbor, new int[]{newXZ, newY, newManhattan});
                    leaves.add(neighbor);
                    leafQueue.add(neighbor);

                    if ((targetLogs.size() + leaves.size()) > maxBlocks) {
                        if (configManager.isDebug()) {
                            org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Arbre trop grand: total=" + (targetLogs.size() + leaves.size()));
                        }
                        return fail(use6Way ? "tree_too_large_6bis" : "tree_too_large", biomeConfig, biomeName, allLogs, blacklistSoftHit);
                    }
                }
            }
        }

        if (configManager.isDebug()) {
            org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Leaf BFS terminé: logs=" + targetLogs.size()
                    + " feuilles=" + leaves.size()
                    + " | seuils: minLogs=" + biomeConfig.minLogs()
                    + " minLeaf=" + biomeConfig.minLeafLike()
                    + " | decayXZ=" + decayRangeXZ + " decayY=" + decayRangeY);
        }

        // Vérification des minimums
        if (targetLogs.size() < biomeConfig.minLogs() || leaves.size() < biomeConfig.minLeafLike()) {
            if (configManager.isDebug()) {
                org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Seuils non atteints → pas un arbre reconnu.");
            }
            return fail("min_limits_not_met", biomeConfig, biomeName, blacklistSoftHit);
        }

        // ══════════════════════════════════════════════════════════════════════
        //  ÉTAPE 4 — Découverte itérative des branches dans la canopée
        // ══════════════════════════════════════════════════════════════════════

        Set<BlockPos> treeBlocks = new HashSet<>(targetLogs);
        treeBlocks.addAll(leaves);

        int branchIter = configManager.getMaxBranchDiscoveryIterations();
        for (int iter = 0; iter < branchIter; iter++) {
            // Collecter les bûches adjacentes aux feuilles déjà dans l'arbre
            Set<BlockPos> candidateLogs = new HashSet<>();
            for (BlockPos leafPos : new HashSet<>(leaves)) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            BlockPos nb = leafPos.add(dx, dy, dz);
                            if (targetLogs.contains(nb)) continue;
                            Material mat = getBlockMaterial(nb, snapshots, world);
                            if (isLog(mat, biomeConfig)) candidateLogs.add(nb);
                        }
                    }
                }
            }

            if (candidateLogs.isEmpty()) break;

            Set<BlockPos> newBranchLogs = new HashSet<>();
            Set<BlockPos> processedCandidates = new HashSet<>();

            for (BlockPos seed : candidateLogs) {
                if (processedCandidates.contains(seed) || targetLogs.contains(seed)) continue;

                Queue<BlockPos> cq = new LinkedList<>();
                Set<BlockPos> cluster = new HashSet<>();
                Set<BlockPos> cVisited = new HashSet<>();
                boolean clusterGrounded = false;

                cq.add(seed);
                cVisited.add(seed);

                while (!cq.isEmpty()) {
                    BlockPos cur = cq.poll();
                    Material curMat = getBlockMaterial(cur, snapshots, world);
                    if (!isLog(curMat, biomeConfig) && !isStructuralAttachment(curMat, biomeConfig)) continue;
                    cluster.add(cur);

                    // Vérification d'enracinement
                    if (!clusterGrounded) {
                        boolean useIsolatedLogsRule = biomeConfig.isolatedLogsRule() != null
                                ? biomeConfig.isolatedLogsRule() : configManager.isIsolatedLogsRule();
                        if (useIsolatedLogsRule) {
                            BlockPos below = cur.add(0, -1, 0);
                            Material bm = getBlockMaterial(below, snapshots, world);
                            if (bm != null && bm != Material.AIR && bm != Material.CAVE_AIR && bm != Material.VOID_AIR
                                    && bm != Material.WATER && !isLog(bm, biomeConfig) && !isLeafOrAttachment(bm, biomeConfig)) {
                                clusterGrounded = true;
                            }
                        } else {
                            for (int sy = cur.y() - 1; sy >= Math.max(cur.y() - maxHeightY, world.getMinHeight()); sy--) {
                                BlockPos below = new BlockPos(cur.x(), sy, cur.z());
                                Material bm = getBlockMaterial(below, snapshots, world);
                                if (bm == null || bm == Material.AIR || bm == Material.CAVE_AIR || bm == Material.VOID_AIR) break;
                                if (bm == Material.WATER) continue;
                                if (isLog(bm, biomeConfig)) continue;
                                if (isLeafOrAttachment(bm, biomeConfig)) continue;
                                clusterGrounded = true;
                                break;
                            }
                        }
                    }

                    // Expansion 26-way
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                if (dx == 0 && dy == 0 && dz == 0) continue;
                                BlockPos next = cur.add(dx, dy, dz);
                                if (cVisited.contains(next) || targetLogs.contains(next)) continue;
                                cVisited.add(next);
                                Material nm = getBlockMaterial(next, snapshots, world);
                                if (isLog(nm, biomeConfig) || isStructuralAttachment(nm, biomeConfig)) cq.add(next);
                            }
                        }
                    }
                }

                processedCandidates.addAll(cVisited);

                // Cluster non enraciné → branche suspendue → inclure
                if (!clusterGrounded && !cluster.isEmpty()) {
                    newBranchLogs.addAll(cluster);
                }
            }

            if (newBranchLogs.isEmpty()) break;

            targetLogs.addAll(newBranchLogs);
            treeBlocks.addAll(newBranchLogs);

            // Expansion des feuilles depuis les nouvelles bûches de branche
            for (BlockPos newLog : newBranchLogs) {
                leafDepths.putIfAbsent(newLog, new int[]{0, 0, 0});
                Queue<BlockPos> leafExpand = new LinkedList<>();
                leafExpand.add(newLog);

                while (!leafExpand.isEmpty()) {
                    BlockPos cur = leafExpand.poll();
                    int[] depth = leafDepths.getOrDefault(cur, new int[]{0, 0, 0});

                    for (int[] dir : leafNeighbors) {
                        BlockPos adj = cur.add(dir[0], dir[1], dir[2]);
                        if (leafDepths.containsKey(adj)) continue;

                        int newXZ2 = depth[0] + Math.abs(dir[0]) + Math.abs(dir[2]);
                        int newY2 = depth[1] + Math.abs(dir[1]);
                        int newManhattan = depth[2] + Math.abs(dir[0]) + Math.abs(dir[1]) + Math.abs(dir[2]);

                        if (newXZ2 > decayRangeXZ || newY2 > decayRangeY) continue;

                        Material adjMat = getBlockMaterial(adj, snapshots, world);
                        if (adjMat == null || adjMat == Material.AIR
                                || adjMat == Material.CAVE_AIR || adjMat == Material.VOID_AIR) continue;
                        if (configManager.getBlacklist().contains(adjMat)) continue;
                        if (isLeafOrAttachment(adjMat, biomeConfig)) {
                            // Prévention de la propagation aux arbres voisins en comparant avec le tag de distance Minecraft
                            if (diagLeaves) {
                                org.bukkit.block.data.BlockData data = getBlockData(adj, snapshots, world);
                                if (data instanceof org.bukkit.block.data.type.Leaves leavesData) {
                                    if (!leavesData.isPersistent()) {
                                        int leafDistance = leavesData.getDistance();
                                        if (newManhattan > leafDistance) {
                                            // La feuille est plus proche d'un autre tronc, on l'ignore
                                            continue;
                                        }
                                    }
                                }
                            }

                            boolean adjacentToForeignLog = false;
                            for (int[] checkDir : DIRS_6) {
                                BlockPos adjPos = adj.add(checkDir[0], checkDir[1], checkDir[2]);
                                Material adjMat2 = getBlockMaterial(adjPos, snapshots, world);
                                if (isLog(adjMat2, biomeConfig) && !targetLogs.contains(adjPos)) {
                                    adjacentToForeignLog = true;
                                    break;
                                }
                            }
                            if (!adjacentToForeignLog) {
                                leafDepths.put(adj, new int[]{newXZ2, newY2, newManhattan});
                                leaves.add(adj);
                                treeBlocks.add(adj);
                                leafExpand.add(adj);
                            } else {
                                leafDepths.put(adj, new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE});
                            }
                        }
                    }
                }
            }

            if (configManager.isDebug()) {
                org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Iteration branche #" + (iter + 1)
                        + ": +" + newBranchLogs.size() + " bûches de branche trouvées");
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        //  ÉTAPE 5 — Clusters orphelins (petits amas flottants)
        // ══════════════════════════════════════════════════════════════════════

        int orphanMax = configManager.getMaxOrphanClusterSize();
        Set<BlockPos> orphanCandidates = new HashSet<>();
        for (BlockPos p : new HashSet<>(treeBlocks)) {
            for (int dx = -6; dx <= 6; dx++) {
                for (int dy = -6; dy <= 6; dy++) {
                    for (int dz = -6; dz <= 6; dz++) {
                        BlockPos nb = p.add(dx, dy, dz);
                        if (treeBlocks.contains(nb)) continue;
                        Material mat = getBlockMaterial(nb, snapshots, world);
                        if (isLog(mat, biomeConfig) || isLeafOrAttachment(mat, biomeConfig)) {
                            orphanCandidates.add(nb);
                        }
                    }
                }
            }
        }

        Set<BlockPos> processedOrphans = new HashSet<>();
        for (BlockPos start : orphanCandidates) {
            if (processedOrphans.contains(start) || treeBlocks.contains(start)) continue;

            Queue<BlockPos> queue = new LinkedList<>();
            Set<BlockPos> clusterLogs = new HashSet<>();
            Set<BlockPos> clusterLeaves = new HashSet<>();
            Set<BlockPos> visited = new HashSet<>();
            boolean touchesGround = false;
            boolean touchesMainTree = false;

            queue.add(start);
            visited.add(start);

            while (!queue.isEmpty()) {
                BlockPos curr = queue.poll();
                Material currMat = getBlockMaterial(curr, snapshots, world);

                if (isLog(currMat, biomeConfig)) clusterLogs.add(curr);
                else if (isLeafOrAttachment(currMat, biomeConfig)) clusterLeaves.add(curr);

                BlockPos below = curr.add(0, -1, 0);
                Material belowMat = getBlockMaterial(below, snapshots, world);
                if (belowMat != null && belowMat != Material.AIR && belowMat != Material.CAVE_AIR
                        && belowMat != Material.VOID_AIR && belowMat != Material.WATER
                        && belowMat != Material.SNOW && !isLog(belowMat, biomeConfig)
                        && !isLeafOrAttachment(belowMat, biomeConfig)) {
                    touchesGround = true;
                }

                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            BlockPos next = curr.add(dx, dy, dz);
                            if (treeBlocks.contains(next)) { touchesMainTree = true; continue; }
                            if (visited.contains(next)) continue;
                            Material nextMat = getBlockMaterial(next, snapshots, world);
                            if (isLog(nextMat, biomeConfig) || isLeafOrAttachment(nextMat, biomeConfig)) {
                                visited.add(next);
                                queue.add(next);
                            }
                        }
                    }
                }
            }

            processedOrphans.addAll(visited);

            int clusterSize = clusterLogs.size() + clusterLeaves.size();
            if (!touchesGround && !touchesMainTree && clusterSize <= orphanMax) {
                targetLogs.addAll(clusterLogs);
                leaves.addAll(clusterLeaves);
                treeBlocks.addAll(clusterLogs);
                treeBlocks.addAll(clusterLeaves);
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        //  ÉTAPE 6 — Calcul de la santé
        // ══════════════════════════════════════════════════════════════════════

        double maxHealth = calculateHealth(targetLogs, leaves, snapshots, world);

        if (configManager.isDebug()) {
            org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Arbre valide: logs=" + targetLogs.size()
                    + " feuilles=" + leaves.size() + " PV=" + maxHealth + " biome=" + biomeName
                    + " fallback=" + false + " troncs=" + (graph != null ? graph.getRootCount() : 1));
        }

        if (use6Way && configManager.isDebug()) {
            org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "6bis scan → core logs collectés: " + targetLogs.size() + " | feuilles associées: " + leaves.size());
        }

        return new TreeDetectionResult(true, null, targetLogs, leaves, maxHealth, biomeConfig, biomeName, false, blacklistSoftHit);
    }


    // ─────────────────────────────────────────────────────────────────────────
    //  Calcul des PV
    // ─────────────────────────────────────────────────────────────────────────

    private double calculateHealth(Set<BlockPos> logs, Set<BlockPos> leaves,
                                   Map<Long, ChunkSnapshot> snapshots, World world) {
        double h = 0.0;
        for (BlockPos pos : logs) {
            Material mat = getBlockMaterial(pos, snapshots, world);
            h += configManager.getLogWeights().getOrDefault(mat, 1.0);
        }
        for (BlockPos pos : leaves) {
            Material mat = getBlockMaterial(pos, snapshots, world);
            h += configManager.getLeafWeights().getOrDefault(mat, 0.1);
        }
        return h * configManager.getBaseCoefficient();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isBlacklisted(Material mat, BiomeConfig biomeConfig) {
        if (mat == null) return false;
        if (!configManager.isBlacklistEnabled()) return false;
        // La whitelist par biome prévaut sur la blacklist
        if (biomeConfig.logBlocks().contains(mat)
                || biomeConfig.leafBlocks().contains(mat)
                || biomeConfig.attachments().contains(mat)) {
            return false;
        }
        return configManager.getBlacklist().contains(mat);
    }

    private boolean isLog(Material material, BiomeConfig biomeConfig) {
        if (material == null) return false;
        return biomeConfig.logBlocks().contains(material)
                || configManager.getLogWeights().containsKey(material);
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
        return biomeConfig.leafBlocks().contains(material)
                || configManager.getLeafWeights().containsKey(material);
    }

    private boolean isLeafOrAttachment(Material material, BiomeConfig biomeConfig) {
        if (material == null) return false;
        return biomeConfig.leafBlocks().contains(material)
                || biomeConfig.attachments().contains(material)
                || configManager.getLeafWeights().containsKey(material);
    }

    private Material getBlockMaterial(BlockPos pos, Map<Long, ChunkSnapshot> snapshots, World world) {
        int chunkX = pos.x() >> 4;
        int chunkZ = pos.z() >> 4;
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        ChunkSnapshot snapshot = snapshots.get(key);
        if (snapshot == null) return Material.AIR;
        if (pos.y() < world.getMinHeight() || pos.y() >= world.getMaxHeight()) return Material.AIR;
        return snapshot.getBlockType(pos.x() & 15, pos.y(), pos.z() & 15);
    }

    private Biome getBlockBiome(BlockPos pos, Map<Long, ChunkSnapshot> snapshots, World world) {
        int chunkX = pos.x() >> 4;
        int chunkZ = pos.z() >> 4;
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        ChunkSnapshot snapshot = snapshots.get(key);
        if (snapshot == null) return null;
        if (pos.y() < world.getMinHeight() || pos.y() >= world.getMaxHeight()) return null;
        return snapshot.getBiome(pos.x() & 15, pos.y(), pos.z() & 15);
    }

    private boolean isRooted(BlockPos pos, BiomeConfig biomeConfig, Map<Long, ChunkSnapshot> snapshots, World world) {
        if (!isLog(getBlockMaterial(pos, snapshots, world), biomeConfig)) {
            return false;
        }
        for (int scanY = pos.y() - 1; scanY >= Math.max(pos.y() - 64, world.getMinHeight()); scanY--) {
            BlockPos below = new BlockPos(pos.x(), scanY, pos.z());
            Material belowMat = getBlockMaterial(below, snapshots, world);

            if (belowMat == null || belowMat == Material.AIR
                    || belowMat == Material.CAVE_AIR || belowMat == Material.VOID_AIR) {
                return false;
            }
            if (belowMat == Material.WATER) continue;
            if (isLog(belowMat, biomeConfig)) continue;

            return true;
        }
        return false;
    }

    private TreeDetectionResult fail(String reason, BiomeConfig biomeConfig, String biomeName, boolean blacklistSoftHit) {
        return new TreeDetectionResult(false, reason, Collections.emptySet(),
                Collections.emptySet(), 0, biomeConfig, biomeName, false, blacklistSoftHit);
    }

    private TreeDetectionResult fail(String reason, BiomeConfig biomeConfig, String biomeName, Set<BlockPos> logs, boolean blacklistSoftHit) {
        return new TreeDetectionResult(false, reason, logs, Collections.emptySet(), 0, biomeConfig, biomeName, false, blacklistSoftHit);
    }

    private boolean isBlacklistSoft(Material mat) {
        if (mat == null) return false;
        String name = mat.name();
        if (name.endsWith("_SLAB")) return true;
        if (mat == Material.MOSS_BLOCK || mat == Material.MOSS_CARPET) return true;
        if (mat == Material.MUSHROOM_STEM || mat == Material.RED_MUSHROOM_BLOCK || mat == Material.BROWN_MUSHROOM_BLOCK) return true;
        return false;
    }

    private boolean hasShort6WayPath(World world, BlockPos start, BlockPos end, BiomeConfig biomeConfig, Map<Long, ChunkSnapshot> snapshots) {
        if (start.equals(end)) return true;

        Queue<BlockPos> queue = new LinkedList<>();
        Map<BlockPos, Integer> depth = new HashMap<>();

        queue.add(start);
        depth.put(start, 0);

        while (!queue.isEmpty()) {
            BlockPos curr = queue.poll();
            int d = depth.get(curr);
            if (curr.equals(end)) {
                return true;
            }
            if (d >= 3) {
                continue;
            }

            for (int[] offset : DIRS_6) {
                BlockPos next = curr.add(offset[0], offset[1], offset[2]);
                if (depth.containsKey(next)) continue;

                Material mat = getBlockMaterial(next, snapshots, world);
                if (isLog(mat, biomeConfig)) {
                    depth.put(next, d + 1);
                    queue.add(next);
                }
            }
        }
        return false;
    }

    private org.bukkit.block.data.BlockData getBlockData(BlockPos pos, Map<Long, ChunkSnapshot> snapshots, World world) {
        int chunkX = pos.x() >> 4;
        int chunkZ = pos.z() >> 4;
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        ChunkSnapshot snapshot = snapshots.get(key);
        if (snapshot != null) {
            if (pos.y() >= world.getMinHeight() && pos.y() < world.getMaxHeight()) {
                return snapshot.getBlockData(pos.x() & 15, pos.y(), pos.z() & 15);
            }
        }
        return world.getBlockAt(pos.x(), pos.y(), pos.z()).getBlockData();
    }
}
