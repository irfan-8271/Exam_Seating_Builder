package com.examseating.entity;

import com.examseating.tenancy.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A candidate / student registered in an institution's roster.
 *
 * <p><b>Pure dynamic schema:</b> no institution-specific academic hierarchy
 * columns (such as roll number, department, course, or year) are hardcoded on
 * this entity. Every institution supplies its own fields (e.g. {@code admissionno},
 * {@code grade}, {@code division}, {@code wing}, {@code stream}, {@code batch},
 * {@code department}, {@code examdate}, {@code paper}) inside {@link #attributes},
 * persisted as a single native JSON column so one student is always one row.</p>
 *
 * <p>Which attribute (or multi-attribute combination) uniquely identifies a
 * student and which attribute(s) define classmates who must be separated are
 * configured per institution via {@link StudentIdentityScheme} and folded into
 * {@link #identityKey}, enforced unique per institution at the database level.</p>
 */
@Entity
@Table(name = "students",
       uniqueConstraints = @UniqueConstraint(name = "uk_students_identity",
               columnNames = {"institution_id", "identity_key"}),
       indexes = @Index(name = "idx_students_institution", columnList = "institution_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Student implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /** Owning institution; null on rows written in open/default-tenant mode. */
    @Column(name = "institution_id")
    private Long institutionId;

    @NotBlank(message = "Name is required")
    @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * Canonical single- or multi-attribute identity key built from the
     * institution's {@link StudentIdentityScheme}. Unique per institution.
     */
    @Size(max = 255)
    @Column(name = "identity_key", length = 255)
    private String identityKey;

    /**
     * All institution-defined student fields (normalized alphanumeric lowercase
     * keys → trimmed non-blank values), stored in a native JSON column.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes")
    @Builder.Default
    private Map<String, String> attributes = new LinkedHashMap<>();

    /** Convenience accessor returning a non-null mutable attribute map. */
    public Map<String, String> ensureAttributes() {
        if (attributes == null) {
            attributes = new LinkedHashMap<>();
        }
        return attributes;
    }

    /** Reads one normalized attribute value, or {@code null} when absent. */
    public String attribute(String key) {
        if (attributes == null || key == null) {
            return null;
        }
        return attributes.get(StudentAttributes.normalize(key));
    }

    @PrePersist
    @PreUpdate
    void normalizeState() {
        if (name != null) {
            name = name.trim().replaceAll("\\s+", " ");
        }
        identityKey = blankToNull(identityKey);
        if (attributes == null) {
            attributes = new LinkedHashMap<>();
        } else {
            Map<String, String> cleaned = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                String k = StudentAttributes.normalize(entry.getKey());
                String v = blankToNull(entry.getValue());
                if (!k.isEmpty() && !StudentAttributes.NAME.equals(k) && v != null) {
                    cleaned.put(k, v);
                }
            }
            attributes = cleaned;
        }
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim().replaceAll("\\s+", " ");
    }
}
