package com.wildtimber.detection;

/**
 * Représente une coordonnée de bloc en 3D.
 */
public record BlockPos(int x, int y, int z) {

    public BlockPos add(int dx, int dy, int dz) {
        return new BlockPos(this.x + dx, this.y + dy, this.z + dz);
    }

    public double distanceSquared(BlockPos other) {
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        double dz = this.z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
