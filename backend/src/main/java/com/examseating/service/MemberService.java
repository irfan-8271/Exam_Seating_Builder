package com.examseating.service;

import com.examseating.dto.AccountDtos.CreateMemberRequest;
import com.examseating.dto.AccountDtos.MemberView;
import com.examseating.dto.AccountDtos.UpdateMemberRequest;
import com.examseating.entity.ActivityLog;
import com.examseating.entity.User;
import com.examseating.exception.ForbiddenException;
import com.examseating.exception.ResourceNotFoundException;
import com.examseating.repository.UserRepository;
import com.examseating.tenancy.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * The members of an institution — the people who share its roster, its halls
 * and its seating plans.
 *
 * <p>An institution's administrator adds people, gives them a role and takes
 * them away again. Nobody outside the institution can do this, and nobody can
 * make a member a platform administrator.</p>
 */
@Service
@RequiredArgsConstructor
public class MemberService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TenantSupport tenant;
    private final ActivityLogService activityLog;

    /** The members of the current institution. */
    public List<MemberView> members() {
        return userRepository.findByInstitutionIdOrderByCreatedAtDesc(tenant.currentId()).stream()
                .map(MemberService::view)
                .toList();
    }

    /**
     * Adds a member to the current institution.
     *
     * @param request the login name, the password and the role to give them
     */
    @Transactional
    public MemberView create(CreateMemberRequest request) {
        String username = request.username().trim();
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("That username is already taken. Try another one.");
        }
        User member = userRepository.save(User.builder()
                .username(username)
                .password(passwordEncoder.encode(request.password()))
                .role(role(request.role()))
                .status(User.Status.ACTIVE)
                .fullName(blankToNull(request.fullName()))
                .email(blankToNull(request.email()))
                .passwordChangedAt(Instant.now())
                .institutionId(tenant.currentId())
                .build());
        activityLog.record(ActivityLog.Type.USER_CHANGE, member,
                "Added to the workspace as " + member.getRole().name());
        return view(member);
    }

    /**
     * Changes a member: their role, their status, their name, their address
     * or their password.
     */
    @Transactional
    public MemberView update(Long id, UpdateMemberRequest request) {
        User member = require(id);
        if (request.role() != null && !request.role().isBlank()) {
            member.setRole(role(request.role()));
        }
        if (request.status() != null && !request.status().isBlank()) {
            User.Status status;
            try {
                status = User.Status.valueOf(request.status().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Unknown status '" + request.status()
                        + "' (expected ACTIVE or SUSPENDED)");
            }
            member.setStatus(status);
        }
        if (request.fullName() != null) {
            member.setFullName(blankToNull(request.fullName()));
        }
        if (request.email() != null) {
            member.setEmail(blankToNull(request.email()));
        }
        if (request.password() != null && !request.password().isBlank()) {
            if (request.password().length() < 8) {
                throw new IllegalArgumentException("A password must have at least 8 characters");
            }
            member.setPassword(passwordEncoder.encode(request.password()));
            member.setPasswordChangedAt(Instant.now());
        }
        return view(userRepository.save(member));
    }

    /**
     * Removes a member.
     *
     * <p>The last administrator of an institution cannot be removed — the
     * workspace would be left with nobody who could run it.</p>
     */
    @Transactional
    public void remove(Long id) {
        User member = require(id);
        Long institutionId = tenant.currentId();
        if (member.getRole() == User.Role.ADMIN
                && userRepository.findByInstitutionId(institutionId).stream()
                        .filter(u -> u.getRole() == User.Role.ADMIN)
                        .count() <= 1) {
            throw new IllegalArgumentException(
                    "This is the last administrator of the workspace — give the role to someone else first");
        }
        userRepository.delete(member);
        activityLog.record(ActivityLog.Type.USER_CHANGE, member, "Removed from the workspace");
    }

    /** One member, as the institution sees it. */
    public MemberView member(Long id) {
        return view(require(id));
    }

    private User require(Long id) {
        User member = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account " + id + " not found"));
        if (!member.getInstitutionId().equals(tenant.currentId())) {
            // Another institution's account is reported as missing rather than
            // forbidden, so its existence is not leaked.
            throw new ResourceNotFoundException("Account " + id + " not found");
        }
        return member;
    }

    /** A role a member may hold. A platform administrator is never one of them. */
    private static User.Role role(String raw) {
        User.Role role;
        try {
            role = User.Role.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown role '" + raw
                    + "' (expected ADMIN, EXAM_CELL or INVIGILATOR)");
        }
        if (role == User.Role.PLATFORM_ADMIN) {
            throw new ForbiddenException("Only the platform administrator can hold that role");
        }
        return role;
    }

    private static MemberView view(User user) {
        return new MemberView(user.getId(), user.getUsername(), user.getRole().name(),
                user.effectiveStatus().name(), user.getFullName(), user.getEmail(),
                user.getCreatedAt(), user.getLastSeenAt());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
