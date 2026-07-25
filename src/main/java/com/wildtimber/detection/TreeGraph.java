package com.wildtimber.detection;

import com.wildtimber.config.BiomeConfig;
import com.wildtimber.config.ConfigManager;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.*;

/**
 * Graphe de bûches pour la partition multi-source BFS.
 * Construit un graphe non-orienté G = (V, E) où V = logs et E = arêtes 26-way.
 * Identifie les racines (logs au sol), calcule les distances multi-source,
 * et détecte les goulots entre sous-arbres.
 */
public class TreeGraph {

    // Voisinage 26-way (inclut diagonales pour les arbres custom Iris)
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

    // Voisinage 6-way (faces uniquement)
    private static final int[][] NEIGHBORS_6 = {
        {1, 0, 0}, {-1, 0, 0},
        {0, 1, 0}, {0, -1, 0},
        {0, 0, 1}, {0, 0, -1}
    };

    /** Adjacence du graphe de logs */
    private final Map<BlockPos, Set<BlockPos>> adjacency = new HashMap<>();
    /** Tous les logs */
    private final Set<BlockPos> allLogs;
    /** Logs enracinés au sol */
    private final Set<BlockPos> roots = new HashSet<>();
    /** Mapping rootId → racine position */
    private final Map<Integer, BlockPos> rootById = new HashMap<>();
    /** Mapping racine position → rootId */
    private final Map<BlockPos, Integer> idByRoot = new HashMap<>();

    /** BFS results: log → rootId du tronc le plus proche */
    private Map<BlockPos, Integer> rootAssignment;
    /** BFS results: log → distance au tronc le plus proche */
    private Map<BlockPos, Integer> distanceMap;
    private final int[][] neighbors;

    private TreeGraph(Set<BlockPos> allLogs, boolean use6Way) {
        this.allLogs = allLogs;
        this.neighbors = use6Way ? NEIGHBORS_6 : NEIGHBORS_26;
    }

    /**
     * Construit le graphe de logs depuis un ensemble de blocs scannés.
     */
    public static TreeGraph build(Set<BlockPos> allLogs, BiomeConfig biomeConfig, ConfigManager configManager,
                                   Map<Long, ChunkSnapshot> snapshots, World world, boolean use6Way) {
        TreeGraph graph = new TreeGraph(allLogs, use6Way);
        graph.buildAdjacency();
        graph.identifyRoots(biomeConfig, configManager, snapshots, world);
        return graph;
    }

    /**
     * Construit les arêtes d'adjacence (6-way ou 26-way entre logs).
     */
    private void buildAdjacency() {
        for (BlockPos log : allLogs) {
            Set<BlockPos> neighborsSet = new HashSet<>();
            for (int[] d : neighbors) {
                BlockPos neighbor = log.add(d[0], d[1], d[2]);
                if (allLogs.contains(neighbor)) {
                    neighborsSet.add(neighbor);
                }
            }
            adjacency.put(log, neighborsSet);
        }
    }

    /**
     * Identifie les racines réelles (logs au sol) et crée des troncs virtuels
     * pour les composantes connexes sans racine au sol.
     */
    private void identifyRoots(BiomeConfig biomeConfig, ConfigManager configManager,
                                Map<Long, ChunkSnapshot> snapshots, World world) {
        int minLogsRooted = configManager.getMinLogsRooted();
        int minLogsIsolated = configManager.getMinLogsIsolated();

        // Trouver les logs enracinés au sol
        for (BlockPos log : allLogs) {
            BlockPos below = log.add(0, -1, 0);
            Material belowMat = getBlockMaterial(below, snapshots, world);

            if (belowMat != null && belowMat != Material.AIR
                    && belowMat != Material.CAVE_AIR && belowMat != Material.VOID_AIR
                    && belowMat != Material.WATER
                    && !isLog(belowMat, biomeConfig, configManager)
                    && !isLeafOrAttachment(belowMat, biomeConfig, configManager)) {
                roots.add(log);
            }
        }

        // Trouver les composantes connexes
        List<Set<BlockPos>> components = findConnectedComponents();

        int rootId = 0;

        for (Set<BlockPos> component : components) {
            // Trouver les racines dans cette composante
            Set<BlockPos> componentRoots = new HashSet<>();
            for (BlockPos log : component) {
                if (roots.contains(log)) {
                    componentRoots.add(log);
                }
            }

            if (!componentRoots.isEmpty() && component.size() >= minLogsRooted) {
                // Composante avec racines au sol → chaque colonne de racines = 1 tronc
                // On regroupe les racines proches comme un seul tronc (la plus basse)
                Set<BlockPos> processed = new HashSet<>();
                for (BlockPos root : componentRoots) {
                    if (processed.contains(root)) continue;

                    // BFS pour regrouper les racines connectées (proches au sol)
                    Queue<BlockPos> queue = new LinkedList<>();
                    Set<BlockPos> cluster = new HashSet<>();
                    queue.add(root);
                    cluster.add(root);
                    processed.add(root);

                    while (!queue.isEmpty()) {
                        BlockPos current = queue.poll();
                        for (int[] d : neighbors) {
                            BlockPos neighbor = current.add(d[0], d[1], d[2]);
                            if (componentRoots.contains(neighbor) && !processed.contains(neighbor)) {
                                // Regrouper si proche verticalement (même colonne ou adjacente)
                                if (Math.abs(neighbor.y() - root.y()) <= 3) {
                                    processed.add(neighbor);
                                    cluster.add(neighbor);
                                    queue.add(neighbor);
                                }
                            }
                        }
                    }

                    // La racine du cluster = la plus basse
                    BlockPos bestRoot = cluster.stream()
                            .min(Comparator.comparingInt(BlockPos::y))
                            .orElse(root);

                    rootById.put(rootId, bestRoot);
                    idByRoot.put(bestRoot, rootId);
                    rootId++;
                }
            } else if (componentRoots.isEmpty() && component.size() >= minLogsIsolated) {
                // Composante sans racine au sol → tronc virtuel (log le plus bas)
                BlockPos virtualRoot = component.stream()
                        .min(Comparator.comparingInt(BlockPos::y))
                        .orElse(component.iterator().next());

                rootById.put(rootId, virtualRoot);
                idByRoot.put(virtualRoot, rootId);
                rootId++;
            }
            // Sinon : composante trop petite sans racine → ignorée (vanilla)
        }
    }

    /**
     * Trouve les composantes connexes du graphe de logs.
     */
    public List<Set<BlockPos>> findConnectedComponents() {
        List<Set<BlockPos>> components = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();

        for (BlockPos log : allLogs) {
            if (visited.contains(log)) continue;

            Set<BlockPos> component = new HashSet<>();
            Queue<BlockPos> queue = new LinkedList<>();
            queue.add(log);
            visited.add(log);

            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                component.add(current);

                Set<BlockPos> neighbors = adjacency.getOrDefault(current, Collections.emptySet());
                for (BlockPos neighbor : neighbors) {
                    if (!visited.contains(neighbor)) {
                        visited.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }

            components.add(component);
        }

        return components;
    }

    /**
     * Lance le BFS multi-source depuis toutes les racines R.
     * Calcule root(v) et d(v) pour chaque log, et identifie les nœuds de frontière.
     */
    public void computeMultiSourceBFS() {
        rootAssignment = new HashMap<>();
        distanceMap = new HashMap<>();

        Queue<BlockPos> queue = new LinkedList<>();

        // Initialiser avec toutes les racines (réelles + virtuelles)
        for (Map.Entry<Integer, BlockPos> entry : rootById.entrySet()) {
            int rId = entry.getKey();
            BlockPos root = entry.getValue();
            rootAssignment.put(root, rId);
            distanceMap.put(root, 0);
            queue.add(root);
        }

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            int currentDist = distanceMap.get(current);
            int currentRoot = rootAssignment.get(current);

            Set<BlockPos> neighbors = adjacency.getOrDefault(current, Collections.emptySet());
            for (BlockPos neighbor : neighbors) {
                int newDist = currentDist + 1;

                if (!distanceMap.containsKey(neighbor)) {
                    // Pas encore visité
                    distanceMap.put(neighbor, newDist);
                    rootAssignment.put(neighbor, currentRoot);
                    queue.add(neighbor);
                }
            }
        }
    }

    /**
     * Représente une section de goulot entre deux sous-arbres.
     */
    public record BridgeSection(
        Set<BlockPos> nodes,     // logs impliqués dans la section
        int rootA,               // ID du premier sous-arbre
        int rootB,               // ID du second sous-arbre
        int minY,                // Y minimum de la section
        int maxY,                // Y maximum de la section
        double cost              // coût pondéré par hauteur
    ) {
        public int size() { return nodes.size(); }
    }

    /**
     * Détecte les goulots (bridges) entre deux sous-arbres donnés.
     * Regroupe les arêtes de jonction par tranche de hauteur.
     */
    public List<BridgeSection> findBridges(int rootA, int rootB, int sMax, double cMax, double alpha) {
        List<BridgeSection> bridges = new ArrayList<>();
        if (rootAssignment == null) return bridges;

        // Collecter les nœuds de frontière entre rootA et rootB
        Set<BlockPos> bridgeNodes = new HashSet<>();
        for (BlockPos log : allLogs) {
            Integer assignedRoot = rootAssignment.get(log);
            if (assignedRoot == null) continue;

            Set<BlockPos> neighbors = adjacency.getOrDefault(log, Collections.emptySet());
            for (BlockPos neighbor : neighbors) {
                Integer neighborRoot = rootAssignment.get(neighbor);
                if (neighborRoot == null) continue;

                if ((assignedRoot.equals(rootA) && neighborRoot.equals(rootB))
                        || (assignedRoot.equals(rootB) && neighborRoot.equals(rootA))) {
                    bridgeNodes.add(log);
                    bridgeNodes.add(neighbor);
                }
            }
        }

        if (bridgeNodes.isEmpty()) return bridges;

        // Grouper par tranche de hauteur (4 blocs)
        int yMin = bridgeNodes.stream().mapToInt(BlockPos::y).min().orElse(0);
        int yMax = bridgeNodes.stream().mapToInt(BlockPos::y).max().orElse(0);
        int sliceHeight = 4;

        for (int y = yMin; y <= yMax; y += sliceHeight) {
            final int sliceYMin = y;
            final int sliceYMax = y + sliceHeight - 1;

            Set<BlockPos> sliceNodes = new HashSet<>();
            for (BlockPos node : bridgeNodes) {
                if (node.y() >= sliceYMin && node.y() <= sliceYMax) {
                    sliceNodes.add(node);
                }
            }

            if (sliceNodes.isEmpty()) continue;

            // Calculer le coût pondéré par hauteur
            double cost = 0;
            for (BlockPos node : sliceNodes) {
                cost += 1.0 + alpha * (node.y() - yMin);
            }

            BridgeSection section = new BridgeSection(sliceNodes, rootA, rootB, sliceYMin, sliceYMax, cost);

            // Vérifier si c'est un goulot admissible
            if (section.size() <= sMax && cost <= cMax) {
                bridges.add(section);
            }
        }

        return bridges;
    }

    /**
     * Extrait le sous-arbre associé au tronc ciblé (le log frappé par le joueur).
     * Si des goulots admissibles sont trouvés, sépare proprement.
     * Sinon, retourne null (le caller doit essayer le fallback).
     */
    public Set<BlockPos> extractSubTree(BlockPos hitLog, int sMax, double cMax, double alpha) {
        if (rootAssignment == null) computeMultiSourceBFS();

        Integer targetRootId = rootAssignment.get(hitLog);
        if (targetRootId == null) {
            // Le log frappé n'est pas assigné → fallback
            return null;
        }

        // Vérifier s'il n'y a qu'un seul tronc → pas besoin de séparation
        if (rootById.size() <= 1) {
            Set<BlockPos> result = new HashSet<>();
            for (Map.Entry<BlockPos, Integer> entry : rootAssignment.entrySet()) {
                if (entry.getValue().equals(targetRootId)) {
                    result.add(entry.getKey());
                }
            }
            return result;
        }

        // Trouver les racines voisines connectées à targetRootId
        Set<Integer> connectedRoots = new HashSet<>();
        for (BlockPos log : allLogs) {
            Integer assignedRoot = rootAssignment.get(log);
            if (assignedRoot == null || assignedRoot.equals(targetRootId)) continue;

            Set<BlockPos> neighbors = adjacency.getOrDefault(log, Collections.emptySet());
            for (BlockPos neighbor : neighbors) {
                Integer neighborRoot = rootAssignment.get(neighbor);
                if (neighborRoot != null && neighborRoot.equals(targetRootId)) {
                    connectedRoots.add(assignedRoot);
                    break;
                }
            }
        }

        // Vérifier que chaque voisin a au moins un goulot admissible
        boolean allHaveBridges = true;
        Set<BlockPos> allBridgeCutNodes = new HashSet<>();

        for (int otherRootId : connectedRoots) {
            List<BridgeSection> bridges = findBridges(targetRootId, otherRootId, sMax, cMax, alpha);
            if (bridges.isEmpty()) {
                allHaveBridges = false;
                break;
            }
            // Prendre le meilleur goulot (le plus petit coût)
            BridgeSection best = bridges.stream()
                    .min(Comparator.comparingDouble(BridgeSection::cost))
                    .orElse(null);
            if (best != null) {
                // Les nœuds du goulot appartenant aux autres arbres restent, 
                // ceux du target tree sont inclus dans la coupe
                for (BlockPos node : best.nodes()) {
                    Integer nodeRoot = rootAssignment.get(node);
                    if (nodeRoot != null && nodeRoot.equals(targetRootId)) {
                        allBridgeCutNodes.add(node);
                    }
                }
            }
        }

        if (!allHaveBridges) {
            return null; // Pas de séparation propre → fallback
        }

        // Extraire tous les logs du sous-arbre ciblé
        Set<BlockPos> result = new HashSet<>();
        for (Map.Entry<BlockPos, Integer> entry : rootAssignment.entrySet()) {
            if (entry.getValue().equals(targetRootId)) {
                result.add(entry.getKey());
            }
        }
        // Ajouter les nœuds de frontière du target
        result.addAll(allBridgeCutNodes);

        return result;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public Set<BlockPos> getAllLogs() { return allLogs; }
    public Set<BlockPos> getRoots() { return roots; }
    public Map<Integer, BlockPos> getRootById() { return rootById; }
    public Map<BlockPos, Integer> getRootAssignment() { return rootAssignment; }
    public Map<BlockPos, Integer> getDistanceMap() { return distanceMap; }
    public int getRootCount() { return rootById.size(); }

    /**
     * Retourne l'ID de la racine associée au log frappé.
     */
    public Integer getRootIdForLog(BlockPos log) {
        return rootAssignment != null ? rootAssignment.get(log) : null;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean isLog(Material material, BiomeConfig biomeConfig, ConfigManager configManager) {
        if (material == null) return false;
        return biomeConfig.logBlocks().contains(material)
                || configManager.getLogWeights().containsKey(material);
    }

    private static boolean isLeafOrAttachment(Material material, BiomeConfig biomeConfig, ConfigManager configManager) {
        if (material == null) return false;
        return biomeConfig.leafBlocks().contains(material)
                || biomeConfig.attachments().contains(material)
                || configManager.getLeafWeights().containsKey(material);
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
