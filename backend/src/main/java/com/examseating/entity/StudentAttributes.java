package com.examseating.entity;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Canonical attribute keys for a student, and the rules for resolving one.
 *
 * <p><b>Why this exists.</b> Institutions identify their students in wildly
 * different ways — a roll number, a registration number, an admission number,
 * a batch plus a serial, a registration number plus a department, or a sheet
 * with no identifier column at all. The engine therefore must never ask
 * "what is the roll number?" but "what is the value of <i>this</i>
 * attribute?"</p>
 *
 * <p>Two kinds of attributes exist:</p>
 * <ul>
 *   <li><b>Typed</b> — the six columns the seating engine reasons about:
 *       {@code name}, {@code rollNo}, {@code department}, {@code course},
 *       {@code yearSession}, {@code subjectCode}. They keep their own table
 *       columns so they stay indexable and fast to query.</li>
 *   <li><b>Dynamic</b> — everything else an institution sends (batch number,
 *       section, registration number, house, …), stored in the
 *       {@code student_attributes} map. Nothing about their names is
 *       hard-coded.</li>
 * </ul>
 *
 * <p>Attribute keys are normalized to lower-case alphanumerics, so
 * {@code "Roll No."}, {@code "roll_no"} and {@code "ROLLNO"} all address the
 * same attribute. Resolution is by key, never by position.</p>
 */
public final class StudentAttributes {

    /** The student's display name. */
    public static final String NAME = "name";
    /** The institution's identifier — roll / registration / admission number. */
    public static final String ROLL_NO = "rollno";
    /** Free-form classification attribute (department, stream, class, ...). */
    public static final String DEPARTMENT = "department";
    /** Free-form classification attribute (course, programme, ...). */
    public static final String COURSE = "course";
    /** Free-form classification attribute (year, semester, section, ...). */
    public static final String YEAR_SESSION = "yearsession";
    /** Optional subject/paper code. */
    public static final String SUBJECT_CODE = "subjectcode";

    /** The typed attributes that have their own column on the {@code students} table. */
    private static final Map<String, String> TYPED = new LinkedHashMap<>();

    static {
        TYPED.put(NAME, "name");
        TYPED.put(ROLL_NO, "rollNo");
        TYPED.put(DEPARTMENT, "department");
        TYPED.put(COURSE, "course");
        TYPED.put(YEAR_SESSION, "yearSession");
        TYPED.put(SUBJECT_CODE, "subjectCode");
    }

    /**
     * Header spellings that are recognised as <i>the</i> identifier column when
     * no identity scheme is configured and none is supplied with the upload.
     * This is only a fallback — the whole point of the identity scheme is that
     * the institution decides.
     */
    private static final Set<String> IDENTIFIER_HINTS = Set.of(
            "rollno", "rollnumber", "regno", "regnumber", "registrationno",
            "registrationnumber", "admissionno", "admissionnumber", "enrollmentno",
            "enrolmentno", "enrollmentnumber", "enrolmentnumber", "studentid",
            "usn", "indexno", "candidatecode", "candidate", "seatno");

    private StudentAttributes() {
    }

    /**
     * Normalizes a raw attribute name (a spreadsheet header, a JSON key, a
     * configured attribute) to its canonical form: lower-case, alphanumerics
     * only. {@code "Roll No."}, {@code "roll_no"} and {@code "ROLLNO"} all
     * become {@code "rollno"}.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Whether the key looks like an identifier column (used only as a fallback). */
    public static boolean looksLikeIdentifier(String key) {
        return IDENTIFIER_HINTS.contains(normalize(key));
    }

    /**
     * Resolves the value of one attribute for one student. Typed attributes
     * come from their own column, everything else from the dynamic attribute
     * map. Returns {@code null} when the student does not have the attribute.
     */
    public static String value(Student student, String key) {
        if (student == null) {
            return null;
        }
        String normalized = normalize(key);
        if (normalized.isEmpty()) {
            return null;
        }
        switch (normalized) {
            case NAME -> {
                return student.getName();
            }
            case ROLL_NO -> {
                return student.getRollNo();
            }
            case DEPARTMENT -> {
                return student.getDepartment();
            }
            case COURSE -> {
                return student.getCourse();
            }
            case YEAR_SESSION -> {
                return student.getYearSession();
            }
            case SUBJECT_CODE -> {
                return student.getSubjectCode();
            }
            default -> {
                return student.getAttributes().get(normalized);
            }
        }
    }

    /**
     * The label to show for a student in a plan, an export or an error
     * message: the institution's identifier when there is one, otherwise the
     * first component of the composite identity, otherwise the name.
     *
     * <p>This keeps the existing output (a roll number in every column)
     * intact for the common case while staying meaningful for institutions
     * that do not use roll numbers.</p>
     */
    public static String displayLabel(Student student) {
        if (student == null) {
            return "";
        }
        if (notBlank(student.getRollNo())) {
            return student.getRollNo().trim();
        }
        String identity = student.getIdentityKey();
        if (notBlank(identity)) {
            int separator = identity.indexOf('|');
            return separator > 0 ? identity.substring(0, separator) : identity;
        }
        return student.getName() == null ? "" : student.getName();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
