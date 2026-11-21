package kr.kro.airbob.domain.reservation.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;

class ReservationCheckoutIdentityTest {

	private static final String IDEMPOTENCY_KEY = "checkout-key_2026.08:25";
	private static final ReservationRequest.Checkout BASE_REQUEST = new ReservationRequest.Checkout(
		UUID.fromString("fa1e54c6-201c-4d09-98b8-68eedfa921ae"),
		"조용한 방을 부탁드립니다."
	);

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {
		"",
		"1234567",
		"contains whitespace",
		"contains/slash",
		"한글키입니다"
	})
	void rejectsMissingShortOrUnsafeKeys(String rawKey) {
		assertThatThrownBy(() -> ReservationCheckoutIdentity.from(rawKey, BASE_REQUEST))
			.isInstanceOf(InvalidInputException.class);
	}

	@Test
	void rejectsKeysLongerThan128Characters() {
		assertThatThrownBy(() -> ReservationCheckoutIdentity.from("a".repeat(129), BASE_REQUEST))
			.isInstanceOf(InvalidInputException.class);
	}

	@Test
	void acceptsInclusiveKeyLengthBoundariesAndSafeCharacters() {
		ReservationCheckoutIdentity minimum = ReservationCheckoutIdentity.from("aB0._:-z", BASE_REQUEST);
		ReservationCheckoutIdentity maximum = ReservationCheckoutIdentity.from("a".repeat(128), BASE_REQUEST);

		assertThat(minimum.keyHash()).matches("[0-9a-f]{64}");
		assertThat(maximum.keyHash()).matches("[0-9a-f]{64}");
	}

	@Test
	void rejectsMissingReservationRequest() {
		assertThatThrownBy(() -> ReservationCheckoutIdentity.from(
			IDEMPOTENCY_KEY, (ReservationRequest.Checkout)null))
			.isInstanceOf(InvalidInputException.class);
	}

	@Test
	void hashesTheRawKeyWithoutRetainingOrExposingIt() {
		ReservationCheckoutIdentity identity = ReservationCheckoutIdentity.from(IDEMPOTENCY_KEY, BASE_REQUEST);
		ReservationCheckoutIdentity replay = ReservationCheckoutIdentity.from(IDEMPOTENCY_KEY, BASE_REQUEST);
		ReservationCheckoutIdentity anotherKey = ReservationCheckoutIdentity.from("another-checkout-key", BASE_REQUEST);

		assertThat(identity.keyHash())
			.matches("[0-9a-f]{64}")
			.doesNotContain(IDEMPOTENCY_KEY)
			.isEqualTo(replay.keyHash())
			.isNotEqualTo(anotherKey.keyHash());
		assertThat(identity.requestFingerprint())
			.matches("[0-9a-f]{64}")
			.doesNotContain(IDEMPOTENCY_KEY)
			.isEqualTo(replay.requestFingerprint());
		assertThat(identity.toString()).doesNotContain(IDEMPOTENCY_KEY);
	}

	@Test
	void fingerprintIncludesQuoteUidAndCheckoutMessage() {
		ReservationRequest.Checkout checkout = new ReservationRequest.Checkout(
			UUID.fromString("fa1e54c6-201c-4d09-98b8-68eedfa921ae"),
			"늦은 체크인 예정입니다"
		);
		ReservationRequest.Checkout changedQuote = new ReservationRequest.Checkout(
			UUID.fromString("50a258ac-f741-47bc-a5eb-af7475f4336b"),
			checkout.requestMessage()
		);
		ReservationRequest.Checkout changedMessage = new ReservationRequest.Checkout(
			checkout.quoteUid(),
			"조용한 방을 부탁드립니다"
		);

		String fingerprint = fingerprint(checkout);

		assertThat(fingerprint).matches("[0-9a-f]{64}");
		assertThat(fingerprint(checkout)).isEqualTo(fingerprint);
		assertThat(fingerprint(changedQuote)).isNotEqualTo(fingerprint);
		assertThat(fingerprint(changedMessage)).isNotEqualTo(fingerprint);
	}

	@Test
	void preservesNullBoundariesInTheRequestFingerprint() {
		ReservationRequest.Checkout nullableMessage = new ReservationRequest.Checkout(
			BASE_REQUEST.quoteUid(), null);
		ReservationRequest.Checkout literalNull = new ReservationRequest.Checkout(
			BASE_REQUEST.quoteUid(), "null");

		assertThat(fingerprint(nullableMessage)).isNotEqualTo(fingerprint(literalNull));
	}

	private String fingerprint(ReservationRequest.Checkout request) {
		return ReservationCheckoutIdentity.from(IDEMPOTENCY_KEY, request).requestFingerprint();
	}
}
