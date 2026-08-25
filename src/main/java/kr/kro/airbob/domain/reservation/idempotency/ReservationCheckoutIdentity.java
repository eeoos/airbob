package kr.kro.airbob.domain.reservation.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;

public record ReservationCheckoutIdentity(
	String keyHash,
	String requestFingerprint
) {
	private static final int MIN_KEY_LENGTH = 8;
	private static final int MAX_KEY_LENGTH = 128;
	private static final String SAFE_KEY_PATTERN = "[A-Za-z0-9._:-]+";

	public static ReservationCheckoutIdentity from(
		String rawKey,
		ReservationRequest.Create request
	) {
		validateKey(rawKey);
		if (request == null) {
			throw new InvalidInputException("예약 요청은 필수입니다.");
		}
		return new ReservationCheckoutIdentity(
			sha256(rawKey),
			sha256(canonicalRequest(request))
		);
	}

	private static void validateKey(String rawKey) {
		if (rawKey == null
			|| rawKey.length() < MIN_KEY_LENGTH
			|| rawKey.length() > MAX_KEY_LENGTH
			|| !rawKey.matches(SAFE_KEY_PATTERN)) {
			throw new InvalidInputException("Idempotency-Key 형식이 유효하지 않습니다.");
		}
	}

	private static String canonicalRequest(ReservationRequest.Create request) {
		StringBuilder canonical = new StringBuilder();
		append(canonical, request.accommodationId());
		append(canonical, request.checkInDate());
		append(canonical, request.checkOutDate());
		append(canonical, request.guestCount());
		append(canonical, request.couponId());
		append(canonical, request.requestMessage());
		return canonical.toString();
	}

	private static void append(StringBuilder target, Object value) {
		if (value == null) {
			target.append("N;");
			return;
		}
		String text = value.toString();
		target.append(text.length()).append(':').append(text).append(';');
	}

	private static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 must be available", exception);
		}
	}
}
