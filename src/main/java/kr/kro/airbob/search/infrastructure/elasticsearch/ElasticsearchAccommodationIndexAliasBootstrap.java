package kr.kro.airbob.search.infrastructure.elasticsearch;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.indices.AliasDefinition;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.GetAliasResponse;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ElasticsearchAccommodationIndexAliasBootstrap
	implements AccommodationIndexAliasBootstrap {

	static final String ALIAS = "accommodations";
	static final String BOOTSTRAP_INDEX = "accommodations-vbootstrap";
	static final String INDEX_DEFINITION = "elasticsearch/accommodations-index.json";

	private final ElasticsearchClient elasticsearchClient;

	@Override
	public void ensureReady() {
		try {
			if (aliasExists()) {
				verifyManagedWriteAlias();
				return;
			}
			if (indexExists(ALIAS)) {
				throw new IllegalStateException(
					"Concrete Elasticsearch index conflicts with managed alias: " + ALIAS);
			}
			if (indexExists(BOOTSTRAP_INDEX)) {
				if (aliasExists()) {
					verifyManagedWriteAlias();
					return;
				}
				throw new IllegalStateException(
					"Bootstrap index exists without managed accommodation alias.");
			} else {
				createBootstrapIndexWithAlias();
			}
			verifyManagedWriteAlias();
		} catch (IOException | ElasticsearchException exception) {
			throw new IllegalStateException("Failed to prepare accommodation search alias.", exception);
		}
	}

	private boolean aliasExists() throws IOException {
		return elasticsearchClient.indices()
			.existsAlias(request -> request.name(ALIAS))
			.value();
	}

	private boolean indexExists(String index) throws IOException {
		return elasticsearchClient.indices()
			.exists(request -> request.index(index))
			.value();
	}

	private void createBootstrapIndexWithAlias() throws IOException {
		ClassPathResource definition = new ClassPathResource(INDEX_DEFINITION);
		try (InputStream input = definition.getInputStream()) {
			CreateIndexRequest request = new CreateIndexRequest.Builder()
				.withJson(input)
				.index(BOOTSTRAP_INDEX)
				.aliases(ALIAS, alias -> alias.isWriteIndex(true))
				.build();
			try {
				var response = elasticsearchClient.indices().create(request);
				if (!response.acknowledged()) {
					throw new IllegalStateException(
						"Elasticsearch did not acknowledge accommodation alias bootstrap.");
				}
			} catch (ElasticsearchException concurrentCreateFailure) {
				if (!aliasExists()) {
					throw concurrentCreateFailure;
				}
			}
		}
	}

	private void verifyManagedWriteAlias() throws IOException {
		GetAliasResponse response = elasticsearchClient.indices()
			.getAlias(request -> request.name(ALIAS));
		Map<String, co.elastic.clients.elasticsearch.indices.get_alias.IndexAliases> indices =
			response.result();
		if (indices.size() != 1) {
			throw new IllegalStateException(
				"Accommodation alias must point to exactly one version index.");
		}
		Map.Entry<String, co.elastic.clients.elasticsearch.indices.get_alias.IndexAliases> entry =
			indices.entrySet().iterator().next();
		if (!entry.getKey().startsWith(ALIAS + "-v")) {
			throw new IllegalStateException(
				"Accommodation alias must point to a versioned index.");
		}
		AliasDefinition definition = entry.getValue().aliases().get(ALIAS);
		if (definition == null || !Boolean.TRUE.equals(definition.isWriteIndex())) {
			throw new IllegalStateException(
				"Accommodation alias must have exactly one write index.");
		}
	}
}
