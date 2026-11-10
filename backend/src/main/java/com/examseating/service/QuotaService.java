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

    /**
     * The limits that actually apply: the plan's values, replaced by the
     * institution's overrides where the operator set one.
     *
     * @param planCode             the plan the institution is on
     * @param planName             the plan's display name
     * @param maxStudents          how many students the institution may hold
     * @param maxRooms             how many halls it may hold
     * @param maxGenerationsPerMonth how many plans it may generate per month
     * @param storageLimitBytes    how much it may store
     * @param maxFileSizeBytes     how large one uploaded file may be
     * @param seatsPerGeneration   how many seats one generation may seat
     */
    public record Limits(String planCode, String planName, int maxStudents, int maxRooms,
                         int maxGenerationsPerMonth, long storageLimitBytes, long maxFileSizeBytes,
                         int seatsPerGeneration) {
    }

    public Plan currentPlan() {
        String code = subscriptionRepository.findByInstitutionId(tenant.currentId())
                .map(Subscription::getPlanCode)
                .orElse(FREE_PLAN);
        return planRepository.findByCode(code)
                .orElseGet(() -> planRepository.findByCode(FREE_PLAN)
                        .orElseThrow(() -> new IllegalStateException("Plan catalogue has not been seeded")));
    }

    /**
     * The limits in force for the current institution.
     */
    public Limits limits() {
        return limitsFor(tenant.currentId());
    }

    /**
     * The limits in force for one institution — the plan it is on, with the
     * operator's overrides applied.
     *
     * @param institutionId the institution to resolve the limits for
     */
    public Limits limitsFor(Long institutionId) {
        Subscription subscription = subscriptionRepository.findByInstitutionId(institutionId)
                .orElse(null);
        String code = subscription == null ? FREE_PLAN : subscription.getPlanCode();
        Plan plan = planRepository.findByCode(code)
                .orElseGet(() -> planRepository.findByCode(FREE_PLAN)
                        .orElseThrow(() -> new IllegalStateException("Plan catalogue has not been seeded")));
        return new Limits(
                plan.getCode(),
                plan.getName(),
                subscription != null && subscription.getMaxStudentsOverride() != null
                        ? subscription.getMaxStudentsOverride() : plan.getMaxStudents(),
                subscription != null && subscription.getMaxRoomsOverride() != null
                        ? subscription.getMaxRoomsOverride() : plan.getMaxRooms(),
                subscription != null && subscription.getMaxGenerationsOverride() != null
                        ? subscription.getMaxGenerationsOverride() : plan.getMaxGenerationsPerMonth(),
                (subscription != null && subscription.getStorageLimitMbOverride() != null
                        ? subscription.getStorageLimitMbOverride() : plan.getStorageLimitMb()) * 1024L * 1024L,
                (subscription != null && subscription.getMaxFileSizeMbOverride() != null
                        ? subscription.getMaxFileSizeMbOverride() : plan.getMaxFileSizeMb()) * 1024L * 1024L,
                subscription != null && subscription.getSeatsPerGenerationOverride() != null
                        ? subscription.getSeatsPerGenerationOverride() : plan.getSeatsPerGeneration());
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
        Limits limits = limits();
        long used = studentsUsed();
        if (used + additional > limits.maxStudents()) {
            throw new QuotaExceededException("STUDENTS",
                    "Student limit reached: " + used + "/" + limits.maxStudents()
                            + " on the " + limits.planName() + " plan. Upgrade to add " + additional + " more.");
        }
    }

    public void checkRooms(long additional) {
        Limits limits = limits();
        long used = roomsUsed();
        if (used + additional > limits.maxRooms()) {
            throw new QuotaExceededException("ROOMS",
                    "Hall limit reached: " + used + "/" + limits.maxRooms()
                            + " on the " + limits.planName() + " plan. Upgrade to add more halls.");
        }
    }

    public void checkGeneration(int seats) {
        Limits limits = limits();
        long used = generationsUsed();
        if (used >= limits.maxGenerationsPerMonth()) {
            throw new QuotaExceededException("GENERATIONS",
                    "Monthly generation limit reached: " + used + "/" + limits.maxGenerationsPerMonth()
                            + " on the " + limits.planName() + " plan. Upgrade to generate more plans.");
        }
        if (seats > limits.seatsPerGeneration()) {
            throw new QuotaExceededException("SEATS_PER_GENERATION",
                    "This plan allows at most " + limits.seatsPerGeneration()
                            + " seats per generation (requested " + seats + "). Upgrade for larger rounds.");
        }
    }

    public void checkStorage(long extraBytes, long fileBytes) {
        Limits limits = limits();
        if (fileBytes > limits.maxFileSizeBytes()) {
            throw new QuotaExceededException("FILE_SIZE",
                    "File is larger than the " + (limits.maxFileSizeBytes() / (1024 * 1024))
                            + " MB limit on the " + limits.planName() + " plan.");
        }
        long used = storageUsedBytes();
        long limit = limits.storageLimitBytes();
        if (used + extraBytes > limit) {
            throw new QuotaExceededException("STORAGE",
                    "Storage limit reached: " + (used / (1024 * 1024)) + "/"
                            + (limit / (1024 * 1024)) + " MB on the " + limits.planName() + " plan.");
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

    /**
     * The same check, but for an institution other than the current one —
     * the operator's view when moving an institution to a smaller plan.
     */
    public void checkDowngradeFor(Long institutionId, Plan target) {
        long students = studentRepository.countByInstitutionId(institutionId);
        long rooms = examRoomRepository.countByInstitutionId(institutionId);
        long storage = storedFileRepository.sumSizeByInstitutionId(institutionId);
        if (students > target.getMaxStudents()
                || rooms > target.getMaxRooms()
                || storage > target.getStorageLimitMb() * 1024L * 1024L) {
            throw new QuotaExceededException("DOWNGRADE",
                    "That institution currently holds " + students + " students, " + rooms
                            + " halls and " + (storage / (1024 * 1024)) + " MB — more than the "
                            + target.getName() + " plan allows. Remove data first or choose a higher plan.");
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
        Limits limits = limits();
        Instant[] window = period();
        return new UsageView(
                limits.planCode(), limits.planName(),
                studentsUsed(), limits.maxStudents(),
                roomsUsed(), limits.maxRooms(),
                generationsUsed(), limits.maxGenerationsPerMonth(),
                storageUsedBytes(), limits.storageLimitBytes(),
                limits.seatsPerGeneration(),
                window[0], window[1]);
    }
}
