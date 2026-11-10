package com.examseating.service;

import com.examseating.dto.AdminDtos.AdminInstitutionView;
import com.examseating.dto.AdminDtos.AdminStatusView;
import com.examseating.dto.AdminDtos.AdminUserView;
import com.examseating.dto.AdminDtos.LimitOverrides;
import com.examseating.dto.AdminDtos.PlanChangeRequest;
import com.examseating.dto.AdminDtos.PlanCount;
import com.examseating.dto.AdminDtos.PlatformGenerationView;
import com.examseating.dto.AdminDtos.PlatformUsageView;
import com.examseating.dto.AdminDtos.UserChangeRequest;
import com.examseating.entity.GenerationUsage;
import com.examseating.entity.Institution;
import com.examseating.entity.Plan;
import com.examseating.entity.Subscription;
import com.examseating.entity.User;
import com.examseating.exception.ResourceNotFoundException;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.GenerationUsageRepository;
import com.examseating.repository.InstitutionRepository;
import com.examseating.repository.PlanRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.repository.StoredFileRepository;
import com.examseating.repository.SubscriptionRepository;
import com.examseating.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The operator's view of the service: every institution, every user, how much
 * each of them generates and how their limits may be changed.
 *
 * <p>This is the service behind {@code /api/v1/admin/**}. It crosses tenant
 * boundaries on purpose — that is the whole point of the platform
 * administrator — so every query names the institution it works on instead of
 * relying on the current tenant.</p>
 */
@Service
@RequiredArgsConstructor
public class PlatformAdminService {

    private final InstitutionRepository institutionRepository;
    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final GenerationUsageRepository generationUsageRepository;
    private final StudentRepository studentRepository;
    private final ExamRoomRepository examRoomRepository;
    private final StoredFileRepository storedFileRepository;
    private final QuotaService quotas;
    private final PasswordEncoder passwordEncoder;

    // ------------------------------------------------------------------
    // Institutions
    // ------------------------------------------------------------------

    /** Every customer institution — the platform's own is not a customer. */
    public List<AdminInstitutionView> institutions() {
        return customerInstitutions().stream().map(this::view).toList();
    }

    /** One institution, with its limits and its usage. */
    public AdminInstitutionView institution(Long id) {
        return view(requireInstitution(id));
    }

    /**
     * Moves an institution to another plan and/or changes the limits an
     * operator granted it.
     *
     * <p>A downgrade that would immediately put the institution over its new
     * limits is refused — the operator can lower the plan or clear the
     * overrides, but not push an institution into a state it cannot hold.</p>
     */
    @Transactional
    public AdminInstitutionView changePlan(Long institutionId, PlanChangeRequest request) {
        Institution institution = requireInstitution(institutionId);
        Subscription subscription = subscriptionRepository.findByInstitutionId(institutionId)
                .orElseGet(() -> Subscription.builder()
                        .institutionId(institutionId)
                        .planCode("FREE")
                        .status(Subscription.Status.ACTIVE)
                        .build());

        if (request.planCode() != null && !request.planCode().isBlank()) {
            Plan target = planRepository
                    .findByCode(request.planCode().trim().toUpperCase(Locale.ROOT))
                    .filter(Plan::isActive)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown plan code '" + request.planCode() + "'"));
            quotas.checkDowngradeFor(institutionId, target);
            subscription.setPlanCode(target.getCode());
        }

        LimitOverrides limits = request.limits();
        if (limits != null) {
            subscription.setMaxStudentsOverride(limits.maxStudents());
            subscription.setMaxRoomsOverride(limits.maxRooms());
            subscription.setMaxGenerationsOverride(limits.maxGenerationsPerMonth());
            subscription.setStorageLimitMbOverride(limits.storageLimitMb());
            subscription.setMaxFileSizeMbOverride(limits.maxFileSizeMb());
            subscription.setSeatsPerGenerationOverride(limits.seatsPerGeneration());
        }
        subscriptionRepository.save(subscription);
        return view(institutionRepository.save(institution));
    }

    /** Suspends an institution (its users can no longer sign in) or wakes it up. */
    @Transactional
    public AdminInstitutionView setStatus(Long institutionId, String status) {
        Institution institution = requireInstitution(institutionId);
        Institution.Status target = switch (status == null ? "" : status.trim().toUpperCase(Locale.ROOT)) {
            case "ACTIVE" -> Institution.Status.ACTIVE;
            case "SUSPENDED" -> Institution.Status.SUSPENDED;
            default -> throw new IllegalArgumentException(
                    "Unknown institution status '" + status + "' (expected ACTIVE or SUSPENDED)");
        };
        institution.setStatus(target);
        return view(institutionRepository.save(institution));
    }

    // ------------------------------------------------------------------
    // Users
    // ------------------------------------------------------------------

    /** Every user of the service. */
    public List<AdminUserView> users() {
        Map<Long, String> names = institutionNames();
        return userRepository.findAll().stream()
                .map(u -> view(u, names))
                .sorted((a, b) -> a.username().compareToIgnoreCase(b.username()))
                .toList();
    }

    /** One user. */
    public AdminUserView user(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User " + id + " not found"));
        return view(user, institutionNames());
    }

    /** Changes a user's role, their institution or their password. */
    @Transactional
    public AdminUserView changeUser(Long id, UserChangeRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User " + id + " not found"));
        if (request.role() != null && !request.role().isBlank()
                && user.getId().equals(currentOperatorId())) {
            // An operator may not demote themselves: the access would be
            // gone before they could undo it.
            throw new IllegalArgumentException(
                    "You cannot change your own role — ask another administrator to do it");
        }
        if (request.role() != null && !request.role().isBlank()) {
            User.Role role;
            try {
                role = User.Role.valueOf(request.role().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Unknown role '" + request.role()
                        + "' (expected ADMIN, EXAM_CELL, INVIGILATOR or PLATFORM_ADMIN)");
            }
            user.setRole(role);
        }
        if (request.institutionId() != null) {
            requireInstitution(request.institutionId());
            user.setInstitutionId(request.institutionId());
        }
        if (request.password() != null && !request.password().isBlank()) {
            if (request.password().length() < 8) {
                throw new IllegalArgumentException("A password must have at least 8 characters");
            }
            user.setPassword(passwordEncoder.encode(request.password()));
        }
        return view(userRepository.save(user), institutionNames());
    }

    // ------------------------------------------------------------------
    // The service as a whole
    // ------------------------------------------------------------------

    /** Totals across every institution. */
    public PlatformUsageView usage() {
        List<Institution> institutions = customerInstitutions();
        Map<String, Plan> plans = new LinkedHashMap<>();
        planRepository.findAll().forEach(p -> plans.put(p.getCode(), p));

        long users = 0;
        long students = 0;
        long halls = 0;
        long generations = 0;
        long seats = 0;
        long storage = 0;
        Map<String, Long> byPlan = new LinkedHashMap<>();

        for (Institution institution : institutions) {
            Long id = institution.getId();
            users += userRepository.countByInstitutionId(id);
            students += studentRepository.countByInstitutionId(id);
            halls += examRoomRepository.countByInstitutionId(id);
            storage += storedFileRepository.sumSizeByInstitutionId(id);
            Instant[] window = window(id);
            List<GenerationUsage> made = generationUsageRepository
                    .findByInstitutionIdAndCreatedAtBetween(id, window[0], window[1]);
            generations += made.size();
            seats += made.stream().mapToInt(GenerationUsage::getSeats).sum();
            String code = subscriptionRepository.findByInstitutionId(id)
                    .map(Subscription::getPlanCode).orElse("FREE");
            byPlan.merge(code, 1L, Long::sum);
        }

        List<PlanCount> planCounts = new ArrayList<>();
        for (Map.Entry<String, Long> entry : byPlan.entrySet()) {
            Plan plan = plans.get(entry.getKey());
            planCounts.add(new PlanCount(entry.getKey(),
                    plan == null ? entry.getKey() : plan.getName(), entry.getValue()));
        }
        planCounts.sort((a, b) -> a.planCode().compareTo(b.planCode()));

        return new PlatformUsageView(institutions.size(), users, students, halls,
                generations, seats, storage, planCounts);
    }

    /** The most recent generations across the whole service. */
    public List<PlatformGenerationView> generations(int limit) {
        Map<Long, String> names = institutionNames();
        return generationUsageRepository.findTop500ByOrderByCreatedAtDesc().stream()
                .limit(Math.max(1, Math.min(limit, 500)))
                .map(g -> new PlatformGenerationView(g.getId(), g.getInstitutionId(),
                        names.getOrDefault(g.getInstitutionId(), "—"),
                        g.getSubjectCode(), g.getSeats(), g.getPlanCode(), g.getCreatedAt()))
                .toList();
    }

    /** Whether an operator account exists at all. */
    public AdminStatusView status() {
        boolean enabled = userRepository.findAll().stream()
                .anyMatch(u -> u.getRole() == User.Role.PLATFORM_ADMIN);
        String username = userRepository.findAll().stream()
                .filter(u -> u.getRole() == User.Role.PLATFORM_ADMIN)
                .map(User::getUsername).findFirst().orElse(null);
        return new AdminStatusView(enabled, username, customerInstitutions().size());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The id of the operator making the request, or null when there is none. */
    private Long currentOperatorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return userRepository.findByUsername(authentication.getName())
                .map(User::getId).orElse(null);
    }

    private List<Institution> customerInstitutions() {
        return institutionRepository.findAll().stream()
                .filter(i -> !i.isPlatform())
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .toList();
    }

    private Institution requireInstitution(Long id) {
        Institution institution = institutionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Institution " + id + " not found"));
        if (institution.isPlatform()) {
            throw new IllegalArgumentException("The platform institution is not a customer");
        }
        return institution;
    }

    private Map<Long, String> institutionNames() {
        Map<Long, String> names = new LinkedHashMap<>();
        institutionRepository.findAll().forEach(i -> names.put(i.getId(), i.getName()));
        return names;
    }

    /** The subscription period of an institution, or a 30-day window. */
    private Instant[] window(Long institutionId) {
        return subscriptionRepository.findByInstitutionId(institutionId)
                .filter(s -> s.getPeriodStart() != null && s.getPeriodEnd() != null)
                .map(s -> new Instant[]{s.getPeriodStart(), s.getPeriodEnd()})
                .orElseGet(() -> {
                    Instant now = Instant.now();
                    return new Instant[]{now.minus(30, ChronoUnit.DAYS), now.plus(30, ChronoUnit.DAYS)};
                });
    }

    private AdminInstitutionView view(Institution institution) {
        Long id = institution.getId();
        Subscription subscription = subscriptionRepository.findByInstitutionId(id).orElse(null);
        String planCode = subscription == null ? "FREE" : subscription.getPlanCode();
        Plan plan = planRepository.findByCode(planCode).orElse(null);
        QuotaService.Limits limits = quotas.limitsFor(id);
        Instant[] window = window(id);

        long users = userRepository.countByInstitutionId(id);
        long students = studentRepository.countByInstitutionId(id);
        long halls = examRoomRepository.countByInstitutionId(id);
        long generations = generationUsageRepository
                .countByInstitutionIdAndCreatedAtBetween(id, window[0], window[1]);
        long storage = storedFileRepository.sumSizeByInstitutionId(id);

        LimitOverrides overrides = new LimitOverrides(
                subscription == null ? null : subscription.getMaxStudentsOverride(),
                subscription == null ? null : subscription.getMaxRoomsOverride(),
                subscription == null ? null : subscription.getMaxGenerationsOverride(),
                subscription == null ? null : subscription.getStorageLimitMbOverride(),
                subscription == null ? null : subscription.getMaxFileSizeMbOverride(),
                subscription == null ? null : subscription.getSeatsPerGenerationOverride());

        return new AdminInstitutionView(
                id,
                institution.getName(),
                institution.getSlug(),
                institution.getContactEmail(),
                institution.getStatus().name(),
                institution.getCreatedAt(),
                limits.planCode(),
                limits.planName(),
                subscription == null ? "ACTIVE" : subscription.getStatus().name(),
                users,
                students, limits.maxStudents(),
                halls, limits.maxRooms(),
                generations, limits.maxGenerationsPerMonth(),
                storage, limits.storageLimitBytes(),
                limits.seatsPerGeneration(),
                overrides);
    }

    private AdminUserView view(User user, Map<Long, String> institutionNames) {
        return new AdminUserView(
                user.getId(),
                user.getUsername(),
                user.getRole().name(),
                user.getFullName(),
                user.getEmail(),
                user.getInstitutionId(),
                user.getInstitutionId() == null ? null
                        : institutionNames.getOrDefault(user.getInstitutionId(), "—"),
                user.getCreatedAt(),
                user.getLastSeenAt());
    }
}
