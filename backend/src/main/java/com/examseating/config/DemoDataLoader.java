package com.examseating.config;

import com.examseating.entity.Bench;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.Student;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.service.StudentIdentityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional sample data loader (enabled via {@code app.demo-data.enabled=true})
 * using the pure dynamic student attribute schema.
 */
@Component
@Order(20)
public class DemoDataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    private final StudentRepository studentRepository;
    private final ExamRoomRepository examRoomRepository;
    private final StudentIdentityService identityService;
    private final boolean enabled;

    public DemoDataLoader(StudentRepository studentRepository,
                          ExamRoomRepository examRoomRepository,
                          StudentIdentityService identityService,
                          @Value("${app.demo-data.enabled:false}") boolean enabled) {
        this.studentRepository = studentRepository;
        this.examRoomRepository = examRoomRepository;
        this.identityService = identityService;
        this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!enabled) {
            return;
        }
        if (studentRepository.count() > 0 || examRoomRepository.count() > 0) {
            log.info("DemoDataLoader skipped — database already contains data");
            return;
        }

        examRoomRepository.saveAll(List.of(
                room("Hall-A", 5, 5, ExamRoom.BenchType.DUAL_SEATER),
                room("Hall-B", 4, 6, ExamRoom.BenchType.SINGLE_SEATER)
        ));

        identityService.recordColumnLabels(Map.of(
                "registrationno", "Registration No",
                "stream", "Stream",
                "batch", "Batch",
                "examdate", "Exam Date"
        ));

        String[] streams = {"CSE", "ECE", "ME", "CE"};
        String[] batches = {"2025", "2026"};
        String[] firstNames = {"Aarav", "Diya", "Rohan", "Priya", "Vikram", "Ananya", "Karan", "Sneha"};
        String[] lastNames = {"Sharma", "Patel", "Nair", "Iyer", "Verma", "Reddy", "Gupta", "Das"};

        int serial = 1;
        for (String stream : streams) {
            for (String batch : batches) {
                for (int i = 1; i <= 8; i++) {
                    String regNo = "%s-%s-%02d".formatted(stream, batch.substring(2), i);
                    String name = firstNames[(serial - 1) % firstNames.length] + " "
                            + lastNames[((serial - 1) / firstNames.length) % lastNames.length];
                    Map<String, String> attrs = new LinkedHashMap<>();
                    attrs.put("registrationno", regNo);
                    attrs.put("stream", stream);
                    attrs.put("batch", batch);
                    attrs.put("examdate", "2026-11-15");
                    Student s = Student.builder()
                            .name(name)
                            .identityKey(regNo)
                            .attributes(attrs)
                            .build();
                    studentRepository.save(s);
                    serial++;
                }
            }
        }
        identityService.saveScheme(
                "Registration No",
                List.of("registrationno"),
                "registrationno",
                List.of("stream", "batch"),
                "examdate");
        log.info("Seeded {} demo students and 2 demo halls", serial - 1);
    }

    private static ExamRoom room(String name, int rows, int cols, ExamRoom.BenchType type) {
        ExamRoom room = ExamRoom.builder()
                .hallName(name)
                .rows(rows)
                .columns(cols)
                .benchType(type)
                .build();
        for (int r = 1; r <= rows; r++) {
            for (int c = 1; c <= cols; c++) {
                room.getBenches().add(Bench.builder()
                        .room(room)
                        .rowIndex(r)
                        .colIndex(c)
                        .build());
            }
        }
        return room;
    }
}
