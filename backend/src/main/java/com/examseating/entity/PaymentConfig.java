package com.examseating.entity;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Platform-wide payment configuration managed exclusively by the platform
 * administrator.
 *
 * <p>Designed with an explicit {@link PaymentMode} so manual UPI / bank-transfer
 * verification works out of the box today while automated payment gateways
 * (Razorpay, Stripe, etc.) can plug in later without redesigning the schema.</p>
 */
@Entity
@Table(
    name = "payment_configs",
    uniqueConstraints = @UniqueConstraint(name = "uk_payment_configs_key", columnNames = "config_key")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PaymentConfig {

    public static final String DEFAULT_KEY = "DEFAULT";
    public static final String DEFAULT_CURRENCY_CODE = "INR";
    public static final String DEFAULT_CURRENCY_SYMBOL = "₹";

    public enum PaymentMode {
        MANUAL,
        GATEWAY
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "config_key", nullable = false, length = 32)
    private String configKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false, length = 20)
    private PaymentMode paymentMode;

    @Column(name = "upi_id", length = 120)
    private String upiId;

    @Column(name = "payee_name", length = 120)
    private String payeeName;

    @Column(name = "instructions", length = 2000)
    private String instructions;

    @Column(name = "additional_info", length = 2000)
    private String additionalInfo;

    @Column(name = "require_screenshot", nullable = false)
    private boolean requireScreenshot;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "qr_image", columnDefinition = "LONGBLOB")
    private byte[] qrImage;

    @Column(name = "qr_content_type", length = 80)
    private String qrContentType;

    @Column(name = "qr_filename", length = 255)
    private String qrFilename;

    /** Reserved for future automated payment gateway identifier (e.g. "RAZORPAY", "STRIPE"). */
    @Column(name = "gateway_provider", length = 40)
    private String gatewayProvider;

    @Column(name = "currency_code", length = 12)
    private String currencyCode;

    @Column(name = "currency_symbol", length = 12)
    private String currencySymbol;

    @Column(name = "updated_by", length = 60)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (configKey == null || configKey.isBlank()) {
            configKey = DEFAULT_KEY;
        }
        if (paymentMode == null) {
            paymentMode = PaymentMode.MANUAL;
        }
        if (currencyCode == null || currencyCode.isBlank()) {
            currencyCode = DEFAULT_CURRENCY_CODE;
        }
        if (currencySymbol == null || currencySymbol.isBlank()) {
            currencySymbol = DEFAULT_CURRENCY_SYMBOL;
        }
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean hasQrImage() {
        return qrImage != null && qrImage.length > 0;
    }

    public String effectiveCurrencyCode() {
        return (currencyCode == null || currencyCode.isBlank())
                ? DEFAULT_CURRENCY_CODE
                : currencyCode.trim().toUpperCase();
    }

    public String effectiveCurrencySymbol() {
        return (currencySymbol == null || currencySymbol.isBlank())
                ? DEFAULT_CURRENCY_SYMBOL
                : currencySymbol.trim();
    }
}
