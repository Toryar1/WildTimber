package com.wildtimber.protection;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

/**
 * Interface pour la gestion des protections de blocs (WorldGuard, GriefPrevention, etc.).
 */
public interface ProtectionHook {

    /**
     * Vérifie si l'abattage d'un bloc est protégé pour un joueur donné.
     *
     * @param player Le joueur tentant l'action.
     * @param location La localisation du bloc.
     * @return true si la zone est protégée et que l'abattage est interdit, false sinon.
     */
    boolean isProtected(Player player, Location location);

    /**
     * Vérifie si la coupe d'un arbre complet est autorisée dans la zone englobante.
     * Par défaut, délègue à isProtected() sur la position de base.
     *
     * @param player Le joueur tentant l'action.
     * @param location La localisation du bloc frappé.
     * @param boundingBox La bounding box de l'arbre complet.
     * @return true si la coupe est autorisée, false si protégée.
     */
    default boolean canCut(Player player, Location location, BoundingBox boundingBox) {
        return !isProtected(player, location);
    }
}
