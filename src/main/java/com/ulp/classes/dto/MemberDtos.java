package com.ulp.classes.dto;

/** View-model DTOs for the class detail page — Members tab. */
public class MemberDtos {

    /**
     * A single row in the member list table (Members tab).
     * Rendered by {@code templates/classes/detail-members.html}.
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
        /**
         * Returns a display-safe phone number, substituting an em-dash when the value is
         * {@code null} or blank.
         *
         * @return the phone number string, or {@code "—"} if absent
         */
        public String displayPhone() {
            return phone == null || phone.isBlank() ? "—" : phone;
        }

        /**
         * Returns a human-readable label for the {@code joinedVia} badge.
         * Maps the enrollment source codes ({@code CODE}, {@code LINK}, {@code IMPORT},
         * {@code MANUAL}) to their display labels; falls back to the raw value for
         * unrecognised codes.
         *
         * @return display label for the enrollment source, or {@code "—"} if absent
         */
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
