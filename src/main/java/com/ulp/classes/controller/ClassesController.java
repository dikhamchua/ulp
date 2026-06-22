package com.ulp.classes.controller;

import com.ulp.auth.Roles;
import com.ulp.classes.service.ClassesService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Controller cho luong cua giang vien — quan ly lop hoc.
 * Chi LECTURER, HEAD, ADMIN co the truy cap (xem {@link Roles}).
 */
@Controller
@RequestMapping("/lecturer")
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class ClassesController {

    private final ClassesService classesService;

    public ClassesController(ClassesService classesService) {
        this.classesService = classesService;
    }

    @GetMapping("/classes")
    public String manage(Model model) {
        model.addAttribute("classes", classesService.findAllForLecturer());
        return "classes/manage";
    }
}
