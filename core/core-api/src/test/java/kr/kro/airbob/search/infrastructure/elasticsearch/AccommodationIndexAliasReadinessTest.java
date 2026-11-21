package kr.kro.airbob.search.infrastructure.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 색인 alias readiness")
class AccommodationIndexAliasReadinessTest {

	@Mock private AccommodationIndexAliasBootstrap bootstrap;

	@Test
	@DisplayName("alias bootstrap이 성공한 뒤에만 Kafka listener 자동 시작을 허용한다")
	void enablesListenerOnlyAfterAliasBootstrap() {
		AccommodationIndexAliasReadiness readiness =
			new AccommodationIndexAliasReadiness(bootstrap, true, true);

		assertThat(readiness.shouldAutoStart()).isFalse();
		readiness.afterPropertiesSet();

		then(bootstrap).should().ensureReady();
		assertThat(readiness.shouldAutoStart()).isTrue();
	}

	@Test
	@DisplayName("재색인 운영 설정으로 listener를 중지해도 alias는 준비한다")
	void bootstrapsAliasWhileListenerIsPaused() {
		AccommodationIndexAliasReadiness readiness =
			new AccommodationIndexAliasReadiness(bootstrap, true, false);

		readiness.afterPropertiesSet();

		then(bootstrap).should().ensureReady();
		assertThat(readiness.shouldAutoStart()).isFalse();
	}

	@Test
	@DisplayName("테스트 profile의 명시적 bootstrap off는 ES I/O 없이 listener를 중지한다")
	void disabledBootstrapDoesNotContactElasticsearch() {
		AccommodationIndexAliasBootstrap contextBootstrap =
			mock(AccommodationIndexAliasBootstrap.class);
		new ApplicationContextRunner()
			.withBean(AccommodationIndexAliasBootstrap.class, () -> contextBootstrap)
			.withBean(AccommodationIndexAliasReadiness.class)
			.withPropertyValues(
				"accommodation.indexing.bootstrap.enabled=false",
				"accommodation.indexing.kafka.auto-startup=false")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(AccommodationIndexAliasReadiness.class)
					.shouldAutoStart()).isFalse();
				then(contextBootstrap).shouldHaveNoInteractions();
			});
	}

	@Test
	@DisplayName("alias 검증 없이 listener만 시작하는 잘못된 설정은 기동을 거부한다")
	void rejectsListenerWithoutBootstrapReadiness() {
		assertThatThrownBy(() -> new AccommodationIndexAliasReadiness(
			bootstrap, false, true))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("requires alias bootstrap readiness");
	}
}
