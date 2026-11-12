package com.examseating.service;

import com.examseating.dto.AccountDtos.AuthSession;
import com.examseating.dto.AccountDtos.GenerationView;
import com.examseating.dto.AccountDtos.InstitutionUpdate;
import com.examseating.dto.AccountDtos.InstitutionView;
import com.examseating.dto.AccountDtos.LoginRequest;
import com.examseating.dto.AccountDtos.PlatformLoginRequest;
import com.examseating.dto.AccountDtos.PlanChangeRequest;
import com.examseating.dto.AccountDtos.PlanView;
import com.examseating.dto.AccountDtos.RegisterRequest;
import com.examseating.dto.AccountDtos.SubscriptionView;
import com.examseating.dto.AccountDtos.UsageView;
import com.examseating.dto.AccountDtos.UserView;
import com.examseating.entity.ActivityLog;
import com.examseating.entity.AuthToken;
import com.examseating.entity.GenerationUsage;
import com.examseating.entity.Institution;
import com.examseating.entity.Plan;
import com.examseating.entity.Subscription;
import com.examseating.entity.User;
import com.examseating.exception.ForbiddenException;
import com.examseating.exception.ResourceNotFoundException;
import com.examseating.exception.UnauthorizedException;
import com.examseating.repository.AuthTokenRepository;
import com.examseating.repository.GenerationUsageRepository;
import com.examseating.repository.InstitutionRepository;
import com.examseating.repository.PlanRepository;
import com.examseating.repository.SubscriptionRepository;
import com.examseating.repository.UserRepository;
import com.examseating.tenancy.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Accounts, sessions, plan catalogue, subscriptions and usage.
 *
 * <p>There are two ways in, and they are deliberately separate:</p>
 * <ul>
 *   <li>{@link #register} — anybody may create an account. They choose their
 *       own login name and their own password; there is no shared
 *       administrator password, no approval step and no slug to invent. The
 *       workspace is created for them and named after them.</li>
 *   <li>{@link #platformLogin} — the platform administrator signs in through
 *       its own endpoint. The customer sign-in refuses administrator
 *       accounts, so the two flows cannot be confused.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    private static final String FREE_PLAN = "FREE";

    private final InstitutionRepository institutionRepository;
    private final UserRepository userRepository;
    private final AuthTokenRepository tokenRepository;
    private final PlanRepository planRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final GenerationUsageRepository generationUsageRepository;
    private final PasswordEncoder passwordEncoder;
    private final TenantSupport tenant;
    private final QuotaService quotas;
    private final ActivityLogService activityLog;

    @Value("${app.auth.token-ttl-hours:24}")
    private long tokenTtlHours;

    @Value("${app.auth.remember-ttl-days:30}")
    private long rememberTtlDays;

    /**
     * Creates an account and the workspace it belongs to.
     *
     * <p>The person picks their own login name and password — nothing is
     * shared and nobody approves the account. The workspace is created for
     * them: its name is what they typed, or their own name when they left
     * it empty, and its short handle is derived from their login name.</p>
     */
    @Transactional
    public AuthSession register(RegisterRequest request) {
        String username = request.username().trim();
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("That username is already taken. Try another one.");
        }
        Institution institution = institutionRepository.save(Institution.builder()
                .name(institutionName(request, username))
                .slug(uniqueSlug(username))
                .contactEmail(blankToNull(request.email()))
                .status(Institution.Status.ACTIVE)
                .build());
        Instant now = Instant.now();
        User user = userRepository.save(User.builder()
                .username(username)
                .password(passwordEncoder.encode(request.password()))
                .role(User.Role.ADMIN)
                .status(User.Status.ACTIVE)
                .fullName(blankToNull(request.fullName()))
                .email(blankToNull(request.email()))
                .passwordChangedAt(now)
                .institutionId(institution.getId())
                .build());
        subscriptionRepository.save(Subscription.builder()
                .institutionId(institution.getId())
                .planCode(FREE_PLAN)
                .status(Subscription.Status.ACTIVE)
                .build());
        activityLog.record(ActivityLog.Type.REGISTRATION, user,
                "Created the account and the workspace \"" + institution.getName() + "\"");
        return session(user, institution, issueToken(user, false));
    }

    /**
     * The general sign-in.
     *
     * <p>Any account may come in here. An administrator who uses this door is
     * told so in the answer, and the interface sends them to the
     * administration screens rather than to a customer workspace — but the
     * door itself stays open to everyone, because refusing a valid account
     * would only make people use the wrong one.</p>
     */
    @Transactional
    public AuthSession login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username().trim())
                .filter(u -> passwordEncoder.matches(request.password(), u.getPassword()))
                .orElseThrow(() -> new UnauthorizedException("Invalid username or password"));
        return establishSession(user, Boolean.TRUE.equals(request.rememberMe()));
    }

    /**
     * The administration sign-in — the door of the operator's own screens.
     *
     * <p>Only an account with the platform administrator role gets in; every
     * other account is refused with a clear reason rather than a generic
     * failure, so the operator knows they used the wrong door. This is a
     * separate check from {@link #login}, not the same one with a flag.</p>
     */
    @Transactional
    public AuthSession platformLogin(PlatformLoginRequest request) {
        User user = userRepository.findByUsername(request.username().trim())
                .filter(u -> passwordEncoder.matches(request.password(), u.getPassword()))
                .orElseThrow(() -> new UnauthorizedException("Invalid username or password"));
        if (user.getRole() != User.Role.PLATFORM_ADMIN) {
            throw new ForbiddenException(
                    "This account is not a platform administrator. Use the normal sign-in.");
        }
        return establishSession(user, false);
    }

    /** Signs an account in: checks it is allowed in, stamps the time, issues a token. */
    private AuthSession establishSession(User user, boolean rememberMe) {
        if (user.effectiveStatus() != User.Status.ACTIVE) {
            throw new UnauthorizedException("This account has been suspended.");
        }
        Institution institution = institution(user);
        if (institution.getStatus() == Institution.Status.SUSPENDED) {
            throw new UnauthorizedException("This institution has been suspended");
        }
        // Remember when the account was last seen — the operator's view.
        user.setLastSeenAt(Instant.now());
        userRepository.save(user);
        activityLog.record(ActivityLog.Type.SIGN_IN, user, "Signed in");
        return session(user, institution, issueToken(user, rememberMe));
    }

    public AuthSession me(String authorization) {
        AuthToken token = validToken(authorization);
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new UnauthorizedException("Session is no longer valid"));
        return session(user, institution(user), token);
    }

    @Transactional
    public void logout(String authorization) {
        String value = tokenFrom(authorization);
        User user = tokenRepository.findByToken(value)
                .flatMap(t -> userRepository.findById(t.getUserId()))
                .orElse(null);
        tokenRepository.deleteByToken(value);
        if (user != null) {
            activityLog.record(ActivityLog.Type.SIGN_OUT, user, "Signed out");
        }
    }

    public InstitutionView currentInstitution() {
        return view(institutionRepository.findById(tenant.currentId())
                .orElseThrow(() -> new ResourceNotFoundException("Institution not found")));
    }

    @Transactional
    public InstitutionView updateInstitution(InstitutionUpdate update) {
        Institution institution = institutionRepository.findById(tenant.currentId())
                .orElseThrow(() -> new ResourceNotFoundException("Institution not found"));
        if (update.name() != null && !update.name().isBlank()) {
            institution.setName(update.name().trim());
        }
        if (update.contactEmail() != null) {
            institution.setContactEmail(blankToNull(update.contactEmail()));
        }
        return view(institutionRepository.save(institution));
    }

    public List<PlanView> plans() {
        return planRepository.findAllByActiveTrueOrderBySortOrderAsc().stream().map(this::view).toList();
    }

    public SubscriptionView currentSubscription() {
        Plan plan = quotas.currentPlan();
        return subscriptionRepository.findByInstitutionId(tenant.currentId())
                .map(s -> new SubscriptionView(s.getPlanCode(), plan.getName(), s.getStatus().name(),
                        s.getPeriodStart(), s.getPeriodEnd(), view(plan)))
                .orElseGet(() -> {
                    Instant[] window = quotas.period();
                    return new SubscriptionView(plan.getCode(), plan.getName(), "ACTIVE",
                            window[0], window[1], view(plan));
                });
    }

    /** Upgrade or downgrade. Downgrades that would breach a lower limit are rejected. */
    @Transactional
    public SubscriptionView changePlan(PlanChangeRequest request) {
        Plan target = planRepository.findByCode(request.planCode().trim().toUpperCase(Locale.ROOT))
                .filter(Plan::isActive)
                .orElseThrow(() -> new IllegalArgumentException("Unknown plan code '" + request.planCode() + "'"));
        quotas.checkDowngrade(target);
        Long institutionId = tenant.currentId();
        Subscription subscription = subscriptionRepository.findByInstitutionId(institutionId)
                .orElseGet(() -> Subscription.builder()
                        .institutionId(institutionId)
                        .status(Subscription.Status.ACTIVE)
                        .build());
        Instant now = Instant.now();
        subscription.setPlanCode(target.getCode());
        subscription.setStatus(Subscription.Status.ACTIVE);
        subscription.setPeriodStart(now);
        subscription.setPeriodEnd(now.plus(30, ChronoUnit.DAYS));
        subscription.setUpdatedAt(now);
        if (subscription.getStartedAt() == null) {
            subscription.setStartedAt(now);
        }
        subscriptionRepository.save(subscription);
        return new SubscriptionView(target.getCode(), target.getName(), subscription.getStatus().name(),
                subscription.getPeriodStart(), subscription.getPeriodEnd(), view(target));
    }

    public UsageView usage() {
        return quotas.usage();
    }

    public List<GenerationView> generations() {
        return generationUsageRepository
                .findTop20ByInstitutionIdOrderByCreatedAtDesc(tenant.currentId())
                .stream()
                .map(g -> new GenerationView(g.getId(), g.getScopeKey(), g.getSeats(),
                        g.getPlanCode(), g.getCreatedAt()))
                .toList();
    }

    /**
     * Issues a session token.
     *
     * @param user       the account the token belongs to
     * @param rememberMe keep the session for a month instead of a day
     */
    private AuthToken issueToken(User user, boolean rememberMe) {
        String value = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        Instant expiresAt = rememberMe
                ? now.plus(Math.max(1, rememberTtlDays), ChronoUnit.DAYS)
                : now.plus(Math.max(1, tokenTtlHours), ChronoUnit.HOURS);
        return tokenRepository.save(AuthToken.builder()
                .token(value)
                .userId(user.getId())
                .institutionId(user.getInstitutionId())
                .expiresAt(expiresAt)
                .build());
    }

    private AuthToken validToken(String authorization) {
        return tokenRepository.findByTokenAndExpiresAtAfter(tokenFrom(authorization), Instant.now())
                .orElseThrow(() -> new UnauthorizedException("Session expired or invalid"));
    }

    /** Accepts both "Bearer x" and a bare token, so clients can send either. */
    public static String tokenFrom(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw new UnauthorizedException("A bearer token is required");
        }
        String value = authorization.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            value = value.substring(7).trim();
        }
        if (value.isEmpty()) {
            throw new UnauthorizedException("A bearer token is required");
        }
        return value;
    }

    private Institution institution(User user) {
        if (user.getInstitutionId() == null) {
            return institutionRepository.findBySlug("default")
                    .orElseThrow(() -> new UnauthorizedException("Institution is not configured"));
        }
        return institutionRepository.findById(user.getInstitutionId())
                .orElseThrow(() -> new UnauthorizedException("Institution no longer exists"));
    }

    private AuthSession session(User user, Institution institution, AuthToken token) {
        return new AuthSession(token.getToken(), token.getExpiresAt(),
                new UserView(user.getId(), user.getUsername(), user.getRole().name(),
                        user.effectiveStatus().name(), user.getFullName(), user.getEmail(),
                        user.getLastSeenAt()),
                view(institution));
    }

    private static InstitutionView view(Institution i) {
        return new InstitutionView(i.getId(), i.getName(), i.getSlug(), i.getContactEmail(),
                i.getStatus() == null ? null : i.getStatus().name());
    }

    private PlanView view(Plan p) {
        return new PlanView(p.getCode(), p.getName(), p.getMonthlyPrice(),
                p.getMaxStudents(), p.getMaxRooms(), p.getMaxGenerationsPerMonth(),
                p.getStorageLimitMb(), p.getMaxFileSizeMb(), p.getSeatsPerGeneration(),
                p.getSortOrder());
    }

    /** The workspace's name: what they typed, else their own name, else their login name. */
    private static String institutionName(RegisterRequest request, String username) {
        if (request.institutionName() != null && !request.institutionName().isBlank()) {
            return request.institutionName().trim();
        }
        if (request.fullName() != null && !request.fullName().isBlank()) {
            return request.fullName().trim() + "'s workspace";
        }
        return username;
    }

    /**
     * A short handle for the workspace, derived from the login name and made
     * unique by counting up. Nobody has to think of one.
     */
    private String uniqueSlug(String username) {
        String base = slugify(username);
        String candidate = base;
        int suffix = 1;
        while (institutionRepository.existsBySlug(candidate)) {
            suffix++;
            candidate = base + "-" + suffix;
        }
        return candidate;
    }

    /** Lowercase letters, digits and single dashes; never empty. */
    static String slugify(String raw) {
        StringBuilder out = new StringBuilder();
        boolean lastWasDash = false;
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
                lastWasDash = false;
            } else if (!lastWasDash && out.length() > 0) {
                out.append('-');
                lastWasDash = true;
            }
        }
        String slug = out.toString().replaceAll("(^-+)|(-+$)", "");
        if (slug.isEmpty()) {
            return "workspace";
        }
        return slug.length() > 58 ? slug.substring(0, 58) : slug;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
