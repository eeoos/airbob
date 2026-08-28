package kr.kro.airbob.common.benchmark;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@Profile("read-model-benchmark & traffic-benchmark")
@ConditionalOnProperty(prefix = "benchmark.read-model", name = "enabled", havingValue = "true")
@RequestMapping("/api/v2/benchmark/read-model")
public class ReadModelRuntimeAssertionController {

	private final BenchmarkAccessGuard accessGuard;
	private final ReadModelRuntimeAssertionService assertionService;

	@PostMapping("/runtime-assertion")
	public ResponseEntity<ReadModelRuntimeAssertionService.Response> assertRuntime(
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String benchmarkToken,
		@RequestBody ReadModelRuntimeAssertionService.Request request
	) {
		accessGuard.verify(benchmarkToken);
		return ResponseEntity.ok(assertionService.assertRuntime(request));
	}
}
