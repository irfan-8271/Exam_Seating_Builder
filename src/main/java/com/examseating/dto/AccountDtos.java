package com.examseating.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/** Request/response records for the institutional-account, plan and quota API. */
public final class AccountDtos {

    private AccountDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 120) String institutionName,
            @NotBlank @Size(max = 60) String slug,
            @NotBlank @Size(min = 3, max = 50) String adminUsername,
            @NotBlank @Size(min = 8, max = 100) String adminPassword,
            @Size(max = 120) String adminEmail) {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record UserView(Long id, String username, String role, String fullName, String email) {
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

    public record GenerationView(Long id, String subjectCode, int seats,
                                 String planCode, Instant createdAt) {
    }
}
