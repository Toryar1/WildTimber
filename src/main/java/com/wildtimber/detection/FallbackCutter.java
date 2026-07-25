package com.wildtimber.detection;

import com.wildtimber.ConsoleColor;
import com.wildtimber.config.BiomeConfig;
import com.wildtimber.config.ConfigManager;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.*;

/**
 * Algorithme de secours cylindrique pour séparer les arbres collés.
 * Utilisé quand la méthode principale (BFS multi-source + goulots) échoue.
 *
 * Principe : on se concentre sur le tronc ciblé, on définit un cylindre
 * autour de lui, et on inclut les blocs tant que la densité reste suffisante.
 */
public class FallbackCutter {

    /**
     * Calcule un sous-arbre via l'algorithme de secours cylindrique.
     *
     * @param hitLog     Le log frappé par le joueur
     * @param allLogs    Tous les logs détectés dans le cluster
     * @param biomeConfig Config du biome
     * @param config     Config globale
     * @param snapshots  Snapshots des chunks
     * @param world      Le monde
     * @return Un SubTreePartition contenant les logs et feuilles du sous-arbre, ou null si échec
     */
    public static SubTreePartition compute(BlockPos hitLog, Set<BlockPos> allLogs,
                                            BiomeConfig biomeConfig, ConfigManager config,
                                            Map<Long, ChunkSnapshot> snapshots, World world) {
        int trunkCoreRadius = biomeConfig != null && biomeConfig.fallbackTrunkCoreRadius() != null
                ? biomeConfig.fallbackTrunkCoreRadius() : config.getFallbackTrunkCoreRadius();
        int trunkMinHeight = biomeConfig != null && biomeConfig.fallbackTrunkMinHeight() != null
                ? biomeConfig.fallbackTrunkMinHeight() : config.getFallbackTrunkMinHeight();
        int maxRadius = biomeConfig != null && biomeConfig.fallbackMaxRadius() != null
                ? biomeConfig.fallbackMaxRadius() : config.getFallbackMaxRadius();
        double minDensity = biomeConfig != null && biomeConfig.fallbackMinDensity() != null
                ? biomeConfig.fallbackMinDensity() : config.getFallbackMinDensity();
        int ringStep = biomeConfig != null && biomeConfig.fallbackRingStep() != null
                ? biomeConfig.fallbackRingStep() : config.getFallbackRingStep();
        int maxBlocks = biomeConfig != null && biomeConfig.fallbackMaxBlocks() != null
                ? biomeConfig.fallbackMaxBlocks() : config.getFallbackMaxBlocks();

        // 1. Détecter la colonne de tronc principale autour du log frappé
        Set<BlockPos> coreColumns = detectCoreColumns(hitLog, allLogs, trunkCoreRadius, trunkMinHeight);

        if (coreColumns.isEmpty()) {
            // Pas de colonne de tronc trouvée → inclure au moins le log frappé
            coreColumns.add(hitLog);
        }

        // 2. Calculer le barycentre des colonnes core
        double centerX = 0, centerZ = 0;
        for (BlockPos p : coreColumns) {
            centerX += p.x();
            centerZ += p.z();
        }
        centerX /= coreColumns.size();
        centerZ /= coreColumns.size();

        // 3. Déterminer les bornes Y
        int yMin = Integer.MAX_VALUE, yMax = Integer.MIN_VALUE;
        for (BlockPos log : allLogs) {
            if (distanceXZ(log, centerX, centerZ) <= maxRadius) {
                yMin = Math.min(yMin, log.y());
                yMax = Math.max(yMax, log.y());
            }
        }
        if (yMin > yMax) {
            yMin = hitLog.y() - 5;
            yMax = hitLog.y() + 30;
        }

        // 4. Expansion par anneaux avec seuil de densité
        Set<BlockPos> includedLogs = new HashSet<>(coreColumns);
        int effectiveMaxRadius = maxRadius;

        for (int ring = 0; ring <= maxRadius; ring += ringStep) {
            int ringInner = ring;
            int ringOuter = ring + ringStep;

            // Compter les blocs dans cet anneau
            int totalBlocksInRing = 0;
            int treeBlocksInRing = 0;
            Set<BlockPos> ringLogs = new HashSet<>();

            for (int x = (int)(centerX - ringOuter); x <= (int)(centerX + ringOuter); x++) {
                for (int z = (int)(centerZ - ringOuter); z <= (int)(centerZ + ringOuter); z++) {
                    double dist = Math.sqrt((x - centerX) * (x - centerX) + (z - centerZ) * (z - centerZ));
                    if (dist < ringInner || dist >= ringOuter) continue;

                    for (int y = yMin; y <= yMax; y++) {
                        totalBlocksInRing++;
                        BlockPos pos = new BlockPos(x, y, z);
                        if (allLogs.contains(pos)) {
                            treeBlocksInRing++;
                            ringLogs.add(pos);
                        } else {
                            Material mat = getBlockMaterial(pos, snapshots, world);
                            if (isLeafOrAttachment(mat, biomeConfig, config)) {
                                treeBlocksInRing++;
                            }
                        }
                    }
                }
            }

            // Vérifier la densité
            double density = totalBlocksInRing > 0 ? (double) treeBlocksInRing / totalBlocksInRing : 0;

            if (ring > 0 && density < minDensity) {
                effectiveMaxRadius = ring;
                break;
            }

            includedLogs.addAll(ringLogs);

            // Vérifier la limite de blocs
            if (includedLogs.size() > maxBlocks) {
                break;
            }
        }

        // 5. Collecter les feuilles associées aux logs inclus
        int decayRangeXZ = (biomeConfig != null && biomeConfig.leafDecayRangeXZ() != null)
                ? biomeConfig.leafDecayRangeXZ() : config.getLeafDecayRangeXZ();
        int decayRangeY = (biomeConfig != null && biomeConfig.leafDecayRangeY() != null)
                ? biomeConfig.leafDecayRangeY() : config.getLeafDecayRangeY();

        Set<BlockPos> includedLeaves = scanLeaves(includedLogs, biomeConfig, config,
                snapshots, world, decayRangeXZ, decayRangeY);

        // Limiter les feuilles au cylindre effectif
        final double finalCenterX = centerX;
        final double finalCenterZ = centerZ;
        final int effRadius = effectiveMaxRadius + decayRangeXZ; // un peu plus large pour les feuilles
        includedLeaves.removeIf(p -> distanceXZ(p, finalCenterX, finalCenterZ) > effRadius);

        // 6. Calculer la santé
        double health = calculateHealth(includedLogs, includedLeaves, config, snapshots, world);

        if (config.isDebug()) {
            org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Fallback BFS feuilles: logs=" + includedLogs.size()
                    + " feuilles=" + includedLeaves.size());
        }

        return new SubTreePartition(includedLogs, includedLeaves, Collections.emptySet(), true, health);
    }

    /**
     * Détecte les colonnes de tronc autour du log frappé.
     */
    private static Set<BlockPos> detectCoreColumns(BlockPos hitLog, Set<BlockPos> allLogs,
                                                     int coreRadius, int minHeight) {
        Set<BlockPos> coreColumns = new HashSet<>();

        for (int dx = -coreRadius; dx <= coreRadius; dx++) {
            for (int dz = -coreRadius; dz <= coreRadius; dz++) {
                // Compter les logs consécutifs verticalement dans cette colonne
                int columnX = hitLog.x() + dx;
                int columnZ = hitLog.z() + dz;
                int consecutiveLogs = 0;
                int maxConsecutive = 0;

                // Scanner de hitLog.y() - 5 à hitLog.y() + 50
                for (int y = hitLog.y() - 5; y <= hitLog.y() + 50; y++) {
                    if (allLogs.contains(new BlockPos(columnX, y, columnZ))) {
                        consecutiveLogs++;
                        maxConsecutive = Math.max(maxConsecutive, consecutiveLogs);
                    } else {
                        consecutiveLogs = 0;
                    }
                }

                if (maxConsecutive >= minHeight) {
                    // Ajouter tous les logs de cette colonne
                    for (int y = hitLog.y() - 5; y <= hitLog.y() + 50; y++) {
                        BlockPos pos = new BlockPos(columnX, y, columnZ);
                        if (allLogs.contains(pos)) {
                            coreColumns.add(pos);
                        }
                    }
                }
            }
        }

        return coreColumns;
    }

    /**
     * Scanne les feuilles par BFS 6-way depuis les logs avec distances XZ/Y séparées.
     */
    private static Set<BlockPos> scanLeaves(Set<BlockPos> targetLogs,
                                             BiomeConfig biomeConfig, ConfigManager config,
                                             Map<Long, ChunkSnapshot> snapshots, World world,
                                             int decayRangeXZ, int decayRangeY) {
        Set<BlockPos> leaves = new HashSet<>();
        Map<BlockPos, int[]> depths = new HashMap<>(); // [distXZ, distY]
        Queue<BlockPos> queue = new LinkedList<>();

        for (BlockPos log : targetLogs) {
            depths.put(log, new int[]{0, 0});
            queue.add(log);
        }

        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            int[] currentDepth = depths.get(current);

            for (int[] dir : dirs) {
                BlockPos neighbor = current.add(dir[0], dir[1], dir[2]);
                if (depths.containsKey(neighbor)) continue;

                int newXZ = currentDepth[0] + Math.abs(dir[0]) + Math.abs(dir[2]);
                int newY = currentDepth[1] + Math.abs(dir[1]);

                if (newXZ > decayRangeXZ || newY > decayRangeY) continue;

                Material mat = getBlockMaterial(neighbor, snapshots, world);
                if (mat == null || mat.isAir()) continue;

                if (isLeafOrAttachment(mat, biomeConfig, config)) {
                    // Vérifier qu'on ne traverse pas vers un log étranger
                    boolean foreignLog = false;
                    for (int[] checkDir : dirs) {
                        BlockPos adj = neighbor.add(checkDir[0], checkDir[1], checkDir[2]);
                        Material adjMat = getBlockMaterial(adj, snapshots, world);
                        if (isLog(adjMat, biomeConfig, config) && !targetLogs.contains(adj)) {
                            foreignLog = true;
                            break;
                        }
                    }

                    if (!foreignLog) {
                        depths.put(neighbor, new int[]{newXZ, newY});
                        leaves.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }
        }

        return leaves;
    }

    private static double distanceXZ(BlockPos pos, double cx, double cz) {
        return Math.sqrt((pos.x() - cx) * (pos.x() - cx) + (pos.z() - cz) * (pos.z() - cz));
    }

    private static double calculateHealth(Set<BlockPos> logs, Set<BlockPos> leaves,
                                           ConfigManager config,
                                           Map<Long, ChunkSnapshot> snapshots, World world) {
        double h = 0;
        for (BlockPos pos : logs) {
            Material mat = getBlockMaterial(pos, snapshots, world);
            h += config.getLogWeights().getOrDefault(mat, 1.0);
        }
        for (BlockPos pos : leaves) {
            Material mat = getBlockMaterial(pos, snapshots, world);
            h += config.getLeafWeights().getOrDefault(mat, 0.1);
        }
        return h * config.getBaseCoefficient();
    }

    private static boolean isLog(Material mat, BiomeConfig biomeConfig, ConfigManager config) {
        if (mat == null) return false;
        return (biomeConfig != null && biomeConfig.logBlocks().contains(mat))
                || config.getLogWeights().containsKey(mat);
    }

    private static boolean isLeafOrAttachment(Material mat, BiomeConfig biomeConfig, ConfigManager config) {
        if (mat == null) return false;
        return (biomeConfig != null && (biomeConfig.leafBlocks().contains(mat) || biomeConfig.attachments().contains(mat)))
                || config.getLeafWeights().containsKey(mat)
                || mat.name().endsWith("_LEAVES");
    }

    private static Material getBlockMaterial(BlockPos pos, Map<Long, ChunkSnapshot> snapshots, World world) {
        int chunkX = pos.x() >> 4;
        int chunkZ = pos.z() >> 4;
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        ChunkSnapshot snapshot = snapshots.get(key);
        if (snapshot == null) return Material.AIR;
        if (pos.y() < world.getMinHeight() || pos.y() >= world.getMaxHeight()) return Material.AIR;
        return snapshot.getBlockType(pos.x() & 15, pos.y(), pos.z() & 15);
    }
}
