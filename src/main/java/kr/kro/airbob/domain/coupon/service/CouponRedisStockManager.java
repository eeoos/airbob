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

@Component
public class CouponRedisStockManager {

	private final RedissonClient redissonClient;
	private String prepareScript;
	private String issueScript;
	private String compensateScript;

	public CouponRedisStockManager(RedissonClient redissonClient) {
		this.redissonClient = redissonClient;
	}

	@PostConstruct
	void loadScripts() {
		prepareScript = loadScript("lua/coupon_prepare.lua");
		issueScript = loadScript("lua/coupon_issue.lua");
		compensateScript = loadScript("lua/coupon_compensate.lua");
	}

	public CouponRedisPreparationResult prepare(
		Long couponId,
		long stock,
		long issueStartAt,
		long issueEndAt,
		boolean active,
		long expiresAt
	) {
		long result = redissonClient.getScript(StringCodec.INSTANCE).eval(
			RScript.Mode.READ_WRITE,
			prepareScript,
			RScript.ReturnType.INTEGER,
			List.of(metaKey(couponId), issuedKey(couponId)),
			String.valueOf(stock),
			String.valueOf(issueStartAt),
			String.valueOf(issueEndAt),
			active ? "1" : "0",
			String.valueOf(expiresAt));

		return result == 1
			? CouponRedisPreparationResult.PREPARED
			: CouponRedisPreparationResult.ALREADY_PREPARED;
	}

	public CouponRedisIssueResult issue(Long couponId, Long memberId) {
		long result = redissonClient.getScript(StringCodec.INSTANCE).eval(
			RScript.Mode.READ_WRITE,
			issueScript,
			RScript.ReturnType.INTEGER,
			List.of(metaKey(couponId), issuedKey(couponId)),
			String.valueOf(memberId));

		return CouponRedisIssueResult.fromRawResult(result);
	}

	public CouponRedisCompensationResult compensate(Long couponId, Long memberId) {
		long result = redissonClient.getScript(StringCodec.INSTANCE).eval(
			RScript.Mode.READ_WRITE,
			compensateScript,
			RScript.ReturnType.INTEGER,
			List.of(metaKey(couponId), issuedKey(couponId)),
			String.valueOf(memberId));

		return switch ((int)result) {
			case 1 -> CouponRedisCompensationResult.COMPENSATED;
			case 0 -> CouponRedisCompensationResult.NO_OP;
			case -1 -> CouponRedisCompensationResult.META_MISSING;
			default -> throw new IllegalArgumentException("알 수 없는 쿠폰 보상 Lua 결과: " + result);
		};
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
}
