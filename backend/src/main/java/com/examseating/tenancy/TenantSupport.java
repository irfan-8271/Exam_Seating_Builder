package com.examseating.tenancy;

import com.examseating.entity.Institution;
import com.examseating.exception.ResourceNotFoundException;
import com.examseating.repository.InstitutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * The single place where tenant ownership is decided.
 *
 * <p>Rows written in single-tenant mode have a {@code null} institution id; they
 * are treated as belonging to the default institution so existing databases keep
 * working. Every read is filtered through {@link #visible(List)} and every new
 * row is stamped by {@link #stamp(Object)}. An id from another institution is
 * deliberately reported as 404 rather than 403 so its existence is not leaked.</p>
 */
@Component
@RequiredArgsConstructor
public class TenantSupport {

    private final InstitutionRepository institutionRepository;

    @Value("${app.tenancy.default-slug:default}")
    private String defaultSlug;

    public Long currentId() {
        Long id = TenantContext.get();
        return id != null ? id : defaultId();
    }

    public Long defaultId() {
        return institutionRepository.findBySlug(defaultSlug)
                .map(Institution::getId)
                .orElseThrow(() -> new IllegalStateException(
                        "Default institution '" + defaultSlug + "' has not been seeded"));
    }

    public boolean isDefaultTenant() {
        return Objects.equals(currentId(), defaultId());
    }

    public boolean owns(TenantScoped entity) {
        if (entity == null) {
            return false;
        }
        Long owner = entity.getInstitutionId();
        if (owner == null) {
            return isDefaultTenant();
        }
        return Objects.equals(owner, currentId());
    }

    public <T extends TenantScoped> List<T> visible(List<T> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream().filter(this::owns).toList();
    }

    public <T extends TenantScoped> T stamp(T entity) {
        entity.setInstitutionId(currentId());
        return entity;
    }

    public void require(TenantScoped entity, String label) {
        if (!owns(entity)) {
            throw new ResourceNotFoundException(label + " not found");
        }
    }
}
