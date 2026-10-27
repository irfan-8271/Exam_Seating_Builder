package com.examseating.dto;

import com.examseating.entity.SeatingAllocation;

import java.util.List;

/**
 * JSON response describing a completed seating round.
 *
 * @param seated            number of students actually seated
 * @param unplaced          students that could not be seated (should be 0)
 * @param totalSlots        total seats across all halls
 * @param backtracks        how many times the engine had to backtrack (diagnostics)
 * @param solved            true when the search completed
 * @param allocations       one entry per occupied seat, ordered by hall/bench/slot
 */
public record SeatingResponse(
        int seated,
        int unplaced,
        int totalSlots,
        int backtracks,
        boolean solved,
        List<AllocationDto> allocations) {

    /** One seat. */
    public record AllocationDto(
            Long allocationId,
            String rollNo,
            String name,
            String department,
            String yearSession,
            String subjectCode,
            String hallName,
            int benchRow,
            int benchCol,
            int slot) {

        /**
         * Maps a persisted allocation to the DTO.
         * <b>Spring/JPA note:</b> this must run while the transaction is open (lazy
         * associations {@code a.getBench().getRoom()} would otherwise blow up with
         * LazyInitializationException) — the service does the mapping inside its
         * {@code @Transactional} method.
         */
        public static AllocationDto from(SeatingAllocation a) {
            return new AllocationDto(
                    a.getId(),
                    a.getStudent().getRollNo(),
                    a.getStudent().getName(),
                    a.getStudent().getDepartment(),
                    a.getStudent().getYearSession(),
                    a.getSubjectCode(),
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
}
