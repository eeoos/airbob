package kr.kro.airbob.domain.reservation.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;

class ReservationCheckoutIdentityTest {

	private static final String IDEMPOTENCY_KEY = "checkout-key_2026.08:25";
	private static final ReservationRequest.Create BASE_REQUEST = new ReservationRequest.Create(
		101L,
		LocalDate.of(2026, 9, 1),
		LocalDate.of(2026, 9, 3),
		2,
		201L,
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
			IDEMPOTENCY_KEY, (ReservationRequest.Create)null))
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
	void includesEveryCheckoutFieldInTheRequestFingerprint() {
		String baseFingerprint = fingerprint(BASE_REQUEST);
		List<ReservationRequest.Create> requestsWithOneChangedField = List.of(
			new ReservationRequest.Create(102L, BASE_REQUEST.checkInDate(), BASE_REQUEST.checkOutDate(),
				BASE_REQUEST.guestCount(), BASE_REQUEST.couponId(), BASE_REQUEST.requestMessage()),
			new ReservationRequest.Create(BASE_REQUEST.accommodationId(), LocalDate.of(2026, 9, 2),
				BASE_REQUEST.checkOutDate(), BASE_REQUEST.guestCount(), BASE_REQUEST.couponId(),
				BASE_REQUEST.requestMessage()),
			new ReservationRequest.Create(BASE_REQUEST.accommodationId(), BASE_REQUEST.checkInDate(),
				LocalDate.of(2026, 9, 4), BASE_REQUEST.guestCount(), BASE_REQUEST.couponId(),
				BASE_REQUEST.requestMessage()),
			new ReservationRequest.Create(BASE_REQUEST.accommodationId(), BASE_REQUEST.checkInDate(),
				BASE_REQUEST.checkOutDate(), 3, BASE_REQUEST.couponId(), BASE_REQUEST.requestMessage()),
			new ReservationRequest.Create(BASE_REQUEST.accommodationId(), BASE_REQUEST.checkInDate(),
				BASE_REQUEST.checkOutDate(), BASE_REQUEST.guestCount(), 202L, BASE_REQUEST.requestMessage()),
			new ReservationRequest.Create(BASE_REQUEST.accommodationId(), BASE_REQUEST.checkInDate(),
				BASE_REQUEST.checkOutDate(), BASE_REQUEST.guestCount(), BASE_REQUEST.couponId(), "늦은 체크인")
		);

		assertThat(requestsWithOneChangedField)
			.extracting(ReservationCheckoutIdentityTest::fingerprint)
			.doesNotContain(baseFingerprint)
			.doesNotHaveDuplicates();
	}

	@Test
	void preservesNullBoundariesInTheRequestFingerprint() {
		ReservationRequest.Create nullableFields = new ReservationRequest.Create(
			BASE_REQUEST.accommodationId(),
			BASE_REQUEST.checkInDate(),
			BASE_REQUEST.checkOutDate(),
			BASE_REQUEST.guestCount(),
			null,
			null
		);
		ReservationRequest.Create literalNulls = new ReservationRequest.Create(
			BASE_REQUEST.accommodationId(),
			BASE_REQUEST.checkInDate(),
			BASE_REQUEST.checkOutDate(),
			BASE_REQUEST.guestCount(),
			null,
			"null"
		);

		assertThat(fingerprint(nullableFields)).isNotEqualTo(fingerprint(literalNulls));
	}

	@Test
	void v2FingerprintIncludesQuoteUidAndCheckoutMessage() {
		ReservationRequest.Checkout checkout = new ReservationRequest.Checkout(
			java.util.UUID.fromString("fa1e54c6-201c-4d09-98b8-68eedfa921ae"),
			"늦은 체크인 예정입니다"
		);
		ReservationRequest.Checkout changedQuote = new ReservationRequest.Checkout(
			java.util.UUID.fromString("50a258ac-f741-47bc-a5eb-af7475f4336b"),
			checkout.requestMessage()
		);
		ReservationRequest.Checkout changedMessage = new ReservationRequest.Checkout(
			checkout.quoteUid(),
			"조용한 방을 부탁드립니다"
		);

		String fingerprint = ReservationCheckoutIdentity.from(
			IDEMPOTENCY_KEY, checkout).requestFingerprint();

		assertThat(fingerprint).matches("[0-9a-f]{64}");
		assertThat(ReservationCheckoutIdentity.from(
			IDEMPOTENCY_KEY, checkout).requestFingerprint()).isEqualTo(fingerprint);
		assertThat(ReservationCheckoutIdentity.from(
			IDEMPOTENCY_KEY, changedQuote).requestFingerprint()).isNotEqualTo(fingerprint);
		assertThat(ReservationCheckoutIdentity.from(
			IDEMPOTENCY_KEY, changedMessage).requestFingerprint()).isNotEqualTo(fingerprint);
	}

	private static String fingerprint(ReservationRequest.Create request) {
		return ReservationCheckoutIdentity.from(IDEMPOTENCY_KEY, request).requestFingerprint();
	}
}
