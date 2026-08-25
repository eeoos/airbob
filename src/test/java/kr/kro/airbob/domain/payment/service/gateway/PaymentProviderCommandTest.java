package kr.kro.airbob.domain.payment.service.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class PaymentProviderCommandTest {

	@Test
	void cancellationReasonUsesTheSameTwoHundredCharacterBoundaryAsPersistence() {
		PaymentProviderCommand maximum = command("r".repeat(200));

		assertThat(maximum.cancellationReason()).hasSize(200);
		assertThatIllegalArgumentException()
			.isThrownBy(() -> command("r".repeat(201)))
			.withMessageContaining("not exceed 200 characters");
	}

	private PaymentProviderCommand command(String cancellationReason) {
		UUID operationUid = UUID.randomUUID();
		return new PaymentProviderCommand(
			operationUid,
			"payment-key",
			UUID.randomUUID().toString(),
			100_000L,
			"airbob-cancel-" + operationUid,
			cancellationReason
		);
	}
}
