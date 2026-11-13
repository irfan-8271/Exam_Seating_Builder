package com.examseating.entity;

import com.examseating.tenancy.TenantScoped;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Request-based subscription upgrade or custom limit increase submitted by an
 * institution administrator and verified by a platform administrator.
 *
 * <p>Submitting a {@code SubscriptionRequest} <b>never</b> activates a plan or
 * raises limits automatically. Only explicit approval by a {@code PLATFORM_ADMIN}
 * transitions the request from {@link Status#PENDING} to {@link Status#APPROVED}
 * and applies the corresponding entitlements to {@link Subscription}.</p>
 */
@Entity
@Table(
    name = "subscription_requests",
    indexes = {
        @Index(name = "idx_sub_req_institution", columnList = "institution_id"),
        @Index(name = "idx_sub_req_status", columnList = "status"),
        @Index(name = "idx_sub_req_tx_ref", columnList = "transaction_reference")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SubscriptionRequest implements TenantScoped {

    public enum RequestType {
        PLAN_UPGRADE,
        LIMIT_INCREASE,
        PLAN_AND_LIMITS
    }

    public enum PaymentMethod {
        MANUAL_UPI,
        BANK_TRANSFER,
        OTHER_MANUAL,
        GATEWAY
    }

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "institution_id", nullable = false)
    private Long institutionId;

    @Column(name = "institution_name", nullable = false, length = 120)
    private String institutionName;

    @Column(name = "requested_by_user_id")
    private Long requestedByUserId;

    @Column(name = "requested_by_username", length = 60)
    private String requestedByUsername;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 24)
    private RequestType requestType;

    @Column(name = "current_plan_code", nullable = false, length = 30)
    private String currentPlanCode;

    @Column(name = "requested_plan_code", length = 30)
    private String requestedPlanCode;

    // Optional requested limit increases (applied as Subscription overrides on approval)
    @Column(name = "requested_max_students")
    private Integer requestedMaxStudents;

    @Column(name = "requested_max_rooms")
    private Integer requestedMaxRooms;

    @Column(name = "requested_max_generations")
    private Integer requestedMaxGenerations;

    @Column(name = "requested_seats_per_generation")
    private Integer requestedSeatsPerGeneration;

    @Column(name = "requested_storage_limit_mb")
    private Long requestedStorageLimitMb;

    @Column(name = "client_notes", length = 1000)
    private String clientNotes;

    // Payment verification fields (supports manual UTR/screenshot today + gateway hooks tomorrow)
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 24)
    private PaymentMethod paymentMethod;

    @Column(name = "transaction_reference", nullable = false, length = 120)
    private String transactionReference;

    @Column(name = "payment_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal paymentAmount;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "screenshot_bytes", columnDefinition = "LONGBLOB")
    private byte[] screenshotBytes;

    @Column(name = "screenshot_filename", length = 255)
    private String screenshotFilename;

    @Column(name = "screenshot_content_type", length = 80)
    private String screenshotContentType;

    /** Reserved for future automated payment gateway integration (e.g. Razorpay/Stripe order/payment id). */
    @Column(name = "gateway_provider", length = 40)
    private String gatewayProvider;

    @Column(name = "gateway_order_id", length = 120)
    private String gatewayOrderId;

    // Administrative review state
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "reviewed_by_user_id")
    private Long reviewedByUserId;

    @Column(name = "reviewed_by_username", length = 60)
    private String reviewedByUsername;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Column(name = "admin_notes", length = 1000)
    private String adminNotes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (status == null) {
            status = Status.PENDING;
        }
        if (paymentMethod == null) {
            paymentMethod = PaymentMethod.MANUAL_UPI;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean hasScreenshot() {
        return screenshotBytes != null && screenshotBytes.length > 0;
    }

    public boolean hasRequestedLimits() {
        return requestedMaxStudents != null
                || requestedMaxRooms != null
                || requestedMaxGenerations != null
                || requestedSeatsPerGeneration != null
                || requestedStorageLimitMb != null;
    }
}
