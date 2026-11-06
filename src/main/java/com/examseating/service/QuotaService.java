package com.examseating.service;

import com.examseating.dto.AccountDtos.UsageView;
import com.examseating.entity.GenerationUsage;
import com.examseating.entity.Plan;
import com.examseating.entity.Subscription;
import com.examseating.exception.QuotaExceededException;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.GenerationUsageRepository;
import com.examseating.repository.PlanRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.repository.StoredFileRepository;
import com.examseating.repository.SubscriptionRepository;
import com.examseating.tenancy.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Enforces every plan limit: students, halls, generations, seats and storage. */
@Service
@RequiredArgsConstructor
public class QuotaService {

    private static final String FREE_PLAN = "FREE";

    private final TenantSupport tenant;
    private final PlanRepository planRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final StudentRepository studentRepository;
    private final ExamRoomRepository examRoomRepository;
    private final GenerationUsageRepository generationUsageRepository;
    private final StoredFileRepository storedFileRepository;

    public Plan currentPlan() {
        String code = subscriptionRepository.findByInstitutionId(tenant.currentId())
                .map(Subscription::getPlanCode)
                .orElse(FREE_PLAN);
        return planRepository.findByCode(code)
                .orElseGet(() -> planRepository.findByCode(FREE_PLAN)
                        .orElseThrow(() -> new IllegalStateException("Plan catalogue has not been seeded")));
    }

    public Instant[] period() {
        return subscriptionRepository.findByInstitutionId(tenant.currentId())
                .map(s -> new Instant[]{s.getPeriodStart(), s.getPeriodEnd()})
                .orElseGet(() -> {
                    Instant now = Instant.now();
                    return new Instant[]{now.minus(30, ChronoUnit.DAYS), now.plus(30, ChronoUnit.DAYS)};
                });
    }

    public long studentsUsed() {
        long id = tenant.currentId();
        long used = studentRepository.countByInstitutionId(id);
        return tenant.isDefaultTenant() ? used + studentRepository.countByInstitutionIdIsNull() : used;
    }

    public long roomsUsed() {
        long id = tenant.currentId();
        long used = examRoomRepository.countByInstitutionId(id);
        return tenant.isDefaultTenant() ? used + examRoomRepository.countByInstitutionIdIsNull() : used;
    }

    public long generationsUsed() {
        Instant[] window = period();
        return generationUsageRepository.countByInstitutionIdAndCreatedAtBetween(
                tenant.currentId(), window[0], window[1]);
    }

    public long storageUsedBytes() {
        long id = tenant.currentId();
        long used = storedFileRepository.sumSizeByInstitutionId(id);
        return tenant.isDefaultTenant() ? used + storedFileRepository.sumSizeByInstitutionIdIsNull() : used;
    }

    public void checkStudents(long additional) {
        Plan plan = currentPlan();
        long used = studentsUsed();
        if (used + additional > plan.getMaxStudents()) {
            throw new QuotaExceededException("STUDENTS",
                    "Student limit reached: " + used + "/" + plan.getMaxStudents()
                            + " on the " + plan.getName() + " plan. Upgrade to add " + additional + " more.");
        }
    }

    public void checkRooms(long additional) {
        Plan plan = currentPlan();
        long used = roomsUsed();
        if (used + additional > plan.getMaxRooms()) {
            throw new QuotaExceededException("ROOMS",
                    "Hall limit reached: " + used + "/" + plan.getMaxRooms()
                            + " on the " + plan.getName() + " plan. Upgrade to add more halls.");
        }
    }

    public void checkGeneration(int seats) {
        Plan plan = currentPlan();
        long used = generationsUsed();
        if (used >= plan.getMaxGenerationsPerMonth()) {
            throw new QuotaExceededException("GENERATIONS",
                    "Monthly generation limit reached: " + used + "/" + plan.getMaxGenerationsPerMonth()
                            + " on the " + plan.getName() + " plan. Upgrade to generate more plans.");
        }
        if (seats > plan.getSeatsPerGeneration()) {
            throw new QuotaExceededException("SEATS_PER_GENERATION",
                    "This plan allows at most " + plan.getSeatsPerGeneration()
                            + " seats per generation (requested " + seats + "). Upgrade for larger rounds.");
        }
    }

    public void checkStorage(long extraBytes, long fileBytes) {
        Plan plan = currentPlan();
        long maxFile = plan.getMaxFileSizeMb() * 1024L * 1024L;
        if (fileBytes > maxFile) {
            throw new QuotaExceededException("FILE_SIZE",
                    "File is larger than the " + plan.getMaxFileSizeMb() + " MB limit on the "
                            + plan.getName() + " plan.");
        }
        long used = storageUsedBytes();
        long limit = plan.getStorageLimitMb() * 1024L * 1024L;
        if (used + extraBytes > limit) {
            throw new QuotaExceededException("STORAGE",
                    "Storage limit reached: " + (used / (1024 * 1024)) + "/"
                            + plan.getStorageLimitMb() + " MB on the " + plan.getName() + " plan.");
        }
    }

    /** Rejects a downgrade that would immediately put the institution over its new limits. */
    public void checkDowngrade(Plan target) {
        if (studentsUsed() > target.getMaxStudents()
                || roomsUsed() > target.getMaxRooms()
                || storageUsedBytes() > target.getStorageLimitMb() * 1024L * 1024L
                || generationsUsed() > target.getMaxGenerationsPerMonth()) {
            throw new QuotaExceededException("DOWNGRADE",
                    "Current usage exceeds the " + target.getName()
                            + " plan limits. Remove data or choose a higher plan.");
        }
    }

    public void recordGeneration(String subjectCode, int seats) {
        generationUsageRepository.save(GenerationUsage.builder()
                .institutionId(tenant.currentId())
                .subjectCode(subjectCode)
                .seats(seats)
                .planCode(currentPlan().getCode())
                .build());
    }

    public UsageView usage() {
        Plan plan = currentPlan();
        Instant[] window = period();
        return new UsageView(
                plan.getCode(), plan.getName(),
                studentsUsed(), plan.getMaxStudents(),
                roomsUsed(), plan.getMaxRooms(),
                generationsUsed(), plan.getMaxGenerationsPerMonth(),
                storageUsedBytes(), plan.getStorageLimitMb() * 1024L * 1024L,
                plan.getSeatsPerGeneration(),
                window[0], window[1]);
    }
}
