package com.ulp.classes.service;

import com.ulp.classes.ClassGradient;
import com.ulp.classes.dto.ClassesDtos.ClassRow;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Doc danh sach lop hoc cho giang vien.
 *
 * <p>Sprint hien tai dung mock data inline. Sprint sau thay
 * {@link #findAllForLecturer()} bang truy van DB qua ClassRepository.
 */
@Service
public class ClassesService {

    /**
     * Tra ve danh sach lop cua giang vien hien tai. Mock data — sprint sau
     * thay bang query JPA loc theo user dang dang nhap.
     */
    public List<ClassRow> findAllForLecturer() {
        List<RawClass> raw = List.of(
                new RawClass(1L, "SE1865",           "NILXM",  1,  0,  5,  1),
                new RawClass(2L, "SE1729",           "KQ8WP", 38, 12,  9,  6),
                new RawClass(3L, "Toán 10A2",        "MZ3RT", 42,  8, 15,  4),
                new RawClass(4L, "Lý 11 Nâng cao",   "XB9LK", 35, 21, 18, 11),
                new RawClass(5L, "Ôn thi THPT 2026", "PVC4N", 57, 30, 24,  9)
        );

        return IntStream.range(0, raw.size())
                .mapToObj(i -> toRow(raw.get(i), i))
                .toList();
    }

    private static ClassRow toRow(RawClass r, int index) {
        return new ClassRow(
                r.id(), r.name(), r.code(),
                ClassGradient.forIndex(index).css(),
                r.studentCount(), r.lectureCount(),
                r.assignmentCount(), r.materialCount()
        );
    }

    /** Mock seed shape; xoa khi co entity Class. */
    private record RawClass(
            Long id, String name, String code,
            int studentCount, int lectureCount,
            int assignmentCount, int materialCount
    ) {}
}
