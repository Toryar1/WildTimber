package com.wildtimber.felling;

import com.wildtimber.config.BiomeConfig;
import com.wildtimber.detection.BlockPos;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Représente une coupe d'arbre en cours ou en attente d'exécution.
 * Contient l'intégralité de l'état nécessaire pour exécuter la coupe
 * de manière autonome et vérifiable.
 */
public class CutJob {

    public enum Status {
        QUEUED,
        RUNNING,
        FINISHED,
        FAILED_PARTIAL,
        CANCELLED
    }

    // Identifiants
    private final UUID jobId;
    private final UUID playerId;
    private final String playerName;
    private final World world;
    private final BlockPos startPos;
    private final String biomeName;
    private final BiomeConfig biomeConfig;

    // État
    private Status status;

    // Collections de blocs (snapshots immutables au moment de la création)
    private final List<List<BlockPos>> logSlices;
    private final List<List<BlockPos>> leafSlices;
    private final List<List<BlockPos>> canopySlices;
    private final Set<BlockPos> allLogs;
    private final Set<BlockPos> allLeaves;
    private final Set<BlockPos> allCanopy;
    private final Map<Long, FillTarget> heightmap;

    // Dimensions
    private final int coreSliceCount;
    private final int canopySliceCount;
    private final int totalSlices;
    private final int totalBlocksPlanned;

    // Compteurs mutables (mis à jour pendant l'exécution)
    private int currentSlice;
    private int totalBlocksProcessed;
    private int blocksSkippedAir;

    // Timestamps
    private final long createdAt;
    private long startedAt;
    private long finishedAt;

    // Référence au scheduler pour annulation
    private BukkitTask schedulerTask;
    private org.bukkit.scheduler.BukkitTask persistenceTask;

    // Tool du joueur pour les drops
    private final org.bukkit.inventory.ItemStack tool;

    public CutJob(UUID playerId, String playerName, World world, BlockPos startPos,
                  String biomeName, BiomeConfig biomeConfig,
                  List<List<BlockPos>> logSlices, List<List<BlockPos>> leafSlices,
                  List<List<BlockPos>> canopySlices,
                  Set<BlockPos> allLogs, Set<BlockPos> allLeaves, Set<BlockPos> allCanopy,
                  Map<Long, FillTarget> heightmap,
                  org.bukkit.inventory.ItemStack tool) {
        this.jobId = UUID.randomUUID();
        this.playerId = playerId;
        this.playerName = playerName;
        this.world = world;
        this.startPos = startPos;
        this.biomeName = biomeName;
        this.biomeConfig = biomeConfig;
        this.logSlices = logSlices;
        this.leafSlices = leafSlices;
        this.canopySlices = canopySlices;
        this.allLogs = allLogs;
        this.allLeaves = allLeaves;
        this.allCanopy = allCanopy;
        this.heightmap = heightmap;
        this.tool = tool;

        this.coreSliceCount = Math.max(logSlices.size(), leafSlices.size());
        this.canopySliceCount = canopySlices.size();
        this.totalSlices = coreSliceCount + canopySliceCount;
        this.totalBlocksPlanned = allLogs.size() + allLeaves.size() + allCanopy.size();

        this.status = Status.QUEUED;
        this.currentSlice = 0;
        this.totalBlocksProcessed = 0;
        this.blocksSkippedAir = 0;
        this.createdAt = System.currentTimeMillis();
    }

    // --- Getters ---

    public UUID getJobId() { return jobId; }
    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public World getWorld() { return world; }
    public BlockPos getStartPos() { return startPos; }
    public String getBiomeName() { return biomeName; }
    public BiomeConfig getBiomeConfig() { return biomeConfig; }
    public Status getStatus() { return status; }
    public List<List<BlockPos>> getLogSlices() { return logSlices; }
    public List<List<BlockPos>> getLeafSlices() { return leafSlices; }
    public List<List<BlockPos>> getCanopySlices() { return canopySlices; }
    public Set<BlockPos> getAllLogs() { return allLogs; }
    public Set<BlockPos> getAllLeaves() { return allLeaves; }
    public Set<BlockPos> getAllCanopy() { return allCanopy; }
    public Map<Long, FillTarget> getHeightmap() { return heightmap; }
    public int getCoreSliceCount() { return coreSliceCount; }
    public int getCanopySliceCount() { return canopySliceCount; }
    public int getTotalSlices() { return totalSlices; }
    public int getTotalBlocksPlanned() { return totalBlocksPlanned; }
    public int getCurrentSlice() { return currentSlice; }
    public int getTotalBlocksProcessed() { return totalBlocksProcessed; }
    public int getBlocksSkippedAir() { return blocksSkippedAir; }
    public long getCreatedAt() { return createdAt; }
    public long getStartedAt() { return startedAt; }
    public long getFinishedAt() { return finishedAt; }
    public BukkitTask getSchedulerTask() { return schedulerTask; }
    public org.bukkit.scheduler.BukkitTask getPersistenceTask() { return persistenceTask; }
    public org.bukkit.inventory.ItemStack getTool() { return tool; }

    // --- Setters ---

    public void setStatus(Status status) { this.status = status; }
    public void setCurrentSlice(int currentSlice) { this.currentSlice = currentSlice; }
    public void setStartedAt(long startedAt) { this.startedAt = startedAt; }
    public void setFinishedAt(long finishedAt) { this.finishedAt = finishedAt; }
    public void setSchedulerTask(BukkitTask task) { this.schedulerTask = task; }
    public void setPersistenceTask(org.bukkit.scheduler.BukkitTask task) { this.persistenceTask = task; }

    public void incrementProcessed() { this.totalBlocksProcessed++; }
    public void incrementSkippedAir() { this.blocksSkippedAir++; }
    public void advanceSlice() { this.currentSlice++; }

    /**
     * Vérifie si le job est terminé (toutes les tranches traitées).
     */
    public boolean isComplete() {
        return currentSlice >= (coreSliceCount + canopySliceCount);
    }

    public int getPlannedCore() {
        return allLogs.size() + allLeaves.size();
    }

    public int getPlannedCanopy() {
        return allCanopy.size();
    }

    public int getPlannedTotal() {
        return totalBlocksPlanned;
    }

    /**
     * Résumé court pour les logs.
     */
    public String toShortString() {
        return "jobId=" + jobId.toString().substring(0, 8)
                + " | player=" + playerName
                + " | planned_core=" + getPlannedCore()
                + " | planned_canopy=" + getPlannedCanopy()
                + " | planned_total=" + getPlannedTotal()
                + " | processed=" + totalBlocksProcessed
                + " | skipped_air=" + blocksSkippedAir
                + " | slices=" + currentSlice + "/" + totalSlices + " (" + coreSliceCount + " core + " + canopySliceCount + " canopy)";
    }
}
