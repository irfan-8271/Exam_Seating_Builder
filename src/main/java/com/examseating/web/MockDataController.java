package com.examseating.web;

import com.examseating.entity.Bench;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.Student;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.SeatingAllocationRepository;
import com.examseating.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
 */
@RestController
@RequestMapping("/api/mock")
@RequiredArgsConstructor
public class MockDataController {

    private final StudentRepository studentRepository;
    private final ExamRoomRepository examRoomRepository;
    private final SeatingAllocationRepository allocationRepository;

    @GetMapping("/stats")
    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        List<ExamRoom> rooms = examRoomRepository.findAll();
        int totalCapacity = rooms.stream().mapToInt(ExamRoom::getCapacity).sum();
        long studentCount = studentRepository.count();
        long allocationCount = allocationRepository.count();
        return Map.of(
                "roomCount", rooms.size(),
                "totalCapacity", totalCapacity,
                "studentCount", studentCount,
                "allocationCount", allocationCount
        );
    }

    @PostMapping("/generate")
    @Transactional
    public ResponseEntity<Map<String, Object>> generateMockData() {
        // 1. Clear previous data to start fresh
        allocationRepository.deleteAllInBatch();
        studentRepository.deleteAllInBatch();
        examRoomRepository.deleteAll();

        // 2. Create Sample Exam Rooms
        List<ExamRoom> rooms = new ArrayList<>();

        ExamRoom room1 = ExamRoom.builder()
                .hallName("Hall 101 (Auditorium)")
                .rows(5)
                .columns(4)
                .benchType(ExamRoom.BenchType.DUAL_SEATER)
                .build();
        fillBenches(room1);
        rooms.add(examRoomRepository.save(room1));

        ExamRoom room2 = ExamRoom.builder()
                .hallName("Hall 102 (North Wing)")
                .rows(4)
                .columns(4)
                .benchType(ExamRoom.BenchType.DUAL_SEATER)
                .build();
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
    @Transactional
    public ResponseEntity<Map<String, String>> clearAll() {
        allocationRepository.deleteAllInBatch();
        studentRepository.deleteAllInBatch();
        examRoomRepository.deleteAll();
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Cleared all exam rooms, students, and seating allocations."
        ));
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
