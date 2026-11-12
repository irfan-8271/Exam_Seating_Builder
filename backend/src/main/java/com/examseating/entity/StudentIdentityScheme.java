package com.examseating.entity;

import com.examseating.tenancy.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-institution dynamic roster schema configuration:
 * <ul>
 *   <li>{@link #attributes} — single or composite attributes uniquely identifying a student</li>
 *   <li>{@link #classmateAttributes} — single or composite attributes defining classmates to separate</li>
 *   <li>{@link #scopeAttribute} — optional attribute used to partition exam rounds (e.g. exam date or paper)</li>
 *   <li>{@link #columnLabels} — recorded human-readable titles for normalized attribute keys</li>
 * </ul>
 */
@Entity
@Table(name = "student_identity_schemes",
       uniqueConstraints = @UniqueConstraint(name = "uk_identity_scheme_institution",
               columnNames = "institution_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class StudentIdentityScheme implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "institution_id")
    private Long institutionId;

    @Size(max = 120)
    @Column(name = "label", length = 120)
    private String label;

    @Size(max = 255)
    @Column(name = "identity_attributes", length = 255)
    private String attributes;

    @Size(max = 64)
    @Column(name = "display_attribute", length = 64)
    private String displayAttribute;

    @Size(max = 255)
    @Column(name = "classmate_attributes", length = 255)
    private String classmateAttributes;

    @Size(max = 64)
    @Column(name = "scope_attribute", length = 64)
    private String scopeAttribute;

    @Size(max = 4000)
    @Column(name = "column_labels", length = 4000)
    private String columnLabels;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = Instant.now();
    }

    public List<String> attributeList() {
        return parseCsvKeys(attributes);
    }

    public void setAttributeList(List<String> list) {
        this.attributes = joinCsvKeys(list);
    }

    public List<String> classmateAttributeList() {
        return parseCsvKeys(classmateAttributes);
    }

    public void setClassmateAttributeList(List<String> list) {
        this.classmateAttributes = joinCsvKeys(list);
    }

    public String describe() {
        List<String> keys = attributeList();
        if (keys.isEmpty()) {
            return label == null || label.isBlank() ? "not configured" : label;
        }
        if (label != null && !label.isBlank()) {
            return label;
        }
        Map<String, String> titles = columnLabelMap();
        return keys.stream()
                .map(k -> titles.getOrDefault(k, k))
                .reduce((a, b) -> a + " + " + b)
                .orElse("not configured");
    }

    public String effectiveDisplayAttribute() {
        List<String> keys = attributeList();
        String display = StudentAttributes.normalize(displayAttribute);
        if (!display.isEmpty() && keys.contains(display)) {
            return display;
        }
        return keys.size() == 1 ? keys.get(0) : null;
    }

    public Map<String, String> columnLabelMap() {
        Map<String, String> map = new LinkedHashMap<>();
        if (columnLabels == null || columnLabels.isBlank()) {
            return map;
        }
        for (String line : columnLabels.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                String k = StudentAttributes.normalize(line.substring(0, eq));
                String v = line.substring(eq + 1).trim();
                if (!k.isEmpty() && !v.isEmpty()) {
                    map.put(k, v);
                }
            }
        }
        return map;
    }

    public void mergeColumnLabels(Map<String, String> additions) {
        if (additions == null || additions.isEmpty()) {
            return;
        }
        Map<String, String> merged = columnLabelMap();
        for (Map.Entry<String, String> entry : additions.entrySet()) {
            String k = StudentAttributes.normalize(entry.getKey());
            String v = entry.getValue() == null ? "" : entry.getValue().replace('\n', ' ').replace('\r', ' ').trim();
            if (!k.isEmpty() && !v.isEmpty()) {
                merged.put(k, v);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            String line = entry.getKey() + "=" + entry.getValue();
            if (sb.length() + line.length() + 1 > 3950) {
                break;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(line);
        }
        this.columnLabels = sb.isEmpty() ? null : sb.toString();
    }

    private static List<String> parseCsvKeys(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return list;
        }
        for (String part : raw.split(",")) {
            String key = StudentAttributes.normalize(part);
            if (!key.isEmpty() && !list.contains(key)) {
                list.add(key);
            }
        }
        return list;
    }

    private static String joinCsvKeys(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        List<String> normalized = new ArrayList<>();
        for (String part : list) {
            String key = StudentAttributes.normalize(part);
            if (!key.isEmpty() && !normalized.contains(key)) {
                normalized.add(key);
            }
        }
        return normalized.isEmpty() ? null : String.join(",", normalized);
    }
}
