package kr.kro.airbob.domain.coupon.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.redisson.api.RLock;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import jakarta.annotation.PostConstruct;
import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.exception.CouponAlreadyIssuedException;
import kr.kro.airbob.domain.coupon.exception.CouponNotFoundException;
import kr.kro.airbob.domain.coupon.exception.CouponSoldOutException;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 선착순 쿠폰 발급 — 동시성 제어 방식별 3가지 구현.
 * <ul>
 *   <li>{@link #issueWithoutLock} : 동시성 제어 없음(anti-pattern, 초과발급 재현용)</li>
 *   <li>{@link #issueWithLock}    : Redisson 분산락 — 정확하나 임계구역에 DB 왕복이 묶임</li>
 *   <li>{@link #issueWithAtomicCounter} : Redis 원자적 카운터(Lua) — 고처리량 선착순</li>
 * </ul>
 * 세 경로 모두 DB {@code UNIQUE(member_id, coupon_id)} 가 1인 1매를 공통 보증한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssueService {

	private final CouponIssueTransactionService txService;
	private final CouponLockManager lockManager;
	private final CouponRepository couponRepository;
	private final RedissonClient redissonClient;

	private String issueScript;

	@PostConstruct
	void loadScript() {
		try {
			this.issueScript = StreamUtils.copyToString(
				new ClassPathResource("lua/coupon_issue.lua").getInputStream(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("쿠폰 발급 Lua 스크립트 로드 실패", e);
		}
	}

	// 1) 동시성 제어 없음 — read-modify-write 경합으로 초과발급이 발생한다(재현 전용)
	public void issueWithoutLock(Long couponId, Long memberId) {
		txService.issue(couponId, memberId);
	}

	// 2) Redisson 분산락 — 트랜잭션 바깥에서 단일 키를 잠그고 발급
	public void issueWithLock(Long couponId, Long memberId) {
		RLock lock = lockManager.acquireLock(lockKey(couponId));
		try {
			txService.issue(couponId, memberId);
		} finally {
			lockManager.releaseLock(lock);
		}
	}

	// 3) Redis 원자적 카운터 — Lua 가 중복/재고를 원자적으로 판정, 당첨 시에만 DB 영속화
	public void issueWithAtomicCounter(Long couponId, Long memberId) {
		long result = redissonClient.getScript(StringCodec.INSTANCE).eval(
			RScript.Mode.READ_WRITE,
			issueScript,
			RScript.ReturnType.INTEGER,
			List.of(stockKey(couponId), issuedKey(couponId)),
			String.valueOf(memberId));

		if (result == -2) {
			throw new CouponAlreadyIssuedException();
		}
		if (result == -1) {
			throw new CouponSoldOutException();
		}

		try {
			txService.persistIssued(couponId, memberId);
		} catch (Exception e) {
			// DB 영속화 실패 → Redis 재고/발급집합 보상(롤백)으로 슬롯 누수 방지
			compensateCounter(couponId, memberId);
			throw e;
		}
	}

	/**
	 * 원자적 카운터 방식의 재고를 Redis 에 시딩한다(이벤트 시작 전 1회).
	 * 남은 발급 가능 수(totalQuantity - issuedQuantity)를 적재하고 발급자 집합을 초기화한다.
	 */
	public void prepareStock(Long couponId) {
		Coupon coupon = couponRepository.findById(couponId)
			.orElseThrow(CouponNotFoundException::new);

		Integer remaining = coupon.remainingQuantity();
		long stock = remaining == null ? Long.MAX_VALUE : remaining;

		redissonClient.<String>getBucket(stockKey(couponId), StringCodec.INSTANCE).set(String.valueOf(stock));
		redissonClient.getKeys().delete(issuedKey(couponId));
	}

	private void compensateCounter(Long couponId, Long memberId) {
		try {
			redissonClient.getScript(StringCodec.INSTANCE).eval(
				RScript.Mode.READ_WRITE,
				"redis.call('INCR', KEYS[1]); redis.call('SREM', KEYS[2], ARGV[1]); return 1",
				RScript.ReturnType.INTEGER,
				List.of(stockKey(couponId), issuedKey(couponId)),
				String.valueOf(memberId));
		} catch (Exception e) {
			log.error("쿠폰 카운터 보상 실패. couponId={}, memberId={}", couponId, memberId, e);
		}
	}

	private String lockKey(Long couponId) {
		return "coupon:lock:" + couponId;
	}

	private String stockKey(Long couponId) {
		return "coupon:stock:" + couponId;
	}

	private String issuedKey(Long couponId) {
		return "coupon:issued:" + couponId;
	}
}
