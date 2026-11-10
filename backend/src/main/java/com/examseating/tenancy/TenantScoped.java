package com.examseating.tenancy;

/** Implemented by every persisted row that belongs to one institution. */
public interface TenantScoped {

    Long getInstitutionId();

    void setInstitutionId(Long institutionId);
}
