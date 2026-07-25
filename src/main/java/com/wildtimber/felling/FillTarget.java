package com.wildtimber.felling;

import org.bukkit.Material;
import java.util.Set;

public record FillTarget(
    int topSurfaceY,        // Y interpolé brut (IDW avant lissage)
    int reconstructedTopY,  // Y final après lissage + validation (utilisé par backfillRoots)
    int groundY,
    Material originalGroundMat,
    int gap,
    Set<Integer> trunkLogYs,
    Material[] columnProfile,
    double snowRatio,
    Material dominantMat
) {
    public int logYMin() {
        return topSurfaceY;
    }

    public boolean hadLogAt(int y) {
        return trunkLogYs != null && trunkLogYs.contains(y);
    }
}
