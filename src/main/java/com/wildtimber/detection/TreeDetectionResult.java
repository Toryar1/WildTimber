package com.wildtimber.detection;

import com.wildtimber.config.BiomeConfig;
import java.util.Set;

/**
 * Résultat du scan de détection d'arbre.
 */
public record TreeDetectionResult(
    boolean success,
    String cancellationReason,
    Set<BlockPos> logs,
    Set<BlockPos> leaves,
    double maxHealth,
    BiomeConfig biomeConfig,
    String biomeName,
    boolean usedFallback,
    boolean hasBlacklistSoftHit
) {
    public TreeDetectionResult(boolean success, String cancellationReason,
                               Set<BlockPos> logs, Set<BlockPos> leaves,
                               double maxHealth, BiomeConfig biomeConfig, String biomeName) {
        this(success, cancellationReason, logs, leaves, maxHealth, biomeConfig, biomeName, false, false);
    }

    public TreeDetectionResult(boolean success, String cancellationReason,
                               Set<BlockPos> logs, Set<BlockPos> leaves,
                               double maxHealth, BiomeConfig biomeConfig, String biomeName, boolean usedFallback) {
        this(success, cancellationReason, logs, leaves, maxHealth, biomeConfig, biomeName, usedFallback, false);
    }
}

