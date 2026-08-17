package kr.kro.airbob.search.reindex;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.kafka.annotation.KafkaListener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.kafka.consumer.AccommodationIndexingConsumer;
import kr.kro.airbob.search.document.AccommodationDocument;

class AccommodationReindexContractTest {

	private static final Path INDEX_DEFINITION = Path.of(
		"logstash/config/elasticsearch/accommodations-index.json");
	private static final Path LOGSTASH_PIPELINE = Path.of("logstash/pipeline/airbob.conf");

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("애플리케이션은 물리 인덱스를 자동 생성하지 않고 alias만 사용한다")
	void applicationUsesManagedAlias() throws NoSuchMethodException {
		Document document = AccommodationDocument.class.getAnnotation(Document.class);
		KafkaListener listener = AccommodationIndexingConsumer.class
			.getMethod("handle", String.class, org.springframework.kafka.support.Acknowledgment.class)
			.getAnnotation(KafkaListener.class);

		assertThat(document.indexName()).isEqualTo("accommodations");
		assertThat(document.createIndex()).isFalse();
		assertThat(listener.autoStartup())
			.isEqualTo("${accommodation.indexing.kafka.auto-startup:true}");
	}

	@Test
	@DisplayName("버전 인덱스 정의는 검색 문서의 엄격한 필드와 분석기를 제공한다")
	void versionIndexDefinitionMatchesSearchContract() throws Exception {
		JsonNode root = objectMapper.readTree(Files.readString(INDEX_DEFINITION));
		JsonNode properties = root.path("mappings").path("properties");
		Set<String> mappedFields = new TreeSet<>();
		properties.fieldNames().forEachRemaining(mappedFields::add);
		Set<String> documentFields = Arrays.stream(AccommodationDocument.class.getRecordComponents())
			.map(component -> component.getName())
			.collect(Collectors.toSet());

		assertThat(root.path("mappings").path("dynamic").asText()).isEqualTo("strict");
		assertThat(mappedFields).containsExactlyInAnyOrderElementsOf(documentFields);
		assertThat(root.path("settings").path("analysis").path("normalizer")
			.path("lowercase_normalizer").path("filter").get(0).asText()).isEqualTo("lowercase");
		assertThat(properties.path("id").path("type").asText()).isEqualTo("keyword");
		assertThat(properties.path("name").path("analyzer").asText()).isEqualTo("nori");
		assertThat(properties.path("city").path("fields").path("keyword").path("type").asText())
			.isEqualTo("keyword");
		assertThat(properties.path("location").path("type").asText()).isEqualTo("geo_point");
		assertThat(properties.path("reservationRanges").path("type").asText())
			.isEqualTo("date_range");
		assertThat(properties.path("thumbnailUrl").path("type").asText()).isEqualTo("keyword");
		assertThat(properties.path("averageRating").path("type").asText()).isEqualTo("double");
	}

	@Test
	@DisplayName("Logstash는 live alias가 아니라 명시적인 버전 인덱스만 받는다")
	void logstashRequiresVersionIndexTarget() throws Exception {
		String pipeline = Files.readString(LOGSTASH_PIPELINE);

		assertThat(pipeline).contains("index => \"${LOGSTASH_TARGET_INDEX}\"");
		assertThat(pipeline).doesNotContain("index => \"accommodations\"");
	}
}
