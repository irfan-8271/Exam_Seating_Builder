package com.examseating.web;

import com.examseating.dto.ImportResult;
import com.examseating.entity.Student;
import com.examseating.exception.ResourceNotFoundException;
import com.examseating.repository.StudentRepository;
import com.examseating.service.ExcelImportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Student roster REST endpoints (Spring MVC).
 *
 * <p><b>How a request reaches this class:</b> Tomcat → Spring Security filter chain
 * (authentication + role checks) → DispatcherServlet → matches the URL + HTTP verb to
 * this {@code @RequestMapping("/api/students")} → calls the method, converts the JSON
 * body into the parameter (Jackson) → the returned object is serialized to JSON.</p>
 *
 * <p><b>Role model</b> (enforced by {@code @PreAuthorize} + {@code @EnableMethodSecurity}):
 * <ul>
 *   <li>ADMIN and EXAM_CELL may write (create/update/delete/import).</li>
 *   <li>Any authenticated user (incl. INVIGILATOR) may read.</li>
 * </ul></p>
 */
@RestController
@RequestMapping("/api/students")
@RequiredArgsConstructor
public class StudentController {

    private final StudentRepository studentRepository;
    private final ExcelImportService excelImportService;

    @GetMapping
    public List<Student> list(@RequestParam(required = false) String subjectCode) {
        return (subjectCode == null || subjectCode.isBlank())
                ? studentRepository.findAll()
                : studentRepository.findBySubjectCode(subjectCode);
    }

    @GetMapping("/{id}")
    public Student get(@PathVariable Long id) {
        return studentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Student " + id + " not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public Student create(@Valid @RequestBody Student student) {
        return studentRepository.save(student);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public Student update(@PathVariable Long id, @Valid @RequestBody Student updates) {
        Student existing = studentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Student " + id + " not found"));
        existing.setName(updates.getName());
        existing.setRollNo(updates.getRollNo());
        existing.setDepartment(updates.getDepartment());
        existing.setCourse(updates.getCourse());
        existing.setYearSession(updates.getYearSession());
        existing.setSubjectCode(updates.getSubjectCode());
        return studentRepository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public void delete(@PathVariable Long id) {
        studentRepository.deleteById(id);
    }

    /** Bulk upload of a student roster Excel file. */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public ImportResult importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        return excelImportService.importStudents(file.getInputStream());
    }
}
