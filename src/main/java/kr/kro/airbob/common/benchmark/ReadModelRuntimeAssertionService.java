package kr.kro.airbob.common.benchmark;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.config.SchedulingConfig;

@Service
@Profile("read-model-benchmark & traffic-benchmark")
@ConditionalOnProperty(prefix = "benchmark.read-model", name = "enabled", havingValue = "true")
public class ReadModelRuntimeAssertionService {

	private static final Pattern RUN_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{2,31}$");
	private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");
	private static final Pattern INSTANCE_ID = Pattern.compile("^i-[0-9a-f]{8,17}$");

	private final Environment environment;
	private final ApplicationContext applicationContext;
	private final KafkaListenerEndpointRegistry kafkaRegistry;
	private final String expectedRunId;
	private final String expectedResourceFencingTokenSha256;
	private final String runtimeRevision;
	private final String appInstanceId;

	public ReadModelRuntimeAssertionService(
		Environment environment,
		ApplicationContext applicationContext,
		@Nullable KafkaListenerEndpointRegistry kafkaRegistry,
		@Value("${benchmark.read-model.run-id:}") String expectedRunId,
		@Value("${benchmark.read-model.resource-fencing-token-sha256:}") String expectedResourceFencingTokenSha256,
		@Value("${benchmark.read-model.runtime-revision:}") String runtimeRevision,
		@Value("${benchmark.read-model.app-instance-id:}") String appInstanceId
	) {
		if (!RUN_ID.matcher(expectedRunId).matches()
			|| !SHA_256.matcher(expectedResourceFencingTokenSha256).matches()
			|| !SHA_256.matcher(runtimeRevision).matches()
			|| !INSTANCE_ID.matcher(appInstanceId).matches()) {
			throw new IllegalArgumentException("read-model runtime identity is invalid");
		}
		this.environment = environment;
		this.applicationContext = applicationContext;
		this.kafkaRegistry = kafkaRegistry;
		this.expectedRunId = expectedRunId;
		this.expectedResourceFencingTokenSha256 = expectedResourceFencingTokenSha256;
		this.runtimeRevision = runtimeRevision;
		this.appInstanceId = appInstanceId;
	}

	public Response assertRuntime(Request request) {
		if (request == null
			|| !RUN_ID.matcher(request.runId() == null ? "" : request.runId()).matches()
			|| !SHA_256.matcher(request.resourceFencingTokenSha256() == null ? "" : request.resourceFencingTokenSha256()).matches()
			|| !SHA_256.matcher(request.challengeSha256() == null ? "" : request.challengeSha256()).matches()
			|| !expectedRunId.equals(request.runId())
			|| !MessageDigest.isEqual(
				expectedResourceFencingTokenSha256.getBytes(StandardCharsets.US_ASCII),
				request.resourceFencingTokenSha256().getBytes(StandardCharsets.US_ASCII)
			)) {
			throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
		}

		List<String> activeProfiles = Arrays.stream(environment.getActiveProfiles())
			.sorted()
			.toList();
		boolean schedulerEnabled = !applicationContext.getBeansOfType(SchedulingConfig.class).isEmpty();
		boolean kafkaListenerEnabled = enabled("spring.kafka.listener.auto-startup")
			|| enabled("operator-alert.kafka.auto-startup")
			|| enabled("accommodation.indexing.kafka.auto-startup")
			|| enabled("accommodation.detail-cache.invalidation.kafka.auto-startup")
			|| kafkaRegistry != null && kafkaRegistry.getListenerContainers().stream().anyMatch(container -> container.isRunning());
		boolean inventoryLifecycleEnabled = enabled("reservation.inventory.startup.enabled")
			|| enabled("reservation.inventory.seed.enabled")
			|| enabled("reservation.inventory.retention.enabled");
		boolean externalSideEffectsEnabled = enabled("accommodation.indexing.bootstrap.enabled")
			|| enabled("payment.toss.enabled")
			|| enabled("google.api.enabled")
			|| enabled("operator-alert.slack.enabled")
			|| enabled("cloud.aws.s3.write-enabled");

		return new Response(
			1,
			expectedRunId,
			expectedResourceFencingTokenSha256,
			request.challengeSha256(),
			runtimeRevision,
			appInstanceId,
			activeProfiles,
			schedulerEnabled,
			kafkaListenerEnabled,
			inventoryLifecycleEnabled,
			externalSideEffectsEnabled
		);
	}

	private boolean enabled(String property) {
		return environment.getProperty(property, Boolean.class, true);
	}

	public record Request(String runId, String resourceFencingTokenSha256, String challengeSha256) {
	}

	public record Response(
		int schemaVersion,
		String runId,
		String resourceFencingTokenSha256,
		String challengeSha256,
		String runtimeRevision,
		String appInstanceId,
		List<String> activeProfiles,
		boolean schedulerEnabled,
		boolean kafkaListenerEnabled,
		boolean inventoryLifecycleEnabled,
		boolean externalSideEffectsEnabled
	) {
	}
}
