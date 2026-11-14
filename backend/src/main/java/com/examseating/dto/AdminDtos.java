package com.examseating.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
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
            LimitOverrides overrides,
            Instant overrideExpiresAt,
            String overrideReason,
            boolean overrideActive,
            boolean overrideExpired) {
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
     * @param planCode          the plan to move the institution to; may be null to keep
     *                          the current plan and only change the limits
     * @param limits            the limits to set; a null field clears that override
     * @param overrideExpiresAt optional promotional expiration timestamp (null = permanent)
     * @param overrideReason    optional operator note for the custom/promotional override
     */
    public record PlanChangeRequest(
            String planCode,
            LimitOverrides limits,
            Instant overrideExpiresAt,
            String overrideReason) {
    }

    /**
     * One subscription tier as managed by the platform administrator,
     * including active/inactive catalog state and live subscriber count.
     */
    public record AdminPlanView(
            Long id,
            String code,
            String name,
            BigDecimal monthlyPrice,
            String currencyCode,
            String currencySymbol,
            int maxStudents,
            int maxRooms,
            int maxGenerationsPerMonth,
            long storageLimitMb,
            long maxFileSizeMb,
            int seatsPerGeneration,
            int sortOrder,
            boolean active,
            long subscriberCount) {
    }

    /**
     * Request payload to create or update a global subscription tier.
     */
    public record AdminPlanUpsertRequest(
            @Size(max = 30, message = "Plan code must be at most 30 characters")
            String code,
            @NotBlank(message = "Plan display name is required")
            @Size(max = 80, message = "Plan name must be at most 80 characters")
            String name,
            @NotNull(message = "Monthly price is required")
            @DecimalMin(value = "0.00", message = "Monthly price cannot be negative")
            BigDecimal monthlyPrice,
            @Min(value = 1, message = "Max students must be at least 1")
            int maxStudents,
            @Min(value = 1, message = "Max exam halls must be at least 1")
            int maxRooms,
            @Min(value = 1, message = "Monthly generations must be at least 1")
            int maxGenerationsPerMonth,
            @Min(value = 1, message = "Storage limit (MB) must be at least 1")
            long storageLimitMb,
            @Min(value = 1, message = "Max upload file size (MB) must be at least 1")
            long maxFileSizeMb,
            @Min(value = 1, message = "Seats per generation must be at least 1")
            int seatsPerGeneration,
            Integer sortOrder,
            Boolean active) {
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
            long pendingRequests,
            long approvedRequests,
            long rejectedRequests,
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
            String scopeKey,
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
     * Public readiness probe indicating whether platform administrator login is
     * configured. Deliberately omits administrator usernames and tenant counts
     * so credentials or operational metrics are never leaked to anonymous callers.
     *
     * @param enabled true when at least one active platform administrator exists
     */
    public record AdminStatusView(boolean enabled) {
    }

    // ------------------------------------------------------------------
    //  Storage management — reclaiming database space
    // ------------------------------------------------------------------

    /**
     * One category of bytes held in the database, with what could be freed.
     *
     * @param category          a stable machine-readable key
     * @param label             a human-readable name for the console
     * @param totalBytes        every byte currently stored in this category
     * @param reclaimableBytes  the bytes a cleanup would remove right now
     * @param itemCount         how many rows make up the category
     * @param reclaimableCount  how many of those rows a cleanup would remove
     * @param description       what the operator may or may not delete here
     */
    public record StorageCategoryView(
            String category,
            String label,
            long totalBytes,
            long reclaimableBytes,
            long itemCount,
            long reclaimableCount,
            String description) {
    }

    /**
     * The whole storage picture: what the database holds, what could be
     * freed, and the caveats the operator needs before acting.
     *
     * @param categories        one row per kind of stored content
     * @param totalBytes        everything held across all categories
     * @param reclaimableBytes  everything a cleanup could remove right now
     * @param protectedBytes    bytes that cannot be deleted (pending evidence, live QR)
     * @param notes             operational warnings, e.g. about how space is reclaimed
     */
    public record StorageUsageView(
            List<StorageCategoryView> categories,
            long totalBytes,
            long reclaimableBytes,
            long protectedBytes,
            List<String> notes) {
    }

    /**
     * What a cleanup would remove — computed without changing anything.
     *
     * @param scope             which cleanup was inspected
     * @param itemCount         how many rows would go
     * @param bytes             how many bytes would be freed
     * @param institutions       how many institutions are affected
     * @param wouldDeleteSample up to five examples of what would be removed
     */
    public record StorageCleanupPreview(
            String scope,
            long itemCount,
            long bytes,
            long institutions,
            List<String> wouldDeleteSample) {
    }

    /**
     * The result of a cleanup that actually ran.
     *
     * @param scope        which cleanup ran
     * @param deletedCount how many rows were removed
     * @param freedBytes   how many bytes of content were removed
     * @param message      a short human-readable summary
     */
    public record StorageCleanupResult(
            String scope,
            long deletedCount,
            long freedBytes,
            String message) {
    }

    /**
     * A request to remove stored files. Nothing is deleted unless
     * {@code confirm} is true, so a mistake cannot destroy data.
     *
     * @param olderThanDays only files uploaded more than this many days ago; 0 means all
     * @param institutionId restrict to one institution; null means every institution
     * @param confirm       must be true for anything to be removed
     */
    public record StoredFileCleanupRequest(
            Integer olderThanDays,
            Long institutionId,
            Boolean confirm) {
    }

    /**
     * A request to strip the screenshot from resolved upgrade requests.
     *
     * @param olderThanDays only requests created more than this many days ago; 0 means all
     * @param confirm       must be true for anything to be removed
     */
    public record ScreenshotCleanupRequest(
            Integer olderThanDays,
            Boolean confirm) {
    }
}
