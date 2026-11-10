package com.examseating.config;

import com.examseating.dto.ArrangeRequest;
import com.examseating.dto.SeatingResponse;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.Student;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.service.SeatingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Optional demo dataset (rooms + students) and an automatic first seating round.
 * Only runs when {@code app.seed-demo=true} (see application.yml) and the student
 * table is empty — purely for trying the API without typing data.
 *
 * <p>{@code @ConditionalOnProperty} is a Spring Boot feature: the bean only exists
 * when the property matches, so this whole class is skipped in production.</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.seed-demo", havingValue = "true")
public class DemoDataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    private final StudentRepository studentRepository;
    private final ExamRoomRepository examRoomRepository;
    private final SeatingService seatingService;

    @Override
    public void run(String... args) {
        if (studentRepository.count() > 0) {
            return;
        }
        log.info("Seeding demo data...");

        // --- 3 halls: 4x5 dual (40 seats) + 3x3 single (9) + 2x4 dual (16) = 65 seats
        examRoomRepository.save(room("Main Auditorium", 4, 5, ExamRoom.BenchType.DUAL_SEATER));
        examRoomRepository.save(room("Room 102", 3, 3, ExamRoom.BenchType.SINGLE_SEATER));
        examRoomRepository.save(room("Room 103", 2, 4, ExamRoom.BenchType.DUAL_SEATER));

        // --- 40 students, 8 groups (dept+year), balanced for the dual-bench rules.
        // Deliberately below the FREE plan's limit of 50 students: a demo
        // dataset that is already over quota makes the very first "add a
        // student" request fail with 429 QUOTA_EXCEEDED.
        String[][] groups = {
                {"CSE", "2025", "6"}, {"CSE", "2026", "6"},
                {"ECE", "2025", "5"}, {"ECE", "2026", "5"},
                {"ME", "2025", "5"}, {"ME", "2026", "5"},
                {"IT", "2025", "4"}, {"CE", "2026", "4"},
        };
        int seq = 1;
        for (String[] g : groups) {
            String dept = g[0], year = g[1];
            int count = Integer.parseInt(g[2]);
            for (int i = 1; i <= count; i++) {
                String rollNo = dept + "-" + year + "-" + String.format("%03d", i);
                studentRepository.save(Student.builder()
                        .name("Student " + String.format("%03d", seq++))
                        .rollNo(rollNo)
                        .identityKey(rollNo.toUpperCase(java.util.Locale.ROOT))
                        .department(dept)
                        .course("B.Tech")
                        .yearSession(year)
                        .subjectCode("CS301")
                        .build());
            }
        }

        // --- run a seating round with dH = dV = dD = 1
        SeatingResponse result = seatingService.arrange(new ArrangeRequest(
                1, 1, 1, null, "CS301", null, true));
        log.info("Demo seating round: seated={}, unplaced={}, totalSlots={}",
                result.seated(), result.unplaced(), result.totalSlots());
    }

    private static ExamRoom room(String hall, int rows, int cols, ExamRoom.BenchType type) {
        ExamRoom room = ExamRoom.builder()
                .hallName(hall).rows(rows).columns(cols).benchType(type).build();
        for (int r = 1; r <= rows; r++) {
            for (int c = 1; c <= cols; c++) {
                room.getBenches().add(com.examseating.entity.Bench.builder()
                        .room(room).rowIndex(r).colIndex(c).benchType(type).build());
            }
        }
        return room;
    }
}
