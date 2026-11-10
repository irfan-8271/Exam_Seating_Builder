package com.examseating.config;

import com.examseating.repository.AuthTokenRepository;
import com.examseating.repository.UserRepository;
import com.examseating.tenancy.TenantFilter;
import com.examseating.tenancy.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class TenancyConfig {

    private final AuthTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final TenantSupport tenantSupport;

    @Bean
    public TenantFilter tenantFilter(@Value("${app.security.mode:open}") String securityMode) {
        return new TenantFilter(tokenRepository, userRepository, tenantSupport,
                "enforced".equalsIgnoreCase(securityMode));
    }
}
