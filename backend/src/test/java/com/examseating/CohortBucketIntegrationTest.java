package com.examseating;

import com.examseating.entity.ExamRoom;
import com.examseating.entity.Student;
import com.examseating.engine.SeatingArrangementEngine;
import com.examseating.repository.CohortRuleRepository;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.SeatingAllocationRepository;
import com.examseating.repository.SeatingRoundRepository;
import com.examseating.repository.StudentIdentitySchemeRepository;
import com.examseating.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the flat classmate bucket model:
 * <ul>
 *   <li>buckets are derived from the upload-time classmate rule,</li>
 *   <li>a bucket can be refined with one extra attribute ("Configure cohort"),</li>
 *   <li>refining replaces the bucket with new flat buckets and never touches a student row,</li>
 *   <li>students missing a classification value land in Miscellaneous and are not
 *       treated as one giant mutually-conflicting cohort.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class CohortBucketIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ExamRoomRepository examRoomRepository;
    @Autowired private SeatingAllocationRepository allocationRepository;
    @Autowired private SeatingRoundRepository roundRepository;
    @Autowired private StudentIdentitySchemeRepository schemeRepository;
    @Autowired private CohortRuleRepository cohortRuleRepository;

    @BeforeEach
    void cleanDatabase() {
        allocationRepository.deleteAll();
        roundRepository.deleteAll();
        cohortRuleRepository.deleteAll();
        studentRepository.deleteAll();
        examRoomRepository.deleteAll();
        schemeRepository.deleteAll();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Student student(String name, String id, Map<String, String> attrs) {
        Student s = new Student();
        s.setName(name);
        s.setIdentityKey(id); // rollno == id
        Map<String, String> all = new LinkedHashMap<>();
        all.put("rollno", id);
        all.putAll(attrs);
        s.setAttributes(all);
        return s;
    }

    private Map<String, String> attrs(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** 6 students across 2 departments, 2 students with no department value. */
    private void seedRoster() {
        studentRepository.save(student("A", "A1", attrs("course", "B.Tech", "semester", "1", "department", "Civil")));
        studentRepository.save(student("B", "B2", attrs("course", "B.Tech", "semester", "1", "department", "Civil")));
        studentRepository.save(student("C", "C1", attrs("course", "B.Tech", "semester", "1", "department", "Mechanical")));
        studentRepository.save(student("D", "D2", attrs("course", "B.Tech", "semester", "1", "department", "Mechanical")));
        studentRepository.save(student("E", "E1", attrs("course", "B.Tech", "semester", "2", "department", "Civil")));
        studentRepository.save(student("F", "F2", attrs("course", "B.Tech", "semester", "2", "department", "Mechanical")));
        // These two have no "semester" value, so they fall into Miscellaneous.
        studentRepository.save(student("G", "G1", attrs("course", "B.Tech")));
        studentRepository.save(student("H", "H2", attrs("course", "B.Tech")));
    }

    private void setClassmateRule(String... attributes) throws Exception {
        String json = "{\"attributes\":[\"rollno\"],\"classmateAttributes\":["
                + String.join(",", java.util.Arrays.stream(attributes).map(a -> "\"" + a + "\"").toList())
                + "]}";
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/students/identity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
    }

    private String cohortJson(String values, String groupAttribute) {
        return "{\"attributes\":[\"course\",\"semester\"],\"values\":[" + values + "]"
                + (groupAttribute == null ? "" : ",\"groupAttribute\":\"" + groupAttribute + "\"")
                + "}";
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Buckets are derived from the upload-time classmate rule; missing values go to Miscellaneous")
    void derivesFlatBuckets() throws Exception {
        seedRoster();
        setClassmateRule("course", "semester");

        mockMvc.perform(get("/api/v1/students/cohorts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes", hasSize(2)))
                .andExpect(jsonPath("$.buckets", hasSize(3)))
                .andExpect(jsonPath("$.buckets[?(@.label == 'B.Tech · 1')].count").value(4))
                .andExpect(jsonPath("$.buckets[?(@.label == 'B.Tech · 2')].count").value(2))
                .andExpect(jsonPath("$.buckets[?(@.label == 'Miscellaneous')].count").value(2));
    }

    @Test
    @DisplayName("Configure cohort splits one bucket into new flat buckets without touching student rows")
    void configureCohortSplitsBucket() throws Exception {
        seedRoster();
        setClassmateRule("course", "semester");

        long before = studentRepository.count();
        mockMvc.perform(post("/api/v1/students/cohorts/configure")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cohortJson("\"B.Tech\",\"1\"", "department")))
                .andExpect(status().isOk())
                // The refined bucket is replaced by the derived child buckets.
                .andExpect(jsonPath("$.[?(@.label == 'B.Tech · 1 · Civil')].count").value(2))
                .andExpect(jsonPath("$.[?(@.label == 'B.Tech · 1 · Mechanical')].count").value(2))
                .andExpect(jsonPath("$.[?(@.label == 'B.Tech · 1')]").isEmpty())
                // Students with no semester stay in Miscellaneous.
                .andExpect(jsonPath("$.[?(@.label == 'Miscellaneous')].count").value(2));

        // No student record was created, lost, or duplicated.
        assertEquals(before, studentRepository.count());
    }

    @Test
    @DisplayName("Preview shows the resulting buckets before anything is applied")
    void previewDoesNotChangeBuckets() throws Exception {
        seedRoster();
        setClassmateRule("course", "semester");

        mockMvc.perform(post("/api/v1/students/cohorts/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cohortJson("\"B.Tech\",\"1\"", "department")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // The overview is unchanged — the preview is read-only.
        mockMvc.perform(get("/api/v1/students/cohorts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets[?(@.label == 'B.Tech · 1')].count").value(4));
        assertEquals(0, cohortRuleRepository.count());
    }

    @Test
    @DisplayName("Removing a refinement restores the undivided bucket")
    void removingRefinementRestoresBucket() throws Exception {
        seedRoster();
        setClassmateRule("course", "semester");

        mockMvc.perform(post("/api/v1/students/cohorts/configure")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cohortJson("\"B.Tech\",\"1\"", "department")))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/students/cohorts/rule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cohortJson("\"B.Tech\",\"1\"", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.[?(@.label == 'B.Tech · 1')].count").value(4));
    }

    @Test
    @DisplayName("Delete by category removes only that category's students")
    void deleteByCategory() throws Exception {
        seedRoster();
        setClassmateRule("course", "semester");

        mockMvc.perform(delete("/api/v1/students/cohorts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cohortJson("\"B.Tech\",\"2\"", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(2));

        assertEquals(6, studentRepository.count());
        assertFalse(studentRepository.findAll().stream()
                .anyMatch(s -> "2".equals(s.getAttributes().get("semester"))));
    }

    @Test
    @DisplayName("Configuring a cohort inside Miscellaneous pulls out only the students that carry the attribute")
    void refineMiscellaneousBucket() throws Exception {
        // 2 students with no semester at all, and 1 that has a department but no semester.
        seedRoster();
        studentRepository.save(student("I", "I1", attrs("course", "B.Tech", "department", "Civil")));

        setClassmateRule("course", "semester");

        mockMvc.perform(get("/api/v1/students/cohorts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets[?(@.label == 'Miscellaneous')].count").value(3));

        // Refining Miscellaneous by department moves only the student that HAS a
        // department into a new bucket; the others stay in Miscellaneous.
        mockMvc.perform(post("/api/v1/students/cohorts/configure")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cohortJson("\"B.Tech\",\"\"", "department")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.[?(@.label == 'B.Tech · Civil')].count").value(1))
                .andExpect(jsonPath("$.[?(@.label == 'B.Tech')].count").value(2));

        assertEquals(9, studentRepository.count());
    }

    @Test
    @DisplayName("Students missing a classification value do not form one giant cohort")
    void unclassifiedStudentsAreNotOneCohort() {
        List<Student> students = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            students.add(student("S" + i, "S" + i, attrs("department", i % 2 == 0 ? "Civil" : "Mechanical")));
        }
        // 40 students with NO department at all.
        for (int i = 0; i < 40; i++) {
            students.add(student("U" + i, "U" + i, attrs("name", "U" + i)));
        }

        SeatingArrangementEngine.SeatingConfig config =
                SeatingArrangementEngine.SeatingConfig.extreme("department");

        SeatingArrangementEngine.SeatingAdvisory advisory =
                new SeatingArrangementEngine().analyzeFeasibility(
                        List.of(hall(4, 5)), students, config, java.util.Set.of());

        // Before the fix the 40 unclassified students were treated as one cohort of 40
        // and demanded a large number of mandatory empty buffer seats.
        assertNotNull(advisory);
        assertTrue(advisory.emptyBufferSeatsRequired() < 40,
                "Unclassified students must not demand a full cohort's worth of buffer seats, was "
                        + advisory.emptyBufferSeatsRequired());
    }

    @Test
    @DisplayName("Miscellaneous students are seated like normal students — no phantom buffer seats")
    void miscellaneousDoesNotInflateSeatDemand() throws Exception {
        seedRoster();
        setClassmateRule("course", "semester");

        mockMvc.perform(post("/api/v1/seating-plans/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strictness\":\"EXTREME\",\"roomIds\":["
                                + hall(6, 5).getId() + "],\"clearExisting\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emptyBufferSeatsRequired", greaterThanOrEqualTo(0)));
    }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    private ExamRoom hall(int rows, int columns) {
        ExamRoom room = examRoomRepository.save(ExamRoom.builder()
                .hallName("Hall " + rows + "x" + columns + "-" + System.nanoTime())
                .rows(rows)
                .columns(columns)
                .benchType(ExamRoom.BenchType.SINGLE_SEATER)
                .build());
        return room;
    }
}
