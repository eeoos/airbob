package kr.kro.airbob.domain.recentlyViewed.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("최근 본 숙소 벤치마크 fixture 서비스 테스트")
class RecentlyViewedBenchmarkFixtureServiceTest {

	private static final String BENCHMARK_EMAIL = "benchmark-nplus1@airbob.cloud";

	@Mock
	private MemberRepository memberRepository;
	@Mock
	private RecentlyViewedService recentlyViewedService;

	private RecentlyViewedBenchmarkFixtureService fixtureService;

	@BeforeEach
	void setUp() {
		fixtureService = new RecentlyViewedBenchmarkFixtureService(
			memberRepository,
			recentlyViewedService,
			BENCHMARK_EMAIL
		);
	}

	@Test
	@DisplayName("설정된 벤치마크 계정은 자신의 최근 본 숙소 fixture를 교체한다")
	void benchmarkAccountCanReplaceItsFixture() {
		Member member = Member.builder()
			.id(7L)
			.email(BENCHMARK_EMAIL)
			.status(MemberStatus.ACTIVE)
			.build();
		List<Long> accommodationIds = List.of(251L, 252L);
		when(memberRepository.findByIdAndStatus(7L, MemberStatus.ACTIVE)).thenReturn(Optional.of(member));

		fixtureService.replaceFixture(7L, accommodationIds);

		verify(recentlyViewedService).replaceRecentlyViewed(7L, accommodationIds);
	}

	@Test
	@DisplayName("다른 계정은 벤치마크 fixture를 교체할 수 없다")
	void nonBenchmarkAccountCannotReplaceFixture() {
		Member member = Member.builder()
			.id(8L)
			.email("member@airbob.cloud")
			.status(MemberStatus.ACTIVE)
			.build();
		when(memberRepository.findByIdAndStatus(8L, MemberStatus.ACTIVE)).thenReturn(Optional.of(member));

		assertThatThrownBy(() -> fixtureService.replaceFixture(8L, List.of(251L)))
			.isInstanceOf(BaseException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.BENCHMARK_ACCESS_DENIED);
		verify(recentlyViewedService, never()).replaceRecentlyViewed(8L, List.of(251L));
	}
}
