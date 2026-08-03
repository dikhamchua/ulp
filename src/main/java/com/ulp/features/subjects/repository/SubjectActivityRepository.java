package com.ulp.features.subjects.repository;

import com.ulp.features.subjects.dto.SubjectActivityRow;
import com.ulp.features.subjects.entity.SubjectActivity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Append-only repository for {@link SubjectActivity}. */
public interface SubjectActivityRepository extends JpaRepository<SubjectActivity, Long> {

    @Query("SELECT new com.ulp.features.subjects.dto.SubjectActivityRow("
            + "a.type, a.message, u.email, a.createdAt) "
            + "FROM SubjectActivity a LEFT JOIN com.ulp.entities.User u ON u.id = a.performedBy "
            + "WHERE a.subjectId = :subjectId "
            + "ORDER BY a.createdAt DESC, a.id DESC")
    Page<SubjectActivityRow> findActivitiesForSubject(
            @Param("subjectId") Long subjectId,
            Pageable pageable);
}
