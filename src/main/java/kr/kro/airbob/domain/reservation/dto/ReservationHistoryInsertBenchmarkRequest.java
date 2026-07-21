package kr.kro.airbob.domain.reservation.dto;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ReservationHistoryInsertBenchmarkRequest(
	@NotNull
	@JsonDeserialize(using = StrictVariantDeserializer.class)
	Variant variant,
	@NotNull @Min(0) @Max(MAX_DATASET_SIZE)
	@JsonDeserialize(using = StrictIntegerDeserializer.class)
	Integer datasetSize
) {
	public static final int MAX_DATASET_SIZE = 2000;

	public enum Variant {
		BEFORE
	}

	public static final class StrictVariantDeserializer extends JsonDeserializer<Variant> {

		@Override
		public Variant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
			if (!parser.hasToken(JsonToken.VALUE_STRING)) {
				return (Variant)context.handleUnexpectedToken(Variant.class, parser);
			}
			String value = parser.getText();
			if (!Variant.BEFORE.name().equals(value)) {
				return (Variant)context.handleWeirdStringValue(
					Variant.class,
					value,
					"variant must be BEFORE"
				);
			}
			return Variant.BEFORE;
		}
	}

	public static final class StrictIntegerDeserializer extends JsonDeserializer<Integer> {

		@Override
		public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
			if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
				return (Integer)context.handleUnexpectedToken(Integer.class, parser);
			}
			return parser.getIntValue();
		}
	}
}
