package com.yourorg.qbank.starter.web;

import com.yourorg.qbank.core.model.Subject;
import com.yourorg.qbank.core.model.SubjectModule;
import com.yourorg.qbank.core.service.CoreSubjectService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("${qbank.base-path:/qbank}/api/v1")
public class QBankSubjectController {

    private final CoreSubjectService subjectService;

    public QBankSubjectController(CoreSubjectService subjectService) {
        this.subjectService = subjectService;
    }

    @GetMapping("/subjects")
    public List<Subject> listSubjects(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return subjectService.listSubjects(includeArchived);
    }

    @GetMapping("/subjects/{subjectId}")
    public Subject getSubject(@PathVariable long subjectId) {
        return subjectService.getSubject(subjectId);
    }

    @GetMapping("/subjects/{subjectId}/modules")
    public List<SubjectModule> listModules(@PathVariable long subjectId) {
        return subjectService.listModules(subjectId);
    }

    @PostMapping({"/subjects", "/admin/subjects"})
    public Subject createSubject(@RequestBody CreateSubjectRequest req) {
        return subjectService.createSubject(
                req.code(),
                req.name(),
                req.expectedQuestionsPerModule(),
                req.marksPerQuestion() <= 0 ? 1 : req.marksPerQuestion()
        );
    }

    @PutMapping("/admin/subjects/{subjectId}")
    public Subject updateSubject(@PathVariable long subjectId, @RequestBody UpdateSubjectRequest req) {
        return subjectService.updateSubject(
                subjectId,
                req.name(),
                req.expectedQuestionsPerModule(),
                req.marksPerQuestion() <= 0 ? 1 : req.marksPerQuestion(),
                req.archived()
        );
    }

    public record CreateSubjectRequest(String code, String name, int expectedQuestionsPerModule, int marksPerQuestion) {}
    public record UpdateSubjectRequest(String name, int expectedQuestionsPerModule, int marksPerQuestion, boolean archived) {}
}
