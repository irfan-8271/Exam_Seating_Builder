package com.examseating.entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Uniform access and formatting utilities for {@link Student} dynamic attributes.
 *
 * <p>Only {@code "name"} maps to {@link Student#getName()}. Every other
 * institutional attribute lives uniformly inside {@link Student#getAttributes()}
 * under a normalized key (lowercase ASCII letters and digits only, so
 * {@code "Roll No"}, {@code "roll_no"}, and {@code "ROLLNO"} resolve identically).</p>
 */
public final class StudentAttributes {

    public static final String NAME = "name";

    private static final Set<String> NAME_HEADERS = Set.of(
            "name", "studentname", "candidatename", "fullname", "examineename", "pupilname"
    );

    private static final List<String> IDENTIFIER_HINTS = List.of(
            "roll", "reg", "adm", "uid", "enrollment", "enrolment",
            "hallticket", "seatno", "indexno", "applicationno", "studentid", "candidateid"
    );

    private static final Set<String> SCOPE_HINTS = Set.of(
            "subject", "subjectcode", "paper", "papercode", "examdate", "date", "shift", "slot"
    );

    private StudentAttributes() {
    }

    /** Normalizes any column heading or attribute key to lowercase alphanumeric form. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** True when a spreadsheet header represents the student's display name. */
    public static boolean looksLikeNameHeader(String raw) {
        return NAME_HEADERS.contains(normalize(raw));
    }

    /** Heuristic ranking hint for candidate unique-ID columns during auto-discovery. */
    public static boolean looksLikeIdentifier(String raw) {
        String key = normalize(raw);
        if (key.isEmpty() || NAME.equals(key)) {
            return false;
        }
        if ("id".equals(key) || key.endsWith("id") || key.endsWith("no") || key.endsWith("number")) {
            return true;
        }
        for (String hint : IDENTIFIER_HINTS) {
            if (key.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** Heuristic hint for exam-scope columns (exam date, paper, shift) vs academic cohorts. */
    public static boolean looksLikeScopeField(String raw) {
        return SCOPE_HINTS.contains(normalize(raw));
    }

    /** Reads the value of {@code attribute} from {@code student}, or {@code null} if absent/blank. */
    public static String value(Student student, String attribute) {
        if (student == null || attribute == null) {
            return null;
        }
        String key = normalize(attribute);
        if (key.isEmpty()) {
            return null;
        }
        if (NAME.equals(key)) {
            return notBlank(student.getName()) ? student.getName().trim() : null;
        }
        Map<String, String> map = student.getAttributes();
        if (map == null || map.isEmpty()) {
            return null;
        }
        String direct = map.get(key);
        if (notBlank(direct)) {
            return direct.trim();
        }
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (key.equals(normalize(entry.getKey())) && notBlank(entry.getValue())) {
                return entry.getValue().trim();
            }
        }
        return null;
    }

    /** Writes or clears {@code attribute} on {@code student}. */
    public static void setValue(Student student, String attribute, String rawValue) {
        if (student == null || attribute == null) {
            return;
        }
        String key = normalize(attribute);
        if (key.isEmpty()) {
            return;
        }
        String trimmed = rawValue == null ? null : rawValue.trim().replaceAll("\\s+", " ");
        if (NAME.equals(key)) {
            student.setName(trimmed);
            return;
        }
        Map<String, String> map = student.ensureAttributes();
        if (trimmed == null || trimmed.isEmpty()) {
            map.remove(key);
        } else {
            map.put(key, trimmed);
        }
    }

    /** Returns every non-blank dynamic attribute carried by {@code student} in insertion order. */
    public static Map<String, String> allPopulatedAttributes(Student student) {
        Map<String, String> out = new LinkedHashMap<>();
        if (student == null || student.getAttributes() == null) {
            return out;
        }
        for (Map.Entry<String, String> entry : student.getAttributes().entrySet()) {
            String k = normalize(entry.getKey());
            if (!k.isEmpty() && !NAME.equals(k) && notBlank(entry.getValue())) {
                out.put(k, entry.getValue().trim());
            }
        }
        return out;
    }

    /** Human-readable student identifier honoring the institution's {@link StudentIdentityScheme}. */
    public static String displayLabel(Student student, StudentIdentityScheme scheme) {
        if (student == null) {
            return "";
        }
        if (scheme != null) {
            String explicit = scheme.effectiveDisplayAttribute();
            if (notBlank(explicit)) {
                String v = value(student, explicit);
                if (notBlank(v)) {
                    return v;
                }
            }
            List<String> keys = scheme.attributeList();
            if (!keys.isEmpty()) {
                List<String> parts = new ArrayList<>(keys.size());
                for (String k : keys) {
                    String v = value(student, k);
                    if (notBlank(v)) {
                        parts.add(v);
                    }
                }
                if (!parts.isEmpty()) {
                    return String.join(" · ", parts);
                }
            }
        }
        return displayLabel(student);
    }

    /** Human-readable student identifier when no scheme reference is passed. */
    public static String displayLabel(Student student) {
        if (student == null) {
            return "";
        }
        String identity = student.getIdentityKey();
        if (notBlank(identity)) {
            return identity.trim().replace("|", " · ");
        }
        Map<String, String> attrs = allPopulatedAttributes(student);
        for (Map.Entry<String, String> entry : attrs.entrySet()) {
            if (looksLikeIdentifier(entry.getKey())) {
                return entry.getValue();
            }
        }
        return student.getName() == null ? "" : student.getName();
    }

    /** Human-readable classmate / cohort label built from active group attributes. */
    public static String cohortLabel(Student student, List<String> classmateAttributes) {
        if (student == null) {
            return "";
        }
        if (classmateAttributes != null && !classmateAttributes.isEmpty()) {
            List<String> parts = new ArrayList<>(classmateAttributes.size());
            for (String key : classmateAttributes) {
                String v = value(student, key);
                if (notBlank(v)) {
                    parts.add(v);
                }
            }
            if (!parts.isEmpty()) {
                return String.join(" · ", parts);
            }
        }
        List<String> fallback = new ArrayList<>();
        for (Map.Entry<String, String> entry : allPopulatedAttributes(student).entrySet()) {
            if (!looksLikeIdentifier(entry.getKey()) && !looksLikeScopeField(entry.getKey())) {
                fallback.add(entry.getValue());
                if (fallback.size() == 2) {
                    break;
                }
            }
        }
        return String.join(" · ", fallback);
    }

    /** Readable default column title when no original spreadsheet heading was recorded. */
    public static String defaultTitle(String key) {
        String normalized = normalize(key);
        if (normalized.isEmpty()) {
            return "";
        }
        if (NAME.equals(normalized)) {
            return "Student Name";
        }
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
