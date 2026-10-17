package kr.kro.airbob.domain.coupon.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import kr.kro.airbob.domain.coupon.exception.CouponInvalidConfigurationException;

class CouponTimeTest {

	@Test
	void preservesNullAndConvertsOffsetInstantsBeforeRounding() {
		assertThat(CouponTime.toUtc(null)).isNull();
		assertThat(CouponTime.toUtc(OffsetDateTime.parse("2027-01-01T00:30:00.123456+09:00").toInstant()))
			.isEqualTo(LocalDateTime.parse("2026-12-31T15:30:00.123456"));
	}

	@Test
	void roundsAtHalfAMicrosecondIncludingSecondAndDayCarry() {
		assertThat(CouponTime.toUtc(Instant.parse("2027-01-01T00:00:00.123456499Z")))
			.isEqualTo(LocalDateTime.parse("2027-01-01T00:00:00.123456"));
		assertThat(CouponTime.toUtc(Instant.parse("2027-01-01T00:00:00.123456500Z")))
			.isEqualTo(LocalDateTime.parse("2027-01-01T00:00:00.123457"));
		assertThat(CouponTime.toUtc(Instant.parse("2027-01-01T23:59:59.999999500Z")))
			.isEqualTo(LocalDateTime.parse("2027-01-02T00:00:00"));
	}

	@Test
	void acceptsSupportedBoundaryValues() {
		assertThat(CouponTime.toUtc(Instant.parse("1000-01-01T00:00:00Z")))
			.isEqualTo(LocalDateTime.parse("1000-01-01T00:00:00"));
		assertThat(CouponTime.toUtc(Instant.parse("9999-12-31T23:59:59.999999499Z")))
			.isEqualTo(LocalDateTime.parse("9999-12-31T23:59:59.999999"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"0999-12-31T23:59:59.999999Z", "9999-12-31T23:59:59.999999500Z",
		"+10000-01-01T00:00:00Z", "+1000000000-12-31T23:59:59.999999999Z"})
	void rejectsOutOfRangeValuesAndOverflowCausedByRounding(String input) {
		assertThatThrownBy(() -> CouponTime.toUtc(Instant.parse(input)))
			.isInstanceOf(CouponInvalidConfigurationException.class);
	}
}
