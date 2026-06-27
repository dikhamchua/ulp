package com.ulp.features.lessons.controller.support;

import com.ulp.entities.ClassEntity;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.security.UlpUserDetails;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import static com.ulp.common.IConstant.*;

/**
 * Shared URL builders + form re-render plumbing for {@code SectionsController}.
 * Extracted during the file-size refactor so the controller stays focused
 * on request mapping.
 */
@Component
public class SectionFormSupport {

    private final ClassesService classesService;

    public SectionFormSupport(ClassesService classesService) {
        this.classesService = classesService;
    }

    /** Builds the canonical URL for the lessons tab of a given class. */
    public static String lessonsUrl(Long classId) {
        return URL_CLASSES_LIST + "/" + classId + "/lessons";
    }

    /** Builds the canonical edit URL for a section within a class. */
    public static String sectionEditUrl(Long classId, Long sectionId) {
        return lessonsUrl(classId) + "/sections/" + sectionId + "/edit";
    }

    /**
     * Re-renders the section form on validation failure. Re-populates the
     * {@code clazz} attribute and the form metadata; the activity-page
     * attribute may already be present (edit re-render path) and is
     * preserved untouched.
     */
    public String reRenderForm(Long classId, UlpUserDetails user,
                                Model model, String mode, String formAction) {
        ClassEntity clazz = classesService.getEditable(classId, user.getId(), user.getRole());
        model.addAttribute(ATTR_CLAZZ, clazz);
        model.addAttribute(ATTR_MODE, mode);
        // Validation always re-renders on the "info" tab.
        model.addAttribute(ATTR_ACTIVE_DETAIL_TAB, TAB_INFO);
        model.addAttribute(ATTR_FORM_ACTION, formAction);
        model.addAttribute(ATTR_CANCEL_URL, lessonsUrl(classId));
        if (MODE_EDIT.equals(mode)) {
            model.addAttribute(ATTR_EDIT_BASE_URL, formAction);
        }
        return "classes/section-form";
    }
}
