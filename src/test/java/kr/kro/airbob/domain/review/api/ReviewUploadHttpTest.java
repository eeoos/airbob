package kr.kro.airbob.domain.review.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.common.exception.GlobalExceptionHandler;
import kr.kro.airbob.domain.review.service.ReviewService;
import kr.kro.airbob.domain.auth.filter.SessionAuthFilter;
import kr.kro.airbob.domain.auth.resolver.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.domain.image.dto.ImageResponse;

@SpringBootTest(classes = ReviewUploadHttpTest.Application.class,
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReviewUploadHttpTest {

	private static final int MIB = 1024 * 1024;
	private static final String BOUNDARY = "airbob-documentation-multipart-boundary";
	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
		.version(HttpClient.Version.HTTP_1_1).build();

	@LocalServerPort int port;
	@Autowired ObjectMapper mapper;
	@MockitoBean ReviewService reviewService;
	@MockitoBean RedisTemplate<String, Object> redis;

	@BeforeEach
	void session() {
		ValueOperations<String, Object> values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		when(values.get("SESSION:upload-contract-session")).thenReturn(10L);
		when(redis.hasKey("MEMBER_SESSION_ACTIVE:10")).thenReturn(true);
	}

	@Test
	void repeatedImagePartsBindToRealController() throws Exception {
		when(reviewService.uploadReviewImages(eq(101L), anyList(), eq(10L)))
			.thenReturn(new ImageResponse.ImageUploadResult(List.of(new ImageResponse.ImageInfo(1L, "https://example.invalid/image.png"))));
		var response = upload(2, 3);
		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(mapper.readTree(response.body()).at("/data/uploaded_images/0/id").asLong()).isEqualTo(1L);
		verify(reviewService).uploadReviewImages(eq(101L), argThat(files -> files.size() == 2), eq(10L));
	}

	@Test
	void missingImagePartIsBadRequest() throws Exception {
		assertError(upload(), 400, "C001");
	}

	@Test
	void jsonBodyIsRejectedAsUnsupportedMediaType() throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/reviews/101/images"))
			.timeout(Duration.ofSeconds(5)).header("Cookie", "SESSION_ID=upload-contract-session")
			.header("Content-Type", "application/json").POST(BodyPublishers.ofString("{}"))
			.build();
		assertError(client.send(request, HttpResponse.BodyHandlers.ofString()), 415, "C011");
	}

	@Test
	void fileOverTenMiBIsRejectedByServletMultipartLimit() throws Exception {
		assertError(upload(10 * MIB + 1), 413, "I009");
	}

	@Test
	void declaredRequestOverTwentyMiBIsRejectedBeforeReadingFiles() throws Exception {
		// Send only headers: streaming an oversized body can trigger Tomcat's separate swallow limit
		// and reset the connection before an HTTP client observes the multipart error response.
		try (Socket socket = new Socket("127.0.0.1", port)) {
			socket.setSoTimeout(5000);
			String headers = "POST /api/v1/reviews/101/images HTTP/1.1\r\n"
				+ "Host: 127.0.0.1:" + port + "\r\n"
				+ "Cookie: SESSION_ID=upload-contract-session\r\n"
				+ "Content-Type: multipart/form-data; boundary=" + BOUNDARY + "\r\n"
				+ "Content-Length: " + (20 * MIB + 1) + "\r\nExpect: 100-continue\r\n\r\n";
			socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
			socket.getOutputStream().flush();
			BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			String status = reader.readLine();
			if (status != null && status.startsWith("HTTP/1.1 100")) {
				while (!reader.readLine().isEmpty()) { }
				status = reader.readLine();
			}
			assertThat(status).startsWith("HTTP/1.1 413");
		}
		verifyNoInteractions(reviewService);
	}

	private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(mapper.readTree(response.body()).at("/error/code").asText()).isEqualTo(code);
		verifyNoInteractions(reviewService);
	}

	private HttpResponse<String> upload(int... sizes) throws Exception {
		List<BodyPublisher> parts = new ArrayList<>();
		for (int index = 0; index < sizes.length; index++) {
			parts.add(BodyPublishers.ofString("--" + BOUNDARY + "\r\n"
				+ "Content-Disposition: form-data; name=\"images\"; filename=\"image-" + index + ".png\"\r\n"
				+ "Content-Type: image/png\r\n\r\n"));
			parts.add(BodyPublishers.ofByteArray(new byte[sizes[index]]));
			parts.add(BodyPublishers.ofString("\r\n"));
		}
		parts.add(BodyPublishers.ofString("--" + BOUNDARY + "--\r\n"));
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/reviews/101/images"))
			.timeout(Duration.ofSeconds(15)).expectContinue(true)
			.header("Cookie", "SESSION_ID=upload-contract-session")
			.header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
			.POST(BodyPublishers.concat(parts.toArray(BodyPublisher[]::new))).build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	@Configuration(proxyBeanMethods = false)
	@ImportAutoConfiguration({PropertyPlaceholderAutoConfiguration.class, ServletWebServerFactoryAutoConfiguration.class,
		DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class, MultipartAutoConfiguration.class,
		JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
	@Import({ReviewController.class, GlobalExceptionHandler.class, SessionAuthFilter.class, CurrentMemberIdArgumentResolver.class})
	static class Application {
		@Bean
		WebMvcConfigurer memberIdResolver(CurrentMemberIdArgumentResolver resolver) {
			return new WebMvcConfigurer() {
				@Override
				public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
					resolvers.add(resolver);
				}
			};
		}
	}
}
