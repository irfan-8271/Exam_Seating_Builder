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
 * @param page              the page of {@code allocations} this response holds (null when not paged)
 * @param size              the page size (null when not paged)
 * @param totalElements     how many seats the plan holds in total (null when not paged)
 * @param totalPages        how many pages the plan has (null when not paged)
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
        Integer totalPages) {

    /** The unpaged form — used by the arrange endpoint and by the service. */
    public SeatingResponse(int seated, int unplaced, int totalSlots, int backtracks,
                           boolean solved, List<AllocationDto> allocations) {
        this(seated, unplaced, totalSlots, backtracks, solved, allocations, null, null, null, null);
    }

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
            int slot,
            String identity) {

        /** The unpaged form — keeps the response shape stable for older clients. */
        public AllocationDto(Long allocationId, String rollNo, String name, String department,
                             String yearSession, String subjectCode, String hallName,
                             int benchRow, int benchCol, int slot) {
            this(allocationId, rollNo, name, department, yearSession, subjectCode, hallName,
                    benchRow, benchCol, slot, null);
        }

        /**
         * Maps a persisted allocation to the DTO.
         * <b>Spring/JPA note:</b> this must run while the transaction is open (lazy
         * associations {@code a.getBench().getRoom()} would otherwise blow up with
         * LazyInitializationException) — the service does the mapping inside its
         * {@code @Transactional} method.
         *
         * <p>{@code rollNo} is whatever the institution calls the student's
         * identifier — it may be null for institutions that identify students
         * by a combination of attributes. {@code identity} always carries the
         * canonical composite identity.</p>
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
                    a.getSlotNumber(),
                    a.getStudent().getIdentityKey());
        }
    }

    public static SeatingResponse of(int seated, int unplaced, int totalSlots, int backtracks,
                                     boolean solved, List<AllocationDto> allocations) {
        return new SeatingResponse(seated, unplaced, totalSlots, backtracks, solved, List.copyOf(allocations));
    }

    /** The paged form — adds the pagination metadata of the seat collection. */
    public static SeatingResponse paged(int seated, int unplaced, int totalSlots, int backtracks,
                                        boolean solved, List<AllocationDto> allocations,
                                        int page, int size, long totalElements, int totalPages) {
        return new SeatingResponse(seated, unplaced, totalSlots, backtracks, solved,
                List.copyOf(allocations), page, size, totalElements, totalPages);
    }
}
