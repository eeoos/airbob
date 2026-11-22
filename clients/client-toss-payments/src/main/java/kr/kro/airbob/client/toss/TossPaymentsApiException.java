package kr.kro.airbob.client.toss;

/**
 * Toss Payments answered with a 4xx or 5xx status.
 * Carries only the HTTP status and the provider error code, never the provider message or body.
 */
public class TossPaymentsApiException extends RuntimeException {

	public static final String UNKNOWN_ERROR = "UNKNOWN_ERROR";

	private final int statusCode;
	private final String code;

	public TossPaymentsApiException(int statusCode, String code) {
		this.statusCode = statusCode;
		this.code = code == null || code.isBlank() ? UNKNOWN_ERROR : code;
	}

	public int statusCode() {
		return statusCode;
	}

	public String code() {
		return code;
	}
}
