package com.wildtimber.detection;

import java.util.Set;

/**
 * Résultat de la partition d'un sous-arbre après BFS multi-source et détection de goulots.
 *
 * @param logs          Bûches appartenant au sous-arbre ciblé
 * @param leaves        Feuilles et attachments associés au sous-arbre
 * @param bridgeCuts    Bûches de frontière coupées pour séparer ce sous-arbre des voisins
 * @param usedFallback  true si l'algorithme de secours cylindrique a été utilisé
 * @param maxHealth     Points de vie calculés pour ce sous-arbre
 */
public record SubTreePartition(
    Set<BlockPos> logs,
    Set<BlockPos> leaves,
    Set<BlockPos> bridgeCuts,
    boolean usedFallback,
    double maxHealth
) {}
