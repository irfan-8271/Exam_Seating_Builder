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
import java.util.List;

/**
 * Per-bucket classmate ("cohort") refinement.
 *
 * <p>The institution picks its base classmate attributes once per Excel upload
 * (see {@link StudentIdentityScheme#getClassmateAttributes()}). After that,
 * the roster is grouped into flat buckets derived from those attributes.
 *
 * <p>When a user wants to split one existing bucket further, they configure a
 * cohort <em>inside</em> that bucket by picking one more attribute. This row
 * records that choice so the bucket can be re-derived on demand:
 *
 * <ul>
 *   <li>{@link #scopeAttributes} — the attribute keys whose values identify the
 *       bucket being refined (for example {@code course}, {@code semester}).</li>
 *   <li>{@link #scopeValues} — the matching values for those attributes
 *       (for example {@code B.Tech}, {@code First Semester}).</li>
 *   <li>{@link #groupAttributes} — the extra attribute chosen inside the bucket
 *       (for example {@code department}) that splits it into new flat buckets.</li>
 * </ul>
 *
 * <p>Because buckets are always <em>derived</em> from these rules, configuring
 * a cohort never mutates or deletes a student row: it simply re-derives which
 * bucket each student belongs to. The refined bucket ceases to exist and is
 * replaced by the newly derived flat buckets. Students whose values do not
 * satisfy the new rule stay in the parent bucket (or in Miscellaneous when a
 * required value is missing).</p>
 */
@Entity
@Table(name = "cohort_rules",
       uniqueConstraints = @UniqueConstraint(name = "uk_cohort_rule_institution_scope",
               columnNames = {"institution_id", "scope_signature"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class CohortRule implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "institution_id")
    private Long institutionId;

    /** Comma-separated normalized attribute keys identifying the refined bucket. */
    @Size(max = 255)
    @Column(name = "scope_attributes", length = 255)
    private String scopeAttributes;

    /** Comma-separated values (same order as {@link #scopeAttributes}). */
    @Size(max = 500)
    @Column(name = "scope_values", length = 500)
    private String scopeValues;

    /** Comma-separated normalized attribute key(s) chosen inside the bucket. */
    @Size(max = 255)
    @Column(name = "group_attributes", length = 255)
    private String groupAttributes;

    /** Stable signature used for uniqueness of a bucket within the institution. */
    @Size(max = 255)
    @Column(name = "scope_signature", length = 255)
    private String scopeSignature;

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

    public List<String> scopeAttributeList() {
        return splitCsv(scopeAttributes);
    }

    public void setScopeAttributeList(List<String> list) {
        this.scopeAttributes = joinCsv(list);
    }

    public List<String> scopeValueList() {
        // Scope values may legitimately be empty (the Miscellaneous bucket is
        // identified by a blank value for a missing attribute), so blanks are
        // preserved here rather than dropped.
        return splitCsvKeepingBlanks(scopeValues);
    }

    public void setScopeValueList(List<String> list) {
        this.scopeValues = joinCsvKeepingBlanks(list);
    }

    public List<String> groupAttributeList() {
        return splitCsv(groupAttributes);
    }

    public void setGroupAttributeList(List<String> list) {
        this.groupAttributes = joinCsv(list);
    }

    /**
     * True when this rule refines the bucket identified by the given attribute
     * keys and values. Blank scope values denote the Miscellaneous bucket
     * (students missing a required classification value).
     */
    public boolean matchesScope(List<String> attributeKeys, List<String> values) {
        List<String> storedKeys = scopeAttributeList();
        List<String> storedValues = scopeValueList();
        if (storedKeys.size() != attributeKeys.size() || storedValues.size() != values.size()) {
            return false;
        }
        for (int i = 0; i < storedKeys.size(); i++) {
            if (!storedKeys.get(i).equals(attributeKeys.get(i))) {
                return false;
            }
            String stored = storedValues.get(i) == null ? "" : storedValues.get(i);
            String candidate = values.get(i) == null ? "" : values.get(i);
            if (!stored.equals(candidate)) {
                return false;
            }
        }
        return true;
    }

    /** Builds the stable uniqueness signature for a bucket scope. */
    public static String signatureOf(List<String> attributeKeys, List<String> values) {
        StringBuilder sb = new StringBuilder();
        List<String> keys = attributeKeys == null ? List.of() : attributeKeys;
        List<String> vals = values == null ? List.of() : values;
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(keys.get(i)).append('=');
            if (i < vals.size() && vals.get(i) != null) {
                sb.append(vals.get(i).trim());
            }
        }
        String raw = sb.toString();
        return raw.length() <= 255 ? raw : raw.substring(0, 240) + "#" + Integer.toHexString(raw.hashCode());
    }

    private static List<String> splitCsv(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return list;
        }
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                list.add(value);
            }
        }
        return list;
    }

    private static String joinCsv(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String part : list) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(part.trim());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Splits a comma-separated list but keeps blank entries (used for scope values). */
    private static List<String> splitCsvKeepingBlanks(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null) {
            return list;
        }
        if (raw.isEmpty()) {
            // A single empty value.
            list.add("");
            return list;
        }
        for (String part : raw.split(",", -1)) {
            list.add(part.trim());
        }
        return list;
    }

    /** Joins a list keeping blank entries (used for scope values). */
    private static String joinCsvKeepingBlanks(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String part : list) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(part == null ? "" : part.trim());
        }
        return sb.toString();
    }
}
