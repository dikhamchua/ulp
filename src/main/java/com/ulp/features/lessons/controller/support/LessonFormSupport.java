package com.ulp.features.lessons.controller.support;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Section;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import static com.ulp.common.IConstant.*;

/**
 * Shared URL builders + form re-render plumbing for the lesson-form
 * controllers ({@code LessonsController}). Extracted during the file-size
 * refactor so the controller stays focused on request mapping.
 *
 * <p>The {@code reRenderForm} helper covers the validation-failure path
 * shared by create and edit; {@code loadSection} centralises the
 * section↔class lookup so cross-class enumeration cannot succeed.
 */
@Component
public class LessonFormSupport {

    private final ClassesService classesService;
    private final SectionRepository sectionRepository;

    public LessonFormSupport(ClassesService classesService,
                             SectionRepository sectionRepository) {
        this.classesService = classesService;
        this.sectionRepository = sectionRepository;
    }

    /** Loads the section, enforcing that it belongs to {@code classId}. */
    public Section loadSection(Long classId, Long sectionId) {
        return sectionRepository.findByIdAndClassId(sectionId, classId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_SECTION_NOT_FOUND));
    }

    /** Builds the base lessons URL for a section (POST target for create). */
    public static String lessonsBaseUrl(Long classId, Long sectionId) {
        return URL_CLASSES_LIST + "/" + classId
                + "/sections/" + sectionId + "/lessons";
    }

    /** Builds the canonical edit URL for a single lesson. */
    public static String lessonEditUrl(Long classId, Long sectionId, Long lessonId) {
        return lessonsBaseUrl(classId, sectionId) + "/" + lessonId + "/edit";
    }

    /** Builds the lessons tab URL for the parent class, preselecting the section. */
    public static String lessonsTabUrl(Long classId, Long sectionId) {
        return URL_CLASSES_LIST + "/" + classId + "/lessons?section=" + sectionId;
    }

    /**
     * Re-renders the lesson form on validation failure. Re-populates the
     * class + section context the template needs; the caller may add the
     * activity-page attribute beforehand (edit path) which is preserved.
     */
    public String reRenderForm(Long classId, Long sectionId, UlpUserDetails user,
                                Model model, String mode, String formAction) {
        ClassEntity clazz = classesService.getEditable(classId, user.getId(), user.getRole());
        // Section may already be in model (edit re-render); only load when absent.
        if (!model.containsAttribute(ATTR_SECTION)) {
            model.addAttribute(ATTR_SECTION, loadSection(classId, sectionId));
        }
        model.addAttribute(ATTR_CLAZZ, clazz);
        model.addAttribute(ATTR_MODE, mode);
        // Validation always re-renders on the "info" tab.
        model.addAttribute(ATTR_ACTIVE_DETAIL_TAB, TAB_INFO);
        model.addAttribute(ATTR_FORM_ACTION, formAction);
        model.addAttribute(ATTR_CANCEL_URL, lessonsTabUrl(classId, sectionId));
        if (MODE_EDIT.equals(mode)) {
            model.addAttribute(ATTR_EDIT_BASE_URL, formAction);
        }
        return VIEW_LESSON_FORM;
    }
}
