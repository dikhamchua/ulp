package com.ulp.classes.repository;

import com.ulp.classes.entity.ClassActivity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository cho {@link ClassActivity}. Sprint 2 chi can luu — sprint
 * sau co the them query theo classId / type cho dashboard.
 */
public interface ClassActivityRepository extends JpaRepository<ClassActivity, Long> {
}
