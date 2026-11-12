package com.examseating.service;

import com.examseating.dto.AdminDtos.AdminActivityView;
import com.examseating.dto.AdminDtos.AdminInstitutionView;
import com.examseating.dto.AdminDtos.AdminStatusView;
import com.examseating.dto.AdminDtos.AdminUserView;
import com.examseating.dto.AdminDtos.LimitOverrides;
import com.examseating.dto.AdminDtos.PlanChangeRequest;
import com.examseating.dto.AdminDtos.PlanCount;
import com.examseating.dto.AdminDtos.PlatformGenerationView;
import com.examseating.dto.AdminDtos.PlatformUsageView;
import com.examseating.dto.AdminDtos.UserChangeRequest;
import com.examseating.dto.AdminDtos.UserLimitsRequest;
import com.examseating.entity.ActivityLog;
import com.examseating.entity.GenerationUsage;
import com.examseating.entity.Institution;
import com.examseating.entity.Plan;
import com.examseating.entity.Subscription;
import com.examseating.entity.User;
import com.examseating.exception.ResourceNotFoundException;
import com.examseating.repository.ActivityLogRepository;
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
 * The operator's view of the service: every institution, every account, how
 * much each of them generates, and how their limits and permissions may be
 * changed.
 *
 * <p>This is the service behind {@code /api/v1/admin/**}. It crosses tenant
 * boundaries on purpose — that is the whole point of the platform
 * administrator — so every query names the institution it works on instead of
 * relying on the current tenant.</p>
 *
 * <p><b>Passwords are never read here.</b> The operator can set a new
 * password for an account; there is no method that returns one, and no view
 * that carries one.</p>
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
    private final ActivityLogRepository activityLogRepository;
    private final QuotaService quotas;
    private final PasswordEncoder passwordEncoder;
    private final ActivityLogService activityLog;

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
            activityLog.record(ActivityLog.Type.PLAN_CHANGE, institution,
                    "Moved to the " + target.getName() + " plan");
        }

        LimitOverrides limits = request.limits();
        if (limits != null) {
            subscription.setMaxStudentsOverride(limits.maxStudents());
            subscription.setMaxRoomsOverride(limits.maxRooms());
            subscription.setMaxGenerationsOverride(limits.maxGenerationsPerMonth());
            subscription.setStorageLimitMbOverride(limits.storageLimitMb());
            subscription.setMaxFileSizeMbOverride(limits.maxFileSizeMb());
            subscription.setSeatsPerGenerationOverride(limits.seatsPerGeneration());
            activityLog.record(ActivityLog.Type.LIMIT_CHANGE, institution,
                    "Changed the workspace limits");
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
        activityLog.record(target == Institution.Status.SUSPENDED
                ? ActivityLog.Type.ACCOUNT_SUSPENDED : ActivityLog.Type.ACCOUNT_RESTORED,
                institution, "Workspace set to " + target.name());
        return view(institutionRepository.save(institution));
    }

    // ------------------------------------------------------------------
    // Accounts
    // ------------------------------------------------------------------

    /** Every account of the service. */
    public List<AdminUserView> users() {
        Map<Long, String> names = institutionNames();
        return userRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(u -> view(u, names))
                .sorted((a, b) -> a.username().compareToIgnoreCase(b.username()))
                .toList();
    }

    /** One account. */
    public AdminUserView user(Long id) {
        User user = requireUser(id);
        return view(user, institutionNames());
    }

    /**
     * Changes an account: its role, its status, its workspace, its name, its
     * address or its password.
     *
     * <p>An operator may not change their own role or suspend themselves — the
     * access would be gone before they could undo it.</p>
     */
    @Transactional
    public AdminUserView changeUser(Long id, UserChangeRequest request) {
        User user = requireUser(id);
        boolean isSelf = user.getId().equals(currentOperatorId());
        StringBuilder changes = new StringBuilder();

        if (request.role() != null && !request.role().isBlank()) {
            if (isSelf) {
                throw new IllegalArgumentException(
                        "You cannot change your own role — ask another administrator to do it");
            }
            User.Role role;
            try {
                role = User.Role.valueOf(request.role().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Unknown role '" + request.role()
                        + "' (expected ADMIN, EXAM_CELL, INVIGILATOR or PLATFORM_ADMIN)");
            }
            if (role != user.getRole()) {
                changes.append("role ").append(user.getRole()).append(" → ").append(role).append("; ");
                user.setRole(role);
            }
        }
        if (request.status() != null && !request.status().isBlank()) {
            User.Status status;
            try {
                status = User.Status.valueOf(request.status().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Unknown status '" + request.status()
                        + "' (expected ACTIVE or SUSPENDED)");
            }
            if (isSelf && status == User.Status.SUSPENDED) {
                throw new IllegalArgumentException(
                        "You cannot suspend your own account — ask another administrator to do it");
            }
            if (status != user.effectiveStatus()) {
                changes.append(status == User.Status.SUSPENDED ? "suspended; " : "restored; ");
                user.setStatus(status);
            }
        }
        if (request.institutionId() != null && !request.institutionId().equals(user.getInstitutionId())) {
            requireInstitution(request.institutionId());
            changes.append("moved to another workspace; ");
            user.setInstitutionId(request.institutionId());
        }
        if (request.fullName() != null) {
            user.setFullName(request.fullName().isBlank() ? null : request.fullName().trim());
        }
        if (request.email() != null) {
            user.setEmail(request.email().isBlank() ? null : request.email().trim());
        }
        if (request.password() != null && !request.password().isBlank()) {
            if (request.password().length() < 8) {
                throw new IllegalArgumentException("A password must have at least 8 characters");
            }
            user.setPassword(passwordEncoder.encode(request.password()));
            user.setPasswordChangedAt(Instant.now());
            changes.append("password reset; ");
        }
        User saved = userRepository.save(user);
        if (changes.length() > 0) {
            activityLog.record(ActivityLog.Type.USER_CHANGE, saved, changes.toString().trim());
        }
        return view(saved, institutionNames());
    }

    /**
     * Grants one account limits of its own, on top of the workspace's.
     *
     * <p>This is how one person is given more room than the rest of their
     * institution without touching the others. A null field falls back to the
     * workspace's value; clearing every field removes the override
     * altogether.</p>
     */
    @Transactional
    public AdminUserView setUserLimits(Long id, UserLimitsRequest request) {
        User user = requireUser(id);
        LimitOverrides limits = request.limits() == null
                ? new LimitOverrides(null, null, null, null, null, null)
                : request.limits();
        user.setMaxStudentsOverride(limits.maxStudents());
        user.setMaxRoomsOverride(limits.maxRooms());
        user.setMaxGenerationsOverride(limits.maxGenerationsPerMonth());
        user.setStorageLimitMbOverride(limits.storageLimitMb());
        user.setMaxFileSizeMbOverride(limits.maxFileSizeMb());
        user.setSeatsPerGenerationOverride(limits.seatsPerGeneration());
        userRepository.save(user);
        activityLog.record(ActivityLog.Type.LIMIT_CHANGE, user,
                "Changed the personal limits");
        return view(user, institutionNames());
    }

    /** The limits that apply to one account — its workspace's, plus its own. */
    public LimitOverrides userOverrides(User account) {
        return new LimitOverrides(
                account.getMaxStudentsOverride(),
                account.getMaxRoomsOverride(),
                account.getMaxGenerationsOverride(),
                account.getStorageLimitMbOverride(),
                account.getMaxFileSizeMbOverride(),
                account.getSeatsPerGenerationOverride());
    }

    // ------------------------------------------------------------------
    // Activity
    // ------------------------------------------------------------------

    /** What has been happening across the service, most recent first. */
    public List<AdminActivityView> activity(int limit) {
        int take = Math.max(1, Math.min(limit, 200));
        return activityLogRepository.findTop200ByOrderByOccurredAtDesc().stream()
                .limit(take)
                .map(e -> new AdminActivityView(e.getId(), e.getType().name(), e.getUsername(),
                        e.getUserId(), e.getInstitutionName(), e.getDetail(), e.getOccurredAt()))
                .toList();
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
                        g.getScopeKey(), g.getSeats(), g.getPlanCode(), g.getCreatedAt()))
                .toList();
    }

    /** Whether an operator account exists at all. */
    public AdminStatusView status() {
        List<User> operators = userRepository.findByRole(User.Role.PLATFORM_ADMIN);
        String username = operators.stream().map(User::getUsername).findFirst().orElse(null);
        return new AdminStatusView(!operators.isEmpty(), username, customerInstitutions().size());
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

    private User requireUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account " + id + " not found"));
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

    /**
     * One account as the operator sees it.
     *
     * <p>Everything about the account except its password — there is no
     * password to show, and no way to ask for one.</p>
     */
    private AdminUserView view(User user, Map<Long, String> institutionNames) {
        Long institutionId = user.getInstitutionId();
        Long userId = user.getId();
        QuotaService.Limits limits = institutionId == null
                ? quotas.limitsFor(null)
                : quotas.limitsForUser(user, institutionId);
        Subscription subscription = institutionId == null
                ? null : subscriptionRepository.findByInstitutionId(institutionId).orElse(null);

        long students = institutionId == null ? 0 : studentRepository.countByInstitutionId(institutionId);
        long halls = institutionId == null ? 0 : examRoomRepository.countByInstitutionId(institutionId);
        long storage = institutionId == null ? 0 : storedFileRepository.sumSizeByInstitutionId(institutionId);
        long generations = 0;
        if (institutionId != null) {
            Instant[] window = window(institutionId);
            generations = generationUsageRepository
                    .countByInstitutionIdAndCreatedAtBetween(institutionId, window[0], window[1]);
        }

        LimitOverrides institutionOverrides = new LimitOverrides(
                subscription == null ? null : subscription.getMaxStudentsOverride(),
                subscription == null ? null : subscription.getMaxRoomsOverride(),
                subscription == null ? null : subscription.getMaxGenerationsOverride(),
                subscription == null ? null : subscription.getStorageLimitMbOverride(),
                subscription == null ? null : subscription.getMaxFileSizeMbOverride(),
                subscription == null ? null : subscription.getSeatsPerGenerationOverride());

        return new AdminUserView(
                userId,
                user.getUsername(),
                user.getRole().name(),
                user.effectiveStatus().name(),
                user.getFullName(),
                user.getEmail(),
                institutionId,
                institutionId == null ? null
                        : institutionNames.getOrDefault(institutionId, "—"),
                user.getCreatedAt(),
                user.getLastSeenAt(),
                user.getPasswordChangedAt(),
                limits.planCode(),
                limits.planName(),
                students, limits.maxStudents(),
                halls, limits.maxRooms(),
                generations, limits.maxGenerationsPerMonth(),
                storage, limits.storageLimitBytes(),
                limits.seatsPerGeneration(),
                institutionOverrides,
                userOverrides(user));
    }
}
