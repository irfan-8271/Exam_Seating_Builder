package com.examseating.repository;

import com.examseating.entity.PaymentConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentConfigRepository extends JpaRepository<PaymentConfig, Long> {

    Optional<PaymentConfig> findByConfigKey(String configKey);
}
