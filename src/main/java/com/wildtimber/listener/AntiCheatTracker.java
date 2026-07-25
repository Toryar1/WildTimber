package com.wildtimber.listener;

import com.wildtimber.config.ConfigManager;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracker anti-triche pour détecter les patterns de clics robotiques.
 * Vérifie le cooldown minimum entre clics, le mouvement de vue, et la régularité des intervalles.
 */
public class AntiCheatTracker {

    private record ClickRecord(long timestamp, float yaw, float pitch) {}

    // Historique des clics par joueur (max 20 entrées)
    private final Map<UUID, List<ClickRecord>> history = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 20;

    /**
     * Vérifie si un clic est autorisé pour le joueur donné.
     * @return true si le clic est autorisé, false si triche détectée
     */
    public boolean isClickAllowed(Player player, ConfigManager config, boolean isRightClick) {
        if (!config.isAntiCheatEnabled()) return true;

        // En clic droit avec une hache, la cadence est déjà strictement contrôlée
        // par le cooldown dynamique de casse du bloc (break speed). On évite tout faux positif.
        if (isRightClick) return true;

        UUID uuid = player.getUniqueId();
        List<ClickRecord> clicks = history.get(uuid);
        if (clicks == null) return true;
        synchronized (clicks) {
            if (clicks.isEmpty()) return true;

            long now = System.currentTimeMillis();
            ClickRecord last = clicks.get(clicks.size() - 1);
            long interval = now - last.timestamp;

            // Détection autoclicker inhumain (< 30ms entre clics gauches sans mouvement)
            if (interval < 30) {
                double minLookChange = config.getAntiCheatMinLookChangeDegrees();
                float yawDiff = Math.abs(player.getLocation().getYaw() - last.yaw);
                float pitchDiff = Math.abs(player.getLocation().getPitch() - last.pitch);
                if (yawDiff + pitchDiff < minLookChange) {
                    if (clicks.size() >= 5) {
                        return false;
                    }
                }
            }

            int maxRegular = config.getAntiCheatMaxRegularClicks();
            if (clicks.size() >= maxRegular) {
                int n = Math.min(clicks.size(), maxRegular);
                List<Long> intervals = new ArrayList<>();
                for (int i = clicks.size() - n; i < clicks.size() - 1; i++) {
                    intervals.add(clicks.get(i + 1).timestamp - clicks.get(i).timestamp);
                }
                intervals.add(now - last.timestamp);

                if (!intervals.isEmpty()) {
                    double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
                    if (mean < 60) {
                        double variance = intervals.stream()
                                .mapToDouble(v -> (v - mean) * (v - mean))
                                .average().orElse(0);
                        double stdDev = Math.sqrt(variance);

                        if (stdDev < 5.0) {
                            return false;
                        }
                    }
                }
            }

            return true;
        }
    }

    /**
     * Enregistre un clic pour le joueur.
     */
    public void recordClick(Player player) {
        UUID uuid = player.getUniqueId();
        List<ClickRecord> clicks = history.computeIfAbsent(uuid, k -> new ArrayList<>());
        synchronized (clicks) {
            clicks.add(new ClickRecord(
                    System.currentTimeMillis(),
                    player.getLocation().getYaw(),
                    player.getLocation().getPitch()
            ));

            // Limiter l'historique
            while (clicks.size() > MAX_HISTORY) { clicks.remove(0); }
        }
    }

    /**
     * Nettoie l'historique d'un joueur (à appeler quand l'arbre est abattu ou désenregistré).
     */
    public void cleanup(UUID playerId) {
        history.remove(playerId);
    }

    /**
     * Nettoie tout l'historique.
     */
    public void cleanupAll() {
        history.clear();
    }
}
