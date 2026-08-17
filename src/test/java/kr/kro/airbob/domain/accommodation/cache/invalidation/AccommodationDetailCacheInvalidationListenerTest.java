package kr.kro.airbob.domain.accommodation.cache.invalidation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCache;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheInvalidationReason;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;

@SpringJUnitConfig(AccommodationDetailCacheInvalidationListenerTest.TestConfiguration.class)
@DisplayName("숙소 상세 캐시 무효화 트랜잭션 이벤트 테스트")
class AccommodationDetailCacheInvalidationListenerTest {

	@Autowired private AccommodationDetailCacheInvalidationPublisher publisher;
	@Autowired private AccommodationDetailCache cache;
	@Autowired private OutboxEventPublisher outboxEventPublisher;
	@Autowired private PlatformTransactionManager transactionManager;

	@BeforeEach
	void resetCacheMock() {
		reset(cache, outboxEventPublisher);
	}

	@Test
	@DisplayName("트랜잭션이 커밋된 뒤에만 상세 캐시를 무효화한다")
	void evictAfterCommit() {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		transaction.executeWithoutResult(status -> {
			publisher.publish(1L, AccommodationDetailCacheInvalidationReason.IMAGE);

			verify(outboxEventPublisher).save(
				eq(EventType.CACHE_INVALIDATION_REQUESTED),
				argThat(payload ->
					payload instanceof AccommodationDetailCacheInvalidationRequestedEvent event
						&& event.accommodationId().equals(1L)
						&& event.reason() == AccommodationDetailCacheInvalidationReason.IMAGE));
			verify(cache, never()).evict(anyLong(), any());
		});

		verify(cache).evict(1L, AccommodationDetailCacheInvalidationReason.IMAGE);
	}

	@Test
	@DisplayName("트랜잭션이 롤백되면 상세 캐시를 유지한다")
	void keepCacheAfterRollback() {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		transaction.executeWithoutResult(status -> {
			publisher.publish(1L, AccommodationDetailCacheInvalidationReason.REVIEW);
			status.setRollbackOnly();
		});

		verifyNoInteractions(cache);
	}

	@Test
	@DisplayName("활성 트랜잭션 밖에서는 원본 변경과 분리된 outbox 저장을 허용하지 않는다")
	void requiresActiveTransaction() {
		assertThatThrownBy(() -> publisher.publish(
			1L, AccommodationDetailCacheInvalidationReason.ACCOMMODATION))
			.isInstanceOf(IllegalTransactionStateException.class);

		verifyNoInteractions(cache, outboxEventPublisher);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableTransactionManagement
	@Import({
		AccommodationDetailCacheInvalidationPublisher.class,
		AccommodationDetailCacheInvalidationListener.class
	})
	static class TestConfiguration {

		@Bean
		AccommodationDetailCache accommodationDetailCache() {
			return mock(AccommodationDetailCache.class);
		}

		@Bean
		OutboxEventPublisher outboxEventPublisher() {
			return mock(OutboxEventPublisher.class);
		}

		@Bean
		PlatformTransactionManager transactionManager() {
			return new TestTransactionManager();
		}
	}

	private static class TestTransactionManager extends AbstractPlatformTransactionManager {
		private final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);

		@Override
		protected Object doGetTransaction() {
			return new Object();
		}

		@Override
		protected boolean isExistingTransaction(Object transaction) {
			return active.get();
		}

		@Override
		protected void doBegin(Object transaction, TransactionDefinition definition) {
			active.set(true);
		}

		@Override
		protected void doCommit(DefaultTransactionStatus status) {
		}

		@Override
		protected void doRollback(DefaultTransactionStatus status) {
		}

		@Override
		protected void doCleanupAfterCompletion(Object transaction) {
			active.remove();
		}
	}
}
