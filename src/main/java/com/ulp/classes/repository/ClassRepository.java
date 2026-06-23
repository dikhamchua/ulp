package com.ulp.classes.repository;

import com.ulp.classes.entity.ClassEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link ClassEntity}.
 *
 * <p>Because the entity is annotated with {@code @SQLRestriction("is_deleted = 0")},
 * every query issued through this repository automatically excludes soft-deleted
 * records without any additional filter in the calling code.
 */
public interface ClassRepository extends JpaRepository<ClassEntity, Long> {

    List<ClassEntity> findAllByLecturerIdOrderByCreatedAtDesc(Long lecturerId);

    List<ClassEntity> findAllByOrderByCreatedAtDesc();

    Optional<ClassEntity> findByCode(String code);
}
