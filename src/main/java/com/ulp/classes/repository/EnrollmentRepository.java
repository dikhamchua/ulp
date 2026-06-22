package com.ulp.classes.repository;

import com.ulp.classes.entity.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository cho {@link Enrollment}. Sprint 2 chi can READ — render danh sach
 * thanh vien tren trang chi tiet lop.
 */
public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {

    /** Danh sach SV (status = ACTIVE) trong 1 lop, sort theo joined_at moi nhat. */
    List<Enrollment> findAllByClassIdAndStatusOrderByJoinedAtDesc(Long classId, String status);

    /** Count nhanh SV active trong 1 lop. */
    long countByClassIdAndStatus(Long classId, String status);
}
