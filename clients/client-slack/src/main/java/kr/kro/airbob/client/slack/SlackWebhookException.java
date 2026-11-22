package kr.kro.airbob.client.slack;

/**
 * A Slack webhook request that was not confirmed with a 2xx response.
 * It never carries the webhook URL, the response, or the underlying cause.
 */
public class SlackWebhookException extends RuntimeException {

	public SlackWebhookException() {
		super("Slack webhook delivery failed.");
	}
}
