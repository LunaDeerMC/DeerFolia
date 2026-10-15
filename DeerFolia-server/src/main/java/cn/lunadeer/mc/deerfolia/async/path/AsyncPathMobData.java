package cn.lunadeer.mc.deerfolia.async.path;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

/** Immutable inputs captured after onPathfindingStart and before onPathfindingDone. */
public final class AsyncPathMobData {
    private final float[] malus = new float[PathType.values().length];
    private final float width;
    private final float height;
    private final float step;
    private final int maxFallDistance;
    private final AABB boundingBox;
    private final Vec3 position;

    public AsyncPathMobData(Mob mob) {
        for (PathType type : PathType.values()) {
            this.malus[type.ordinal()] = mob.getPathfindingMalus(type);
        }
        this.width = mob.getBbWidth();
        this.height = mob.getBbHeight();
        this.step = mob.maxUpStep();
        this.maxFallDistance = mob.getMaxFallDistance();
        this.boundingBox = mob.getBoundingBox();
        this.position = mob.position();
    }

    public float malus(PathType type) { return this.malus[type.ordinal()]; }
    public float width() { return this.width; }
    public float height() { return this.height; }
    public float step() { return this.step; }
    public int maxFallDistance() { return this.maxFallDistance; }
    public AABB boundingBox() { return this.boundingBox; }
    public Vec3 position() { return this.position; }

    public boolean noCollision(CollisionGetter level, AABB box) {
        // Capture only admits entity-independent collision shapes for walking.
        if (!level.noBlockCollision(null, box)) {
            return false;
        }
        WorldBorder border = level.getWorldBorder();
        double margin = Math.max(Mth.absMax(box.getXsize(), box.getZsize()), 1.0);
        boolean closeToBorder = border.getDistanceToBorder(this.position.x, this.position.z) < margin * 2.0
            && border.isWithinBounds(this.position.x, this.position.z, margin);
        return !closeToBorder || !Shapes.joinIsNotEmpty(border.getCollisionShape(), Shapes.create(box), BooleanOp.AND);
    }
}
