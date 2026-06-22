package com.ulp.classes;

/**
 * Mau gradient cho thumbnail lop hoc. 5 mau co dinh duoc rotate
 * theo {@link #forIndex(int)} de mot danh sach lop co mau phan biet
 * ma khong phai random hoa moi lan render.
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

    /** Tra ve gradient theo chi muc 0-based, vong lai khi vuot length. */
    public static ClassGradient forIndex(int index) {
        ClassGradient[] all = values();
        int i = Math.floorMod(index, all.length);
        return all[i];
    }
}
