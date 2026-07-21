package kr.kro.airbob.domain.coupon.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import jakarta.annotation.PostConstruct;
import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder;

@Component
public class CouponRedisStockManager {

	private static final long UNLIMITED_STOCK_SENTINEL = 0L;

	private final RedissonClient redissonClient;
	private final CouponIssueMetricRecorder metricRecorder;
	private String prepareScript;
	private String issueScript;
	private String compensateScript;

	public CouponRedisStockManager(
		RedissonClient redissonClient,
		CouponIssueMetricRecorder metricRecorder
	) {
		this.redissonClient = redissonClient;
		this.metricRecorder = metricRecorder;
	}

	@PostConstruct
	void loadScripts() {
		prepareScript = loadScript("lua/coupon_prepare.lua");
		issueScript = loadScript("lua/coupon_issue.lua");
		compensateScript = loadScript("lua/coupon_compensate.lua");
	}

	public CouponRedisPreparationResult prepare(
		Long couponId,
		Integer totalQuantity,
		long issueStartAt,
		long issueEndAt,
		boolean active,
		long expiresAt
	) {
		boolean unlimited = totalQuantity == null;
		long stock = unlimited ? UNLIMITED_STOCK_SENTINEL : totalQuantity;
		long startedAt = System.nanoTime();
		CouponIssueMetricRecorder.LuaResult metricResult = CouponIssueMetricRecorder.LuaResult.ERROR;
		try {
			long result = redissonClient.getScript(StringCodec.INSTANCE).eval(
				RScript.Mode.READ_WRITE,
				prepareScript,
				RScript.ReturnType.INTEGER,
				List.of(metaKey(couponId), issuedKey(couponId)),
				String.valueOf(stock),
				String.valueOf(issueStartAt),
				String.valueOf(issueEndAt),
				active ? "1" : "0",
				String.valueOf(expiresAt),
				unlimited ? "1" : "0");

			CouponRedisPreparationResult preparationResult = result == 1
				? CouponRedisPreparationResult.PREPARED
				: CouponRedisPreparationResult.ALREADY_PREPARED;
			metricResult = preparationResult == CouponRedisPreparationResult.PREPARED
				? CouponIssueMetricRecorder.LuaResult.PREPARED
				: CouponIssueMetricRecorder.LuaResult.ALREADY_PREPARED;
			return preparationResult;
		} finally {
			metricRecorder.recordLua(
				CouponIssueMetricRecorder.LuaOperation.PREPARE,
				metricResult,
				System.nanoTime() - startedAt);
		}
	}

	public CouponRedisIssueResult issue(Long couponId, Long memberId) {
		long startedAt = System.nanoTime();
		CouponIssueMetricRecorder.LuaResult metricResult = CouponIssueMetricRecorder.LuaResult.ERROR;
		try {
			long result = redissonClient.getScript(StringCodec.INSTANCE).eval(
				RScript.Mode.READ_WRITE,
				issueScript,
				RScript.ReturnType.INTEGER,
				List.of(metaKey(couponId), issuedKey(couponId)),
				String.valueOf(memberId));

			CouponRedisIssueResult issueResult = CouponRedisIssueResult.fromRawResult(result);
			metricResult = luaMetricResult(issueResult.status());
			return issueResult;
		} finally {
			metricRecorder.recordLua(
				CouponIssueMetricRecorder.LuaOperation.ISSUE,
				metricResult,
				System.nanoTime() - startedAt);
		}
	}

	public CouponRedisCompensationResult compensate(Long couponId, Long memberId) {
		long startedAt = System.nanoTime();
		CouponIssueMetricRecorder.LuaResult metricResult = CouponIssueMetricRecorder.LuaResult.ERROR;
		try {
			long result = redissonClient.getScript(StringCodec.INSTANCE).eval(
				RScript.Mode.READ_WRITE,
				compensateScript,
				RScript.ReturnType.INTEGER,
				List.of(metaKey(couponId), issuedKey(couponId)),
				String.valueOf(memberId));

			CouponRedisCompensationResult compensationResult = switch ((int)result) {
				case 1 -> CouponRedisCompensationResult.COMPENSATED;
				case 0 -> CouponRedisCompensationResult.NO_OP;
				case -1 -> CouponRedisCompensationResult.META_MISSING;
				default -> throw new IllegalArgumentException("알 수 없는 쿠폰 보상 Lua 결과: " + result);
			};
			metricResult = switch (compensationResult) {
				case COMPENSATED -> CouponIssueMetricRecorder.LuaResult.COMPENSATED;
				case NO_OP -> CouponIssueMetricRecorder.LuaResult.NO_OP;
				case META_MISSING -> CouponIssueMetricRecorder.LuaResult.META_MISSING;
			};
			return compensationResult;
		} finally {
			metricRecorder.recordLua(
				CouponIssueMetricRecorder.LuaOperation.COMPENSATE,
				metricResult,
				System.nanoTime() - startedAt);
		}
	}

	public boolean isPrepared(Long couponId) {
		return redissonClient.getKeys().countExists(metaKey(couponId), issuedKey(couponId)) > 0;
	}

	public long remainingStock(Long couponId) {
		RMap<String, String> meta = redissonClient.getMap(metaKey(couponId), StringCodec.INSTANCE);
		String stock = meta.get("stock");
		if (stock == null) {
			throw new IllegalStateException("준비되지 않은 쿠폰의 Redis 재고는 조회할 수 없습니다.");
		}
		return Long.parseLong(stock);
	}

	static String metaKey(Long couponId) {
		return "coupon:{" + couponId + "}:meta";
	}

	static String issuedKey(Long couponId) {
		return "coupon:{" + couponId + "}:issued";
	}

	private String loadScript(String path) {
		try (InputStream input = new ClassPathResource(path).getInputStream()) {
			return StreamUtils.copyToString(input, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("쿠폰 Lua 스크립트 로드 실패: " + path, e);
		}
	}

	private CouponIssueMetricRecorder.LuaResult luaMetricResult(CouponRedisIssueStatus status) {
		return switch (status) {
			case APPROVED -> CouponIssueMetricRecorder.LuaResult.APPROVED;
			case SOLD_OUT -> CouponIssueMetricRecorder.LuaResult.SOLD_OUT;
			case DUPLICATE -> CouponIssueMetricRecorder.LuaResult.DUPLICATE;
			case NOT_STARTED -> CouponIssueMetricRecorder.LuaResult.NOT_STARTED;
			case ENDED -> CouponIssueMetricRecorder.LuaResult.ENDED;
			case UNPREPARED -> CouponIssueMetricRecorder.LuaResult.UNPREPARED;
			case INACTIVE -> CouponIssueMetricRecorder.LuaResult.INACTIVE;
		};
	}
}
