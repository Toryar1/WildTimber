package com.wildtimber.manager;

import com.wildtimber.detection.BlockPos;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import java.util.Map;

/**
 * Représente un snapshot des blocs d'un arbre abattu pour permettre l'annulation (undo).
 */
public record UndoSnapshot(World world, Map<BlockPos, BlockData> blocks) {

    public UndoSnapshot {
        // Copie défensive : le snapshot ne doit pas être affecté par les mutations ultérieures de la map originale (M8)
        blocks = java.util.Map.copyOf(blocks);
    }

    /**
     * Restaure les blocs sauvegardés dans le monde.
     */
    public void restore() {
        if (world == null || blocks == null) {
            return;
        }
        for (Map.Entry<BlockPos, BlockData> entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockData data = entry.getValue();
            world.getBlockAt(pos.x(), pos.y(), pos.z()).setBlockData(data, false);
        }
    }
}
