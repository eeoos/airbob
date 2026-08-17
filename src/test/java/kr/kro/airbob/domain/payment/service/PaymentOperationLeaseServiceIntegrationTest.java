package kr.kro.airbob.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.domain.payment.config.PaymentOperationProperties;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	JpaAuditingConfig.class,
	QueryDslConfig.class,
	PaymentOperationLeaseService.class,
	PaymentOperationLeaseServiceIntegrationTest.LeaseTestConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentOperationLeaseServiceIntegrationTest {

	private static final UUID OPERATION_UID = UUID.fromString("183e6e9c-a9ad-4e64-bb73-4fb6e359aa51");
	private static final UUID RESERVATION_UID = UUID.fromString("65fb488c-a7b5-4d9c-b099-d818eea0b8ee");
	private static final UUID ACCOMMODATION_UID = UUID.fromString("38679b45-6e79-4088-b2cb-4dd3835c195b");
	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_payment_lease");

	@DynamicPropertySource
	static void setProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired private JdbcTemplate jdbc;
	@Autowired private PaymentOperationLeaseService service;
	@Autowired private HoldingClaimTransaction holdingClaimTransaction;

	@BeforeEach
	void insertReadyOperation() {
		jdbc.update("INSERT INTO member (email, nickname, role, status, updated_at) VALUES (?, ?, 'MEMBER', 'ACTIVE', NOW(6))",
			"payment-lease@test.com", "payment-lease-member");
		long memberId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		jdbc.update("""
			INSERT INTO accommodation (
			  member_id, check_in_time, check_out_time, accommodation_uid, updated_at, status, time_zone_id
			) VALUES (?, '15:00:00', '11:00:00', UNHEX(REPLACE(?, '-', '')), NOW(6), 'DRAFT', 'UTC')
			""", memberId, ACCOMMODATION_UID.toString());
		long accommodationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		jdbc.update("""
			INSERT INTO reservation (
			  reservation_uid, accommodation_id, guest_id, check_in_date, check_out_date,
			  check_in_at, check_out_at, time_zone_id, guest_count, total_price, discount_amount,
			  status, reservation_code, created_at, expires_at, updated_at, currency
			) VALUES (
			  UNHEX(REPLACE(?, '-', '')), ?, ?, '2026-08-15', '2026-08-16',
			  '2026-08-15 15:00:00', '2026-08-16 11:00:00', 'UTC', 2, 100000, 0,
			  'PAYMENT_PROCESSING', 'LEASE0001', NOW(6), '2026-08-14 01:00:00', NOW(6), 'KRW'
			)
			""", RESERVATION_UID.toString(), accommodationId, memberId);
		long reservationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		jdbc.update("""
			INSERT INTO payment_operation (
			  operation_uid, reservation_id, requester_member_id, operation_type, status,
			  payment_key, expected_amount, provider_idempotency_key, deduplication_key,
			  attempt_count, next_attempt_at, last_enqueued_at, version, created_at, updated_at
			) VALUES (
			  UNHEX(REPLACE(?, '-', '')), ?, ?, 'CONFIRM', 'READY',
			  'payment-key', 100000, 'provider-key', ?,
			  0, '2026-08-14 00:00:00', '2026-08-14 00:00:00', 0, NOW(6), NOW(6)
			)
			""", OPERATION_UID.toString(), reservationId, memberId, "CONFIRM:" + RESERVATION_UID);
	}

	@Test
	void pessimisticLockSerializesTwoProxiedClaimsAndCommitsOneLease() throws Exception {
		assertThat(AopUtils.isAopProxy(service)).isTrue();
		assertThat(AopUtils.isAopProxy(holdingClaimTransaction)).isTrue();
		CountDownLatch firstClaimed = new CountDownLatch(1);
		CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
		CountDownLatch secondStarted = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			CompletableFuture<PaymentOperationClaimResult> first = CompletableFuture.supplyAsync(
				() -> holdingClaimTransaction.claimAndHold(
					OPERATION_UID, firstClaimed, releaseFirstTransaction), executor);
			assertThat(firstClaimed.await(5, TimeUnit.SECONDS)).isTrue();

			CompletableFuture<PaymentOperationClaimResult> second = CompletableFuture.supplyAsync(() -> {
				secondStarted.countDown();
				return service.claim(OPERATION_UID);
			}, executor);
			assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS))
				.isInstanceOf(TimeoutException.class);

			releaseFirstTransaction.countDown();
			PaymentExecution claimed = first.get(5, TimeUnit.SECONDS).execution().orElseThrow();
			PaymentOperationClaimResult secondResult = second.get(5, TimeUnit.SECONDS);
			assertThat(secondResult.execution()).isEmpty();
			assertThat(secondResult.manualReviewNotice()).isEmpty();

			Map<String, Object> row = jdbc.queryForMap("""
				SELECT status, attempt_count, lease_owner
				FROM payment_operation
				WHERE operation_uid = UNHEX(REPLACE(?, '-', ''))
				""", OPERATION_UID.toString());
			assertThat(row)
				.containsEntry("status", "EXECUTING")
				.containsEntry("attempt_count", 1)
				.containsEntry("lease_owner", claimed.leaseOwner());
		} finally {
			releaseFirstTransaction.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class LeaseTestConfiguration {
		@Bean
		Clock paymentLeaseTestClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		@Bean
		PaymentOperationProperties paymentOperationProperties() {
			return new PaymentOperationProperties(
				Duration.ofSeconds(30), Duration.ofSeconds(10), 100, 5,
				Duration.ofSeconds(10), Duration.ofMinutes(5), Duration.ofSeconds(10));
		}

		@Bean
		PaymentRetryBackoff paymentRetryBackoff(PaymentOperationProperties properties) {
			return new PaymentRetryBackoff(properties.retryInitialDelay(), properties.retryMaxDelay());
		}

		@Bean
		HoldingClaimTransaction holdingClaimTransaction(PaymentOperationLeaseService service) {
			return new HoldingClaimTransaction(service);
		}
	}

	static class HoldingClaimTransaction {
		private final PaymentOperationLeaseService service;

		HoldingClaimTransaction(PaymentOperationLeaseService service) {
			this.service = service;
		}

		@Transactional
		public PaymentOperationClaimResult claimAndHold(
			UUID operationUid,
			CountDownLatch claimed,
			CountDownLatch release
		) {
			PaymentOperationClaimResult result = service.claim(operationUid);
			claimed.countDown();
			try {
				if (!release.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("timed out waiting to release the first claim transaction");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted while holding the first claim transaction", exception);
			}
			return result;
		}
	}
}
