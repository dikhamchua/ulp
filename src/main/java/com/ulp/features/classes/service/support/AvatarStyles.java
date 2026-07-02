package com.ulp.features.classes.service.support;

/**
 * Avatar initials + gradient helper for member-style rows.
 *
 * <p>Single source of the avatar look shared by the class members list and the
 * lecturer progress dashboard. Change the palette here and both surfaces follow.
 */
public final class AvatarStyles {

    private static final int HUE_COUNT = 5;
    private static final String[][] GRADIENTS = {
            {"#5E92F3", "#1E88E5"},
            {"#EC407A", "#D81B60"},
            {"#26A69A", "#00897B"},
            {"#FFA726", "#FB8C00"},
            {"#7E57C2", "#5E35B1"}
    };

    private AvatarStyles() {
        // utility holder
    }

    /** CSS gradient string for the row at the given zero-based index. */
    public static String gradient(int index) {
        String[] colors = GRADIENTS[Math.floorMod(index, HUE_COUNT)];
        return "linear-gradient(135deg," + colors[0] + "," + colors[1] + ")";
    }

    /** Uppercase initials derived from the full name; "?" when blank. */
    public static String label(String fullName) {
        if (fullName == null || fullName.isBlank()) return "?";
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        }
        String first = parts[0].substring(0, 1);
        String last = parts[parts.length - 1].substring(0, 1);
        return (first + last).toUpperCase();
    }
}
