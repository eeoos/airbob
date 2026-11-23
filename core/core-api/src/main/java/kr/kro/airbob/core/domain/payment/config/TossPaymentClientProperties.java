package kr.kro.airbob.core.domain.payment.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payment.toss")
public record TossPaymentClientProperties(
	String secretKey,
	String baseUrl,
	Duration connectTimeout,
	Duration readTimeout
) {
}
