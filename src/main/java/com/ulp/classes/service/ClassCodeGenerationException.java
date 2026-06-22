package com.ulp.classes.service;

/**
 * Nem khi {@link ClassesService} retry sinh ma lop {@code N} lan
 * nhung van dung phai collision tren {@code uk_classes_code}.
 *
 * <p>Trong thuc te gan nhu khong xay ra (32^5 = 33.5M combo + timestamp
 * suffix); nhung neu xay ra, exception nay duoc {@code GlobalExceptionHandler}
 * map ve 500 va user nhan thong bao than thien.
 */
public class ClassCodeGenerationException extends RuntimeException {

    public ClassCodeGenerationException(String message) {
        super(message);
    }

    public ClassCodeGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
