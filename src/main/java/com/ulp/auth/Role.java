package com.ulp.auth;

/**
 * 4 vai tro he thong cua ULP — khop voi CHECK constraint cua cot users.role:
 * CHECK (role IN ('STUDENT','LECTURER','HEAD','ADMIN')).
 *
 * <p>Quan he ke thua nghiep vu: HEAD ke thua quyen cua LECTURER. Phan
 * permission chi tiet (RBAC) duoc xu ly o sprint sau; o Sprint 0 chi can
 * map role nay thanh authority "ROLE_&lt;name&gt;" cho Spring Security.
 */
public enum Role {
    STUDENT,
    LECTURER,
    HEAD,
    ADMIN
}
