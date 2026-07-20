package kr.kro.airbob.domain.recentlyViewed.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.member.repository.MemberRepository;

@Service
@Profile("nplus1-benchmark")
public class RecentlyViewedBenchmarkFixtureService {

	private final MemberRepository memberRepository;
	private final RecentlyViewedService recentlyViewedService;
	private final String benchmarkAccountEmail;

	public RecentlyViewedBenchmarkFixtureService(
		MemberRepository memberRepository,
		RecentlyViewedService recentlyViewedService,
		@Value("${benchmark.nplus1.account-email}") String benchmarkAccountEmail
	) {
		this.memberRepository = memberRepository;
		this.recentlyViewedService = recentlyViewedService;
		this.benchmarkAccountEmail = benchmarkAccountEmail;
	}

	public void replaceFixture(Long memberId, List<Long> accommodationIds) {
		Member member = memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE)
			.orElseThrow(MemberNotFoundException::new);
		if (!benchmarkAccountEmail.equalsIgnoreCase(member.getEmail())) {
			throw new BaseException(ErrorCode.BENCHMARK_ACCESS_DENIED);
		}

		recentlyViewedService.replaceRecentlyViewed(memberId, accommodationIds);
	}
}
