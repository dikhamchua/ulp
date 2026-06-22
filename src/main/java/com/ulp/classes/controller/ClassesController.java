package com.ulp.classes.controller;

import com.ulp.classes.dto.ClassesDtos.ClassRow;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

/**
 * Controller cho luong cua giang vien — quan ly lop hoc.
 * Chi LECTURER, HEAD, ADMIN co the truy cap. Sprint hien tai mock data;
 * sprint sau thay bang ClassService + ClassRepository.
 */
@Controller
@RequestMapping("/lecturer")
@PreAuthorize("hasAnyRole('LECTURER','HEAD','ADMIN')")
public class ClassesController {

    @GetMapping("/classes")
    public String manage(Model model) {
        // Mock data theo template shub-class-3.html. Doi sang DB query
        // khi co entity Class + ClassRepository.
        List<ClassRow> classes = List.of(
                new ClassRow(1L, "SE1865",          "NILXM", "SE", "linear-gradient(135deg,#26C6DA,#00ACC1)",  1,  0,  5,  1),
                new ClassRow(2L, "SE1729",          "KQ8WP", "SE", "linear-gradient(135deg,#7E57C2,#5E35B1)", 38, 12,  9,  6),
                new ClassRow(3L, "Toán 10A2",       "MZ3RT", "T1", "linear-gradient(135deg,#FF7043,#F4511E)", 42,  8, 15,  4),
                new ClassRow(4L, "Lý 11 Nâng cao",  "XB9LK", "L1", "linear-gradient(135deg,#42A5F5,#1E88E5)", 35, 21, 18, 11),
                new ClassRow(5L, "Ôn thi THPT 2026", "PVC4N", "ÔT", "linear-gradient(135deg,#26A69A,#00897B)", 57, 30, 24,  9)
        );
        model.addAttribute("classes", classes);
        return "classes/manage";
    }
}
