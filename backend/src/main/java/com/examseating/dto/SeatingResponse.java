package com.examseating.dto;

import com.examseating.engine.SeatingArrangementEngine.SeatingAdvisory;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.Student;
import com.examseating.entity.StudentAttributes;
import com.examseating.entity.StudentIdentityScheme;

import java.util.List;
import java.util.Map;

/**
 * JSON response describing a completed or persisted seating plan, including
 * optional empty-seat / cohort-distribution advisory metadata.
 */
public record SeatingResponse(
        int seated,
        int unplaced,
        int totalSlots,
        int backtracks,
        boolean solved,
        List<AllocationDto> allocations,
        Integer page,
        Integer size,
        Long totalElements,
        Integer totalPages,
        SeatingAdvisory advisory) {

    public SeatingResponse(int seated, int unplaced, int totalSlots, int backtracks,
                           boolean solved, List<AllocationDto> allocations) {
        this(seated, unplaced, totalSlots, backtracks, solved, allocations, null, null, null, null, null);
    }

    public SeatingResponse(int seated, int unplaced, int totalSlots, int backtracks,
                           boolean solved, List<AllocationDto> allocations,
                           Integer page, Integer size, Long totalElements, Integer totalPages) {
        this(seated, unplaced, totalSlots, backtracks, solved, allocations,
                page, size, totalElements, totalPages, null);
    }

    /** One allocated seat in a pure dynamic format. */
    public record AllocationDto(
            Long allocationId,
            Long studentId,
            String identity,
            String name,
            String cohort,
            Map<String, String> attributes,
            String scopeKey,
            String hallName,
            int benchRow,
            int benchCol,
            int slot) {

        public static AllocationDto from(SeatingAllocation a) {
            return from(a, null, List.of());
        }

        public static AllocationDto from(SeatingAllocation a,
                                         StudentIdentityScheme scheme,
                                         List<String> groupKeys) {
            Student s = a.getStudent();
            String displayId = StudentAttributes.displayLabel(s, scheme);
            String cohortLabel = StudentAttributes.cohortLabel(s, groupKeys);
            return new AllocationDto(
                    a.getId(),
                    s.getId(),
                    displayId.isEmpty() ? s.getIdentityKey() : displayId,
                    s.getName(),
                    cohortLabel.isEmpty() ? null : cohortLabel,
                    StudentAttributes.allPopulatedAttributes(s),
                    a.getScopeKey(),
                    a.getBench().getRoom().getHallName(),
                    a.getBench().getRowIndex(),
                    a.getBench().getColIndex(),
                    a.getSlotNumber());
        }
    }

    public static SeatingResponse of(int seated, int unplaced, int totalSlots, int backtracks,
                                     boolean solved, List<AllocationDto> allocations) {
        return new SeatingResponse(seated, unplaced, totalSlots, backtracks, solved, List.copyOf(allocations));
    }

    public static SeatingResponse of(int seated, int unplaced, int totalSlots, int backtracks,
                                     boolean solved, List<AllocationDto> allocations,
                                     SeatingAdvisory advisory) {
        return new SeatingResponse(seated, unplaced, totalSlots, backtracks, solved,
                List.copyOf(allocations), null, null, null, null, advisory);
    }

    public static SeatingResponse paged(int seated, int unplaced, int totalSlots, int backtracks,
                                        boolean solved, List<AllocationDto> allocations,
                                        int page, int size, long totalElements, int totalPages) {
        return new SeatingResponse(seated, unplaced, totalSlots, backtracks, solved,
                List.copyOf(allocations), page, size, totalElements, totalPages, null);
    }

    public static SeatingResponse paged(int seated, int unplaced, int totalSlots, int backtracks,
                                        boolean solved, List<AllocationDto> allocations,
                                        int page, int size, long totalElements, int totalPages,
                                        SeatingAdvisory advisory) {
        return new SeatingResponse(seated, unplaced, totalSlots, backtracks, solved,
                List.copyOf(allocations), page, size, totalElements, totalPages, advisory);
    }
}
