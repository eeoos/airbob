package kr.kro.airbob.outbox;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

public class EventTimestampSerializer extends JsonSerializer<Instant> {

	@Override
	public void serialize(Instant value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
		LocalDateTime utcDateTime = LocalDateTime.ofInstant(value, ZoneOffset.UTC);
		generator.writeString(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(utcDateTime));
	}
}
