package kr.kro.airbob.client.toss;

/**
 * A Toss Payments error response whose body could not be read or parsed.
 * The raw body is never included.
 */
public class TossPaymentsResponseParsingException extends RuntimeException {

	public TossPaymentsResponseParsingException() {
		super("Toss Payments error response could not be parsed.");
	}
}
