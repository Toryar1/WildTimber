package com.wildtimber.config;

import org.bukkit.Material;
import java.util.List;
import java.util.Set;

/**
 * Représente la configuration spécifique à un biome pour la détection et les drops d'arbres.
 */
public record BiomeConfig(
    boolean enabled,
    int minLogs,
    int minLeafLike,
    Set<Material> logBlocks,
    Set<Material> leafBlocks,
    Set<Material> attachments,
    boolean extraDropsEnabled,
    List<ExtraDropEntry> extraDrops,
    int protectionBeltRadius,
    Integer maxLogs,
    Integer maxBlocks,
    Integer maxRadiusXZ,
    Integer maxHeightY,
    // Nouveaux paramètres par biome
    Integer leafDecayRangeXZ,
    Integer leafDecayRangeY,
    Boolean isolatedLogsRule,
    Boolean orphanLeavesCleanup,
    Integer orphanLeavesRadius,
    Boolean rootReplacementEnabled,
    Material rootReplacementMaterial,
    // Paramètres fallback par biome (Module de secours cylindrique)
    Boolean fallbackEnabled,
    Integer fallbackMaxBlocks,
    Integer fallbackTrunkCoreRadius,
    Integer fallbackTrunkMinHeight,
    Integer fallbackMaxRadius,
    Double fallbackMinDensity,
    Integer fallbackRingStep,
    Integer isolatedLogsRadius,
    Integer isolatedLogMax,
    Boolean allowNonRootedStart,
    Integer maxRootSearchDepth,
    Integer sixWayMaxLogs,
    Boolean canopyCleanupEnabled,
    Integer canopyCleanupPadding,
    String fillMode,
    Integer fillRadiusExtra,
    Double elephantFactor,
    Integer leafDecayScalingLogs,
    Integer leafDecayScalingXzBonus,
    Integer leafDecayScalingYBonus,
    Boolean allowDiagonalLogs,
    Boolean allowDiagonalLeaves
) {
    /** Constructeur compact — copie défensive des collections mutables (H10) */
    public BiomeConfig {
        if (logBlocks != null) logBlocks = java.util.Set.copyOf(logBlocks);
        if (leafBlocks != null) leafBlocks = java.util.Set.copyOf(leafBlocks);
        if (attachments != null) attachments = java.util.Set.copyOf(attachments);
        if (extraDrops != null) extraDrops = java.util.List.copyOf(extraDrops);
    }
}
