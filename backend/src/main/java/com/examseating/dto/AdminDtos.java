package com.examseating.dto;

import java.time.Instant;
import java.util.List;

/**
 * The views the platform administrator sees: every institution, every user,
 * the usage of the service as a whole and the limits an operator may grant.
 *
 * <p>These are deliberately separate from the customer-facing views in
 * {@link AccountDtos}: an institution may see its own subscription, but only
 * the operator may see (and change) everyone else's.</p>
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
     * plan says".
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
     * One user as the operator sees it.
     *
     * @param id            the user's id
     * @param username      their login name
     * @param role          ADMIN, EXAM_CELL, INVIGILATOR or PLATFORM_ADMIN
     * @param fullName      their name
     * @param email         their address
     * @param institutionId the institution they belong to
     * @param institutionName that institution's name
     * @param createdAt     when the account was created
     * @param lastSeenAt    when the account last signed in
     */
    public record AdminUserView(
            Long id,
            String username,
            String role,
            String fullName,
            String email,
            Long institutionId,
            String institutionName,
            Instant createdAt,
            Instant lastSeenAt) {
    }

    /**
     * The body of a user change: the role and/or the institution.
     *
     * @param role          the new role, or null to keep it
     * @param institutionId the new institution, or null to keep it
     * @param password      a new password, or null to keep it
     */
    public record UserChangeRequest(
            String role,
            Long institutionId,
            String password) {
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
     * Whether the operator view is available at all.
     *
     * @param enabled      true when a platform administrator exists
     * @param username     its login name, or null
     * @param institutions how many institutions it would see
     */
    public record AdminStatusView(boolean enabled, String username, long institutions) {
    }
}
