package kr.kro.airbob.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;

import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
@EnableElasticsearchRepositories
public class ElasticsearchConfig {

	@Value("${spring.elasticsearch.uris}")
	private String elasticsearchUrl;

	@Value("${spring.elasticsearch.username}")
	private String username;

	@Value("${spring.elasticsearch.password}")
	private String password;

	@Bean
	public ElasticsearchClient elasticsearchClient(ObjectMapper objectMapper) {
		CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
		credentialsProvider.setCredentials(AuthScope.ANY,
			new UsernamePasswordCredentials(username, password));

		RestClient restClient = RestClient.builder(HttpHost.create(elasticsearchUrl))
			.setHttpClientConfigCallback(httpClientBuilder ->
				httpClientBuilder
					.setDefaultCredentialsProvider(credentialsProvider)
					.setDefaultRequestConfig(RequestConfig.custom()
						.setConnectTimeout(10000)
						.setSocketTimeout(30000)
						.build())
			)
			.build();

		ElasticsearchTransport transport = new RestClientTransport(
			restClient, new JacksonJsonpMapper(objectMapper));

		return new ElasticsearchClient(transport);
	}
}
