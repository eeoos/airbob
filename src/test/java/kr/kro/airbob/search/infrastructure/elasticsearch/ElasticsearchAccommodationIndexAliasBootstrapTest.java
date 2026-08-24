package kr.kro.airbob.search.infrastructure.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.GetAliasResponse;
import co.elastic.clients.elasticsearch.indices.UpdateAliasesRequest;
import co.elastic.clients.transport.endpoints.BooleanResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 색인 alias Elasticsearch bootstrap")
class ElasticsearchAccommodationIndexAliasBootstrapTest {

	@Mock private ElasticsearchClient elasticsearchClient;
	@Mock private ElasticsearchIndicesClient indicesClient;

	private ElasticsearchAccommodationIndexAliasBootstrap bootstrap;

	@BeforeEach
	void setUp() {
		given(elasticsearchClient.indices()).willReturn(indicesClient);
		bootstrap = new ElasticsearchAccommodationIndexAliasBootstrap(elasticsearchClient);
	}

	@Test
	@DisplayName("alias가 없으면 canonical mapping의 version index와 write alias를 함께 생성한다")
	@SuppressWarnings("unchecked")
	void createsVersionIndexAndWriteAliasAtomically() throws Exception {
		given(indicesClient.existsAlias(any(Function.class)))
			.willReturn(new BooleanResponse(false));
		given(indicesClient.exists(any(Function.class)))
			.willReturn(new BooleanResponse(false));
		given(indicesClient.create(any(CreateIndexRequest.class)))
			.willReturn(CreateIndexResponse.of(response -> response
				.index("accommodations-vbootstrap")
				.acknowledged(true)
				.shardsAcknowledged(true)));
		given(indicesClient.getAlias(any(Function.class)))
			.willReturn(validAliasResponse());

		bootstrap.ensureReady();

		ArgumentCaptor<CreateIndexRequest> request =
			ArgumentCaptor.forClass(CreateIndexRequest.class);
		then(indicesClient).should().create(request.capture());
		assertThat(request.getValue().index()).isEqualTo("accommodations-vbootstrap");
		assertThat(request.getValue().aliases()).containsKey("accommodations");
		assertThat(request.getValue().aliases().get("accommodations").isWriteIndex()).isTrue();
		assertThat(request.getValue().mappings()).isNotNull();
		assertThat(request.getValue().settings()).isNotNull();
	}

	@Test
	@DisplayName("write alias가 이미 정상이면 인덱스를 새로 만들지 않는다")
	@SuppressWarnings("unchecked")
	void acceptsExactlyOneManagedWriteAlias() throws Exception {
		given(indicesClient.existsAlias(any(Function.class)))
			.willReturn(new BooleanResponse(true));
		given(indicesClient.getAlias(any(Function.class)))
			.willReturn(validAliasResponse());

		bootstrap.ensureReady();

		then(indicesClient).should(never()).create(any(CreateIndexRequest.class));
	}

	@Test
	@DisplayName("동시 bootstrap 생성 경쟁은 상대가 만든 유효한 alias를 다시 검증한 뒤만 readiness를 연다")
	@SuppressWarnings("unchecked")
	void rechecksAValidAliasAfterConcurrentCreateFailure() throws Exception {
		given(indicesClient.existsAlias(any(Function.class)))
			.willReturn(new BooleanResponse(false), new BooleanResponse(true));
		given(indicesClient.exists(any(Function.class)))
			.willReturn(new BooleanResponse(false), new BooleanResponse(false));
		given(indicesClient.create(any(CreateIndexRequest.class)))
			.willThrow(mock(ElasticsearchException.class));
		given(indicesClient.getAlias(any(Function.class)))
			.willReturn(validAliasResponse());
		AccommodationIndexAliasReadiness readiness = readiness();

		readiness.afterPropertiesSet();

		assertThat(readiness.shouldAutoStart()).isTrue();
		then(indicesClient).should().create(any(CreateIndexRequest.class));
		then(indicesClient).should().getAlias(any(Function.class));
		then(indicesClient).should(never()).updateAliases(any(UpdateAliasesRequest.class));
	}

	@Test
	@DisplayName("관리된 bootstrap index를 alias로 임의 전환하지 않고 readiness를 닫는다")
	@SuppressWarnings("unchecked")
	void rejectsAnOrphanBootstrapIndexWithoutCreatingOrCuttingOver() throws Exception {
		given(indicesClient.existsAlias(any(Function.class)))
			.willReturn(new BooleanResponse(false));
		given(indicesClient.exists(any(Function.class)))
			.willReturn(new BooleanResponse(false), new BooleanResponse(true));
		AccommodationIndexAliasReadiness readiness = readiness();

		assertThatThrownBy(readiness::afterPropertiesSet)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Bootstrap index exists without managed accommodation alias");

		assertThat(readiness.shouldAutoStart()).isFalse();
		then(indicesClient).should(never()).create(any(CreateIndexRequest.class));
		then(indicesClient).should(never()).updateAliases(any(UpdateAliasesRequest.class));
		then(indicesClient).should(never()).getAlias(any(Function.class));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidExistingAliases")
	@DisplayName("잘못된 기존 alias 모양은 자동 생성·전환 없이 readiness를 닫는다")
	@SuppressWarnings("unchecked")
	void rejectsInvalidExistingAliasShapesWithoutMutation(
		String scenario,
		GetAliasResponse invalidAlias,
		String expectedMessage
	) throws Exception {
		given(indicesClient.existsAlias(any(Function.class)))
			.willReturn(new BooleanResponse(true));
		given(indicesClient.getAlias(any(Function.class)))
			.willReturn(invalidAlias);
		AccommodationIndexAliasReadiness readiness = readiness();

		assertThatThrownBy(readiness::afterPropertiesSet)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(expectedMessage);

		assertThat(readiness.shouldAutoStart()).isFalse();
		then(indicesClient).should(never()).create(any(CreateIndexRequest.class));
		then(indicesClient).should(never()).updateAliases(any(UpdateAliasesRequest.class));
		then(indicesClient).should(never()).exists(any(Function.class));
	}

	@Test
	@DisplayName("동일한 이름의 물리 인덱스가 있으면 자동 삭제/전환하지 않는다")
	@SuppressWarnings("unchecked")
	void rejectsConcreteIndexNameConflict() throws Exception {
		given(indicesClient.existsAlias(any(Function.class)))
			.willReturn(new BooleanResponse(false));
		given(indicesClient.exists(any(Function.class)))
			.willReturn(new BooleanResponse(true));

		assertThatThrownBy(bootstrap::ensureReady)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Concrete Elasticsearch index conflicts");
		then(indicesClient).should(never()).create(any(CreateIndexRequest.class));
	}

	private AccommodationIndexAliasReadiness readiness() {
		return new AccommodationIndexAliasReadiness(bootstrap, true, true);
	}

	private static Stream<Arguments> invalidExistingAliases() {
		return Stream.of(
			Arguments.of(
				"복수 target",
				GetAliasResponse.of(response -> response
					.result("accommodations-v20260817090000", index -> index.aliases(
						"accommodations", alias -> alias.isWriteIndex(true)))
					.result("accommodations-v20260817100000", index -> index.aliases(
						"accommodations", alias -> alias.isWriteIndex(true)))),
				"exactly one version index"
			),
			Arguments.of(
				"비관리 index target",
				GetAliasResponse.of(response -> response.result(
					"legacy-accommodations",
					index -> index.aliases(
						"accommodations", alias -> alias.isWriteIndex(true)))),
				"versioned index"
			),
			Arguments.of(
				"alias definition 누락",
				GetAliasResponse.of(response -> response.result(
					"accommodations-v20260817090000",
					index -> index.aliases(Map.of()))),
				"exactly one write index"
			),
			Arguments.of(
				"write flag 누락",
				GetAliasResponse.of(response -> response.result(
					"accommodations-v20260817090000",
					index -> index.aliases("accommodations", alias -> alias))),
				"exactly one write index"
			),
			Arguments.of(
				"write flag false",
				GetAliasResponse.of(response -> response.result(
					"accommodations-v20260817090000",
					index -> index.aliases(
						"accommodations", alias -> alias.isWriteIndex(false)))),
				"exactly one write index"
			)
		);
	}

	private GetAliasResponse validAliasResponse() {
		return GetAliasResponse.of(response -> response.result(
			"accommodations-v20260817090000",
			index -> index.aliases(
				"accommodations", alias -> alias.isWriteIndex(true))));
	}
}
