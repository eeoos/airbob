package kr.kro.airbob.outbox;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

public class EventTimestampDeserializer extends JsonDeserializer<Instant> {

	@Override
	public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
		if (!parser.hasToken(JsonToken.VALUE_STRING)) {
			return (Instant) context.handleUnexpectedToken(Instant.class, parser);
		}

		String timestamp = parser.getText().trim();
		if (timestamp.isEmpty()) {
			throw InvalidFormatException.from(parser, "timestamp must not be blank", timestamp, Instant.class);
		}

		try {
			return OffsetDateTime.parse(timestamp, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
		} catch (DateTimeParseException ignored) {
			return parseLegacyUtcTimestamp(parser, timestamp);
		}
	}

	@Override
	public Instant getNullValue(DeserializationContext context) throws JsonMappingException {
		throw MismatchedInputException.from(
			context.getParser(),
			Instant.class,
			"timestamp must not be null"
		);
	}

	private Instant parseLegacyUtcTimestamp(JsonParser parser, String timestamp) throws InvalidFormatException {
		try {
			return LocalDateTime.parse(timestamp, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
				.toInstant(ZoneOffset.UTC);
		} catch (DateTimeParseException e) {
			throw InvalidFormatException.from(
				parser,
				"timestamp must be ISO-8601 local UTC, Z, or offset date-time",
				timestamp,
				Instant.class
			);
		}
	}
}
