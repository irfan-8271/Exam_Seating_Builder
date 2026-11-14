package com.examseating.service;

import com.examseating.dto.CohortView;
import com.examseating.entity.CohortRule;
import com.examseating.entity.Student;
import com.examseating.entity.StudentAttributes;
import com.examseating.repository.CohortRuleRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.tenancy.TenantSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Derives the institution's flat classmate buckets and applies bucket-level
 * "Configure cohort" refinements.
 *
 * <p>The base classmate attributes are chosen once per Excel upload and stored
 * on {@link com.examseating.entity.StudentIdentityScheme}. Buckets are never
 * persisted — they are always derived from the live student rows so that
 * editing a student, or refining a bucket, can never lose or duplicate a
 * student record.</p>
 *
 * <p>A refinement is stored as a {@link CohortRule} that names the bucket it
 * refines (by attribute keys and values) and the extra attribute chosen
 * inside that bucket. Refining a bucket therefore <em>replaces</em> that
 * bucket with newly derived flat buckets; students that do not satisfy the new
 * rule stay in the parent bucket, and students missing the new attribute stay
 * in Miscellaneous.</p>
 */
@Service
public class CohortService {

    public static final String MISCELLANEOUS = "Miscellaneous";

    private final CohortRuleRepository ruleRepository;
    private final StudentRepository studentRepository;
    private final StudentIdentityService identityService;
    private final TenantSupport tenant;

    public CohortService(CohortRuleRepository ruleRepository,
                         StudentRepository studentRepository,
                         StudentIdentityService identityService,
                         TenantSupport tenant) {
        this.ruleRepository = ruleRepository;
        this.studentRepository = studentRepository;
        this.identityService = identityService;
        this.tenant = tenant;
    }

    /** The institution's base classmate attributes (chosen at upload time). */
    @Transactional(readOnly = true)
    public List<String> baseClassmateAttributes() {
        List<String> configured = identityService.currentClassmateAttributes();
        if (!configured.isEmpty()) {
            return configured;
        }
        return identityService.inspectSchema().suggestedClassmateGroups().stream()
                .findFirst()
                .map(group -> group.attributes())
                .orElseGet(List::of);
    }

    @Transactional(readOnly = true)
    public List<Student> visibleStudents() {
        return tenant.visible(studentRepository.findAll());
    }

    /**
     * Derives the flat buckets for the given students.
     *
     * <p>Students are grouped by the base classmate attributes. Any bucket that
     * carries a configured refinement is then replaced by buckets derived from
     * the refinement's extra attribute. Students missing any base classification
     * value land in Miscellaneous; a refinement configured on Miscellaneous
     * pulls out only the students that actually carry the chosen attribute.</p>
     */
    @Transactional(readOnly = true)
    public List<CohortView> deriveBuckets(List<Student> students, List<String> baseAttributes) {
        List<CohortRule> rules = ruleRepository.findByInstitutionId(tenant.currentId());

        // 1. Group students by their base-attribute value tuple.
        Map<List<String>, List<Student>> grouped = new LinkedHashMap<>();
        for (Student student : students) {
            grouped.computeIfAbsent(scopeOf(student, baseAttributes), k -> new ArrayList<>()).add(student);
        }

        // 2. Replace any refined bucket with its derived child buckets.
        Map<List<String>, List<Student>> finalBuckets = new LinkedHashMap<>();
        Map<List<String>, String> refinementAttributeByBucket = new LinkedHashMap<>();
        Map<List<String>, String> refinedChildLabels = new LinkedHashMap<>();

        for (Map.Entry<List<String>, List<Student>> entry : grouped.entrySet()) {
            List<String> scopeValues = entry.getKey();
            List<Student> members = entry.getValue();
            CohortRule rule = findRule(rules, baseAttributes, scopeValues);

            if (rule == null || rule.groupAttributeList().isEmpty()) {
                finalBuckets.put(scopeValues, members);
                continue;
            }

            String attribute = rule.groupAttributeList().get(0);
            Map<List<String>, List<Student>> children = new LinkedHashMap<>();
            for (Student student : members) {
                List<String> childValues = new ArrayList<>(scopeValues);
                String value = StudentAttributes.value(student, attribute);
                childValues.add(value == null ? "" : value.trim());
                children.computeIfAbsent(childValues, k -> new ArrayList<>()).add(student);
            }
            for (Map.Entry<List<String>, List<Student>> child : children.entrySet()) {
                List<String> childKey = child.getKey();
                finalBuckets.put(childKey, child.getValue());
                refinementAttributeByBucket.put(childKey, attribute);
                // Remember which base values were present, so a child of a
                // Miscellaneous bucket (missing a base value) can still be labelled
                // by the values it actually carries.
                refinedChildLabels.put(childKey, childLabel(attribute, scopeValues, childKey));
            }
        }

        // 3. Build the flat views.
        List<CohortView> views = new ArrayList<>();
        for (Map.Entry<List<String>, List<Student>> entry : finalBuckets.entrySet()) {
            List<String> values = entry.getKey();
            List<Student> members = entry.getValue();
            if (members.isEmpty()) {
                continue;
            }

            boolean refined = refinementAttributeByBucket.containsKey(values);
            String refinementAttribute = refinementAttributeByBucket.get(values);

            List<String> attributes = new ArrayList<>(baseAttributes);
            List<String> displayValues = new ArrayList<>(values);
            if (refined) {
                attributes.add(refinementAttribute);
            } else {
                displayValues = new ArrayList<>(values.subList(0, baseAttributes.size()));
            }

            boolean hasMissing = displayValues.stream().anyMatch(v -> v == null || v.isBlank());
            String label;
            if (refined) {
                // A refined bucket is named by the values it actually carries, so a
                // child of Miscellaneous (missing a base value) is still named after
                // the value it does have.
                label = refinedChildLabels.getOrDefault(values, labelFor(attributes, displayValues, hasMissing));
            } else {
                label = labelFor(attributes, displayValues, hasMissing);
            }

            List<String> missing = new ArrayList<>();
            if (hasMissing) {
                for (int i = 0; i < baseAttributes.size(); i++) {
                    if (displayValues.get(i).isEmpty()) {
                        missing.add(baseAttributes.get(i));
                    }
                }
            }

            views.add(new CohortView(label, displayValues, attributes, members.size(),
                    refined, refinementAttribute, missing));
        }

        views.sort(Comparator
                .comparing((CohortView c) -> !MISCELLANEOUS.equalsIgnoreCase(c.label()))
                .thenComparing(c -> c.label().toLowerCase(Locale.ROOT)));
        return views;
    }

    /** Preview of the buckets that would result from configuring a cohort inside a bucket. */
    @Transactional(readOnly = true)
    public List<CohortView> previewRefinement(List<Student> students,
                                               List<String> baseAttributes,
                                               List<String> scopeValues,
                                               String newAttribute) {
        String normalized = StudentAttributes.normalize(newAttribute);
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException("Choose an attribute to configure the cohort with.");
        }
        List<Student> scoped = students.stream()
                .filter(s -> inScope(s, baseAttributes, scopeValues))
                .toList();

        Map<String, List<Student>> children = new LinkedHashMap<>();
        for (Student student : scoped) {
            String value = StudentAttributes.value(student, normalized);
            String key = value == null ? "" : value.trim();
            children.computeIfAbsent(key, k -> new ArrayList<>()).add(student);
        }
        List<CohortView> views = new ArrayList<>();
        for (Map.Entry<String, List<Student>> entry : children.entrySet()) {
            String value = entry.getKey();
            List<String> attributes = new ArrayList<>(baseAttributes);
            attributes.add(normalized);
            List<String> values = new ArrayList<>(scopeValues);
            values.add(value);
            List<String> childKey = new ArrayList<>(scopeValues);
            childKey.add(value);
            String label = childLabel(normalized, scopeValues, childKey);
            views.add(new CohortView(label, values, attributes, entry.getValue().size(),
                    false, normalized, List.of()));
        }
        views.sort(Comparator.comparing(c -> c.label().toLowerCase(Locale.ROOT)));
        return views;
    }

    /**
     * Configures a cohort inside one bucket, replacing that bucket with the newly
     * derived flat buckets. No student row is modified.
     */
    @Transactional
    public void configureCohort(List<String> scopeAttributeKeys,
                                List<String> scopeValues,
                                String newAttribute) {
        String normalized = StudentAttributes.normalize(newAttribute);
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException("Choose an attribute to configure the cohort with.");
        }
        List<String> keys = new ArrayList<>();
        for (String key : scopeAttributeKeys) {
            String normalizedKey = StudentAttributes.normalize(key);
            if (normalizedKey != null && !normalizedKey.isBlank()) {
                keys.add(normalizedKey);
            }
        }
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("The bucket to refine could not be identified.");
        }
        String signature = CohortRule.signatureOf(keys, scopeValues);
        CohortRule rule = ruleRepository
                .findByInstitutionIdAndScopeSignature(tenant.currentId(), signature)
                .orElseGet(CohortRule::new);
        rule.setInstitutionId(tenant.currentId());
        rule.setScopeAttributeList(keys);
        rule.setScopeValueList(scopeValues == null ? List.of() : scopeValues);
        rule.setGroupAttributeList(List.of(normalized));
        rule.setScopeSignature(signature);
        ruleRepository.save(rule);
    }

    /** Removes a bucket-level refinement, restoring the bucket to its undivided form. */
    @Transactional
    public void removeCohort(String signature) {
        ruleRepository.findByInstitutionIdAndScopeSignature(tenant.currentId(), signature)
                .ifPresent(ruleRepository::delete);
    }

    @Transactional(readOnly = true)
    public List<CohortRule> rules() {
        return ruleRepository.findByInstitutionId(tenant.currentId());
    }

    @Transactional
    public void clearRules() {
        ruleRepository.deleteByInstitutionId(tenant.currentId());
    }

    /** Finds the refinement registered for a bucket scope, if any. */
    private CohortRule findRule(List<CohortRule> rules, List<String> baseAttributes, List<String> scopeValues) {
        for (CohortRule rule : rules) {
            if (rule.matchesScope(baseAttributes, scopeValues)) {
                return rule;
            }
        }
        return null;
    }

    /** The base-attribute value tuple identifying a student's bucket. */
    public List<String> scopeOf(Student student, List<String> baseAttributes) {
        List<String> values = new ArrayList<>(baseAttributes.size());
        for (String attribute : baseAttributes) {
            String value = StudentAttributes.value(student, attribute);
            values.add(value == null ? "" : value.trim());
        }
        return values;
    }

    /** True when a student belongs to the bucket identified by the scope values. */
    public boolean inScope(Student student, List<String> baseAttributes, List<String> scopeValues) {
        return scopeOf(student, baseAttributes).equals(scopeValues == null ? List.of() : scopeValues);
    }

    /** Builds the display label for a bucket, e.g. "B.Tech · First Semester". */
    public String labelFor(List<String> attributes, List<String> values, boolean hasMissing) {
        if (hasMissing) {
            return MISCELLANEOUS;
        }
        List<String> parts = new ArrayList<>();
        for (String iValue : values) {
            if (iValue == null || iValue.isBlank()) {
                return MISCELLANEOUS;
            }
            parts.add(iValue);
        }
        return parts.isEmpty() ? MISCELLANEOUS : String.join(" · ", parts);
    }

    /**
     * Label for a bucket that was created by refining another bucket.
     *
     * <p>The base values that the parent bucket carried are kept, and the new
     * attribute's value is appended. When the parent bucket was Miscellaneous
     * (i.e. it had a missing base value), the missing value is simply left out,
     * so a student that has a Department but no Semester is named after the
     * Department it does carry instead of being hidden in Miscellaneous.</p>
     */
    private String childLabel(String attribute, List<String> parentScopeValues, List<String> childValues) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < parentScopeValues.size(); i++) {
            String value = parentScopeValues.get(i);
            if (value != null && !value.isBlank()) {
                parts.add(value);
            }
        }
        String refinementValue = childValues.get(childValues.size() - 1);
        if (refinementValue != null && !refinementValue.isBlank()) {
            parts.add(refinementValue);
        }
        if (parts.isEmpty()) {
            return MISCELLANEOUS;
        }
        return String.join(" · ", parts);
    }

    /** Attribute keys available for configuring a cohort inside a bucket. */
    @Transactional(readOnly = true)
    public List<String> candidateAttributes(List<String> baseAttributes) {
        List<Student> students = visibleStudents();
        Set<String> keys = new LinkedHashSet<>();
        for (Student student : students) {
            keys.addAll(StudentAttributes.allPopulatedAttributes(student).keySet());
        }
        keys.removeAll(baseAttributes);
        keys.remove(StudentAttributes.NAME);
        return new ArrayList<>(keys);
    }

    /** Students belonging to a bucket scope. */
    @Transactional(readOnly = true)
    /**
     * Students belonging to a bucket scope.
     *
     * <p>Handles both a plain base bucket and a bucket created by refining
     * another one: when the scope carries one more value than the base
     * attribute set, the extra value is matched against the refinement
     * attribute.</p>
     */
    public List<Student> membersOf(List<String> baseAttributes, List<String> scopeValues) {
        List<String> values = scopeValues == null ? List.of() : scopeValues;
        List<String> base = baseAttributes == null ? List.of() : baseAttributes;

        if (values.size() <= base.size()) {
            return visibleStudents().stream()
                    .filter(s -> inScope(s, base, values))
                    .toList();
        }

        // A refined bucket: the base values plus one refinement attribute value.
        String refinementAttribute = null;
        for (CohortRule rule : rules()) {
            List<String> groupAttributes = rule.groupAttributeList();
            if (!groupAttributes.isEmpty() && rule.matchesScope(base, values.subList(0, base.size()))) {
                refinementAttribute = groupAttributes.get(0);
                break;
            }
        }
        if (refinementAttribute == null) {
            return visibleStudents().stream()
                    .filter(s -> inScope(s, base, values.subList(0, base.size())))
                    .toList();
        }
        String expected = values.get(values.size() - 1);
        List<String> baseScope = values.subList(0, base.size());
        String refinement = refinementAttribute;
        return visibleStudents().stream()
                .filter(s -> inScope(s, base, baseScope))
                .filter(s -> {
                    String value = StudentAttributes.value(s, refinement);
                    String actual = value == null ? "" : value.trim();
                    String want = expected == null ? "" : expected.trim();
                    return actual.equals(want);
                })
                .toList();
    }
}
