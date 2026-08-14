package kr.kro.airbob.domain.payment.service.gateway;

public interface PaymentConfirmationGateway {

	PaymentGatewayResult confirm(PaymentConfirmationCommand command);

	PaymentGatewayResult inquire(PaymentConfirmationCommand command);
}
