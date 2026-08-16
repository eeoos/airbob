package kr.kro.airbob.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import kr.kro.airbob.outbox.EventType;

@DisplayName("숙소 색인 이벤트 파서 테스트")
class AccommodationIndexingEventParserTest {

	private static final UUID ACCOMMODATION_UID =
		UUID.fromString("109cc081-b87d-4502-9a5e-7d7b65993056");
	private static final String RAW_SECRET = "poison-secret-019ffe7e";
	private final AccommodationIndexingEventParser parser =
		new AccommodationIndexingEventParser(new ObjectMapper()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));

	@Test
	@DisplayName("지원하는 이벤트에서 이벤트 타입과 숙소 UID만 읽는다")
	void parsesSupportedEventToIdentifierOnlyCommand() {
		String message = """
			{
			  "event_type": "REVIEW_SUMMARY_CHANGED",
			  "payload": {"accommodation_uid": "%s", "ignored": "value"}
			}
			""".formatted(ACCOMMODATION_UID);

		AccommodationIndexingCommand command = parser.parse(message);

		assertThat(command.eventType()).isEqualTo(EventType.REVIEW_SUMMARY_CHANGED);
		assertThat(command.accommodationUid()).isEqualTo(ACCOMMODATION_UID);
	}

	@Test
	@DisplayName("깨진 원문은 원문과 원인을 보존하지 않는 예외로 바꾼다")
	void rejectsMalformedPayloadWithoutRetainingRawMessage() {
		String malformed = "not-json " + RAW_SECRET;

		Throwable failure = catchThrowable(() -> parser.parse(malformed));

		assertThat(failure)
			.isInstanceOf(AccommodationIndexingEventParsingException.class)
			.hasMessage("Invalid accommodation-indexing event.")
			.hasNoCause();
		assertThat(failure.toString()).doesNotContain(malformed, RAW_SECRET);
	}

	@Test
	@DisplayName("지원하지 않는 이벤트와 잘못된 UUID를 poison 이벤트로 거부한다")
	void rejectsUnsupportedTypeAndInvalidUid() {
		assertThat(catchThrowable(() -> parser.parse("""
			{"event_type":"PAYMENT_CANCELLATION_REQUESTED","payload":{"accommodation_uid":"%s"}}
			""".formatted(ACCOMMODATION_UID))))
			.isInstanceOf(AccommodationIndexingEventParsingException.class);

		assertThat(catchThrowable(() -> parser.parse("""
			{"event_type":"ACCOMMODATION_UPDATED","payload":{"accommodation_uid":"not-a-uuid"}}
			""")))
			.isInstanceOf(AccommodationIndexingEventParsingException.class);
	}
}
