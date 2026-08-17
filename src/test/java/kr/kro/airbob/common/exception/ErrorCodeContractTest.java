package kr.kro.airbob.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class ErrorCodeContractTest {

	@Test
	void exposesAUniqueExternalCodeForEveryError() {
		assertThat(Arrays.stream(ErrorCode.values()))
			.extracting(ErrorCode::getCode)
			.doesNotHaveDuplicates();
	}

	@Test
	void publishingValidationUsesItsOwnAccommodationErrorCode() {
		assertThat(ErrorCode.PUBLISHING_VALIDATION_FAILED.getCode()).isEqualTo("A009");
	}
}
