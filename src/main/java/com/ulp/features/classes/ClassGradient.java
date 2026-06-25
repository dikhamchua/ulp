package com.ulp.features.classes;

/**
 * Gradient presets for class thumbnail backgrounds.
 *
 * <p>Five fixed colours are cycled via {@link #forIndex(int)} so that a list
 * of classes renders with visually distinct thumbnails without randomising the
 * colour on every render.
 */
public enum ClassGradient {

    CYAN  ("linear-gradient(135deg,#26C6DA,#00ACC1)"),
    PURPLE("linear-gradient(135deg,#7E57C2,#5E35B1)"),
    ORANGE("linear-gradient(135deg,#FF7043,#F4511E)"),
    BLUE  ("linear-gradient(135deg,#42A5F5,#1E88E5)"),
    TEAL  ("linear-gradient(135deg,#26A69A,#00897B)");

    private final String css;

    ClassGradient(String css) {
        this.css = css;
    }

    public String css() {
        return css;
    }

    /**
     * Returns the gradient constant for a 0-based index, wrapping around when
     * the index exceeds the number of available constants.
     *
     * @param index 0-based position (any integer; negative values are handled correctly)
     * @return the corresponding {@link ClassGradient}, never {@code null}
     */
    public static ClassGradient forIndex(int index) {
        ClassGradient[] all = values();
        int i = Math.floorMod(index, all.length);
        return all[i];
    }
}
