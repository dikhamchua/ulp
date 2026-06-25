package com.ulp.features.classes.repository;

import com.ulp.entities.ClassActivity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link ClassActivity} entities.
 *
 * <p>Sprint 2 requires only basic persistence. Future sprints may add
 * query methods filtered by {@code classId} or activity {@code type}
 * to support the dashboard and reporting features.
 */
public interface ClassActivityRepository extends JpaRepository<ClassActivity, Long> {
}
