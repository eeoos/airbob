package kr.kro.airbob.common.benchmark;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.mock.env.MockEnvironment;

import kr.kro.airbob.common.exception.BaseException;

class ReadModelRuntimeAssertionServiceTest {

	private static final String RUNTIME_REVISION = "a".repeat(64);
	private static final String INSTANCE_ID = "i-0123456789abcdef0";
	private static final String DIGEST = "b".repeat(64);
	private static final String RUN_ID = "read-model-run";

	@Test
	void reportsTheExactIsolatedReadRuntimeWithoutReturningRawFenceOrChallenge() {
		MockEnvironment environment = isolatedReadEnvironment();
		ApplicationContext context = mock(ApplicationContext.class);
		given(context.getBeansOfType(kr.kro.airbob.config.SchedulingConfig.class))
			.willReturn(Map.of());
		KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
		given(registry.getListenerContainers()).willReturn(java.util.List.of());
		ReadModelRuntimeAssertionService service = new ReadModelRuntimeAssertionService(
			environment, context, registry, RUN_ID, DIGEST, RUNTIME_REVISION, INSTANCE_ID
		);

		var response = service.assertRuntime(new ReadModelRuntimeAssertionService.Request(
			RUN_ID, DIGEST, "c".repeat(64)
		));

		assertThat(response.runtimeRevision()).isEqualTo(RUNTIME_REVISION);
		assertThat(response.appInstanceId()).isEqualTo(INSTANCE_ID);
		assertThat(response.activeProfiles()).containsExactly(
			"aws", "read-model-benchmark", "traffic-benchmark"
		);
		assertThat(response.schedulerEnabled()).isFalse();
		assertThat(response.kafkaListenerEnabled()).isFalse();
		assertThat(response.inventoryLifecycleEnabled()).isFalse();
		assertThat(response.externalSideEffectsEnabled()).isFalse();
		assertThat(response.toString()).doesNotContain("resource-fencing-token", "fresh-challenge");
	}

	@Test
	void reportsProfileAndWriterDriftForTheRunnerToReject() {
		MockEnvironment environment = isolatedReadEnvironment()
			.withProperty("reservation.inventory.seed.enabled", "true")
			.withProperty("accommodation.indexing.bootstrap.enabled", "true");
		environment.setActiveProfiles("aws", "read-model-benchmark");
		ApplicationContext context = mock(ApplicationContext.class);
		given(context.getBeansOfType(kr.kro.airbob.config.SchedulingConfig.class))
			.willReturn(Map.of("schedulingConfig", mock(kr.kro.airbob.config.SchedulingConfig.class)));
		KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
		given(registry.getListenerContainers()).willReturn(java.util.List.of());
		ReadModelRuntimeAssertionService service = new ReadModelRuntimeAssertionService(
			environment, context, registry, RUN_ID, DIGEST, RUNTIME_REVISION, INSTANCE_ID
		);

		var response = service.assertRuntime(new ReadModelRuntimeAssertionService.Request(
			RUN_ID, DIGEST, "c".repeat(64)
		));

		assertThat(response.activeProfiles()).containsExactly("aws", "read-model-benchmark");
		assertThat(response.schedulerEnabled()).isTrue();
		assertThat(response.inventoryLifecycleEnabled()).isTrue();
		assertThat(response.externalSideEffectsEnabled()).isTrue();
	}

	@Test
	void reportsAListenerThatIsRunningDespiteDisabledAutoStartup() {
		ApplicationContext context = mock(ApplicationContext.class);
		given(context.getBeansOfType(kr.kro.airbob.config.SchedulingConfig.class))
			.willReturn(Map.of());
		KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
		MessageListenerContainer listener = mock(MessageListenerContainer.class);
		given(listener.isRunning()).willReturn(true);
		given(registry.getListenerContainers()).willReturn(java.util.List.of(listener));
		ReadModelRuntimeAssertionService service = new ReadModelRuntimeAssertionService(
			isolatedReadEnvironment(), context, registry,
			RUN_ID, DIGEST, RUNTIME_REVISION, INSTANCE_ID
		);

		var response = service.assertRuntime(new ReadModelRuntimeAssertionService.Request(
			RUN_ID, DIGEST, "c".repeat(64)
		));

		assertThat(response.kafkaListenerEnabled()).isTrue();
	}

	@Test
	void acceptsAnAbsentKafkaRegistryWhenAllListenerPropertiesAreDisabled() {
		ApplicationContext context = mock(ApplicationContext.class);
		given(context.getBeansOfType(kr.kro.airbob.config.SchedulingConfig.class))
			.willReturn(Map.of());
		ReadModelRuntimeAssertionService service = new ReadModelRuntimeAssertionService(
			isolatedReadEnvironment(), context, null,
			RUN_ID, DIGEST, RUNTIME_REVISION, INSTANCE_ID
		);

		var response = service.assertRuntime(new ReadModelRuntimeAssertionService.Request(
			RUN_ID, DIGEST, "c".repeat(64)
		));

		assertThat(response.kafkaListenerEnabled()).isFalse();
	}

	@Test
	void rejectsRawOrMalformedRunFenceAndChallengeValues() {
		ReadModelRuntimeAssertionService service = new ReadModelRuntimeAssertionService(
			isolatedReadEnvironment(), mock(ApplicationContext.class),
			mock(KafkaListenerEndpointRegistry.class), RUN_ID, DIGEST, RUNTIME_REVISION, INSTANCE_ID
		);

		assertThatThrownBy(() -> service.assertRuntime(
			new ReadModelRuntimeAssertionService.Request("bad run", "17", "fresh-challenge")
		)).isInstanceOf(BaseException.class);

		assertThatThrownBy(() -> service.assertRuntime(
			new ReadModelRuntimeAssertionService.Request("another-run", DIGEST, "c".repeat(64))
		)).isInstanceOf(BaseException.class);
		assertThatThrownBy(() -> service.assertRuntime(
			new ReadModelRuntimeAssertionService.Request(RUN_ID, "d".repeat(64), "c".repeat(64))
		)).isInstanceOf(BaseException.class);
	}

	@Test
	void rejectsMissingRuntimeIdentity() {
		assertThatThrownBy(() -> new ReadModelRuntimeAssertionService(
			isolatedReadEnvironment(), mock(ApplicationContext.class),
			mock(KafkaListenerEndpointRegistry.class), "", "", "", ""
		)).isInstanceOf(IllegalArgumentException.class);
	}

	private MockEnvironment isolatedReadEnvironment() {
		MockEnvironment environment = new MockEnvironment()
			.withProperty("spring.kafka.listener.auto-startup", "false")
			.withProperty("operator-alert.kafka.auto-startup", "false")
			.withProperty("accommodation.indexing.kafka.auto-startup", "false")
			.withProperty("accommodation.detail-cache.invalidation.kafka.auto-startup", "false")
			.withProperty("reservation.inventory.startup.enabled", "false")
			.withProperty("reservation.inventory.seed.enabled", "false")
			.withProperty("reservation.inventory.retention.enabled", "false")
			.withProperty("accommodation.indexing.bootstrap.enabled", "false")
			.withProperty("payment.toss.enabled", "false")
			.withProperty("google.api.enabled", "false")
			.withProperty("operator-alert.slack.enabled", "false")
			.withProperty("cloud.aws.s3.write-enabled", "false");
		environment.setActiveProfiles(
			"aws", "traffic-benchmark", "read-model-benchmark"
		);
		return environment;
	}
}
