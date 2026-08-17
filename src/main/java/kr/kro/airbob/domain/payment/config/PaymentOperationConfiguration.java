package kr.kro.airbob.domain.payment.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import kr.kro.airbob.domain.payment.service.PaymentRetryBackoff;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PaymentOperationProperties.class, TossPaymentClientProperties.class})
public class PaymentOperationConfiguration {

	@Bean
	public PaymentRetryBackoff paymentRetryBackoff(PaymentOperationProperties properties) {
		return new PaymentRetryBackoff(properties.retryInitialDelay(), properties.retryMaxDelay());
	}

	@Bean
	public InitializingBean paymentOperationTimeoutGuard(
		PaymentOperationProperties operation, TossPaymentClientProperties toss
	) {
		return () -> {
			if (toss.connectTimeout().compareTo(operation.leaseDuration()) >= 0
				|| toss.readTimeout().compareTo(operation.leaseDuration()) >= 0) {
				throw new IllegalStateException("Toss 타임아웃은 payment-operation lease보다 짧아야 합니다.");
			}
		};
	}
}
