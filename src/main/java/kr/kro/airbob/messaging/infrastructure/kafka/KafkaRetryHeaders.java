package kr.kro.airbob.messaging.infrastructure.kafka;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.kafka.retrytopic.RetryTopicHeaders;
import org.springframework.kafka.support.KafkaHeaders;

public final class KafkaRetryHeaders {

	private static final String RETRY_SUFFIX = ".RETRY";
	private static final String DLT_SUFFIX = ".DLT";
	private static final List<String> RETRY_HEADERS = List.of(
		RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS,
		RetryTopicHeaders.DEFAULT_HEADER_BACKOFF_TIMESTAMP,
		RetryTopicHeaders.DEFAULT_HEADER_ORIGINAL_TIMESTAMP,
		KafkaHeaders.ORIGINAL_TOPIC,
		KafkaHeaders.ORIGINAL_PARTITION,
		KafkaHeaders.ORIGINAL_OFFSET
	);
	private static final List<String> RESERVED_HEADERS = List.of(
		RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS,
		RetryTopicHeaders.DEFAULT_HEADER_BACKOFF_TIMESTAMP,
		RetryTopicHeaders.DEFAULT_HEADER_ORIGINAL_TIMESTAMP,
		KafkaHeaders.ORIGINAL_TOPIC,
		KafkaHeaders.ORIGINAL_PARTITION,
		KafkaHeaders.ORIGINAL_OFFSET,
		KafkaHeaders.ORIGINAL_CONSUMER_GROUP,
		KafkaHeaders.ORIGINAL_TIMESTAMP,
		KafkaHeaders.ORIGINAL_TIMESTAMP_TYPE,
		KafkaHeaders.EXCEPTION_FQCN,
		KafkaHeaders.EXCEPTION_CAUSE_FQCN,
		KafkaHeaders.EXCEPTION_STACKTRACE,
		KafkaHeaders.EXCEPTION_MESSAGE,
		KafkaHeaders.KEY_EXCEPTION_FQCN,
		KafkaHeaders.KEY_EXCEPTION_STACKTRACE,
		KafkaHeaders.KEY_EXCEPTION_MESSAGE,
		KafkaHeaders.DLT_EXCEPTION_FQCN,
		KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN,
		KafkaHeaders.DLT_EXCEPTION_STACKTRACE,
		KafkaHeaders.DLT_EXCEPTION_MESSAGE,
		KafkaHeaders.DLT_KEY_EXCEPTION_FQCN,
		KafkaHeaders.DLT_KEY_EXCEPTION_STACKTRACE,
		KafkaHeaders.DLT_KEY_EXCEPTION_MESSAGE,
		KafkaHeaders.DLT_ORIGINAL_TOPIC,
		KafkaHeaders.DLT_ORIGINAL_PARTITION,
		KafkaHeaders.DLT_ORIGINAL_OFFSET,
		KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP,
		KafkaHeaders.DLT_ORIGINAL_TIMESTAMP,
		KafkaHeaders.DLT_ORIGINAL_TIMESTAMP_TYPE,
		KafkaHeaders.DELIVERY_ATTEMPT
	);

	private KafkaRetryHeaders() {
	}

	public static void stripFrameworkOwned(Headers headers) {
		RESERVED_HEADERS.forEach(headers::remove);
	}

	public static Headers copyValidatedFrameworkOwned(
		Headers source,
		String destinationTopic,
		Integer destinationPartition
	) {
		Headers sanitized = new RecordHeaders();
		for (String name : RETRY_HEADERS) {
			Header candidate = source.lastHeader(name);
			if (candidate != null
				&& isValid(name, candidate.value(), destinationTopic, destinationPartition)) {
				sanitized.add(name, candidate.value().clone());
			}
		}
		return sanitized;
	}

	private static boolean isValid(
		String name,
		byte[] value,
		String destinationTopic,
		Integer destinationPartition
	) {
		if (value == null) {
			return false;
		}
		if (RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS.equals(name)) {
			return readInt(value) > 0;
		}
		if (RetryTopicHeaders.DEFAULT_HEADER_BACKOFF_TIMESTAMP.equals(name)
			|| RetryTopicHeaders.DEFAULT_HEADER_ORIGINAL_TIMESTAMP.equals(name)) {
			return readNonNegativeLong(value);
		}
		if (KafkaHeaders.ORIGINAL_TOPIC.equals(name)) {
			return matchesOriginalTopic(value, destinationTopic);
		}
		if (KafkaHeaders.ORIGINAL_PARTITION.equals(name)) {
			int partition = readInt(value);
			return partition >= 0
				&& (destinationPartition == null || partition == destinationPartition);
		}
		if (KafkaHeaders.ORIGINAL_OFFSET.equals(name)) {
			return readLong(value) >= 0;
		}
		return false;
	}

	private static int readInt(byte[] value) {
		return value.length == Integer.BYTES ? ByteBuffer.wrap(value).getInt() : -1;
	}

	private static long readLong(byte[] value) {
		return value.length == Long.BYTES ? ByteBuffer.wrap(value).getLong() : -1;
	}

	private static boolean readNonNegativeLong(byte[] value) {
		if (value.length == 0 || value.length > Long.BYTES + 1) {
			return false;
		}
		try {
			return new BigInteger(value).longValueExact() >= 0;
		} catch (ArithmeticException ignored) {
			return false;
		}
	}

	private static boolean matchesOriginalTopic(byte[] value, String destinationTopic) {
		String originalTopic = removeSuffix(destinationTopic, RETRY_SUFFIX);
		if (originalTopic.equals(destinationTopic)) {
			originalTopic = removeSuffix(destinationTopic, DLT_SUFFIX);
		}
		return !originalTopic.equals(destinationTopic)
			&& Arrays.equals(value, originalTopic.getBytes(StandardCharsets.UTF_8));
	}

	private static String removeSuffix(String value, String suffix) {
		return value != null && value.endsWith(suffix)
			? value.substring(0, value.length() - suffix.length())
			: value;
	}
}
