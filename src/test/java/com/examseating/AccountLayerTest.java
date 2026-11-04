package com.examseating;

import com.examseating.config.AccountSeeder;
import com.examseating.dto.AccountDtos.UsageView;
import com.examseating.entity.Institution;
import com.examseating.entity.Plan;
import com.examseating.entity.Student;
import com.examseating.exception.QuotaExceededException;
import com.examseating.exception.UnauthorizedException;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.GenerationUsageRepository;
import com.examseating.repository.InstitutionRepository;
import com.examseating.repository.PlanRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.repository.StoredFileRepository;
import com.examseating.repository.SubscriptionRepository;
import com.examseating.service.AccountService;
import com.examseating.service.FileStorageService;
import com.examseating.service.QuotaService;
import com.examseating.tenancy.TenantContext;
import com.examseating.tenancy.TenantSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the institutional-account / tenant / quota layer. No Spring
 * context, database or servlet container is required: repositories are JDK dynamic
 * proxies that return deterministic values.
 */
class AccountLayerTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** Creates a repository proxy with per-method overrides. */
    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, Map<String, Object> overrides) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "fake:" + type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            if (overrides.containsKey(method.getName())) {
                return overrides.get(method.getName());
            }
            Class<?> result = method.getReturnType();
            if (result == Optional.class) return Optional.empty();
            if (result == List.class) return List.of();
            if (result == long.class) return 0L;
            if (result == int.class) return 0;
            if (result == boolean.class) return false;
            return null;
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static InstitutionRepository institutions() {
        return fake(InstitutionRepository.class, Map.of(
                "findBySlug", Optional.of(Institution.builder()
                        .id(7L).name("Default").slug("default")
                        .status(Institution.Status.ACTIVE).build()),
                "existsBySlug", true));
    }

    private static Plan freePlan() {
        return Plan.builder().code("FREE").name("Free").monthlyPrice(new BigDecimal("0.00"))
                .maxStudents(50).maxRooms(3).maxGenerationsPerMonth(5)
                .storageLimitMb(10).maxFileSizeMb(2).seatsPerGeneration(100)
                .sortOrder(10).active(true).build();
    }

    private static QuotaService quotas(TenantSupport tenant, long students, long generations) {
        return new QuotaService(tenant,
                fake(PlanRepository.class, Map.of("findByCode", Optional.of(freePlan()))),
                fake(SubscriptionRepository.class, Map.of()),
                fake(StudentRepository.class, Map.of(
                        "countByInstitutionId", students, "countByInstitutionIdIsNull", 0L)),
                fake(ExamRoomRepository.class, Map.of(
                        "countByInstitutionId", 0L, "countByInstitutionIdIsNull", 0L)),
                fake(GenerationUsageRepository.class, Map.of(
                        "countByInstitutionIdAndCreatedAtBetween", generations)),
                fake(StoredFileRepository.class, Map.of(
                        "sumSizeByInstitutionId", 0L, "sumSizeByInstitutionIdIsNull", 0L)));
    }

    @Test
    void tenantContextIsScopedToTheCurrentThread() {
        TenantContext.clear();
        assertNotNull(TenantContext.class);
        TenantContext.set(7L);
        assertEquals(7L, TenantContext.require().longValue());
        TenantContext.clear();
        assertThrows(IllegalStateException.class, TenantContext::require);
    }

    @Test
    void bearerTokenParsingAcceptsBothFormsAndRejectsMissing() {
        assertEquals("abc", AccountService.tokenFrom("Bearer abc"));
        assertEquals("xyz", AccountService.tokenFrom("xyz"));
        assertThrows(UnauthorizedException.class, () -> AccountService.tokenFrom(null));
    }

    @Test
    void legacyRowsBelongToTheDefaultInstitutionAndForeignRowsAreHidden() {
        TenantContext.set(7L);
        TenantSupport tenant = new TenantSupport(institutions());
        assertEquals(7L, tenant.defaultId().longValue());
        assertTrue(tenant.isDefaultTenant());
        assertTrue(tenant.owns(new Student()));       // null institutionId = legacy
        Student foreign = new Student();
        foreign.setInstitutionId(9L);
        assertFalse(tenant.owns(foreign));
        assertTrue(tenant.visible(List.of(foreign)).isEmpty());
        Student fresh = new Student();
        tenant.stamp(fresh);
        assertEquals(7L, fresh.getInstitutionId().longValue());
    }

    @Test
    void planLimitsAreEnforcedBeforeWorkIsDone() {
        TenantContext.set(7L);
        TenantSupport tenant = new TenantSupport(institutions());
        QuotaService full = quotas(tenant, 50L, 5L);
        UsageView usage = full.usage();
        assertEquals(50L, usage.studentsUsed());
        assertEquals(5L, usage.generationsUsed());
        assertThrows(QuotaExceededException.class, () -> full.checkStudents(1));
        assertThrows(QuotaExceededException.class, () -> full.checkGeneration(10));
        QuotaService roomy = quotas(tenant, 0L, 0L);
        assertThrows(QuotaExceededException.class, () -> roomy.checkGeneration(101));
        assertThrows(QuotaExceededException.class, () -> roomy.checkRooms(4));
    }

    @Test
    void storageAndDowngradeChecksUsePlanLimits() {
        TenantContext.set(7L);
        QuotaService service = quotas(new TenantSupport(institutions()), 0L, 0L);
        assertThrows(QuotaExceededException.class,
                () -> service.checkStorage(0L, 3L * 1024 * 1024));
        service.checkStorage(0L, 1024L);
        Plan enterprise = Plan.builder().code("ENTERPRISE").maxStudents(100000)
                .maxRooms(1000).maxGenerationsPerMonth(10000)
                .storageLimitMb(10240).build();
        service.checkDowngrade(enterprise);
    }

    @Test
    void clientFilenamesAreStrippedBeforeTouchingDisk() throws Exception {
        Method safeName = FileStorageService.class.getDeclaredMethod("safeName", String.class);
        safeName.setAccessible(true);
        assertEquals("passwd", safeName.invoke(null, "../../etc/passwd"));
        assertEquals("upload.bin", safeName.invoke(null, (Object) null));
        assertEquals("a_b", safeName.invoke(null, "a\u0001b"));
    }

    @Test
    void seededFreePlanHasTheDocumentedLimits() throws Exception {
        Method plan = AccountSeeder.class.getDeclaredMethod("plan", String.class, String.class,
                String.class, int.class, int.class, int.class, long.class, long.class,
                int.class, int.class);
        plan.setAccessible(true);
        Plan free = (Plan) plan.invoke(null, "FREE", "Free", "0.00", 50, 3, 5,
                10L, 2L, 100, 10);
        assertEquals(50, free.getMaxStudents());
        assertEquals(10L, free.getStorageLimitMb());
        assertEquals(100, free.getSeatsPerGeneration());
        assertTrue(free.isActive());
    }

    @Test
    void usageRecordCarriesAllCounters() {
        Instant now = Instant.now();
        UsageView view = new UsageView("FREE", "Free", 1, 2, 3, 4, 5, 6, 7, 8, 9, now, now);
        assertEquals("FREE", view.planCode());
        assertEquals(8L, view.storageLimitBytes());
        assertEquals(9, view.seatsPerGeneration());
        assertEquals(now, view.periodStart());
    }
}
