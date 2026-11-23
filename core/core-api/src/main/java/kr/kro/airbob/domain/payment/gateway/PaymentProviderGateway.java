package kr.kro.airbob.domain.payment.gateway;

public interface PaymentProviderGateway {

	PaymentGatewayResult confirm(PaymentProviderCommand command);

	PaymentGatewayResult inquireConfirmation(PaymentProviderCommand command);

	PaymentGatewayResult cancel(PaymentProviderCommand command);

	PaymentGatewayResult inquireCancellation(PaymentProviderCommand command);
}
