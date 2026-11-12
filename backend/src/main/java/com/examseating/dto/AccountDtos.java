package com.examseating.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Request/response records for the institutional-account, plan and quota API. */
public final class AccountDtos {

    private AccountDtos() {
    }

    /**
     * The registration body — an ordinary person creating an account.
     *
     * <p>There is no shared administrator password and no slug to choose: the
     * account picks its own login name and password, and the institution is
     * created for it and named after it. Nobody has to approve a new
     * account.</p>
     *
     * @param username         the login name, unique across the service
     * @param password         the account's own password, at least 8 characters
     * @param fullName         the person's name
     * @param email            the person's address
     * @param institutionName  the workspace's name; defaults to the person's
     *                         own name when it is left empty
     */
    public record RegisterRequest(
            @NotBlank(message = "Choose a username")
            @Size(min = 3, max = 50, message = "must be 3-50 characters")
            @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                    message = "may only contain letters, digits, dots, dashes and underscores")
            String username,
            @NotBlank(message = "Choose a password")
            @Size(min = 8, max = 100, message = "must be at least 8 characters")
            String password,
            @Size(max = 100, message = "must be at most 100 characters")
            String fullName,
            @Email(message = "must be a valid email address")
            @Size(max = 120, message = "must be at most 120 characters")
            String email,
            @Size(max = 120, message = "must be at most 120 characters")
            String institutionName) {
    }

    /**
     * The sign-in body.
     *
     * @param username    the login name
     * @param password    the password
     * @param rememberMe  keep the session for a month instead of a day
     */
    public record LoginRequest(
            @NotBlank(message = "Enter your username")
            String username,
            @NotBlank(message = "Enter your password")
            String password,
            Boolean rememberMe) {
    }

    /**
     * The body of the administration sign-in. Kept as its own record so the
     * two flows cannot drift apart: the platform administrator's credentials
     * are checked against a different rule, not the same one with a flag.
     */
    public record PlatformLoginRequest(
            @NotBlank(message = "Enter the administrator username")
            String username,
            @NotBlank(message = "Enter the administrator password")
            String password) {
    }

    /** The signed-in person. Carries no password — there is nothing to carry. */
    public record UserView(Long id, String username, String role, String status,
                           String fullName, String email, Instant lastSeenAt) {
    }

    /** One member of an institution, as that institution sees it. */
    public record MemberView(Long id, String username, String role, String status,
                             String fullName, String email,
                             Instant createdAt, Instant lastSeenAt) {
    }

    /**
     * The body of "add a member to my institution".
     *
     * @param username    the login name to create
     * @param password    the password to set for it
     * @param role        the role to give it
     * @param fullName    the person's name
     * @param email       the person's address
     */
    public record CreateMemberRequest(
            @NotBlank(message = "Choose a username")
            @Size(min = 3, max = 50, message = "must be 3-50 characters")
            @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                    message = "may only contain letters, digits, dots, dashes and underscores")
            String username,
            @NotBlank(message = "Choose a password")
            @Size(min = 8, max = 100, message = "must be at least 8 characters")
            String password,
            @NotBlank(message = "Choose a role")
            String role,
            @Size(max = 100, message = "must be at most 100 characters")
            String fullName,
            @Email(message = "must be a valid email address")
            @Size(max = 120, message = "must be at most 120 characters")
            String email) {
    }

    /**
     * The body of a member change.
     *
     * @param role      the new role, or null to keep it
     * @param status    the new status, or null to keep it
     * @param fullName  the new name, or null to keep it
     * @param email     the new address, or null to keep it
     * @param password  a new password, or null to keep it
     */
    public record UpdateMemberRequest(
            String role,
            String status,
            @Size(max = 100, message = "must be at most 100 characters")
            String fullName,
            @Email(message = "must be a valid email address")
            @Size(max = 120, message = "must be at most 120 characters")
            String email,
            @Size(min = 8, max = 100, message = "must be at least 8 characters")
            String password) {
    }

    public record InstitutionView(Long id, String name, String slug, String contactEmail, String status) {
    }

    public record InstitutionUpdate(@Size(max = 120) String name, @Size(max = 120) String contactEmail) {
    }

    public record AuthSession(String token, Instant expiresAt, UserView user, InstitutionView institution) {
    }

    public record PlanView(String code, String name, BigDecimal monthlyPrice,
                           int maxStudents, int maxRooms, int maxGenerationsPerMonth,
                           long storageLimitMb, long maxFileSizeMb, int seatsPerGeneration,
                           int sortOrder) {
    }

    public record SubscriptionView(String planCode, String planName, String status,
                                   Instant periodStart, Instant periodEnd, PlanView plan) {
    }

    public record PlanChangeRequest(@NotBlank String planCode) {
    }

    public record UsageView(String planCode, String planName,
                            long studentsUsed, int studentsLimit,
                            long roomsUsed, int roomsLimit,
                            long generationsUsed, int generationsLimit,
                            long storageUsedBytes, long storageLimitBytes,
                            int seatsPerGeneration,
                            Instant periodStart, Instant periodEnd) {
    }

    public record StoredFileView(Long id, String originalName, String contentType,
                                 long sizeBytes, Instant uploadedAt) {
    }

    public record GenerationView(Long id, String scopeKey, int seats,
                                 String planCode, Instant createdAt) {
    }

    /** The roles a member of an institution may hold. */
    public static final List<String> MEMBER_ROLES = List.of("ADMIN", "EXAM_CELL", "INVIGILATOR");
}
