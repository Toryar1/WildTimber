package com.wildtimber.felling;

import com.wildtimber.WildTimber;
import com.wildtimber.ConsoleColor;
import org.bukkit.Bukkit;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Gestionnaire FIFO de coupes. Garantit qu'une seule coupe progressive
 * est active à la fois et que chaque job est traité intégralement.
 */
public class CutJobManager {

    private final WildTimber plugin;
    private final Queue<CutJob> pendingJobs = new ArrayDeque<>();
    private CutJob activeJob = null;

    public CutJobManager(WildTimber plugin) {
        this.plugin = plugin;
    }

    /**
     * Soumet un nouveau job de coupe. Si aucun job n'est actif, il démarre immédiatement.
     * Sinon, il est mis en file d'attente.
     *
     * @return true si le job a démarré immédiatement, false s'il est en queue
     */
    public synchronized boolean submit(CutJob job) {
        if (activeJob == null) {
            startJob(job);
            return true;
        } else {
            job.setStatus(CutJob.Status.QUEUED);
            pendingJobs.add(job);

            Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX
                    + "Cut queued → " + job.toShortString()
                    + " | queue_size=" + pendingJobs.size());
            return false;
        }
    }

    /**
     * Démarre un job de coupe. Appelé depuis submit() ou quand le job précédent se termine.
     */
    private void startJob(CutJob job) {
        activeJob = job;
        job.setStatus(CutJob.Status.RUNNING);
        job.setStartedAt(System.currentTimeMillis());

        Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX
                + "Cut started → " + job.toShortString());

        // Lancer le scheduler qui exécutera les tranches une par une
        StagedCutScheduler scheduler = new StagedCutScheduler(plugin);
        scheduler.executeJob(job, this);
    }

    /**
     * Appelé par le StagedCutScheduler quand un job termine (succès ou partiel).
     */
    public synchronized void onJobComplete(CutJob job) {
        job.setFinishedAt(System.currentTimeMillis());

        int planned = job.getPlannedTotal();
        int processed = job.getTotalBlocksProcessed();
        int skippedAir = job.getBlocksSkippedAir();
        int missing = planned - processed - skippedAir;
        long durationMs = job.getFinishedAt() - job.getStartedAt();

        if (missing > (planned * 0.05)) {
            job.setStatus(CutJob.Status.FAILED_PARTIAL);
            Bukkit.getLogger().warning(ConsoleColor.WARN_PREFIX
                    + "Cut incomplete → jobId=" + job.getJobId().toString().substring(0, 8)
                    + " | missing=" + missing + " blocs | planned_total=" + planned
                    + " | processed=" + processed + " | skipped_air=" + skippedAir);
        } else {
            job.setStatus(CutJob.Status.FINISHED);
            if (skippedAir > 0) {
                Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX
                        + "Cut finished with air overlap → jobId=" + job.getJobId().toString().substring(0, 8)
                        + " | overlap=" + skippedAir + " blocs (zone partagée) | duration=" + durationMs + "ms");
            } else {
                Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX
                        + "Cut finished → " + job.toShortString()
                        + " | duration=" + durationMs + "ms");
            }
        }

        activeJob = null;

        // Dépiler le job suivant
        CutJob next = pendingJobs.poll();
        if (next != null) {
            Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX
                    + "Dequeueing next cut job. Remaining in queue: " + pendingJobs.size());
            startJob(next);
        }
    }

    /**
     * Annule le job actif et vide la queue. Appelé à l'arrêt du plugin.
     */
    public synchronized void cancelAll() {
        int cancelled = 0;

        if (activeJob != null) {
            activeJob.setStatus(CutJob.Status.CANCELLED);
            if (activeJob.getSchedulerTask() != null) {
                activeJob.getSchedulerTask().cancel();
            }
            if (activeJob.getPersistenceTask() != null) {
                activeJob.getPersistenceTask().cancel();
            }
            activeJob = null;
            cancelled++;
        }

        for (CutJob job : pendingJobs) {
            job.setStatus(CutJob.Status.CANCELLED);
            cancelled++;
        }
        int queued = pendingJobs.size();
        pendingJobs.clear();

        if (cancelled > 0) {
            Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX
                    + "CutJobManager: cancelled " + cancelled + " jobs (1 active + " + queued + " queued)");
        }
    }

    /**
     * @return le job actif, ou null si aucun
     */
    public synchronized CutJob getActiveJob() {
        return activeJob;
    }

    /**
     * @return le nombre de jobs en attente
     */
    public synchronized int getQueueSize() {
        return pendingJobs.size();
    }

    /**
     * @return true si un job est en cours d'exécution
     */
    public synchronized boolean hasActiveJob() {
        return activeJob != null;
    }
}
