package com.ulp.classes.repository;

import com.ulp.classes.entity.ClassEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository cho {@link ClassEntity}. Nho {@code @SQLRestriction("is_deleted = 0")}
 * tren entity, moi truy van mac dinh da loai bo ban ghi soft-delete.
 */
public interface ClassRepository extends JpaRepository<ClassEntity, Long> {

    List<ClassEntity> findAllByLecturerIdOrderByCreatedAtDesc(Long lecturerId);

    List<ClassEntity> findAllByOrderByCreatedAtDesc();

    Optional<ClassEntity> findByCode(String code);
}
