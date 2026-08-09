package kr.kro.airbob.domain.commoncode.service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Ticker;

import kr.kro.airbob.domain.commoncode.dto.CommonCodeResponse;
import kr.kro.airbob.domain.commoncode.entity.CommonCodeGroup;
import kr.kro.airbob.domain.commoncode.exception.CommonCodeGroupNotFoundException;
import kr.kro.airbob.domain.commoncode.repository.CommonCodeGroupRepository;
import kr.kro.airbob.domain.commoncode.repository.CommonCodeRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * 공통 코드 조회 서비스
 *
 * 로컬 캐시 + TTL(1분): group_code 단위로 활성 공통 코드 목록을 Caffeine 에 적재
 * DB 에서 라벨/정렬/활성여부를 바꾸면 최대 1분 내 반영
 */
@Slf4j
@Service
public class CommonCodeService {

	private static final Duration CACHE_TTL = Duration.ofMinutes(1);

	private final LoadingCache<String, CachedGroupCodes> cache;

	@Autowired
	public CommonCodeService(
		CommonCodeGroupRepository groupRepository,
		CommonCodeRepository commonCodeRepository
	) {
		this(groupRepository, commonCodeRepository, Ticker.systemTicker());
	}

	static CommonCodeService withTicker(
		CommonCodeGroupRepository groupRepository,
		CommonCodeRepository commonCodeRepository,
		Ticker ticker
	) {
		return new CommonCodeService(groupRepository, commonCodeRepository, ticker);
	}

	private CommonCodeService(
		CommonCodeGroupRepository groupRepository,
		CommonCodeRepository commonCodeRepository,
		Ticker ticker
	) {
		CacheLoader<String, CachedGroupCodes> loader = groupCode -> {
			Optional<CommonCodeGroup> group = groupRepository.findById(groupCode);
			if (group.isEmpty()) {
				return CachedGroupCodes.notFound();
			}
			if (!group.get().isActive()) {
				return CachedGroupCodes.found(List.of());
			}
			List<CommonCodeResponse> codes = commonCodeRepository
				.findByGroupCodeAndActiveTrueOrderBySortOrderAsc(groupCode)
				.stream()
				.map(CommonCodeResponse::from)
				.toList();
			return CachedGroupCodes.found(codes);
		};

		this.cache = Caffeine.newBuilder()
			.maximumSize(100)
			.expireAfterWrite(CACHE_TTL)
			.ticker(ticker)
			.build(loader);
	}

	/**
	 * 그룹의 활성 코드 목록(셀렉트 박스용)
	 * 캐시 미스 시 DB 로더가 적재
	 */
	public List<CommonCodeResponse> getCodes(String groupCode) {
		if (groupCode == null) {
			throw new CommonCodeGroupNotFoundException();
		}
		CachedGroupCodes cached = cache.get(groupCode);
		if (!cached.exists()) {
			throw new CommonCodeGroupNotFoundException();
		}
		return cached.codes();
	}

	/**
	 * 단건 표시명 조회
	 * 코드가 없거나 비활성이면 코드 원본을 폴백으로 반환
	 */
	public String getLabel(String groupCode, String code) {
		return getCodes(groupCode).stream()
			.filter(c -> c.code().equals(code))
			.map(CommonCodeResponse::name)
			.findFirst()
			.orElse(code);
	}

	/**
	 * 원본 테이블 저장 전 정합성 검증용
	 * FK 대신 애플리케이션 레벨에서 유효 코드만 통과시킴
	 */
	public boolean isValidCode(String groupCode, String code) {
		if (code == null) {
			return false;
		}
		return getCodes(groupCode).stream()
			.anyMatch(c -> c.code().equals(code));
	}

	private record CachedGroupCodes(boolean exists, List<CommonCodeResponse> codes) {

		private static CachedGroupCodes notFound() {
			return new CachedGroupCodes(false, List.of());
		}

		private static CachedGroupCodes found(List<CommonCodeResponse> codes) {
			return new CachedGroupCodes(true, List.copyOf(codes));
		}
	}
}
