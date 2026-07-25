package com.wildtimber.felling;

import com.wildtimber.WildTimber;
import com.wildtimber.ConsoleColor;
import com.wildtimber.config.BiomeConfig;
import com.wildtimber.config.ConfigManager;
import com.wildtimber.detection.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Planifie et exécute la coupe progressive d'un arbre en tranches de hauteur.
 * Utilise un unique runTaskTimer par job au lieu de N×runTaskLater.
 */
public class StagedCutScheduler {

    private final WildTimber plugin;
    private final ConfigManager configManager;

    public StagedCutScheduler(WildTimber plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
    }

    /**
     * Crée un CutJob à partir d'un ActiveTree et le soumet au CutJobManager.
     *
     * @return le CutJob créé
     */
    public CutJob createAndSubmitJob(com.wildtimber.manager.ActiveTree tree, Player cutter,
                                      int sliceHeight, Set<BlockPos> canopyBlocks) {
        World world = tree.getWorld();
        BiomeConfig biomeConfig = configManager.getBiomeConfig(tree.getBiomeName());

        // Snapshot immutable des blocs à couper AVANT le démarrage
        Set<BlockPos> snapshotLogs = new HashSet<>(tree.getLogs());
        Set<BlockPos> snapshotLeaves = new HashSet<>(tree.getLeaves());
        Set<BlockPos> snapshotCanopy = new HashSet<>(canopyBlocks);

        Map<Long, FillTarget> heightmap = plugin.getTreeFeller().takeHeightmapSnapshot(world, snapshotLogs, biomeConfig);

        int plannedTotal = snapshotLogs.size() + snapshotLeaves.size() + snapshotCanopy.size();
        int maxBlocksPerSlice = 200;
        if (plannedTotal > 8000) {
            maxBlocksPerSlice = 100;
        } else if (plannedTotal > 5000) {
            maxBlocksPerSlice = 150;
        }

        // Trier les blocs par tranche de hauteur (du bas vers le haut) et subdiviser si trop denses
        List<List<BlockPos>> logSlices = partitionByHeight(snapshotLogs, sliceHeight, maxBlocksPerSlice);
        List<List<BlockPos>> leafSlices = partitionByHeight(snapshotLeaves, sliceHeight, maxBlocksPerSlice);
        List<List<BlockPos>> canopySlices = partitionByHeight(snapshotCanopy, sliceHeight, maxBlocksPerSlice);

        // Persistence update en batch
        Map<BlockPos, Material> blockCache = new HashMap<>();
        Map<BlockPos, Boolean> resultCache = new HashMap<>();
        Set<BlockPos> leavesToUpdate = plugin.getTreeFeller().collectLeavesForPersistenceUpdate(
                world, snapshotLogs, snapshotLeaves, biomeConfig, resultCache, blockCache);
        int batchSize = configManager.getLeavesPersistenceBatchSize();

        if (configManager.isDebug()) {
            Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Persistence update : "
                    + leavesToUpdate.size() + " feuilles en queue, batch=" + batchSize);
        }


        ItemStack tool = cutter != null ? cutter.getInventory().getItemInMainHand().clone() : null;
        UUID playerId = cutter != null ? cutter.getUniqueId() : null;
        String playerName = cutter != null ? cutter.getName() : "unknown";
        BlockPos startPos = snapshotLogs.isEmpty() ? new BlockPos(0, 0, 0) : snapshotLogs.iterator().next();

        CutJob job = new CutJob(
                playerId, playerName, world, startPos,
                tree.getBiomeName(), biomeConfig,
                logSlices, leafSlices, canopySlices,
                snapshotLogs, snapshotLeaves, snapshotCanopy,
                heightmap, tool
        );

        // Démarrer la mise à jour de persistance des feuilles (maintenant que job existe)
        if (!leavesToUpdate.isEmpty()) {
            startPersistenceUpdateTask(job, world, new ArrayDeque<>(leavesToUpdate), batchSize);
        }

        plugin.getCutJobManager().submit(job);


        if (configManager.isDebug()) {
            Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Coupe progressive planifiée: "
                    + job.getTotalSlices() + " tranches, intervalle="
                    + configManager.getStagedCutIntervalTicks() + " ticks, total="
                    + job.getTotalBlocksPlanned() + " blocs");
        }

        return job;
    }

    /**
     * Exécute un CutJob en planifiant chaque tranche tick par tick.
     * Appelé par CutJobManager.startJob().
     */
    public void executeJob(CutJob job, CutJobManager manager) {
        int intervalTicks = configManager.getStagedCutIntervalTicks();
        scheduleNextSlice(job, manager, intervalTicks);
    }

    private void scheduleNextSlice(CutJob job, CutJobManager manager, int nextTickDelay) {
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                if (job.getStatus() == CutJob.Status.CANCELLED) {
                    return;
                }

                long start = System.currentTimeMillis();
                try {
                    processSlice(job);
                    job.advanceSlice(); // only advance if no exception
                } catch (Exception e) {
                    Bukkit.getLogger().severe(ConsoleColor.WARN_PREFIX
                            + "Erreur tranche " + job.getCurrentSlice() + " : " + e.getMessage());
                    e.printStackTrace();
                    // Ne pas avancer la slice : elle sera réessayée au prochain tick
                }

                if (job.isComplete()) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        try {
                            finalizeJob(job);
                        } catch (Exception e) {
                            Bukkit.getLogger().severe(ConsoleColor.WARN_PREFIX
                                    + "Erreur finalisation : " + e.getMessage());
                            e.printStackTrace();
                        }
                        manager.onJobComplete(job);
                    }, 1L);
                } else {
                    long elapsed = System.currentTimeMillis() - start;
                    int baseInterval = configManager.getStagedCutIntervalTicks();
                    int nextDelay = baseInterval;
                    if (elapsed > 80) {
                        nextDelay = baseInterval * 2;
                        if (configManager.isDebug()) {
                            Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX 
                                    + "Tranche lente detectee (" + elapsed + "ms) -> doublement du delai pour le tick suivant (" + nextDelay + " ticks)");
                        }
                    }
                    scheduleNextSlice(job, manager, nextDelay);
                }
            }
        }, nextTickDelay);
        job.setSchedulerTask(task);
    }

    /**
     * Traite une seule tranche du job.
     */
    private void processSlice(CutJob job) {
        long start = System.nanoTime();
        int sliceIndex = job.getCurrentSlice();
        int blocksInSlice = 0;

        World world = job.getWorld();
        BiomeConfig biomeConfig = job.getBiomeConfig();
        int coreSliceCount = job.getCoreSliceCount();

        if (sliceIndex < coreSliceCount) {
            // Couper les logs de cette tranche
            if (sliceIndex < job.getLogSlices().size()) {
                List<BlockPos> sliceLogs = job.getLogSlices().get(sliceIndex);
                blocksInSlice += sliceLogs.size();
                double logYieldMultiplier = configManager.getLogYieldMultiplier();
                for (BlockPos pos : sliceLogs) {
                    org.bukkit.block.Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
                    Material mat = block.getType();
                    if (mat.isAir()) {
                        job.incrementSkippedAir();
                        continue;
                    }
                    if (mat == Material.SNOW || mat == Material.SNOW_BLOCK || mat == Material.POWDER_SNOW) {
                        job.incrementSkippedAir();
                        continue;
                    }

                    // Drops respectant le yield multiplier
                    if (mat.isItem() && java.util.concurrent.ThreadLocalRandom.current().nextDouble() < logYieldMultiplier) {
                        Location dropLoc = new Location(world, pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5);
                        try {
                            world.dropItemNaturally(dropLoc, new ItemStack(mat, 1));
                        } catch (Exception e) { /* ignore */ }
                    }

                    fellBlock(block, Material.AIR, false);
                    job.incrementProcessed();
                }
            }

            // Couper les feuilles de cette tranche
            if (sliceIndex < job.getLeafSlices().size()) {
                List<BlockPos> sliceLeaves = job.getLeafSlices().get(sliceIndex);
                blocksInSlice += sliceLeaves.size();
                Map<BlockPos, Material> blockCache = new HashMap<>();
                Map<BlockPos, Boolean> resultCache = new HashMap<>();

                for (BlockPos pos : sliceLeaves) {
                    org.bukkit.block.Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
                    Material mat = block.getType();
                    if (mat.isAir()) {
                        job.incrementSkippedAir();
                        continue;
                    }
                    if (mat == Material.SNOW || mat == Material.SNOW_BLOCK || mat == Material.POWDER_SNOW) {
                        job.incrementSkippedAir();
                        continue;
                    }

                    // Protection de ceinture configurable autour des troncs étrangers
                    int beltRadius = biomeConfig != null ? biomeConfig.protectionBeltRadius() : 4;
                    if (plugin.getTreeFeller().isNearForeignLog(world, pos, job.getAllLogs(), beltRadius, biomeConfig, resultCache, blockCache)) {
                        org.bukkit.block.data.BlockData data = block.getBlockData();
                        if (data instanceof Leaves leavesData) {
                            if (!leavesData.isPersistent()) {
                                leavesData.setPersistent(true);
                                block.setBlockData(leavesData, false);
                            }
                        }
                        job.incrementSkippedAir();
                        continue;
                    }

                    plugin.getTreeFeller().dropLeaf(block);
                    block.setType(Material.AIR, false);
                    plugin.getTreeFeller().postFellPhysicsUpdate(block);
                    job.incrementProcessed();
                }
            }


        } else {
            // Couper la canopée orpheline (tranche progressive)
            int canopyIdx = sliceIndex - coreSliceCount;
            if (canopyIdx < job.getCanopySlices().size()) {
                List<BlockPos> sliceCanopy = job.getCanopySlices().get(canopyIdx);
                blocksInSlice += sliceCanopy.size();
                for (BlockPos pos : sliceCanopy) {
                    org.bukkit.block.Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
                    Material mat = block.getType();
                    if (mat.isAir()) {
                        job.incrementSkippedAir();
                        continue;
                    }
                    if (mat == Material.SNOW || mat == Material.SNOW_BLOCK || mat == Material.POWDER_SNOW) {
                        job.incrementSkippedAir();
                        continue;
                    }

                    plugin.getTreeFeller().cleanBlockAbove(world, pos);
                    if (plugin.getTreeFeller().isLog(mat, biomeConfig)) {
                        fellBlock(block, Material.AIR, false);
                        if (mat.isItem()) {
                            Location dropLoc = new Location(world, pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5);
                            try {
                                world.dropItemNaturally(dropLoc, new ItemStack(mat, 1));
                            } catch (Exception e) { /* ignore */ }
                        }
                    } else {
                        if (plugin.getTreeFeller().isLeafMaterial(mat)) {
                            plugin.getTreeFeller().dropLeaf(block);
                            block.setType(Material.AIR, false);
                        } else {
                            block.breakNaturally();
                        }
                        plugin.getTreeFeller().postFellPhysicsUpdate(block);
                    }
                    job.incrementProcessed();
                }
            }
        }

        // Diagnostic de performance
        long elapsed = System.nanoTime() - start;
        long elapsedMs = elapsed / 1_000_000L;
        if (elapsedMs > 50L) {
            Bukkit.getLogger().warning(ConsoleColor.WARN_PREFIX + "Tranche lente : " + elapsedMs
                    + "ms | tranche=" + sliceIndex + " | blocs=" + blocksInSlice
                    + " | est_dernière=" + job.isComplete());
        }
    }

    /**
     * Finalisation du job après la dernière tranche.
     */
    private void finalizeJob(CutJob job) {
        World world = job.getWorld();
        BiomeConfig biomeConfig = job.getBiomeConfig();

        // Rebouchage des racines avec 1 tick de délai
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.getTreeFeller().backfillRoots(world, job.getAllLogs(), biomeConfig, job.getHeightmap(), job.getJobId());
        }, 1L);

        // Nettoyage des blocs flottants
        plugin.getTreeFeller().cleanupFloatingBlocks(world, job.getAllLogs(), job.getAllLeaves(), biomeConfig);

        // Nettoyage des bûches orphelines
        plugin.getTreeFeller().cleanupIsolatedLogs(world, job.getAllLogs(), job.getAllLeaves(), biomeConfig);

        if (configManager.isDebug()) {
            Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Coupe progressive terminée. " + job.toShortString());
        }

        if (job.getPlayerId() != null) {
            Player player = Bukkit.getPlayer(job.getPlayerId());
            if (player != null && player.isOnline()) {
                double durationSec = (System.currentTimeMillis() - job.getStartedAt()) / 1000.0;
                plugin.getTreeFeller().sendFellCompletionMessage(
                        player,
                        job.getAllLogs().size(),
                        job.getAllLeaves().size() + job.getAllCanopy().size(),
                        job.getBiomeName(),
                        durationSec
                );
            }
        }
    }

    /**
     * Partitionne un ensemble de positions en tranches de hauteur.
     */
    private List<List<BlockPos>> partitionByHeight(Set<BlockPos> positions, int sliceHeight, int maxBlocksPerSlice) {
        if (positions.isEmpty()) return Collections.emptyList();

        int minY = positions.stream().mapToInt(BlockPos::y).min().orElse(0);
        int maxY = positions.stream().mapToInt(BlockPos::y).max().orElse(0);

        int numSlices = ((maxY - minY) / sliceHeight) + 1;
        List<List<BlockPos>> heightSlices = new ArrayList<>();
        for (int i = 0; i < numSlices; i++) {
            heightSlices.add(new ArrayList<>());
        }

        for (BlockPos pos : positions) {
            int sliceIdx = (pos.y() - minY) / sliceHeight;
            sliceIdx = Math.min(sliceIdx, numSlices - 1);
            heightSlices.get(sliceIdx).add(pos);
        }

        List<List<BlockPos>> finalSlices = new ArrayList<>();
        for (List<BlockPos> slice : heightSlices) {
            if (slice.isEmpty()) continue;
            for (int i = 0; i < slice.size(); i += maxBlocksPerSlice) {
                finalSlices.add(new ArrayList<>(slice.subList(i, Math.min(slice.size(), i + maxBlocksPerSlice))));
            }
        }

        return finalSlices;
    }

    private void fellBlock(org.bukkit.block.Block block, Material replacement, boolean physics) {
        org.bukkit.block.data.BlockData blockData = block.getBlockData();
        boolean setWater = blockData instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged();

        if (replacement == Material.AIR) {
            block.setType(setWater ? Material.WATER : Material.AIR, physics);
        } else {
            block.setType(replacement, physics);
        }

        if (!physics) {
            plugin.getTreeFeller().postFellPhysicsUpdate(block);
        }
    }

    private void startPersistenceUpdateTask(CutJob job, World world, Queue<BlockPos> pendingLeaves, int batchSize) {
        if (pendingLeaves.isEmpty()) return;

        org.bukkit.scheduler.BukkitTask[] taskRef = new org.bukkit.scheduler.BukkitTask[1];
        taskRef[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (pendingLeaves.isEmpty() || job.getStatus() == CutJob.Status.CANCELLED) {
                taskRef[0].cancel();
                return;
            }
            int processed = 0;
            while (!pendingLeaves.isEmpty() && processed < batchSize) {
                BlockPos pos = pendingLeaves.poll();
                org.bukkit.block.Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
                org.bukkit.block.data.BlockData data = block.getBlockData();
                if (data instanceof Leaves leavesData) {
                    if (leavesData.isPersistent()) {
                        leavesData.setPersistent(false);
                        leavesData.setDistance(7);
                        block.setBlockData(leavesData, true);
                    }
                }
                processed++;
            }
        }, 1L, 1L);
        job.setPersistenceTask(taskRef[0]);
    }
}
