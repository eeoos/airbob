package kr.kro.airbob.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder;

@Testcontainers
class CouponRedisStockManagerIntegrationTest {

	private static final long MINUTE = 60_000L;

	@Container
	private static final GenericContainer<?> REDIS = new GenericContainer<>(
		DockerImageName.parse("redis:7.2-alpine"))
		.withExposedPorts(6379);

	private static RedissonClient redissonClient;
	private static CouponRedisStockManager stockManager;
	private static CouponIssueMetricRecorder metricRecorder;

	@BeforeAll
	static void setUpClient() {
		Config config = new Config();
		config.useSingleServer()
			.setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
		redissonClient = Redisson.create(config);
		metricRecorder = org.mockito.Mockito.mock(CouponIssueMetricRecorder.class);
		stockManager = new CouponRedisStockManager(redissonClient, metricRecorder);
		stockManager.loadScripts();
	}

	@AfterAll
	static void shutDownClient() {
		if (redissonClient != null) {
			redissonClient.shutdown();
		}
	}

	@BeforeEach
	void clearCouponKeys() {
		redissonClient.getKeys().deleteByPattern("coupon:*");
		reset(metricRecorder);
	}

	@Test
	@DisplayName("쿠폰 재고는 기존 키를 덮어쓰지 않고 한 번만 준비한다")
	void preparesOnlyOnceWithoutOverwriting() {
		long now = System.currentTimeMillis();

		assertThat(stockManager.prepare(1L, 2, now - MINUTE, now + MINUTE, true, now + 2 * MINUTE))
			.isEqualTo(CouponRedisPreparationResult.PREPARED);
		assertThat(stockManager.prepare(1L, 100, now - MINUTE, now + MINUTE, true, now + 2 * MINUTE))
			.isEqualTo(CouponRedisPreparationResult.ALREADY_PREPARED);

		assertThat(stockManager.issue(1L, 10L))
			.isEqualTo(CouponRedisIssueResult.approved(1));
		assertThat(stockManager.remainingStock(1L)).isEqualTo(1);
		assertThat(redissonClient.getMap(
			CouponRedisStockManager.metaKey(1L), StringCodec.INSTANCE).get("unlimited"))
			.isEqualTo("0");
	}

	@Test
	@DisplayName("무제한 쿠폰은 고유 회원을 계속 승인하고 보상해도 재고 센티넬을 변경하지 않는다")
	void supportsUnlimitedIssueAndCompensationWithoutChangingStockSentinel() {
		long now = System.currentTimeMillis();
		long expiresAt = now + 2 * MINUTE;

		assertThat(stockManager.prepare(
			50L, null, now - MINUTE, now + MINUTE, true, expiresAt))
			.isEqualTo(CouponRedisPreparationResult.PREPARED);
		assertThat(stockManager.prepare(
			50L, 10, now - MINUTE, now + MINUTE, true, expiresAt))
			.isEqualTo(CouponRedisPreparationResult.ALREADY_PREPARED);

		RMap<String, String> meta = redissonClient.getMap(
			CouponRedisStockManager.metaKey(50L), StringCodec.INSTANCE);
		assertThat(meta.get("unlimited")).isEqualTo("1");
		assertThat(meta.get("stock")).isEqualTo("0");

		assertThat(stockManager.issue(50L, 1L)).isEqualTo(CouponRedisIssueResult.approved(0));
		assertThat(stockManager.issue(50L, 2L)).isEqualTo(CouponRedisIssueResult.approved(0));
		assertThat(stockManager.issue(50L, 1L).status()).isEqualTo(CouponRedisIssueStatus.DUPLICATE);
		assertThat(meta.get("stock")).isEqualTo("0");

		assertThat(stockManager.compensate(50L, 1L))
			.isEqualTo(CouponRedisCompensationResult.COMPENSATED);
		assertThat(meta.get("stock")).isEqualTo("0");
		assertThat(stockManager.compensate(50L, 1L))
			.isEqualTo(CouponRedisCompensationResult.NO_OP);
		assertThat(stockManager.issue(50L, 1L)).isEqualTo(CouponRedisIssueResult.approved(0));
		assertThat(meta.get("stock")).isEqualTo("0");

		long metaTtl = meta.remainTimeToLive();
		long issuedTtl = redissonClient.getSet(
			CouponRedisStockManager.issuedKey(50L), StringCodec.INSTANCE).remainTimeToLive();
		assertThat(metaTtl).isBetween(MINUTE, 2 * MINUTE);
		assertThat(issuedTtl).isBetween(MINUTE, 2 * MINUTE);
		assertThat(Math.abs(metaTtl - issuedTtl)).isLessThan(1_000L);
	}

	@Test
	@DisplayName("무제한 쿠폰도 비활성·시작 전·종료 상태에서는 발급하지 않는다")
	void enforcesActiveAndIssueWindowForUnlimitedCoupons() {
		long now = System.currentTimeMillis();

		stockManager.prepare(52L, null, now - MINUTE, now + MINUTE, false, now + 2 * MINUTE);
		assertThat(stockManager.issue(52L, 1L).status()).isEqualTo(CouponRedisIssueStatus.INACTIVE);

		stockManager.prepare(53L, null, now + MINUTE, now + 2 * MINUTE, true, now + 3 * MINUTE);
		assertThat(stockManager.issue(53L, 1L).status()).isEqualTo(CouponRedisIssueStatus.NOT_STARTED);

		stockManager.prepare(54L, null, now - 2 * MINUTE, now - MINUTE, true, now + MINUTE);
		assertThat(stockManager.issue(54L, 1L).status()).isEqualTo(CouponRedisIssueStatus.ENDED);
	}

	@Test
	@DisplayName("무제한 마커가 없는 기존 Redis 메타데이터는 유한 재고로 발급·보상한다")
	void treatsLegacyMetadataWithoutUnlimitedMarkerAsFinite() {
		long now = System.currentTimeMillis();
		long expiresAt = now + 2 * MINUTE;
		RMap<String, String> meta = redissonClient.getMap(
			CouponRedisStockManager.metaKey(51L), StringCodec.INSTANCE);
		meta.putAll(Map.of(
			"stock", "1",
			"issueStartAt", String.valueOf(now - MINUTE),
			"issueEndAt", String.valueOf(now + MINUTE),
			"active", "1",
			"expiresAt", String.valueOf(expiresAt)));
		meta.expire(Instant.ofEpochMilli(expiresAt));

		assertThat(meta.get("unlimited")).isNull();
		assertThat(stockManager.issue(51L, 1L)).isEqualTo(CouponRedisIssueResult.approved(0));
		assertThat(stockManager.issue(51L, 2L).status()).isEqualTo(CouponRedisIssueStatus.SOLD_OUT);
		assertThat(stockManager.compensate(51L, 1L))
			.isEqualTo(CouponRedisCompensationResult.COMPENSATED);
		assertThat(stockManager.remainingStock(51L)).isEqualTo(1);
		assertThat(stockManager.issue(51L, 2L)).isEqualTo(CouponRedisIssueResult.approved(0));
	}

	@Test
	@DisplayName("Lua는 미준비·비활성·시작 전·종료·중복·매진을 구분한다")
	void distinguishesAllIssueResultsUsingRedisTime() {
		long now = System.currentTimeMillis();

		assertThat(stockManager.issue(10L, 1L).status())
			.isEqualTo(CouponRedisIssueStatus.UNPREPARED);

		stockManager.prepare(11L, 1, now - MINUTE, now + MINUTE, false, now + 2 * MINUTE);
		assertThat(stockManager.issue(11L, 1L).status())
			.isEqualTo(CouponRedisIssueStatus.INACTIVE);

		stockManager.prepare(12L, 1, now + MINUTE, now + 2 * MINUTE, true, now + 3 * MINUTE);
		assertThat(stockManager.issue(12L, 1L).status())
			.isEqualTo(CouponRedisIssueStatus.NOT_STARTED);

		stockManager.prepare(13L, 1, now - 2 * MINUTE, now - MINUTE, true, now + MINUTE);
		assertThat(stockManager.issue(13L, 1L).status())
			.isEqualTo(CouponRedisIssueStatus.ENDED);

		stockManager.prepare(14L, 1, now - MINUTE, now + MINUTE, true, now + 2 * MINUTE);
		assertThat(stockManager.issue(14L, 1L))
			.isEqualTo(CouponRedisIssueResult.approved(0));
		assertThat(stockManager.issue(14L, 1L).status())
			.as("중복 회원은 재고가 0이어도 중복으로 판정한다")
			.isEqualTo(CouponRedisIssueStatus.DUPLICATE);
		assertThat(stockManager.issue(14L, 2L).status())
			.isEqualTo(CouponRedisIssueStatus.SOLD_OUT);
	}

	@Test
	@DisplayName("보상은 실제 승인 회원을 제거한 첫 호출에서만 재고를 복구한다")
	void compensationIsIdempotent() {
		long now = System.currentTimeMillis();
		stockManager.prepare(20L, 1, now - MINUTE, now + MINUTE, true, now + 2 * MINUTE);
		assertThat(stockManager.issue(20L, 1L)).isEqualTo(CouponRedisIssueResult.approved(0));

		assertThat(stockManager.compensate(20L, 1L))
			.isEqualTo(CouponRedisCompensationResult.COMPENSATED);
		assertThat(stockManager.remainingStock(20L)).isEqualTo(1);

		assertThat(stockManager.compensate(20L, 1L))
			.isEqualTo(CouponRedisCompensationResult.NO_OP);
		assertThat(stockManager.remainingStock(20L)).isEqualTo(1);
		assertThat(stockManager.compensate(999L, 1L))
			.isEqualTo(CouponRedisCompensationResult.META_MISSING);
	}

	@Test
	@DisplayName("메타와 발급자 키는 같은 해시 태그와 만료 시각을 사용한다")
	void usesSharedHashTagAndExpiration() {
		long now = System.currentTimeMillis();
		long expiresAt = now + 2 * MINUTE;
		stockManager.prepare(30L, 1, now - MINUTE, now + MINUTE, true, expiresAt);
		stockManager.issue(30L, 1L);

		assertThat(CouponRedisStockManager.metaKey(30L)).isEqualTo("coupon:{30}:meta");
		assertThat(CouponRedisStockManager.issuedKey(30L)).isEqualTo("coupon:{30}:issued");

		RMap<String, String> meta = redissonClient.getMap(
			CouponRedisStockManager.metaKey(30L), StringCodec.INSTANCE);
		assertThat(meta.get("stock")).isEqualTo("0");
		assertThat(meta.get("issueStartAt")).isEqualTo(String.valueOf(now - MINUTE));

		long metaTtl = meta.remainTimeToLive();
		long issuedTtl = redissonClient.getSet(
			CouponRedisStockManager.issuedKey(30L), StringCodec.INSTANCE).remainTimeToLive();
		assertThat(metaTtl).isBetween(MINUTE, 2 * MINUTE);
		assertThat(issuedTtl).isBetween(MINUTE, 2 * MINUTE);
		assertThat(Math.abs(metaTtl - issuedTtl)).isLessThan(1_000L);
	}

	@Test
	@DisplayName("준비·발급·보상 Lua의 실행 시간과 고정 결과를 기록한다")
	void recordsEveryLuaOperation() {
		long now = System.currentTimeMillis();
		stockManager.prepare(40L, 1, now - MINUTE, now + MINUTE, true, now + 2 * MINUTE);
		stockManager.issue(40L, 1L);
		stockManager.compensate(40L, 1L);

		verify(metricRecorder).recordLua(
			eq(CouponIssueMetricRecorder.LuaOperation.PREPARE),
			eq(CouponIssueMetricRecorder.LuaResult.PREPARED),
			anyLong());
		verify(metricRecorder).recordLua(
			eq(CouponIssueMetricRecorder.LuaOperation.ISSUE),
			eq(CouponIssueMetricRecorder.LuaResult.APPROVED),
			anyLong());
		verify(metricRecorder).recordLua(
			eq(CouponIssueMetricRecorder.LuaOperation.COMPENSATE),
			eq(CouponIssueMetricRecorder.LuaResult.COMPENSATED),
			anyLong());
	}
}
