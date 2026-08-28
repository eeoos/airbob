package kr.kro.airbob.common.benchmark;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class ReadModelRuntimeAssertionControllerTest {

	@Test
	void verifiesTheBenchmarkTokenBeforeReadingRuntimeState() {
		BenchmarkAccessGuard guard = mock(BenchmarkAccessGuard.class);
		ReadModelRuntimeAssertionService service = mock(ReadModelRuntimeAssertionService.class);
		ReadModelRuntimeAssertionController controller =
			new ReadModelRuntimeAssertionController(guard, service);
		var request = new ReadModelRuntimeAssertionService.Request(
			"read-model-run", "a".repeat(64), "b".repeat(64)
		);
		var expected = new ReadModelRuntimeAssertionService.Response(
			1, "read-model-run", "a".repeat(64), "b".repeat(64), "c".repeat(64),
			"i-0123456789abcdef0",
			List.of("aws", "read-model-benchmark", "traffic-benchmark"),
			false, false, false, false
		);
		given(service.assertRuntime(request)).willReturn(expected);

		var response = controller.assertRuntime("benchmark-token", request);

		then(guard).should().verify("benchmark-token");
		then(service).should().assertRuntime(request);
		assertThat(response.getBody()).isEqualTo(expected);
	}
}
