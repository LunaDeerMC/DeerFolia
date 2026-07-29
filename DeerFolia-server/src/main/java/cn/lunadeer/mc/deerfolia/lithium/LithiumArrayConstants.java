package cn.lunadeer.mc.deerfolia.lithium;

/**
 * Shared immutable-by-convention arrays for allocation-sensitive return paths.
 *
 * <p>Derived from CaffeineMC Lithium's {@code ArrayConstants}.</p>
 */
public final class LithiumArrayConstants {

    public static final int[] EMPTY = new int[0];
    public static final int[] ZERO = new int[]{0};

    private LithiumArrayConstants() {
    }
}
