package com.examseating.web.rest;

import com.examseating.dto.CohortOverview;
import com.examseating.dto.CohortRequests;
import com.examseating.dto.CohortView;
import com.examseating.entity.ActivityLog;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.Student;
import com.examseating.entity.StudentAttributes;
import com.examseating.entity.User;
import com.examseating.repository.SeatingAllocationRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.service.ActivityLogService;
import com.examseating.service.CohortService;
import com.examseating.tenancy.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flat classmate bucket API.
 *
 * <p>The base classmate attributes are chosen once per Excel upload. Buckets
 * are always derived from the live roster, so configuring a cohort inside a
 * bucket simply re-derives which flat bucket each student belongs to — no
 * student record is ever modified, lost, or duplicated.</p>
 */
@RestController
@RequestMapping("/api/v1/students/cohorts")
@RequiredArgsConstructor
public class CohortRestController {

    private final CohortService cohortService;
    private final StudentRepository studentRepository;
    private final SeatingAllocationRepository allocationRepository;
    private final TenantSupport tenant;
    private final ActivityLogService activityLog;

    /** GET /api/v1/students/cohorts — the flat buckets plus refinement candidates. */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    @Transactional(readOnly = true)
    public CohortOverview overview() {
        List<String> attributes = cohortService.baseClassmateAttributes();
        List<Student> students = cohortService.visibleStudents();
        List<CohortView> buckets = cohortService.deriveBuckets(students, attributes);
        return new CohortOverview(attributes, buckets, cohortService.candidateAttributes(attributes));
    }

    /**
     * GET /api/v1/students/cohorts/members — the students belonging to one bucket.
     */
    @GetMapping("/members")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> members(
            @RequestParam(required = false) List<String> attributes,
            @RequestParam(required = false) List<String> values) {
        List<String> baseAttributes = cohortService.baseClassmateAttributes();
        List<String> scopeValues = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                scopeValues.add(value == null ? "" : value.trim());
            }
        }
        List<Student> members = cohortService.membersOf(baseAttributes, scopeValues);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Student student : members) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", student.getId());
            row.put("name", student.getName());
            row.put("identityKey", student.getIdentityKey());
            row.put("attributes", student.getAttributes());
            out.add(row);
        }
        return out;
    }

    /**
     * POST /api/v1/students/cohorts/preview — shows the flat buckets that would
     * result from configuring a cohort inside one bucket, before anything changes.
     */
    @PostMapping("/preview")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    @Transactional(readOnly = true)
    public List<CohortView> preview(@RequestBody CohortRequests.PreviewCohortRequest request) {
        List<String> attributes = cohortService.baseClassmateAttributes();
        return cohortService.previewRefinement(
                cohortService.visibleStudents(),
                attributes,
                normalizeValues(request.values()),
                request.groupAttribute());
    }

    /**
     * POST /api/v1/students/cohorts/configure — configures a cohort inside one
     * bucket. The refined bucket is replaced by newly derived flat buckets.
     */
    @PostMapping("/configure")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    @Transactional
    public List<CohortView> configure(@RequestBody CohortRequests.ConfigureCohortRequest request) {
        List<String> attributes = cohortService.baseClassmateAttributes();
        cohortService.configureCohort(
                attributes,
                normalizeValues(request.values()),
                request.groupAttribute());
        List<Student> students = cohortService.visibleStudents();
        return cohortService.deriveBuckets(students, attributes);
    }

    /**
     * DELETE /api/v1/students/cohorts/rule — removes a bucket-level refinement so
     * the bucket returns to its undivided form.
     */
    @DeleteMapping("/rule")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    @Transactional
    public List<CohortView> removeRule(@RequestBody CohortRequests.DeleteCohortRequest request) {
        List<String> attributes = cohortService.baseClassmateAttributes();
        String signature = com.examseating.entity.CohortRule.signatureOf(
                attributes, normalizeValues(request.values()));
        cohortService.removeCohort(signature);
        return cohortService.deriveBuckets(cohortService.visibleStudents(), attributes);
    }

    /**
     * DELETE /api/v1/students/cohorts — removes every student in one bucket,
     * together with their seating allocations.
     */
    @DeleteMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    @Transactional
    public ResponseEntity<?> delete(@RequestBody CohortRequests.DeleteCohortRequest request) {
        List<String> attributes = cohortService.baseClassmateAttributes();
        List<String> values = normalizeValues(request.values());
        List<Student> members = cohortService.membersOf(attributes, values);
        if (members.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        Set<Long> memberIds = new LinkedHashSet<>();
        for (Student student : members) {
            memberIds.add(student.getId());
        }
        // Remove any seating allocations that reference these students.
        List<SeatingAllocation> allocations = allocationRepository.findAll();
        for (SeatingAllocation allocation : allocations) {
            Student student = allocation.getStudent();
            if (student != null && student.getId() != null && memberIds.contains(student.getId())) {
                allocationRepository.delete(allocation);
            }
        }
        studentRepository.deleteAll(members);
        activityLog.record(ActivityLog.Type.PLAN_CHANGE, (User) null,
                "Deleted %d student(s) from cohort '%s'".formatted(
                        members.size(),
                        cohortService.labelFor(attributes, values, values.stream().anyMatch(v -> v == null || v.isBlank()))));
        return ResponseEntity.ok(java.util.Map.of("deleted", members.size()));
    }

    private static List<String> normalizeValues(List<String> raw) {
        List<String> values = new ArrayList<>();
        if (raw == null) {
            return values;
        }
        for (String value : raw) {
            values.add(value == null ? "" : value.trim());
        }
        return values;
    }
}
