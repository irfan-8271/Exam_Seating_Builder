package com.examseating.dto;

import java.time.Instant;
import java.util.List;

/**
 * The views the platform administrator sees: every institution, every account,
 * the usage of the service as a whole and the limits an operator may grant.
 *
 * <p>These are deliberately separate from the customer-facing views in
 * {@link AccountDtos}: an institution may see its own subscription, but only
 * the operator may see (and change) everyone else's.</p>
 *
 * <p>No view here carries a password, a hash or anything derived from one.
 * The operator manages accounts; the operator never reads credentials.</p>
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    /**
     * One institution as the operator sees it.
     *
     * @param id               the institution's id
     * @param name             its display name
     * @param slug             its short handle
     * @param contactEmail     its contact address
     * @param status           ACTIVE or SUSPENDED
     * @param createdAt        when it registered
     * @param planCode         the plan it is on
     * @param planName         the plan's display name
     * @param subscriptionStatus the state of its subscription
     * @param usersCount       how many users it has
     * @param studentsUsed     how many students it holds
     * @param studentsLimit    how many students it may hold
     * @param roomsUsed        how many halls it holds
     * @param roomsLimit       how many halls it may hold
     * @param generationsUsed  how many plans it generated this period
     * @param generationsLimit how many plans it may generate this period
     * @param storageUsedBytes how much it stores
     * @param storageLimitBytes how much it may store
     * @param seatsPerGeneration how many seats one of its generations may seat
     * @param overrides        the limits the operator set by hand
     */
    public record AdminInstitutionView(
            Long id,
            String name,
            String slug,
            String contactEmail,
            String status,
            Instant createdAt,
            String planCode,
            String planName,
            String subscriptionStatus,
            long usersCount,
            long studentsUsed,
            int studentsLimit,
            long roomsUsed,
            int roomsLimit,
            long generationsUsed,
            int generationsLimit,
            long storageUsedBytes,
            long storageLimitBytes,
            int seatsPerGeneration,
            LimitOverrides overrides) {
    }

    /**
     * The limits an operator set by hand. A null field means "whatever the
     * level above says".
     */
    public record LimitOverrides(
            Integer maxStudents,
            Integer maxRooms,
            Integer maxGenerationsPerMonth,
            Long storageLimitMb,
            Long maxFileSizeMb,
            Integer seatsPerGeneration) {

        /** Whether no override is set at all. */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isEmpty() {
            return maxStudents == null && maxRooms == null && maxGenerationsPerMonth == null
                    && storageLimitMb == null && maxFileSizeMb == null && seatsPerGeneration == null;
        }
    }

    /**
     * The body of a plan or limit change.
     *
     * @param planCode the plan to move the institution to; may be null to keep
     *                 the current plan and only change the limits
     * @param limits   the limits to set; a null field clears that override
     */
    public record PlanChangeRequest(
            String planCode,
            LimitOverrides limits) {
    }

    /**
     * One account as the operator sees it — everything about it except its
     * password, which is never available in any form.
     *
     * @param id            the account's id
     * @param username      their login name
     * @param role          ADMIN, EXAM_CELL, INVIGILATOR or PLATFORM_ADMIN
     * @param status        ACTIVE or SUSPENDED
     * @param fullName      their name
     * @param email         their address
     * @param institutionId the institution they belong to
     * @param institutionName that institution's name
     * @param createdAt     when the account was created
     * @param lastSeenAt    when the account last signed in
     * @param passwordChangedAt when the password was last set or reset
     * @param planCode      the plan their institution is on
     * @param planName      that plan's display name
     * @param studentsUsed  how many students their institution holds
     * @param studentsLimit how many students they may add
     * @param roomsUsed     how many halls their institution holds
     * @param roomsLimit    how many halls they may add
     * @param generationsUsed how many plans their institution generated
     * @param generationsLimit how many plans they may generate
     * @param storageUsedBytes how much their institution stores
     * @param storageLimitBytes how much they may store
     * @param seatsPerGeneration how many seats one of their plans may seat
     * @param institutionOverrides the limits set on the institution
     * @param userOverrides      the limits set on this account alone
     */
    public record AdminUserView(
            Long id,
            String username,
            String role,
            String status,
            String fullName,
            String email,
            Long institutionId,
            String institutionName,
            Instant createdAt,
            Instant lastSeenAt,
            Instant passwordChangedAt,
            String planCode,
            String planName,
            long studentsUsed,
            int studentsLimit,
            long roomsUsed,
            int roomsLimit,
            long generationsUsed,
            int generationsLimit,
            long storageUsedBytes,
            long storageLimitBytes,
            int seatsPerGeneration,
            LimitOverrides institutionOverrides,
            LimitOverrides userOverrides) {
    }

    /**
     * The body of an account change made by the operator.
     *
     * @param role          the new role, or null to keep it
     * @param status        the new status, or null to keep it
     * @param institutionId the new institution, or null to keep it
     * @param fullName      the new name, or null to keep it
     * @param email         the new address, or null to keep it
     * @param password      a new password, or null to keep it
     */
    public record UserChangeRequest(
            String role,
            String status,
            Long institutionId,
            String fullName,
            String email,
            String password) {
    }

    /**
     * The body of a per-account limit change.
     *
     * @param limits the limits to set for this account alone; a null field
     *               falls back to the institution's value
     */
    public record UserLimitsRequest(LimitOverrides limits) {
    }

    /**
     * The service as a whole.
     *
     * @param institutions  how many institutions there are
     * @param users        how many users there are
     * @param students     how many students are held in total
     * @param halls        how many halls are held in total
     * @param generations  how many plans were generated this period
     * @param seats        how many seats those plans seated
     * @param storageUsedBytes how much is stored in total
     * @param byPlan       how the institutions are spread over the plans
     */
    public record PlatformUsageView(
            long institutions,
            long users,
            long students,
            long halls,
            long generations,
            long seats,
            long storageUsedBytes,
            List<PlanCount> byPlan) {
    }

    /**
     * How many institutions are on one plan.
     *
     * @param planCode the plan
     * @param planName its display name
     * @param count how many institutions are on it
     */
    public record PlanCount(String planCode, String planName, long count) {
    }

    /**
     * One generation across the whole service.
     *
     * @param id             the generation's id
     * @param institutionId  the institution that made it
     * @param institutionName that institution's name
     * @param subjectCode    the subject it was for
     * @param seats          how many students it seated
     * @param planCode       the plan it was on
     * @param createdAt      when it was made
     */
    public record PlatformGenerationView(
            Long id,
            Long institutionId,
            String institutionName,
            String subjectCode,
            int seats,
            String planCode,
            Instant createdAt) {
    }

    /**
     * One thing that happened, for the operator's activity feed.
     *
     * @param id             the entry's id
     * @param type           REGISTRATION, SIGN_IN, GENERATION, …
     * @param username       the account it happened to
     * @param userId         that account's id
     * @param institutionName the institution it happened in
     * @param detail         a short human-readable description
     * @param occurredAt     when it happened
     */
    public record AdminActivityView(
            Long id,
            String type,
            String username,
            Long userId,
            String institutionName,
            String detail,
            Instant occurredAt) {
    }

    /**
     * Whether the operator view is available at all.
     *
     * @param enabled      true when a platform administrator exists
     * @param username     its login name, or null
     * @param institutions how many institutions it would see
     */
    public record AdminStatusView(boolean enabled, String username, long institutions) {
    }
}
