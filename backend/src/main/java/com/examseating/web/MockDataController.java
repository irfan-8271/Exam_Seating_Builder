package com.examseating.web;

import com.examseating.entity.Bench;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.Student;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.SeatingAllocationRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.tenancy.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Controller for generating and clearing mock datasets for interactive UI testing.
 *
 * @deprecated legacy alias kept for existing clients. These <code>/api/mock</code>
 * paths are the only mock-data endpoints that exist.
 */
@Deprecated
@RestController
@RequestMapping("/api/mock")
@RequiredArgsConstructor
public class MockDataController {

    private final StudentRepository studentRepository;
    private final ExamRoomRepository examRoomRepository;
    private final SeatingAllocationRepository allocationRepository;
    private final TenantSupport tenant;

    @GetMapping("/stats")
    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        List<ExamRoom> rooms = tenant.visible(examRoomRepository.findAll());
        int totalCapacity = rooms.stream().mapToInt(ExamRoom::getCapacity).sum();
        long studentCount = tenant.visible(studentRepository.findAll()).size();
        long allocationCount = tenant.visible(allocationRepository.findAll()).size();
        return Map.of(
                "roomCount", rooms.size(),
                "totalCapacity", totalCapacity,
                "studentCount", studentCount,
                "allocationCount", allocationCount
        );
    }

    @PostMapping("/generate")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    @Transactional
    public ResponseEntity<Map<String, Object>> generateMockData() {
        // 1. Clear previous data to start fresh
        clearTenantData();

        // 2. Create Sample Exam Rooms
        List<ExamRoom> rooms = new ArrayList<>();

        ExamRoom room1 = ExamRoom.builder()
                .hallName("Hall 101 (Auditorium)")
                .rows(5)
                .columns(4)
                .benchType(ExamRoom.BenchType.DUAL_SEATER)
                .build();
        tenant.stamp(room1);
        fillBenches(room1);
        rooms.add(examRoomRepository.save(room1));

        ExamRoom room2 = ExamRoom.builder()
                .hallName("Hall 102 (North Wing)")
                .rows(4)
                .columns(4)
                .benchType(ExamRoom.BenchType.DUAL_SEATER)
                .build();
        tenant.stamp(room2);
        fillBenches(room2);
        rooms.add(examRoomRepository.save(room2));

        // 3. Create Sample Students across 2 batches and 3 streams with numeric roll numbers
        List<Student> students = new ArrayList<>();

        String[] firstNames = {"Aarav", "Vivaan", "Aditya", "Vihaan", "Arjun", "Sai", "Reyansh", "Ayaan", "Krishna", "Ishaan",
                               "Diya", "Saanvi", "Ananya", "Aadhya", "Pari", "Chiara", "Myra", "Riya", "Avni", "Ahana",
                               "Rohan", "Kabir", "Neha", "Pooja", "Vikram", "Tanvi", "Siddharth", "Meera", "Kunal", "Sneha"};
        String[] lastNames = {"Sharma", "Verma", "Patel", "Reddy", "Gupta", "Kumar", "Singh", "Nair", "Iyer", "Rao"};

        int nameIdx = 0;

        // Group 1: CSE - Batch 2024 (25 students)
        for (int i = 1; i <= 25; i++) {
            String name = firstNames[nameIdx % firstNames.length] + " " + lastNames[(nameIdx / firstNames.length) % lastNames.length];
            nameIdx++;
            students.add(Student.builder()
                    .rollNo(String.valueOf(1000 + i))
                    .name(name)
                    .department("Computer Science")
                    .course("B.Tech")
                    .yearSession("2024")
                    .subjectCode("ENGG101")
                    .build());
        }

        // Group 2: ECE - Batch 2025 (25 students)
        for (int i = 1; i <= 25; i++) {
            String name = firstNames[nameIdx % firstNames.length] + " " + lastNames[(nameIdx / firstNames.length) % lastNames.length];
            nameIdx++;
            students.add(Student.builder()
                    .rollNo(String.valueOf(2000 + i))
                    .name(name)
                    .department("Electronics")
                    .course("B.Tech")
                    .yearSession("2025")
                    .subjectCode("ENGG101")
                    .build());
        }

        // Group 3: MECH - Batch 2024 (15 students)
        for (int i = 1; i <= 15; i++) {
            String name = firstNames[nameIdx % firstNames.length] + " " + lastNames[(nameIdx / firstNames.length) % lastNames.length];
            nameIdx++;
            students.add(Student.builder()
                    .rollNo(String.valueOf(3000 + i))
                    .name(name)
                    .department("Mechanical")
                    .course("B.Tech")
                    .yearSession("2024")
                    .subjectCode("ENGG101")
                    .build());
        }

        students.forEach(tenant::stamp);
        studentRepository.saveAll(students);

        int totalCap = rooms.stream().mapToInt(ExamRoom::getCapacity).sum();

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Successfully generated 2 exam halls (" + totalCap + " seats) and " + students.size() + " students across 3 streams and 2 batches.",
                "roomsCreated", rooms.size(),
                "studentsCreated", students.size(),
                "totalCapacity", totalCap
        ));
    }

    @DeleteMapping("/clear")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    @Transactional
    public ResponseEntity<Map<String, String>> clearAll() {
        clearTenantData();
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Cleared all exam rooms, students, and seating allocations."
        ));
    }

    /** Demo resets must never wipe another institution's data. */
    private void clearTenantData() {
        List<SeatingAllocation> allocations = tenant.visible(allocationRepository.findAll());
        allocationRepository.deleteAll(allocations);
        allocationRepository.flush();
        studentRepository.deleteAll(tenant.visible(studentRepository.findAll()));
        studentRepository.flush();
        examRoomRepository.deleteAll(tenant.visible(examRoomRepository.findAll()));
        examRoomRepository.flush();
    }

    private static void fillBenches(ExamRoom room) {
        for (int r = 1; r <= room.getRows(); r++) {
            for (int c = 1; c <= room.getColumns(); c++) {
                room.getBenches().add(Bench.builder()
                        .room(room)
                        .rowIndex(r)
                        .colIndex(c)
                        .benchType(room.getBenchType())
                        .build());
            }
        }
    }
}
