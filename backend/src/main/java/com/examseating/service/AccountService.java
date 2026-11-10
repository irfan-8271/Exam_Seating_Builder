package com.examseating.service;

import com.examseating.dto.AccountDtos.AuthSession;
import com.examseating.dto.AccountDtos.GenerationView;
import com.examseating.dto.AccountDtos.InstitutionUpdate;
import com.examseating.dto.AccountDtos.InstitutionView;
import com.examseating.dto.AccountDtos.LoginRequest;
import com.examseating.dto.AccountDtos.PlanChangeRequest;
import com.examseating.dto.AccountDtos.PlanView;
import com.examseating.dto.AccountDtos.RegisterRequest;
import com.examseating.dto.AccountDtos.SubscriptionView;
import com.examseating.dto.AccountDtos.UsageView;
import com.examseating.dto.AccountDtos.UserView;
import com.examseating.entity.AuthToken;
import com.examseating.entity.GenerationUsage;
import com.examseating.entity.Institution;
import com.examseating.entity.Plan;
import com.examseating.entity.Subscription;
import com.examseating.entity.User;
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

/** Institutional accounts, sessions, plan catalogue, subscriptions and usage. */
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

    @Value("${app.auth.token-ttl-hours:24}")
    private long tokenTtlHours;

    @Transactional
    public AuthSession register(RegisterRequest request) {
        String slug = normalizeSlug(request.slug());
        if (institutionRepository.existsBySlug(slug)) {
            throw new IllegalArgumentException("An institution with slug '" + slug + "' already exists");
        }
        String username = request.adminUsername().trim();
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("Username '" + username + "' is already taken");
        }
        Institution institution = institutionRepository.save(Institution.builder()
                .name(request.institutionName().trim())
                .slug(slug)
                .contactEmail(blankToNull(request.adminEmail()))
                .status(Institution.Status.ACTIVE)
                .build());
        User admin = userRepository.save(User.builder()
                .username(username)
                .password(passwordEncoder.encode(request.adminPassword()))
                .role(User.Role.ADMIN)
                .fullName("Institution administrator")
                .email(blankToNull(request.adminEmail()))
                .institutionId(institution.getId())
                .build());
        subscriptionRepository.save(Subscription.builder()
                .institutionId(institution.getId())
                .planCode(FREE_PLAN)
                .status(Subscription.Status.ACTIVE)
                .build());
        return session(admin, institution, issueToken(admin));
    }

    @Transactional
    public AuthSession login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username().trim())
                .filter(u -> passwordEncoder.matches(request.password(), u.getPassword()))
                .orElseThrow(() -> new UnauthorizedException("Invalid username or password"));
        Institution institution = institution(user);
        if (institution.getStatus() == Institution.Status.SUSPENDED) {
            throw new UnauthorizedException("This institution has been suspended");
        }
        // Remember when the account was last seen — the operator's view.
        user.setLastSeenAt(Instant.now());
        userRepository.save(user);
        return session(user, institution, issueToken(user));
    }

    public AuthSession me(String authorization) {
        AuthToken token = validToken(authorization);
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new UnauthorizedException("Session is no longer valid"));
        return session(user, institution(user), token);
    }

    @Transactional
    public void logout(String authorization) {
        tokenRepository.deleteByToken(tokenFrom(authorization));
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
                .map(g -> new GenerationView(g.getId(), g.getSubjectCode(), g.getSeats(),
                        g.getPlanCode(), g.getCreatedAt()))
                .toList();
    }

    private AuthToken issueToken(User user) {
        String value = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        return tokenRepository.save(AuthToken.builder()
                .token(value)
                .userId(user.getId())
                .institutionId(user.getInstitutionId())
                .expiresAt(now.plus(Math.max(1, tokenTtlHours), ChronoUnit.HOURS))
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
                        user.getFullName(), user.getEmail()),
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

    private static String normalizeSlug(String raw) {
        String slug = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!slug.matches("[a-z0-9][a-z0-9-]{1,58}[a-z0-9]")) {
            throw new IllegalArgumentException("Slug must be 3-60 lowercase letters, digits or hyphens, "
                    + "starting and ending with a letter or digit");
        }
        return slug;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
