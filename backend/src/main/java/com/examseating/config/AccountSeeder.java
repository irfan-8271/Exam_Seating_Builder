package com.examseating.config;

import com.examseating.entity.Institution;
import com.examseating.entity.Plan;
import com.examseating.repository.InstitutionRepository;
import com.examseating.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Installs the default (single-tenant) institution and the plan catalogue on
 * first boot. Existing installations keep their data: rows with a null
 * institution id are treated as belonging to this default institution.
 */
@Component
@RequiredArgsConstructor
public class AccountSeeder implements CommandLineRunner {

    private final InstitutionRepository institutionRepository;
    private final PlanRepository planRepository;

    @Value("${app.tenancy.default-slug:default}")
    private String defaultSlug;

    @Override
    public void run(String... args) {
        if (institutionRepository.findBySlug(defaultSlug).isEmpty()) {
            institutionRepository.save(Institution.builder()
                    .name("Default institution")
                    .slug(defaultSlug)
                    .status(Institution.Status.ACTIVE)
                    .build());
        }
        if (planRepository.count() == 0) {
            planRepository.saveAll(List.of(
                    plan("FREE", "Free", "0.00", 50, 3, 5, 10, 2, 100, 10),
                    plan("STARTER", "Starter", "19.00", 500, 20, 50, 100, 5, 500, 20),
                    plan("PRO", "Pro", "79.00", 5000, 100, 500, 1024, 10, 5000, 30),
                    plan("ENTERPRISE", "Enterprise", "249.00", 100000, 1000, 10000, 10240, 25, 50000, 40)));
        }
    }

    private static Plan plan(String code, String name, String price, int students, int rooms,
                             int generations, long storageMb, long fileMb, int seats, int order) {
        return Plan.builder()
                .code(code).name(name).monthlyPrice(new BigDecimal(price))
                .maxStudents(students).maxRooms(rooms).maxGenerationsPerMonth(generations)
                .storageLimitMb(storageMb).maxFileSizeMb(fileMb).seatsPerGeneration(seats)
                .sortOrder(order).active(true)
                .build();
    }
}
