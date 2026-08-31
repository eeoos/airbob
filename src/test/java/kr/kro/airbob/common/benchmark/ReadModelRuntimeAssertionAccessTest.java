package kr.kro.airbob.common.benchmark;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.common.exception.GlobalExceptionHandler;
import kr.kro.airbob.domain.auth.filter.SessionAuthFilter;

@ExtendWith(MockitoExtension.class)
class ReadModelRuntimeAssertionAccessTest {

	private static final String TOKEN = "read-model-secret";
	private static final String PATH = "/api/v2/benchmark/read-model/runtime-assertion";
	private static final ReadModelRuntimeAssertionService.Request REQUEST =
		new ReadModelRuntimeAssertionService.Request(
			"read-model-run", "a".repeat(64), "b".repeat(64)
		);

	@Mock
	private ReadModelRuntimeAssertionService assertionService;
	@Mock
	private RedisTemplate<String, Object> redisTemplate;

	private MockMvc mockMvc;
	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();
		BenchmarkAccessGuard accessGuard = new BenchmarkAccessGuard(TOKEN);
		ReadModelRuntimeAssertionController controller =
			new ReadModelRuntimeAssertionController(accessGuard, assertionService);
		SessionAuthFilter sessionAuthFilter = new SessionAuthFilter(redisTemplate, objectMapper);
		ReadModelBenchmarkIsolationFilter isolationFilter =
			new ReadModelBenchmarkIsolationFilter(accessGuard);
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.addFilters(isolationFilter, sessionAuthFilter)
			.build();
	}

	@Test
	void tokenOnlyPostReachesTheRuntimeAssertionController() throws Exception {
		var response = new ReadModelRuntimeAssertionService.Response(
			1, "read-model-run", "a".repeat(64), "b".repeat(64), "c".repeat(64),
			"i-0123456789abcdef0",
			List.of("aws", "read-model-benchmark", "traffic-benchmark"),
			false, false, false, false
		);
		given(assertionService.assertRuntime(REQUEST)).willReturn(response);

		mockMvc.perform(post(PATH)
				.header(BenchmarkAccessGuard.HEADER_NAME, TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(REQUEST)))
			.andExpect(status().isOk());

		then(assertionService).should().assertRuntime(REQUEST);
	}

	@Test
	void missingOrInvalidBenchmarkTokenNeverReadsRuntimeState() throws Exception {
		mockMvc.perform(post(PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(REQUEST)))
			.andExpect(status().isForbidden());

		mockMvc.perform(post(PATH)
				.header(BenchmarkAccessGuard.HEADER_NAME, "wrong-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(REQUEST)))
			.andExpect(status().isForbidden());

		then(assertionService).shouldHaveNoInteractions();
	}
}
