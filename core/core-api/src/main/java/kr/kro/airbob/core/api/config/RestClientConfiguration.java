package kr.kro.airbob.core.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

// The Toss Payments client is created by domain/payment/config/PaymentOperationConfiguration
// from clients:client-toss-payments.
@Configuration
public class RestClientConfiguration {

	@Bean(name = "generalRestClient")
	public RestClient generalRestClient() {
		return RestClient.create();
	}
}
