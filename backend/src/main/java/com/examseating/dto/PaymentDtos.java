package com.examseating.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Request and response DTOs for manual payment verification and request-based
 * subscription / limit upgrades.
 */
public final class PaymentDtos {

    private PaymentDtos() {
    }

    public static final String CONFIRMATION_MESSAGE =
            "Your upgrade request has been submitted successfully. "
            + "Your subscription limits will be updated within 2–3 working days, "
            + "subject to payment verification and administrative approval.";

    public record PaymentConfigView(
            String paymentMode,
            String upiId,
            String payeeName,
            String instructions,
            String additionalInfo,
            boolean requireScreenshot,
            boolean hasQrImage,
            String qrFilename,
            String gatewayProvider,
            Instant updatedAt) {
    }

    public record PaymentConfigUpdateRequest(
            @Size(max = 20) String paymentMode,
            @Size(max = 120) String upiId,
            @Size(max = 120) String payeeName,
            @Size(max = 2000) String instructions,
            @Size(max = 2000) String additionalInfo,
            Boolean requireScreenshot,
            @Size(max = 40) String gatewayProvider) {
    }

    public record CreateSubscriptionRequestBody(
            @Size(max = 30) String requestedPlanCode,
            @Positive Integer requestedMaxStudents,
            @Positive Integer requestedMaxRooms,
            @Positive Integer requestedMaxGenerations,
            @Positive Integer requestedSeatsPerGeneration,
            @Positive Long requestedStorageLimitMb,
            @Size(max = 1000) String clientNotes,
            @Size(max = 24) String paymentMethod,
            @NotBlank(message = "Transaction ID / UTR number is required")
            @Size(max = 120) String transactionReference,
            @NotNull(message = "Payment amount is required")
            @DecimalMin(value = "0.01", message = "Payment amount must be greater than 0")
            BigDecimal paymentAmount,
            @NotNull(message = "Payment date is required")
            LocalDate paymentDate) {
    }

    public record SubscriptionRequestView(
            Long id,
            Long institutionId,
            String institutionName,
            Long requestedByUserId,
            String requestedByUsername,
            String requestType,
            String currentPlanCode,
            String requestedPlanCode,
            String requestedPlanName,
            Integer requestedMaxStudents,
            Integer requestedMaxRooms,
            Integer requestedMaxGenerations,
            Integer requestedSeatsPerGeneration,
            Long requestedStorageLimitMb,
            String clientNotes,
            String paymentMethod,
            String transactionReference,
            BigDecimal paymentAmount,
            LocalDate paymentDate,
            boolean hasScreenshot,
            String screenshotFilename,
            String status,
            String reviewedByUsername,
            Instant reviewedAt,
            String rejectionReason,
            String adminNotes,
            Instant createdAt,
            String confirmationMessage) {
    }

    public record ReviewSubscriptionRequest(
            @Size(max = 1000) String reason,
            @Size(max = 1000) String adminNotes) {
    }
}
