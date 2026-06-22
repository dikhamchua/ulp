package com.ulp.classes.dto;

/** View-model DTOs cho trang chi tiet lop hoc — tab Thanh vien. */
public class MemberDtos {

    /**
     * 1 dong trong bang danh sach thanh vien (tab Thanh vien).
     * Render boi {@code templates/classes/detail-members.html}.
     */
    public record MemberRow(
            Long userId,
            String fullName,
            String avatarLabel,
            String avatarGradient,
            String email,
            String phone,
            String joinedVia
    ) {
        /** Hien thi phone gon (rut ngan neu null hoac quoc te format). */
        public String displayPhone() {
            return phone == null || phone.isBlank() ? "—" : phone;
        }

        /** Hien thi joined-via badge: CODE/LINK/IMPORT/MANUAL → label tieng Viet. */
        public String displayJoinedVia() {
            if (joinedVia == null) return "—";
            return switch (joinedVia) {
                case "CODE" -> "Mã mời";
                case "LINK" -> "Link";
                case "IMPORT" -> "Import";
                case "MANUAL" -> "Thủ công";
                default -> joinedVia;
            };
        }
    }
}
