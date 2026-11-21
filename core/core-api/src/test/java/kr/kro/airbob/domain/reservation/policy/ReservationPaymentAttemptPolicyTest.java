package kr.kro.airbob.domain.reservation.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.domain.reservation.exception.ReservationPaymentAttemptTooLateException;

@DisplayName("예약 결제 시도 정책 테스트")
class ReservationPaymentAttemptPolicyTest {

	private final ReservationPaymentAttemptPolicy policy =
		new ReservationPaymentAttemptPolicy(Duration.ofSeconds(90));
	private final Instant expiresAt = Instant.parse("2026-08-25T03:15:00Z");

	@Test
	@DisplayName("최소 잔여 시간은 양수여야 한다")
	void requiresPositiveMinimumRemaining() {
		assertThatThrownBy(() -> new ReservationPaymentAttemptPolicy(Duration.ZERO))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("최초 발급은 남은 시간이 정확히 90초면 허용한다")
	void allowsFirstIssueAtMinimumBoundary() {
		policy.validateFirstIssue(expiresAt.minusSeconds(90), expiresAt);
	}

	@Test
	@DisplayName("최초 발급은 남은 시간이 90초보다 짧으면 R022로 거절한다")
	void rejectsFirstIssueBelowMinimum() {
		assertThatThrownBy(() -> policy.validateFirstIssue(expiresAt.minusSeconds(89), expiresAt))
			.isInstanceOf(ReservationPaymentAttemptTooLateException.class);
	}

	@Test
	@DisplayName("기존 미소비 토큰도 정확한 만료 시각에는 R022로 거절한다")
	void rejectsReplayAtExactExpiry() {
		assertThatThrownBy(() -> policy.validateReplay(expiresAt, expiresAt))
			.isInstanceOf(ReservationPaymentAttemptTooLateException.class);
	}

	@Test
	@DisplayName("기존 토큰도 만료 시각이 지나면 R022로 거절한다")
	void rejectsReplayAfterExpiry() {
		assertThatThrownBy(() -> policy.validateReplay(expiresAt.plusNanos(1), expiresAt))
			.isInstanceOf(ReservationPaymentAttemptTooLateException.class);
	}
}
