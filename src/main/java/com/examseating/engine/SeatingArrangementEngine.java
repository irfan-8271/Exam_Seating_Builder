package com.examseating.engine;

import com.examseating.entity.Bench;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.Student;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic constraint-satisfaction engine that distributes students
 * across exam halls while enforcing the anti-cheating layout rules.
 *
 * <h2>Inputs</h2>
 * <ul>
 *   <li>Rooms: each hall is an {@code M × N} grid of benches (1-based row/col)
 *       with a {@code SINGLE_SEATER} or {@code DUAL_SEATER} bench type.</li>
 *   <li>Students: department, year/session, roll no, subject code.</li>
 *   <li>{@link SeatingConfig}: horizontal gap {@code dH}, vertical gap
 *       {@code dV}, diagonal gap {@code dD}, conflict scope, backtrack budget.</li>
 * </ul>
 *
 * <h2>Constraints enforced</h2>
 * <ol>
 *   <li><b>Dual-seater rule</b> — the two students sharing a bench must belong
 *       to a different department <i>and</i> a different year/session.</li>
 *   <li><b>Horizontal gap dH</b> — two conflicting students in the same row
 *       must be more than {@code dH} bench columns apart
 *       (i.e. {@code |c1 - c2| > dH}); with {@code dH = 1} adjacent benches in
 *       a row may not hold conflicting students.</li>
 *   <li><b>Vertical gap dV</b> — same column, {@code |r1 - r2| > dV}.</li>
 *   <li><b>Diagonal gap dD</b> — {@code |r1 - r2| > dD} OR {@code |c1 - c2| > dD}
 *       for diagonally placed conflicting students.</li>
 * </ol>
 * A gap value of {@code 0} disables that axis. "Conflicting" is defined by
 * {@link ConflictScope} (default {@code DEPARTMENT_AND_YEAR}, i.e. classmates —
 * same department <i>and</i> same year). The dual-seater bench rule is
 * independent of the scope and always forbids bench-mates from the same
 * department <i>or</i> the same year, exactly as specified.
 *
 * <h2>Algorithm</h2>
 * <p>The engine first runs fast, provable <b>feasibility pre-checks</b> — total
 * capacity, per-group density bounds, per-year/per-department balance of
 * dual-seater benches, and per-hall "must sit here" quotas — and fails
 * immediately with a diagnostic when the input is provably impossible. It then
 * tries two deterministic solver paths:</p>
 * <ol>
 *   <li><b>Layer-decomposed fast path</b> (dual-seater halls, exactly two year
 *       values, classmate conflict scope). Every bench pairs one student of
 *       each year, so the whole instance is modelled as one flat list of
 *       seat-cells (single benches take anyone; dual benches take one cell per
 *       year, coupled by the bench-mate rule) and solved with a global
 *       forward-checking search. The hall compositions are decided coherently
 *       across all halls in the same search.</li>
 *   <li><b>General seat-level search</b> — a classic CSP solver over the raw
 *       seat grid, run with several deterministic heuristic profiles:
 *       <ul>
 *         <li><b>MRV seat selection</b> — seat the most-constrained empty seat
 *             (fewest legal students) first; dual benches are assigned
 *             atomically (slot 2 right after its slot 1);</li>
 *         <li><b>Forward checking</b> — per-seat candidate counts are
 *             maintained exactly with incremental BitSets; a zero-candidate
 *             seat triggers conflict-directed backjumping instead of slow
 *             chronological unwinding;</li>
 *         <li><b>Heuristic value choice</b> — prefer groups with an unmet
 *             "must sit here" quota, then the least-represented group in the
 *             hall, then the largest unplaced group;</li>
 *         <li><b>Multi-pass</b> — four deterministic permutations of those
 *             priorities (plus a reversed tie-break) are tried; a stall
 *             detector aborts a stuck pass early.</li>
 *       </ul></li>
 * </ol>
 *
 * <p>Both paths are complete within their domains; the step budgets
 * ({@link SeatingConfig#maxBacktrackSteps()}, and the internal layer-search
 * budget) bound the worst case.</p>
 *
 * <h2>Determinism &amp; side effects</h2>
 * <p>Identical inputs always produce the identical layout (the only
 * non-deterministic field is {@code allocatedAt}). The engine normalizes each
 * room's bench grid in place: if a room has no (or an incomplete) bench
 * collection, a transient M×N grid is attached to it. The returned
 * {@link SeatingPlan} carries transient {@link SeatingAllocation} instances
 * ({@code id == null}) that a service layer must persist.</p>
 *
 * <p>Complexity: worst case exponential (backtracking), bounded by the step
 * budget; real exam-hall sizes (tens of benches, hundreds of students) solve
 * in milliseconds.</p>
 *
 * <pre>{@code
 * SeatingConfig config = SeatingConfig.of(1, 1, 1);       // dH = dV = dD = 1
 * SeatingPlan plan = new SeatingArrangementEngine()
 *         .arrange(rooms, students, config);              // or arrangeForSubject(...)
 * }</pre>
 */
public class SeatingArrangementEngine {

    /** Default safety budget for the backtracking search (overridable per call). */
    public static final int DEFAULT_MAX_BACKTRACK_STEPS = 500_000;

    /**
     * Deterministic search strategies tried in order: each permutes the
     * candidate-ordering priorities and/or the MRV seat tie-break, so a
     * feasible instance that traps one strategy is usually solved by another.
     * Columns = priority order of (quota, least-represented, largest-remaining).
     */
    private static final int[][] SEARCH_PROFILES = {
            {0, 1, 2},
            {0, 2, 1},
            {2, 1, 0},
            {1, 2, 0},
    };
    /** Stall detector threshold: consecutive dead ends on the same seat abort a pass. */
    private static final int STALL_LIMIT = 25_000;
    /**
     * Step budget for the layer-decomposed fast path. Kept modest: cases the
     * layer search cannot crack quickly fall back to the general seat-level
     * solver, which is faster on those (typically multi-hall) structures.
     */
    private static final long GRID_SEARCH_STEPS = 100_000;

    // ------------------------------------------------------------------
    //  Public API
    // ------------------------------------------------------------------

    /**
     * Seats all given students across the given rooms.
     *
     * @param rooms    exam halls (must be non-empty, rows/columns &gt; 0)
     * @param students students to seat (may be empty)
     * @param config   gap / scope / budget configuration
     * @return the computed seating plan
     * @throws IllegalSeatingConfigurationException if the input configuration is invalid
     * @throws InsufficientCapacityException        if total seats &lt; student count
     * @throws SeatingUnsatisfiableException        if no legal layout exists (or the
     *                                              backtrack budget is exhausted)
     */
    public SeatingPlan arrange(List<ExamRoom> rooms, List<Student> students, SeatingConfig config) {
        Objects.requireNonNull(rooms, "rooms must not be null");
        Objects.requireNonNull(students, "students must not be null");
        Objects.requireNonNull(config, "config must not be null");
        if (rooms.isEmpty()) {
            throw new IllegalSeatingConfigurationException("At least one ExamRoom is required");
        }
        List<ExamRoom> orderedRooms = orderedRooms(rooms);
        if (students.isEmpty()) {
            return new SeatingPlan(List.of(), List.of(), List.copyOf(orderedRooms), 0, 0, true);
        }
        validateRooms(rooms);
        validateCapacity(rooms, students.size());
        validateGroupFeasibility(rooms, students, config);
        validateAttributeBalance(rooms, students, config);
        List<Student> orderedStudents = orderedStudents(students);

        // Fast path: dual-seater halls with exactly two year values decompose
        // into independent per-year grid colorings (see solveLayerDecomposed).
        SeatingPlan layerPlan = solveLayerDecomposed(orderedRooms, orderedStudents, config);
        if (layerPlan != null) {
            return layerPlan;
        }

        // General path: multi-pass seat-level search. Each profile is a
        // different deterministic heuristic. Pre-check failures are definitive
        // and propagate immediately; search failures (budget/stall/dead end)
        // simply advance to the next profile.
        SeatingSearchFailureException lastFailure = null;
        for (int profile = 0; profile < SEARCH_PROFILES.length; profile++) {
            try {
                return solve(orderedRooms, orderedStudents, config, profile, new SearchControl());
            } catch (SeatingSearchFailureException e) {
                lastFailure = e;
            }
        }
        throw new SeatingUnsatisfiableException(
                "No seating layout found after trying %d deterministic search strategies: %s"
                        .formatted(SEARCH_PROFILES.length,
                                lastFailure == null ? "search exhausted" : lastFailure.getMessage()));
    }

    /**
     * Convenience: seats only the students registered for the given subject
     * code (one round per paper/session).
     */
    public SeatingPlan arrangeForSubject(List<ExamRoom> rooms, List<Student> students,
                                         String subjectCode, SeatingConfig config) {
        Objects.requireNonNull(subjectCode, "subjectCode must not be null");
        List<Student> filtered = students.stream()
                .filter(s -> Objects.equals(s.getSubjectCode(), subjectCode))
                .toList();
        return arrange(rooms, filtered, config);
    }

    // ------------------------------------------------------------------
    //  Nested types
    // ------------------------------------------------------------------

    /**
     * Which students count as a "conflict group" for the gap rules.
     * The dual-seater bench rule is always stricter and independent of this:
     * bench-mates must differ in department <i>and</i> year (per spec).
     */
    public enum ConflictScope {
        /** Only same department. */
        DEPARTMENT,
        /** Only same year/session. */
        YEAR,
        /** Same department <b>and</b> same year (classmates — default). */
        DEPARTMENT_AND_YEAR,
        /** Same department <b>or</b> same year (very strict; with gaps &gt;= 1 this makes
         *  any 2×2 bench block impossible, since two same-year students always end up
         *  adjacent). */
        DEPARTMENT_OR_YEAR
    }

    /**
     * Rules governing how two students can share the same dual-seater bench.
     */
    public enum BenchPairingRule {
        /** Dual-seater bench mates must belong to a different department AND different year (strict separation). */
        DIFFERENT_DEPARTMENT_AND_YEAR,
        /** Dual-seater bench mates must belong to a different department only (allows two streams of the same batch/year). */
        DIFFERENT_DEPARTMENT_ONLY
    }

    /**
     * Engine configuration.
     *
     * @param horizontalGap           dH: minimum column separation for conflicting students in a row
     * @param verticalGap             dV: minimum row separation for conflicting students in a column
     * @param diagonalGap             dD: minimum diagonal separation for conflicting students
     * @param conflictScope           which attribute defines a conflict group
     * @param maxBacktrackSteps       safety budget for the backtracking search
     * @param benchPairingRule        dual-seater bench pairing constraint
     * @param horizontalDeptIsolation if true, horizontally adjacent seats in the same row cannot share department
     */
    public record SeatingConfig(int horizontalGap, int verticalGap, int diagonalGap,
                                ConflictScope conflictScope, int maxBacktrackSteps,
                                BenchPairingRule benchPairingRule,
                                boolean horizontalDeptIsolation) {

        public SeatingConfig(int horizontalGap, int verticalGap, int diagonalGap,
                             ConflictScope conflictScope, int maxBacktrackSteps) {
            this(horizontalGap, verticalGap, diagonalGap, conflictScope, maxBacktrackSteps,
                    BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR, true);
        }

        public SeatingConfig {
            if (horizontalGap < 0 || verticalGap < 0 || diagonalGap < 0) {
                throw new IllegalSeatingConfigurationException(
                        "Gap values must be >= 0 (0 disables the axis): dH=%d dV=%d dD=%d"
                                .formatted(horizontalGap, verticalGap, diagonalGap));
            }
            if (conflictScope == null) {
                conflictScope = ConflictScope.DEPARTMENT_AND_YEAR;
            }
            if (benchPairingRule == null) {
                benchPairingRule = BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR;
            }
            if (maxBacktrackSteps <= 0) {
                maxBacktrackSteps = DEFAULT_MAX_BACKTRACK_STEPS;
            }
        }

        public static SeatingConfig of(int horizontalGap, int verticalGap, int diagonalGap) {
            return new SeatingConfig(horizontalGap, verticalGap, diagonalGap,
                    ConflictScope.DEPARTMENT_AND_YEAR, DEFAULT_MAX_BACKTRACK_STEPS);
        }
    }

    /**
     * Result of an allocation run.
     *
     * @param allocations         every occupied seat, in assignment order
     * @param unplacedStudents    students left over when capacity &gt; student count
     * @param roomsInFillOrder    the rooms exactly as they were filled
     * @param totalSlots          total seats across all rooms
     * @param totalBacktrackSteps how many times the solver had to backtrack
     * @param solved              true when the search completed successfully
     */
    public record SeatingPlan(List<SeatingAllocation> allocations,
                              List<Student> unplacedStudents,
                              List<ExamRoom> roomsInFillOrder,
                              int totalSlots,
                              int totalBacktrackSteps,
                              boolean solved) {

        public SeatingPlan {
            allocations = List.copyOf(allocations);
            unplacedStudents = List.copyOf(unplacedStudents);
            roomsInFillOrder = List.copyOf(roomsInFillOrder);
        }

        public int seatedCount() {
            return allocations.size();
        }

        /**
         * Allocations grouped per room, in fill order.
         * Rooms are keyed by identity: transient (unsaved) ExamRoom instances
         * are all "equal" under their id-based equals/hashCode, so value maps
         * would collapse distinct halls into one bucket.
         */
        public Map<ExamRoom, List<SeatingAllocation>> allocationsByRoom() {
            Map<ExamRoom, List<SeatingAllocation>> byIdentity = new IdentityHashMap<>();
            for (ExamRoom room : roomsInFillOrder) {
                byIdentity.put(room, new ArrayList<>());
            }
            for (SeatingAllocation allocation : allocations) {
                ExamRoom room = roomOf(allocation);
                List<SeatingAllocation> list = byIdentity.get(room);
                if (list != null) {
                    list.add(allocation);
                }
            }
            Map<ExamRoom, List<SeatingAllocation>> result = new LinkedHashMap<>();
            for (ExamRoom room : roomsInFillOrder) {
                result.put(room, Collections.unmodifiableList(byIdentity.get(room)));
            }
            return Collections.unmodifiableMap(result);
        }

        public List<SeatingAllocation> allocationsIn(ExamRoom room) {
            return allocationsByRoom().getOrDefault(room, List.of());
        }

        private static ExamRoom roomOf(SeatingAllocation allocation) {
            Bench bench = allocation.getBench();
            return bench == null ? null : bench.getRoom();
        }

        private static boolean sameRoom(ExamRoom a, ExamRoom b) {
            if (a == b) {
                return true;
            }
            if (a == null || b == null) {
                return false;
            }
            return a.getId() != null && a.getId().equals(b.getId());
        }
    }

    // ------------------------------------------------------------------
    //  Exceptions
    // ------------------------------------------------------------------

    /** Base runtime exception of the engine. */
    public static class SeatingEngineException extends RuntimeException {
        public SeatingEngineException(String message) {
            super(message);
        }
    }

    public static class IllegalSeatingConfigurationException extends SeatingEngineException {
        public IllegalSeatingConfigurationException(String message) {
            super(message);
        }
    }

    public static class InsufficientCapacityException extends SeatingEngineException {
        public InsufficientCapacityException(String message) {
            super(message);
        }
    }

    public static class SeatingUnsatisfiableException extends SeatingEngineException {
        public SeatingUnsatisfiableException(String message) {
            super(message);
        }
    }

    /** Internal: a single search pass failed (budget, stall or dead end). */
    private static final class SeatingSearchFailureException extends SeatingEngineException {
        SeatingSearchFailureException(String message) {
            super(message);
        }
    }

    /** Mutable stall detector shared by the solver and the backjumper. */
    private static final class SearchControl {
        int lastDeadSeat = -1;
        int consecutive = 0;
        boolean stalled = false;
    }

    // ------------------------------------------------------------------
    //  Internal state types
    // ------------------------------------------------------------------

    /** A single seat in the hall: bench + slot. */
    private static final class Slot {
        final int roomIndex;
        final Bench bench;
        final int slotNumber;
        Student occupant;

        Slot(int roomIndex, Bench bench, int slotNumber) {
            this.roomIndex = roomIndex;
            this.bench = bench;
            this.slotNumber = slotNumber;
        }
    }

    /** Per-room mutable bookkeeping used by the solver. */
    private static final class RoomContext {
        final int index;
        final ExamRoom room;
        final List<Slot> occupiedSlots = new ArrayList<>();
        final Map<Group, Integer> groupCount = new HashMap<>();

        RoomContext(int index, ExamRoom room) {
            this.index = index;
            this.room = room;
        }
    }

    /** One committed choice on the backtracking stack. */
    private static final class Decision {
        final int seatIndex;
        /** Student indices (into the ordered student list), in heuristic order. */
        final List<Integer> candidates;
        /** Index into {@link #candidates} of the next candidate to try. */
        int nextCandidateIndex;

        Decision(int seatIndex, List<Integer> candidates, int nextCandidateIndex) {
            this.seatIndex = seatIndex;
            this.candidates = candidates;
            this.nextCandidateIndex = nextCandidateIndex;
        }
    }

    /** Conflict group key: (department, year/session). */
    private record Group(String department, String yearSession) {
    }

    // ------------------------------------------------------------------
    //  Layer-decomposed fast path (dual-seater halls, two year values)
    // ------------------------------------------------------------------

    /**
     * Fast, exact path for the classic exam scenario: dual-seater halls with
     * exactly two distinct year values and classmate conflict scope.
     *
     * <p>The dual-seater rule forces every bench to pair one student of each
     * year, so each hall splits into two independent grid colorings — the
     * "year layers" — coupled only by the per-bench department mismatch. Each
     * layer is a small king-adjacency coloring whose search also decides the
     * hall composition (which students land here). Single-seater halls are
     * colored directly from the leftover pool.</p>
     *
     * @return the plan, or {@code null} when the preconditions do not hold and
     *         the caller must fall back to the general seat-level search
     */
    private SeatingPlan solveLayerDecomposed(List<ExamRoom> orderedRooms, List<Student> orderedStudents,
                                             SeatingConfig config) {
        if (config.conflictScope() != ConflictScope.DEPARTMENT_AND_YEAR) {
            return null; // other scopes couple the layers — use the general solver
        }
        if (config.benchPairingRule() == BenchPairingRule.DIFFERENT_DEPARTMENT_ONLY) {
            return null; // Layer decomposition is strictly for 2-year per-bench pairing
        }
        // solveLayerDecomposed is ONLY valid for all-DUAL_SEATER halls in Normal Mode (dH=1, dV=0, dD=0)
        if (config.verticalGap() > 0 || config.diagonalGap() > 0) {
            return null;
        }
        boolean anySingleSeater = orderedRooms.stream()
                .anyMatch(r -> r.getBenchType() == ExamRoom.BenchType.SINGLE_SEATER);
        if (anySingleSeater) {
            return null; // single-seater halls must use the general solver
        }
        List<String> years = orderedStudents.stream()
                .map(Student::getYearSession)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (years.size() != 2) {
            return null;
        }
        String yearSlot1 = years.get(0);
        String yearSlot2 = years.get(1);

        int totalSeats = 0;
        for (ExamRoom r : orderedRooms) {
            totalSeats += r.getRows() * r.getColumns() * r.getBenchType().getSeatsPerBench();
        }

        // Build one global seat-cell model: dual benches contribute two coupled
        // cells (one per year, per-bench department mismatch); single benches
        // contribute one cell that may take any student. One shared search then
        // decides the hall compositions coherently across all halls.
        List<Bench> cells = new ArrayList<>();
        List<List<Integer>> poolsPerCell = new ArrayList<>();
        int[] mateOfCell = new int[totalSeats];
        Arrays.fill(mateOfCell, -1);

        List<Integer> year1Pool = new ArrayList<>();
        List<Integer> year2Pool = new ArrayList<>();
        List<Integer> allPool = new ArrayList<>();
        for (int i = 0; i < orderedStudents.size(); i++) {
            allPool.add(i);
            if (Objects.equals(orderedStudents.get(i).getYearSession(), yearSlot1)) {
                year1Pool.add(i);
            } else {
                year2Pool.add(i);
            }
        }
        List<Integer> year1Cells = new ArrayList<>();
        List<Integer> year2Cells = new ArrayList<>();

        int index = 0;
        for (ExamRoom room : orderedRooms) {
            ensureBenchGrid(room);
            for (Bench bench : room.getBenches()) {
                if (room.getBenchType() == ExamRoom.BenchType.DUAL_SEATER) {
                    int cellA = index++;
                    int cellB = index++;
                    cells.add(bench);
                    cells.add(bench);
                    poolsPerCell.add(year1Pool);
                    poolsPerCell.add(year2Pool);
                    mateOfCell[cellA] = cellB;
                    mateOfCell[cellB] = cellA;
                    year1Cells.add(cellA);
                    year2Cells.add(cellB);
                } else {
                    cells.add(bench);
                    poolsPerCell.add(allPool);
                    index++;
                }
            }
        }

        boolean[] used = new boolean[orderedStudents.size()];
        CellSearch search = new CellSearch(cells, orderedStudents, used, poolsPerCell, mateOfCell, config);
        if (!search.solve()) {
            return null;
        }

        List<SeatingAllocation> allocations = new ArrayList<>();
        for (int c = 0; c < cells.size(); c++) {
            int pi = search.assign[c];
            if (pi < 0) {
                continue; // empty seat (spare capacity or skipped cell)
            }
            int slot = year2Cells.contains(c) ? 2 : 1;
            allocations.add(toAllocation(orderedStudents.get(pi), cells.get(c), slot));
        }
        List<Student> unplaced = new ArrayList<>();
        for (int i = 0; i < used.length; i++) {
            if (!used[i]) {
                unplaced.add(orderedStudents.get(i));
            }
        }
        return new SeatingPlan(List.copyOf(allocations), List.copyOf(unplaced),
                List.copyOf(orderedRooms), totalSeats, 0, true);
    }

    /**
     * Global forward-checking search over one flat list of seat-cells. Each
     * cell is a bench seat with its own candidate pool (single benches: any
     * student; dual-seater benches: one year each), an optional bench-mate cell
     * (dual-seater rule) and same-room gap neighbours (gap rules). MRV seat
     * selection, incremental BitSet candidate maintenance, chronological
     * backtracking with a step budget.
     */
    private static final class CellSearch {
        final int n;
        final List<Bench> cells;
        final List<Student> students;
        final boolean[] used;
        final SeatingConfig config;
        final int[][] neighbors;             // per cell: same-room gap-neighbour cells
        final int[] mate;                    // per cell: bench-mate cell (-1 when none)
        final int[] assign;                  // per cell: student index or -1
        final List<BitSet> cand;             // per cell
        final int[] candCount;
        final List<Integer>[] cellsOfStudent;            // student -> cells whose pool contains it
        final Map<String, List<Integer>>[] byDept;       // per cell: dept -> pool student indices
        final Map<String, List<Integer>>[] byYear;       // per cell: year -> pool student indices
        final int[] roomOfCell;                          // per cell: room ordinal
        final Map<String, Integer>[] deptCount;          // per room: placed dept counts (incremental)
        final Map<Group, Integer> remaining;             // global unplaced group counts (incremental)
        final int totalStudents;
        /** Sentinel: cell permanently left empty (only possible with spare capacity). */
        static final int SKIPPED = -2;
        int usedCount;
        long steps;

        @SuppressWarnings("unchecked")
        CellSearch(List<Bench> cells, List<Student> students, boolean[] used,
                   List<List<Integer>> poolsPerCell, int[] mateOfCell, SeatingConfig config) {
            this.n = cells.size();
            this.cells = cells;
            this.students = students;
            this.used = used;
            this.config = config;
            this.neighbors = gridNeighbors(cells, config);
            this.mate = mateOfCell;
            this.assign = new int[n];
            Arrays.fill(assign, -1);
            this.cand = new ArrayList<>(n);
            this.candCount = new int[n];
            this.byDept = new Map[n];
            this.byYear = new Map[n];
            this.totalStudents = students.size();
            this.usedCount = 0;

            List<List<Integer>> cellsOfStudentList = new ArrayList<>();
            for (int i = 0; i < students.size(); i++) {
                cellsOfStudentList.add(new ArrayList<>());
            }
            this.cellsOfStudent = cellsOfStudentList.toArray(new List[0]);
            Map<ExamRoom, Integer> roomOrdinal = new IdentityHashMap<>();
            this.roomOfCell = new int[n];
            for (int c = 0; c < n; c++) {
                roomOrdinal.computeIfAbsent(cells.get(c).getRoom(), r -> roomOrdinal.size());
            }
            this.deptCount = new Map[roomOrdinal.size()];
            for (int r = 0; r < roomOrdinal.size(); r++) {
                deptCount[r] = new HashMap<>();
            }
            for (int c = 0; c < n; c++) {
                roomOfCell[c] = roomOrdinal.get(cells.get(c).getRoom());
            }
            this.remaining = new HashMap<>();
            for (Student s : students) {
                remaining.merge(groupOf(s), 1, Integer::sum);
            }
            for (int c = 0; c < n; c++) {
                cand.add(new BitSet(students.size()));
                byDept[c] = new HashMap<>();
                byYear[c] = new HashMap<>();
                for (int pi : poolsPerCell.get(c)) {
                    cellsOfStudent[pi].add(c);
                    byDept[c].computeIfAbsent(students.get(pi).getDepartment(), k -> new ArrayList<>()).add(pi);
                    byYear[c].computeIfAbsent(students.get(pi).getYearSession(), k -> new ArrayList<>()).add(pi);
                }
            }
            recomputeAll();
        }

        boolean solve() {
            return search(0);
        }

        private boolean search(int placed) {
            if (usedCount == totalStudents) {
                return true; // every student seated (spare seats stay empty)
            }
            if (placed == n) {
                return false; // every cell resolved but students remain — impossible
            }
            if (++steps > GRID_SEARCH_STEPS) {
                return false;
            }
            // MRV: most-constrained empty cell first (ties by cell index — deterministic)
            int best = -1;
            int bestCount = Integer.MAX_VALUE;
            for (int c = 0; c < n; c++) {
                if (assign[c] != -1) {
                    continue;
                }
                if (candCount[c] < bestCount) {
                    bestCount = candCount[c];
                    best = c;
                }
            }
            if (best < 0) {
                return true;
            }
            if (bestCount == 0) {
                if (n > totalStudents) {
                    // spare capacity: this cell can never be filled in this branch —
                    // it must stay empty. Skipping is undone on backtrack.
                    assign[best] = SKIPPED;
                    if (search(placed + 1)) {
                        return true;
                    }
                    assign[best] = -1;
                    return false;
                }
                return false;
            }
            // value order: least-represented department in the cell's room first,
            // then largest still-unplaced classmate group, then student index (deterministic)
            Map<String, Integer> roomDept = deptCount[roomOfCell[best]];
            List<Integer> ordered = new ArrayList<>();
            BitSet bits = cand.get(best);
            for (int pi = bits.nextSetBit(0); pi >= 0; pi = bits.nextSetBit(pi + 1)) {
                ordered.add(pi);
            }
            ordered.sort(Comparator
                    .comparingInt((Integer pi) -> roomDept.getOrDefault(students.get(pi).getDepartment(), 0))
                    .thenComparing((a, b) -> Integer.compare(
                            remaining.getOrDefault(groupOf(students.get(b)), 0),
                            remaining.getOrDefault(groupOf(students.get(a)), 0)))
                    .thenComparingInt(pi -> pi));
            for (int pi : ordered) {
                if (!legal(best, pi)) {
                    throw new IllegalStateException("FC under-prune: committing illegal candidate "
                            + students.get(pi).getRollNo() + " at cell " + best + " (placed=" + placed + ")");
                }
                place(best, pi);
                if (search(placed + 1)) {
                    return true;
                }
                unplace(best, pi);
            }
            return false;
        }

        private void place(int c, int pi) {
            assign[c] = pi;
            used[pi] = true;
            usedCount++;
            Student placed = students.get(pi);
            deptCount[roomOfCell[c]].merge(placed.getDepartment(), 1, Integer::sum);
            remaining.merge(groupOf(placed), -1, Integer::sum);
            // pi is no longer a candidate in any other cell that holds it in its pool
            for (int c2 : cellsOfStudent[pi]) {
                if (c2 != c && assign[c2] == -1 && cand.get(c2).get(pi)) {
                    cand.get(c2).clear(pi);
                    candCount[c2]--;
                }
            }
            Student s = students.get(pi);
            // same-room gap neighbours lose conflicting (classmate-group) candidates
            for (int k : neighbors[c]) {
                removeBlocked(k, neighborBlockers(k, s), pi);
            }
            // bench-mate cell loses dept/year shares (dual-seater rule)
            if (mate[c] >= 0) {
                List<Integer> blockers = new ArrayList<>();
                blockers.addAll(byDept[mate[c]].getOrDefault(s.getDepartment(), List.of()));
                blockers.addAll(byYear[mate[c]].getOrDefault(s.getYearSession(), List.of()));
                removeBlocked(mate[c], blockers, pi);
            }
        }

        private void removeBlocked(int c, List<Integer> blockers, int pi) {
            if (assign[c] != -1) {
                return;
            }
            BitSet bits = cand.get(c);
            for (int q : blockers) {
                if (bits.get(q)) {
                    bits.clear(q);
                    candCount[c]--;
                }
            }
            if (bits.get(pi)) {
                bits.clear(pi);
                candCount[c]--;
            }
        }

        private void unplace(int c, int pi) {
            assign[c] = -1;
            used[pi] = false;
            usedCount--;
            Student s = students.get(pi);
            deptCount[roomOfCell[c]].merge(s.getDepartment(), -1, Integer::sum);
            remaining.merge(groupOf(s), 1, Integer::sum);
            // pi may be a candidate again in cells holding it in their pool, where legal
            for (int c2 : cellsOfStudent[pi]) {
                if (c2 != c && assign[c2] == -1 && !cand.get(c2).get(pi) && legal(c2, pi)) {
                    cand.get(c2).set(pi);
                    candCount[c2]++;
                }
            }
            // same-room gap neighbours may regain conflicting (classmate-group) candidates
            for (int k : neighbors[c]) {
                for (int q : neighborBlockers(k, s)) {
                    if (q != pi && !used[q] && !cand.get(k).get(q) && legal(k, q)) {
                        cand.get(k).set(q);
                        candCount[k]++;
                    }
                }
            }
            // bench-mate cell may regain dept/year shares
            if (mate[c] >= 0) {
                List<Integer> blockers = new ArrayList<>();
                blockers.addAll(byDept[mate[c]].getOrDefault(s.getDepartment(), List.of()));
                blockers.addAll(byYear[mate[c]].getOrDefault(s.getYearSession(), List.of()));
                for (int q : blockers) {
                    if (q != pi && !used[q] && !cand.get(mate[c]).get(q) && legal(mate[c], q)) {
                        cand.get(mate[c]).set(q);
                        candCount[mate[c]]++;
                    }
                }
            }
        }

        /**
         * Students in cell {@code k}'s pool that conflict with {@code s} under
         * the gap rules (i.e. the same classmate group). For the classmate
         * scope this is same department <i>and</i> same year — different-year
         * same-department students are NOT blockers.
         */
        private List<Integer> neighborBlockers(int k, Student s) {
            List<Integer> result = new ArrayList<>();
            for (int q : byDept[k].getOrDefault(s.getDepartment(), List.of())) {
                if (config.conflictScope() == ConflictScope.DEPARTMENT_AND_YEAR) {
                    if (Objects.equals(students.get(q).getYearSession(), s.getYearSession())) {
                        result.add(q);
                    }
                } else if (conflicts(s, students.get(q), config.conflictScope())) {
                    result.add(q);
                }
            }
            return result;
        }

        /** Whether student {@code pi} may sit at cell {@code c} under the current assignment. */
        private boolean legal(int c, int pi) {
            Student s = students.get(pi);
            // dual-seater rule vs bench-mate
            if (mate[c] >= 0 && assign[mate[c]] >= 0) {
                Student m = students.get(assign[mate[c]]);
                if (sameDepartment(s, m) || sameYear(s, m)) {
                    return false;
                }
            }
            // gap rules vs same-room neighbours
            for (int k : neighbors[c]) {
                int other = assign[k];
                if (other >= 0 && conflicts(s, students.get(other), config.conflictScope())) {
                    return false;
                }
            }
            return true;
        }

        /** Full recompute of every cell's candidate set (construction only). */
        private void recomputeAll() {
            for (int c = 0; c < n; c++) {
                BitSet bits = cand.get(c);
                bits.clear();
                int count = 0;
                for (int pi : poolOf(c)) {
                    if (!used[pi] && legal(c, pi)) {
                        bits.set(pi);
                        count++;
                    }
                }
                candCount[c] = count;
            }
        }

        private List<Integer> poolOf(int c) {
            // union of byDept[c] values = the pool
            List<Integer> pool = new ArrayList<>();
            for (List<Integer> list : byDept[c].values()) {
                pool.addAll(list);
            }
            return pool;
        }
    }

    /** Per-cell indices of benches within the configured gap distance (same room only). */
    private static int[][] gridNeighbors(List<Bench> cells, SeatingConfig config) {
        int[][] neighbors = new int[cells.size()][];
        for (int c = 0; c < cells.size(); c++) {
            List<Integer> list = new ArrayList<>();
            for (int k = 0; k < cells.size(); k++) {
                if (k != c && cells.get(k).getRoom() == cells.get(c).getRoom()
                        && withinGap(cells.get(c), cells.get(k), config)) {
                    list.add(k);
                }
            }
            neighbors[c] = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                neighbors[c][i] = list.get(i);
            }
        }
        return neighbors;
    }

    private static boolean withinGap(Bench a, Bench b, SeatingConfig config) {
        int dr = Math.abs(a.getRowIndex() - b.getRowIndex());
        int dc = Math.abs(a.getColIndex() - b.getColIndex());
        if (config.horizontalGap() > 0 && dr == 0 && dc <= config.horizontalGap()) {
            return true;
        }
        if (config.verticalGap() > 0 && dc == 0 && dr <= config.verticalGap()) {
            return true;
        }
        return config.diagonalGap() > 0 && dr > 0 && dc > 0
                && dr <= config.diagonalGap() && dc <= config.diagonalGap();
    }

    private static SeatingAllocation toAllocation(Student student, Bench bench, int slotNumber) {
        return SeatingAllocation.builder()
                .student(student)
                .bench(bench)
                .slotNumber(slotNumber)
                .subjectCode(student.getSubjectCode())
                .allocatedAt(Instant.now())
                .build();
    }

    // ------------------------------------------------------------------
    //  Solver
    // ------------------------------------------------------------------

    private SeatingPlan solve(List<ExamRoom> orderedRooms, List<Student> orderedStudents,
                              SeatingConfig config, int profile, SearchControl control) {
        // ---- build the flat seat list (rooms in order, row-major, slot 1 before slot 2) ----
        List<RoomContext> contexts = new ArrayList<>();
        List<Slot> slots = new ArrayList<>();
        int[] roomStart = new int[orderedRooms.size()];
        int[] roomEnd = new int[orderedRooms.size()];
        for (int ri = 0; ri < orderedRooms.size(); ri++) {
            ExamRoom room = orderedRooms.get(ri);
            ensureBenchGrid(room);
            contexts.add(new RoomContext(ri, room));
            roomStart[ri] = slots.size();
            for (Bench bench : room.getBenches()) {
                int seats = bench.getBenchType() != null
                        ? bench.getBenchType().getSeatsPerBench()
                        : room.getBenchType().getSeatsPerBench();
                for (int slotNumber = 1; slotNumber <= seats; slotNumber++) {
                    slots.add(new Slot(ri, bench, slotNumber));
                }
            }
            roomEnd[ri] = slots.size();
        }
        int totalSeats = slots.size();

        // per-room: how many seats of a single group later halls can hold in total
        // (= sum of density bounds of the halls after this one)
        long[] laterBoundPerRoom = new long[orderedRooms.size()];
        long runningLaterBound = 0;
        for (int ri = orderedRooms.size() - 1; ri >= 0; ri--) {
            laterBoundPerRoom[ri] = runningLaterBound;
            ExamRoom room = orderedRooms.get(ri);
            int effectiveCols = (config.diagonalGap() > 0 || config.verticalGap() > 0)
                    ? room.getColumns()
                    : room.getColumns() * (room.getBenchType() != null ? room.getBenchType().getSeatsPerBench() : 1);
            runningLaterBound += (long) ceilDiv(room.getRows(), config.verticalGap() + 1)
                    * ceilDiv(effectiveCols, config.horizontalGap() + 1);
        }

        boolean[] placed = new boolean[orderedStudents.size()];
        int[] unplacedCount = new int[]{orderedStudents.size()};
        Map<Group, Integer> remaining = new HashMap<>();
        for (Student s : orderedStudents) {
            remaining.merge(groupOf(s), 1, Integer::sum);
        }

        List<Map<Group, Integer>> roomQuotaMaps = new ArrayList<>();
        for (int i = 0; i < orderedRooms.size(); i++) {
            roomQuotaMaps.add(null);
        }
        boolean[] quotaComputed = new boolean[orderedRooms.size()];

        // ---- forward-checking state: per-seat candidate sets & counts ----
        boolean[] seatAssigned = new boolean[totalSeats];
        List<BitSet> seatCandidates = new ArrayList<>(totalSeats);
        int[] seatCandidateCount = new int[totalSeats];
        for (int t = 0; t < totalSeats; t++) {
            seatCandidates.add(new BitSet(orderedStudents.size()));
            recomputeCandidates(t, slots, orderedStudents, placed, seatAssigned, seatCandidates,
                    seatCandidateCount, contexts, config);
        }

        Deque<Decision> stack = new ArrayDeque<>();
        int[] backtracks = new int[1];
        int steps = 0;
        int currentRoomIndex = -1;

        while (true) {
            if (unplacedCount[0] == 0) {
                break; // every student seated — spare seats (capacity > count) stay empty
            }

            // first room (in fill order) that still has an empty seat
            int roomIndex = -1;
            for (int ri = 0; ri < orderedRooms.size(); ri++) {
                if (roomStart[ri] < roomEnd[ri] && !roomFullyAssigned(roomStart[ri], roomEnd[ri], seatAssigned)) {
                    roomIndex = ri;
                    break;
                }
            }
            if (roomIndex < 0) {
                break; // every seat filled
            }

            // ---- room entry: per-group "must seat here" quotas, computed once per hall ----
            if (roomIndex != currentRoomIndex) {
                currentRoomIndex = roomIndex;
                if (!quotaComputed[roomIndex]) {
                    quotaComputed[roomIndex] = true;
                    Map<Group, Integer> quota = computeRoomQuota(roomIndex, laterBoundPerRoom, remaining);
                    roomQuotaMaps.set(roomIndex, quota);
                    long totalQuota = 0;
                    for (int q : quota.values()) {
                        totalQuota += q;
                    }
                    if (totalQuota > (long) roomEnd[roomIndex] - roomStart[roomIndex]) {
                        throw new SeatingUnsatisfiableException(
                                ("Hall '%s' has %d seats but %d students must sit here (they cannot fit "
                                        + "in later halls with the current gaps) — add halls or relax dH/dV/dD.")
                                        .formatted(orderedRooms.get(roomIndex).getHallName(),
                                                roomEnd[roomIndex] - roomStart[roomIndex], totalQuota));
                    }
                }
            }

            if (++steps > config.maxBacktrackSteps()) {
                throw new SeatingSearchFailureException(
                        ("backtrack budget of %d steps exhausted after %d backtracks (search strategy %d) — "
                                + "relax dH/dV/dD, add rooms, or split students by subject/session")
                                .formatted(config.maxBacktrackSteps(), backtracks[0], profile));
            }

            // ---- MRV: pick the most-constrained empty seat of the current hall ----
            // Dual benches are assigned atomically: slot 2 is forced right after
            // its slot 1, so the bench-mate pairing is decided while both are open.
            int forcedSeat = -1;
            if (!stack.isEmpty()) {
                Decision top = stack.peek();
                Slot topSlot = slots.get(top.seatIndex);
                if (topSlot.slotNumber == 1 && top.seatIndex + 1 < totalSeats
                        && slots.get(top.seatIndex + 1).bench == topSlot.bench
                        && !seatAssigned[top.seatIndex + 1]) {
                    forcedSeat = top.seatIndex + 1;
                }
            }
            int bestSeat = -1;
            int bestCount = Integer.MAX_VALUE;
            if (forcedSeat >= 0) {
                bestSeat = forcedSeat;
                bestCount = seatCandidateCount[forcedSeat];
            } else {
                // Pick the most-constrained unassigned seat that HAS legal candidates (seatCandidateCount > 0)
                if (profile == 3) {
                    for (int t = roomEnd[roomIndex] - 1; t >= roomStart[roomIndex]; t--) {
                        if (seatAssigned[t] || seatCandidateCount[t] == 0) {
                            continue;
                        }
                        if (seatCandidateCount[t] < bestCount) {
                            bestCount = seatCandidateCount[t];
                            bestSeat = t;
                        }
                    }
                } else {
                    for (int t = roomStart[roomIndex]; t < roomEnd[roomIndex]; t++) {
                        if (seatAssigned[t] || seatCandidateCount[t] == 0) {
                            continue;
                        }
                        if (seatCandidateCount[t] < bestCount) {
                            bestCount = seatCandidateCount[t];
                            bestSeat = t;
                        }
                    }
                }
                // If current hall has no fillable seats left, check other halls
                if (bestSeat < 0) {
                    for (int ri = 0; ri < orderedRooms.size(); ri++) {
                        if (ri == roomIndex) continue;
                        for (int t = roomStart[ri]; t < roomEnd[ri]; t++) {
                            if (!seatAssigned[t] && seatCandidateCount[t] > 0 && seatCandidateCount[t] < bestCount) {
                                bestCount = seatCandidateCount[t];
                                bestSeat = t;
                            }
                        }
                        if (bestSeat >= 0) break;
                    }
                }
            }

            if (bestSeat < 0 || bestCount == 0) {
                // No seat can legally accept any remaining student while unplaced students exist!
                int deadSeat = forcedSeat >= 0 ? forcedSeat : (stack.isEmpty() ? 0 : stack.peek().seatIndex);
                if (!resolveDeadEnd(deadSeat, stack, slots, orderedStudents, placed, unplacedCount,
                        contexts, remaining, seatAssigned, seatCandidates, seatCandidateCount, config,
                        backtracks, control)) {
                    throw new SeatingSearchFailureException(
                            ("no legal candidate for unplaced students in hall '%s' (search strategy %d)")
                                    .formatted(orderedRooms.get(roomIndex).getHallName(), profile));
                }
                continue;
            }

            // ---- choose the best legal student for the chosen seat ----
            int chosenRoomIndex = slots.get(bestSeat).roomIndex;
            List<Integer> candidates = collectCandidates(bestSeat, slots, orderedStudents, placed,
                    seatAssigned, contexts, config, remaining, roomQuotaMaps.get(chosenRoomIndex), profile);
            if (candidates.isEmpty()) {
                int deadSeat = bestSeat;
                if (!resolveDeadEnd(deadSeat, stack, slots, orderedStudents, placed, unplacedCount,
                        contexts, remaining, seatAssigned, seatCandidates, seatCandidateCount, config,
                        backtracks, control)) {
                    throw new SeatingSearchFailureException(
                            ("no candidate available for seat %d in search strategy %d").formatted(bestSeat, profile));
                }
                continue;
            }
            int chosen = candidates.get(0);
            place(chosen, bestSeat, slots, orderedStudents, placed, unplacedCount, contexts, remaining,
                    seatAssigned, seatCandidates, seatCandidateCount, config);
            stack.push(new Decision(bestSeat, candidates, 1));

            // ---- forward checking: verify sufficient fillable seats remain ----
            if (unplacedCount[0] > 0) {
                if (++steps > config.maxBacktrackSteps()) {
                    throw new SeatingSearchFailureException(
                            ("backtrack budget of %d steps exhausted after %d backtracks (search strategy %d) — "
                                    + "relax dH/dV/dD, add rooms, or split students by subject/session")
                                    .formatted(config.maxBacktrackSteps(), backtracks[0], profile));
                }
                int fillable = 0;
                for (int t = 0; t < totalSeats; t++) {
                    if (!seatAssigned[t] && seatCandidateCount[t] > 0) {
                        fillable++;
                    }
                }
                if (fillable < unplacedCount[0]) {
                    if (!resolveDeadEnd(bestSeat, stack, slots, orderedStudents, placed, unplacedCount,
                            contexts, remaining, seatAssigned, seatCandidates, seatCandidateCount, config,
                            backtracks, control)) {
                        throw new SeatingSearchFailureException(
                                ("dead end detected by forward checking while seating hall '%s' (search strategy %d)")
                                        .formatted(orderedRooms.get(roomIndex).getHallName(), profile));
                    }
                }
            }
        }

        // ---- assemble the plan ----
        List<SeatingAllocation> allocations = new ArrayList<>();
        for (int t = 0; t < totalSeats; t++) {
            Student occupant = slots.get(t).occupant;
            if (occupant == null) {
                continue; // spare seat (capacity > student count)
            }
            Slot slot = slots.get(t);
            allocations.add(SeatingAllocation.builder()
                    .student(occupant)
                    .bench(slot.bench)
                    .slotNumber(slot.slotNumber)
                    .subjectCode(occupant.getSubjectCode())
                    .allocatedAt(Instant.now())
                    .build());
        }
        List<Student> unplaced = new ArrayList<>();
        for (int i = 0; i < placed.length; i++) {
            if (!placed[i]) {
                unplaced.add(orderedStudents.get(i));
            }
        }
        return new SeatingPlan(List.copyOf(allocations), List.copyOf(unplaced),
                List.copyOf(orderedRooms), totalSeats, backtracks[0], true);
    }

    private static boolean roomFullyAssigned(int start, int end, boolean[] seatAssigned) {
        for (int t = start; t < end; t++) {
            if (!seatAssigned[t]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Resolves a dead end (a seat with zero legal students) by conflict-directed
     * backjumping: decisions whose seats cannot constrain {@code deadSeat} are
     * unwound without trying their alternatives (those seats will be re-picked
     * from scratch by MRV), and the closest decision that can actually affect
     * {@code deadSeat} gets its next candidate committed. Returns false when the
     * search space is exhausted.
     */
    private static boolean resolveDeadEnd(int deadSeat, Deque<Decision> stack, List<Slot> slots,
                                          List<Student> orderedStudents, boolean[] placed, int[] unplacedCount,
                                          List<RoomContext> contexts, Map<Group, Integer> remaining,
                                          boolean[] seatAssigned, List<BitSet> seatCandidates,
                                          int[] seatCandidateCount, SeatingConfig config, int[] backtracks,
                                          SearchControl control) {
        // stall detection: repeatedly dead-ending on the same seat means this
        // search strategy is cycling in a local trap — abort the pass.
        if (deadSeat == control.lastDeadSeat) {
            if (++control.consecutive > STALL_LIMIT) {
                control.stalled = true;
                throw new SeatingSearchFailureException(
                        ("search strategy stalled after %d consecutive dead ends on the same seat")
                                .formatted(control.consecutive));
            }
        } else {
            control.lastDeadSeat = deadSeat;
            control.consecutive = 0;
        }
        // conflict-directed backjump: unwind decisions whose seats cannot
        // constrain the dead seat (they will be re-picked fresh by MRV).
        while (!stack.isEmpty() && !influences(stack.peek().seatIndex, deadSeat, slots, config)) {
            Decision d = stack.pop();
            unplace(d.seatIndex, d.candidates.get(d.nextCandidateIndex - 1), slots, orderedStudents, placed,
                    unplacedCount, contexts, remaining, seatAssigned, seatCandidates, seatCandidateCount, config);
            backtracks[0]++;
        }
        if (stack.isEmpty()) {
            return false;
        }
        return backtrack(stack, slots, orderedStudents, placed, unplacedCount, contexts, remaining,
                seatAssigned, seatCandidates, seatCandidateCount, config, backtracks);
    }

    /**
     * Pops the most recent committed choice, undoes it, and (if the decision
     * still has untried candidates) commits the next one. Returns false when
     * the whole stack is exhausted.
     */
    private static boolean backtrack(Deque<Decision> stack, List<Slot> slots, List<Student> orderedStudents,
                                     boolean[] placed, int[] unplacedCount, List<RoomContext> contexts,
                                     Map<Group, Integer> remaining, boolean[] seatAssigned,
                                     List<BitSet> seatCandidates, int[] seatCandidateCount, SeatingConfig config,
                                     int[] backtracks) {
        if (stack.isEmpty()) {
            return false;
        }
        Decision d = stack.pop();
        unplace(d.seatIndex, d.candidates.get(d.nextCandidateIndex - 1), slots, orderedStudents, placed,
                unplacedCount, contexts, remaining, seatAssigned, seatCandidates, seatCandidateCount, config);
        backtracks[0]++;
        while (d.nextCandidateIndex >= d.candidates.size() && !stack.isEmpty()) {
            d = stack.pop();
            unplace(d.seatIndex, d.candidates.get(d.nextCandidateIndex - 1), slots, orderedStudents, placed,
                    unplacedCount, contexts, remaining, seatAssigned, seatCandidates, seatCandidateCount, config);
            backtracks[0]++;
        }
        if (d.nextCandidateIndex >= d.candidates.size()) {
            return false; // every alternative exhausted
        }
        int nextStudent = d.candidates.get(d.nextCandidateIndex);
        d.nextCandidateIndex++;
        place(nextStudent, d.seatIndex, slots, orderedStudents, placed, unplacedCount, contexts, remaining,
                seatAssigned, seatCandidates, seatCandidateCount, config);
        stack.push(d);
        return true;
    }

    private static int findZeroSeat(boolean[] seatAssigned, int[] seatCandidateCount) {
        for (int t = 0; t < seatAssigned.length; t++) {
            if (!seatAssigned[t] && seatCandidateCount[t] == 0) {
                return t;
            }
        }
        return -1;
    }

    /**
     * For the hall at {@code roomIndex}: the minimum number of students of each
     * group that MUST be seated here, because later halls together can hold at
     * most {@code laterBoundPerRoom[roomIndex]} members of any single group.
     */
    private static Map<Group, Integer> computeRoomQuota(int roomIndex, long[] laterBoundPerRoom,
                                                        Map<Group, Integer> remaining) {
        Map<Group, Integer> quota = new HashMap<>();
        long laterBound = laterBoundPerRoom[roomIndex];
        for (Map.Entry<Group, Integer> e : remaining.entrySet()) {
            long q = Math.max(0, e.getValue() - laterBound);
            if (q > 0) {
                quota.put(e.getKey(), (int) q);
            }
        }
        return quota;
    }

    /** Legal candidates for the seat, sorted by the deterministic heuristic of the search profile. */
    private static List<Integer> collectCandidates(int seatIndex, List<Slot> slots, List<Student> orderedStudents,
                                                   boolean[] placed, boolean[] seatAssigned,
                                                   List<RoomContext> contexts,
                                                   SeatingConfig config, Map<Group, Integer> remaining,
                                                   Map<Group, Integer> roomQuota, int profile) {
        Slot slot = slots.get(seatIndex);
        RoomContext ctx = contexts.get(slot.roomIndex);
        Student benchMate = benchMateOf(seatIndex, slots, seatAssigned);
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < orderedStudents.size(); i++) {
            if (placed[i]) {
                continue;
            }
            if (canPlace(orderedStudents.get(i), slot, ctx, config, benchMate)) {
                result.add(i);
            }
        }
        Slot frontSlot = findFrontSlot(slot, ctx);
        Student frontStudent = frontSlot != null ? frontSlot.occupant : null;

        // Column continuity priority: prefer matching the department of the student sitting directly in front (only in Normal mode when verticalGap == 0)
        Comparator<Integer> columnAlignment = (a, b) -> {
            if (config.verticalGap() > 0 || frontStudent == null) return 0;
            boolean aMatches = Objects.equals(orderedStudents.get(a).getDepartment(), frontStudent.getDepartment());
            boolean bMatches = Objects.equals(orderedStudents.get(b).getDepartment(), frontStudent.getDepartment());
            return Boolean.compare(bMatches, aMatches);
        };

        // Priority 0: groups with an unmet "must seat here" quota (most urgent first).
        Comparator<Integer> mustPlaceFirst = (a, b) -> Integer.compare(
                quotaRemaining(orderedStudents.get(b), roomQuota, ctx),
                quotaRemaining(orderedStudents.get(a), roomQuota, ctx));
        // Priority 1: least-represented group in this hall (spreads groups across the grid).
        Comparator<Integer> leastRepresentedFirst =
                Comparator.comparingInt(idx -> ctx.groupCount.getOrDefault(groupOf(orderedStudents.get(idx)), 0));
        // Priority 2: largest still-unplaced group.
        Comparator<Integer> largestRemainingGroupFirst = (a, b) -> Integer.compare(
                remaining.getOrDefault(groupOf(orderedStudents.get(b)), 0),
                remaining.getOrDefault(groupOf(orderedStudents.get(a)), 0));
        Comparator<Integer>[] priorities = new Comparator[]{mustPlaceFirst, leastRepresentedFirst, largestRemainingGroupFirst};
        int[] order = SEARCH_PROFILES[profile];
        Comparator<Integer> chain = columnAlignment.thenComparing(priorities[order[0]]);
        for (int p = 1; p < order.length; p++) {
            chain = chain.thenComparing(priorities[order[p]]);
        }
        Comparator<Integer> indexTie = profile == 3
                ? (a, b) -> Integer.compare(b, a)
                : Comparator.comparingInt(idx -> idx);
        result.sort(chain.thenComparing(indexTie));
        return result;
    }

    private static Slot findFrontSlot(Slot slot, RoomContext ctx) {
        int r = slot.bench.getRowIndex();
        if (r <= 1) return null;
        for (Slot other : ctx.occupiedSlots) {
            if (other.bench.getColIndex() == slot.bench.getColIndex()
                    && other.bench.getRowIndex() == r - 1
                    && other.slotNumber == slot.slotNumber) {
                return other;
            }
        }
        return null;
    }

    private static int quotaRemaining(Student student, Map<Group, Integer> roomQuota, RoomContext ctx) {
        if (roomQuota == null) {
            return 0;
        }
        int quota = roomQuota.getOrDefault(groupOf(student), 0);
        int placedHere = ctx.groupCount.getOrDefault(groupOf(student), 0);
        return Math.max(0, quota - placedHere);
    }

    /**
     * Recomputes the exact candidate set of seat {@code t} from the current
     * assignment (used by the forward-checking bookkeeping).
     */
    private static void recomputeCandidates(int t, List<Slot> slots, List<Student> orderedStudents,
                                            boolean[] placed, boolean[] seatAssigned, List<BitSet> seatCandidates,
                                            int[] seatCandidateCount, List<RoomContext> contexts,
                                            SeatingConfig config) {
        Slot slot = slots.get(t);
        RoomContext ctx = contexts.get(slot.roomIndex);
        Student benchMate = benchMateOf(t, slots, seatAssigned);
        BitSet bits = seatCandidates.get(t);
        bits.clear();
        int count = 0;
        for (int i = 0; i < orderedStudents.size(); i++) {
            if (placed[i]) {
                continue;
            }
            if (canPlace(orderedStudents.get(i), slot, ctx, config, benchMate)) {
                bits.set(i);
                count++;
            }
        }
        seatCandidateCount[t] = count;
    }

    private static int physicalCol(Slot s) {
        if (s.bench.getBenchType() == ExamRoom.BenchType.DUAL_SEATER) {
            return (s.bench.getColIndex() - 1) * 2 + s.slotNumber;
        }
        return s.bench.getColIndex();
    }

    /** True when assigning one of the two seats changes the other seat's candidate set. */
    private static boolean influences(int s, int t, List<Slot> slots, SeatingConfig config) {
        Slot a = slots.get(s);
        Slot b = slots.get(t);
        if (a.bench == b.bench) {
            return true; // bench-mate slots
        }
        if (a.roomIndex != b.roomIndex) {
            return false;
        }
        int dr = Math.abs(a.bench.getRowIndex() - b.bench.getRowIndex());
        int benchDc = Math.abs(a.bench.getColIndex() - b.bench.getColIndex());
        int seatDc = Math.abs(physicalCol(a) - physicalCol(b));
        if (dr == 0 && seatDc <= config.horizontalGap()) {
            return true;
        }
        if (benchDc == 0 && dr <= config.verticalGap()) {
            return true;
        }
        return dr > 0 && benchDc > 0 && dr <= config.diagonalGap() && benchDc <= config.diagonalGap();
    }

    /** Occupant of the other slot on the same bench, or null. */
    private static Student benchMateOf(int seatIndex, List<Slot> slots, boolean[] seatAssigned) {
        Slot slot = slots.get(seatIndex);
        int other = -1;
        if (slot.slotNumber == 2) {
            other = seatIndex - 1;
        } else if (seatIndex + 1 < slots.size()
                && slots.get(seatIndex + 1).bench == slot.bench
                && slots.get(seatIndex + 1).slotNumber == 2) {
            other = seatIndex + 1;
        }
        if (other >= 0 && seatAssigned[other]) {
            return slots.get(other).occupant;
        }
        return null;
    }

    /** Commits a student to a seat and maintains the forward-checking counts. */
    private static void place(int studentIndex, int seatIndex, List<Slot> slots, List<Student> orderedStudents,
                              boolean[] placed, int[] unplacedCount, List<RoomContext> contexts,
                              Map<Group, Integer> remaining, boolean[] seatAssigned,
                              List<BitSet> seatCandidates, int[] seatCandidateCount, SeatingConfig config) {
        Student s = orderedStudents.get(studentIndex);
        Slot slot = slots.get(seatIndex);
        slot.occupant = s;
        placed[studentIndex] = true;
        unplacedCount[0]--;
        seatAssigned[seatIndex] = true;
        RoomContext ctx = contexts.get(slot.roomIndex);
        ctx.occupiedSlots.add(slot);
        Group g = groupOf(s);
        ctx.groupCount.merge(g, 1, Integer::sum);
        remaining.merge(g, -1, Integer::sum);

        updateCountsAfterChange(seatIndex, studentIndex, slots, orderedStudents, placed,
                seatAssigned, seatCandidates, seatCandidateCount, contexts, config, true);
    }

    /** Removes a student from a seat and maintains the forward-checking counts. */
    private static void unplace(int seatIndex, int studentIndex, List<Slot> slots, List<Student> orderedStudents,
                                boolean[] placed, int[] unplacedCount, List<RoomContext> contexts,
                                Map<Group, Integer> remaining, boolean[] seatAssigned,
                                List<BitSet> seatCandidates, int[] seatCandidateCount, SeatingConfig config) {
        Student s = orderedStudents.get(studentIndex);
        Slot slot = slots.get(seatIndex);
        slot.occupant = null;
        placed[studentIndex] = false;
        unplacedCount[0]++;
        seatAssigned[seatIndex] = false;
        RoomContext ctx = contexts.get(slot.roomIndex);
        Slot tail = ctx.occupiedSlots.remove(ctx.occupiedSlots.size() - 1);
        if (tail != slot) {
            throw new IllegalStateException("Corrupted backtrack state at seat " + seatIndex);
        }
        Group g = groupOf(s);
        ctx.groupCount.merge(g, -1, Integer::sum);
        remaining.merge(g, 1, Integer::sum);

        updateCountsAfterChange(seatIndex, studentIndex, slots, orderedStudents, placed,
                seatAssigned, seatCandidates, seatCandidateCount, contexts, config, false);
    }

    /**
     * After a student {@code x} is placed (or unplaced) at {@code seatIndex},
     * refreshes candidate counts incrementally:
     * <ul>
     *   <li>seats within constraint distance of {@code seatIndex}: only
     *       candidates that could actually change legality (bench-mates: same
     *       department/year; gap neighbours: same conflict group) are touched;</li>
     *   <li>every other empty seat just gains/loses {@code x} itself.</li>
     * </ul>
     */
    private static void updateCountsAfterChange(int seatIndex, int studentIndex, List<Slot> slots,
                                                List<Student> orderedStudents, boolean[] placed,
                                                boolean[] seatAssigned, List<BitSet> seatCandidates,
                                                int[] seatCandidateCount, List<RoomContext> contexts,
                                                SeatingConfig config, boolean placing) {
        Student x = orderedStudents.get(studentIndex);
        Slot sSlot = slots.get(seatIndex);
        for (int t = 0; t < slots.size(); t++) {
            if (t == seatIndex || seatAssigned[t]) {
                continue;
            }
            Slot tSlot = slots.get(t);
            if (influences(seatIndex, t, slots, config)) {
                boolean sameBench = sSlot.bench == tSlot.bench;
                boolean bothSingleSeater = sSlot.bench.getBenchType() == ExamRoom.BenchType.SINGLE_SEATER
                        && tSlot.bench.getBenchType() == ExamRoom.BenchType.SINGLE_SEATER;
                int rowDiff = Math.abs(sSlot.bench.getRowIndex() - tSlot.bench.getRowIndex());
                int benchColDiff = Math.abs(sSlot.bench.getColIndex() - tSlot.bench.getColIndex());
                int seatColDiff = Math.abs(physicalCol(sSlot) - physicalCol(tSlot));
                boolean isH = config.horizontalGap() > 0 && rowDiff == 0 && seatColDiff <= config.horizontalGap();
                boolean isV = config.verticalGap() > 0 && benchColDiff == 0 && rowDiff <= config.verticalGap();
                boolean isD = config.diagonalGap() > 0 && rowDiff > 0 && benchColDiff > 0
                        && rowDiff <= config.diagonalGap() && benchColDiff <= config.diagonalGap();
                BitSet bits = seatCandidates.get(t);
                int count = seatCandidateCount[t];
                if (placing) {
                    if (bits.get(studentIndex)) {
                        bits.clear(studentIndex);
                        count--;
                    }
                    for (int i = 0; i < orderedStudents.size(); i++) {
                        if (placed[i] || !bits.get(i)) {
                            continue;
                        }
                        if (illegalDueTo(orderedStudents.get(i), x, sameBench, config, isH, isV, isD, bothSingleSeater)) {
                            bits.clear(i);
                            count--;
                        }
                    }
                } else {
                    if (!bits.get(studentIndex)
                            && canPlace(x, tSlot, contexts.get(tSlot.roomIndex), config,
                                    benchMateOf(t, slots, seatAssigned))) {
                        bits.set(studentIndex);
                        count++;
                    }
                    for (int i = 0; i < orderedStudents.size(); i++) {
                        if (i == studentIndex || placed[i] || bits.get(i)) {
                            continue;
                        }
                        if (illegalDueTo(orderedStudents.get(i), x, sameBench, config, isH, isV, isD, bothSingleSeater)
                                && canPlace(orderedStudents.get(i), tSlot,
                                        contexts.get(tSlot.roomIndex), config,
                                        benchMateOf(t, slots, seatAssigned))) {
                            bits.set(i);
                            count++;
                        }
                    }
                }
                seatCandidateCount[t] = count;
            } else if (placing) {
                if (seatCandidates.get(t).get(studentIndex)) {
                    seatCandidates.get(t).clear(studentIndex);
                    seatCandidateCount[t]--;
                }
            } else {
                // unplacing: the student may become a candidate again for this seat
                if (!seatCandidates.get(t).get(studentIndex)
                        && canPlace(x, tSlot, contexts.get(tSlot.roomIndex), config,
                                benchMateOf(t, slots, seatAssigned))) {
                    seatCandidates.get(t).set(studentIndex);
                    seatCandidateCount[t]++;
                }
            }
        }
    }

    /**
     * Whether placing student {@code x} at a nearby seat makes {@code i}
     * illegal at the influenced seat {@code t}: for the bench-mate seat the
     * dual-seater rule forbids sharing department or year; for gap-adjacent
     * seats the conflict-scope rule forbids same-group pairs.
     */
    private static boolean illegalDueTo(Student i, Student x, boolean sameBench, SeatingConfig config,
                                        boolean isH, boolean isV, boolean isD, boolean bothSingleSeater) {
        if (sameBench) {
            if (config.benchPairingRule() == BenchPairingRule.DIFFERENT_DEPARTMENT_ONLY) {
                return sameDepartment(i, x);
            }
            return sameDepartment(i, x) || sameYear(i, x);
        }
        return isConflict(i, x, config, isH, isV, isD, bothSingleSeater);
    }

    /**
     * Constraint check for one candidate against the current partial layout.
     * {@code benchMate} is the occupant of the other slot on the same bench
     * (null when that slot is empty or the bench is a single-seater).
     */
    private static boolean canPlace(Student candidate, Slot slot, RoomContext ctx, SeatingConfig config,
                                    Student benchMate) {
        // Dual-seater rule: bench-mates must differ in department (and optionally year).
        if (benchMate != null) {
            if (sameDepartment(candidate, benchMate)) {
                return false;
            }
            if (config.benchPairingRule() != BenchPairingRule.DIFFERENT_DEPARTMENT_ONLY
                    && sameYear(candidate, benchMate)) {
                return false;
            }
        }
        // Gap rules against every already-placed student in the same room.
        for (Slot other : ctx.occupiedSlots) {
            if (other.bench == slot.bench) {
                continue;
            }
            int rowDiff = Math.abs(slot.bench.getRowIndex() - other.bench.getRowIndex());
            int benchColDiff = Math.abs(slot.bench.getColIndex() - other.bench.getColIndex());
            int seatColDiff = Math.abs(physicalCol(slot) - physicalCol(other));

            boolean isH = (config.horizontalGap() > 0 && rowDiff == 0 && seatColDiff <= config.horizontalGap());
            boolean isV = (config.verticalGap() > 0 && benchColDiff == 0 && rowDiff <= config.verticalGap());
            boolean isD = (config.diagonalGap() > 0 && rowDiff > 0 && benchColDiff > 0
                    && rowDiff <= config.diagonalGap() && benchColDiff <= config.diagonalGap());

            if (isH || isV || isD) {
                boolean bothSingleSeater = slot.bench.getBenchType() == ExamRoom.BenchType.SINGLE_SEATER
                        && other.bench.getBenchType() == ExamRoom.BenchType.SINGLE_SEATER;
                if (isConflict(candidate, other.occupant, config, isH, isV, isD, bothSingleSeater)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isConflict(Student a, Student b, SeatingConfig config,
                                      boolean isH, boolean isV, boolean isD,
                                      boolean bothSingleSeater) {
        if (a == null || b == null) {
            return false;
        }
        if (config.conflictScope() == ConflictScope.DEPARTMENT_OR_YEAR) {
            return sameDepartment(a, b) || sameYear(a, b);
        }
        if (config.conflictScope() == ConflictScope.DEPARTMENT) {
            return sameDepartment(a, b);
        }
        if (config.conflictScope() == ConflictScope.YEAR) {
            return sameYear(a, b);
        }
        // Horizontal stream isolation: horizontally adjacent desks must not share department!
        if (isH && (config.horizontalDeptIsolation() || bothSingleSeater)) {
            if (sameDepartment(a, b)) {
                return true;
            }
        }
        return conflicts(a, b, config.conflictScope());
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private static void validateRooms(List<ExamRoom> rooms) {
        for (ExamRoom room : rooms) {
            Objects.requireNonNull(room, "rooms must not contain null entries");
            if (room.getRows() <= 0 || room.getColumns() <= 0) {
                throw new IllegalSeatingConfigurationException(
                        "Room '%s' must have rows > 0 and columns > 0 (got %dx%d)"
                                .formatted(room.getHallName(), room.getRows(), room.getColumns()));
            }
            if (room.getBenchType() == null) {
                throw new IllegalSeatingConfigurationException(
                        "Room '%s' must declare a bench type (SINGLE_SEATER | DUAL_SEATER)"
                                .formatted(room.getHallName()));
            }
            if (room.getHallName() == null || room.getHallName().isBlank()) {
                throw new IllegalSeatingConfigurationException("Room hall name must not be blank");
            }
        }
    }

    private static void validateCapacity(List<ExamRoom> rooms, int studentCount) {
        long capacity = 0;
        for (ExamRoom room : rooms) {
            capacity += (long) room.getRows() * room.getColumns() * room.getBenchType().getSeatsPerBench();
        }
        if (capacity < studentCount) {
            throw new InsufficientCapacityException(
                    "Total capacity of %d seats is less than %d students — add rooms or switch to DUAL_SEATER benches"
                            .formatted(capacity, studentCount));
        }
    }

    /**
     * Fast necessary-condition check: a conflict group can occupy at most
     * {@code ceil(rows/(dV+1)) * ceil(cols/(dH+1))} benches per hall (one per
     * bench, since bench-mates must differ in department and year). If any
     * group has more students than the sum of these bounds across all halls,
     * the instance is provably unsatisfiable — fail immediately instead of
     * burning the backtrack budget. (The bound ignores the diagonal gap, so it
     * is conservative: passing it does not guarantee solvability.)
     */
    private static void validateGroupFeasibility(List<ExamRoom> rooms, List<Student> students,
                                                 SeatingConfig config) {
        long totalBound = 0;
        for (ExamRoom room : rooms) {
            int effectiveCols = (config.diagonalGap() > 0 || config.verticalGap() > 0)
                    ? room.getColumns()
                    : room.getColumns() * (room.getBenchType() != null ? room.getBenchType().getSeatsPerBench() : 1);
            int rowsSpan = ceilDiv(room.getRows(), config.verticalGap() + 1);
            int colsSpan = ceilDiv(effectiveCols, config.horizontalGap() + 1);
            totalBound += (long) rowsSpan * colsSpan;
        }

        List<String> offenders = new ArrayList<>();

        if (config.conflictScope() == ConflictScope.DEPARTMENT_OR_YEAR) {
            Map<String, Integer> perDept = new HashMap<>();
            Map<String, Integer> perYear = new HashMap<>();
            for (Student s : students) {
                if (s.getDepartment() != null) perDept.merge(s.getDepartment(), 1, Integer::sum);
                if (s.getYearSession() != null) perYear.merge(s.getYearSession(), 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> e : perDept.entrySet()) {
                if (e.getValue() > totalBound) {
                    offenders.add("department '%s' has %d students but can occupy at most %d seats with current gaps"
                            .formatted(e.getKey(), e.getValue(), totalBound));
                }
            }
            for (Map.Entry<String, Integer> e : perYear.entrySet()) {
                if (e.getValue() > totalBound) {
                    offenders.add("year '%s' has %d students but can occupy at most %d seats with current gaps"
                            .formatted(e.getKey(), e.getValue(), totalBound));
                }
            }
        } else if (config.conflictScope() == ConflictScope.DEPARTMENT) {
            Map<String, Integer> perDept = new HashMap<>();
            for (Student s : students) {
                if (s.getDepartment() != null) perDept.merge(s.getDepartment(), 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> e : perDept.entrySet()) {
                if (e.getValue() > totalBound) {
                    offenders.add("department '%s' has %d students but can occupy at most %d seats with current gaps"
                            .formatted(e.getKey(), e.getValue(), totalBound));
                }
            }
        } else if (config.conflictScope() == ConflictScope.YEAR) {
            Map<String, Integer> perYear = new HashMap<>();
            for (Student s : students) {
                if (s.getYearSession() != null) perYear.merge(s.getYearSession(), 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> e : perYear.entrySet()) {
                if (e.getValue() > totalBound) {
                    offenders.add("year '%s' has %d students but can occupy at most %d seats with current gaps"
                            .formatted(e.getKey(), e.getValue(), totalBound));
                }
            }
        } else {
            // ConflictScope.DEPARTMENT_AND_YEAR (Classmates / Same Exam Paper)
            Map<Group, Integer> perGroup = new HashMap<>();
            for (Student s : students) {
                perGroup.merge(groupOf(s), 1, Integer::sum);
            }
            for (Map.Entry<Group, Integer> e : perGroup.entrySet()) {
                if (e.getValue() > totalBound) {
                    offenders.add("classmate group '%s (%s)' has %d students but can occupy at most %d seats with current gaps"
                            .formatted(e.getKey().department(), e.getKey().yearSession(), e.getValue(), totalBound));
                }
            }
        }

        if (!offenders.isEmpty()) {
            throw new SeatingUnsatisfiableException(
                    "Seating is impossible with the current configuration: " + String.join("; ", offenders)
                            + ". Reduce the group size, add more halls, or relax the strictness level.");
        }
    }

    /**
     * Necessary-condition check for the dual-seater rule: on a DUAL_SEATER
     * bench the two occupants must differ in department <i>and</i> year, so a
     * dual bench can hold at most one student of any given year — and of any
     * given department. Therefore each hall contributes {@code benches} seats
     * (not {@code seats}) toward the capacity of any single year or department
     * value. If any year or department has more students than this combined
     * cap, the instance is provably unsatisfiable.
     */
    private static void validateAttributeBalance(List<ExamRoom> rooms, List<Student> students, SeatingConfig config) {
        boolean hasDual = rooms.stream().anyMatch(r -> r.getBenchType() == ExamRoom.BenchType.DUAL_SEATER);
        if (!hasDual) {
            return;
        }
        Map<String, Integer> perYear = new HashMap<>();
        Map<String, Integer> perDepartment = new HashMap<>();
        for (Student s : students) {
            if (s.getYearSession() != null) {
                perYear.merge(s.getYearSession(), 1, Integer::sum);
            }
            if (s.getDepartment() != null) {
                perDepartment.merge(s.getDepartment(), 1, Integer::sum);
            }
        }
        long cap = 0;
        for (ExamRoom room : rooms) {
            cap += (long) room.getRows() * room.getColumns(); // dual benches hold at most 1 per bench
        }
        List<String> offenders = new ArrayList<>();
        if (config.benchPairingRule() != BenchPairingRule.DIFFERENT_DEPARTMENT_ONLY) {
            for (Map.Entry<String, Integer> e : perYear.entrySet()) {
                if (e.getValue() > cap) {
                    offenders.add("year '%s' has %d students but dual benches can hold at most %d of any one year"
                            .formatted(e.getKey(), e.getValue(), cap));
                }
            }
        }
        for (Map.Entry<String, Integer> e : perDepartment.entrySet()) {
            if (e.getValue() > cap) {
                offenders.add("department '%s' has %d students but dual benches can hold at most %d of any one department"
                        .formatted(e.getKey(), e.getValue(), cap));
            }
        }
        if (!offenders.isEmpty()) {
            throw new SeatingUnsatisfiableException(
                    "Seating is impossible: " + String.join("; ", offenders)
                            + ". Balance the year/department mix or use SINGLE_SEATER halls.");
        }
    }

    /**
     * Normalizes the room's bench grid in place:
     * builds a transient M×N grid (row-major, 1-based) when the collection is
     * missing or incomplete, sorts it, and fills any missing bench types.
     */
    private static void ensureBenchGrid(ExamRoom room) {
        int expected = room.getRows() * room.getColumns();
        List<Bench> benches = room.getBenches();
        if (benches == null || benches.isEmpty() || benches.size() != expected) {
            List<Bench> fresh = new ArrayList<>(expected);
            for (int r = 1; r <= room.getRows(); r++) {
                for (int c = 1; c <= room.getColumns(); c++) {
                    fresh.add(Bench.builder()
                            .room(room)
                            .rowIndex(r)
                            .colIndex(c)
                            .benchType(room.getBenchType())
                            .build());
                }
            }
            if (benches == null) {
                room.setBenches(fresh);
            } else {
                benches.clear();
                benches.addAll(fresh);
            }
            benches = room.getBenches();
        }
        benches.sort(Comparator.comparingInt(Bench::getRowIndex)
                .thenComparingInt(Bench::getColIndex));
        for (Bench bench : benches) {
            if (bench.getBenchType() == null) {
                bench.setBenchType(room.getBenchType());
            }
        }
    }

    private static List<ExamRoom> orderedRooms(List<ExamRoom> rooms) {
        return rooms.stream()
                .sorted(Comparator.comparing(ExamRoom::getHallName, Comparator.nullsLast(String::compareTo))
                        .thenComparingInt(ExamRoom::getTotalBenches)
                        .thenComparingLong(r -> r.getId() == null ? Long.MAX_VALUE : r.getId()))
                .toList();
    }

    private static List<Student> orderedStudents(List<Student> students) {
        return students.stream()
                .sorted(Comparator.comparing(Student::getDepartment, Comparator.nullsLast(String::compareTo))
                        .thenComparing(Student::getYearSession, Comparator.nullsLast(String::compareTo))
                        .thenComparing(Student::getRollNo, Comparator.nullsLast(String::compareTo))
                        .thenComparing(Student::getName, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private static Group groupOf(Student student) {
        return new Group(student.getDepartment(), student.getYearSession());
    }

    private static boolean conflicts(Student a, Student b, ConflictScope scope) {
        boolean sameDept = sameDepartment(a, b);
        boolean sameYear = sameYear(a, b);
        return switch (scope) {
            case DEPARTMENT -> sameDept;
            case YEAR -> sameYear;
            case DEPARTMENT_AND_YEAR -> sameDept && sameYear;
            case DEPARTMENT_OR_YEAR -> sameDept || sameYear;
        };
    }

    private static boolean sameDepartment(Student a, Student b) {
        return Objects.equals(a.getDepartment(), b.getDepartment());
    }

    private static boolean sameYear(Student a, Student b) {
        return Objects.equals(a.getYearSession(), b.getYearSession());
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
