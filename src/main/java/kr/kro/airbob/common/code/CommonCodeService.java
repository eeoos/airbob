package kr.kro.airbob.common.code;

import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import lombok.extern.slf4j.Slf4j;

/**
 * 공통 코드 조회 서비스 (하이브리드 전략의 읽기 경로).
 *
 * <p>로컬 캐시 + TTL(1분): group_code 단위로 활성 상세 코드 목록을 Caffeine 에 적재한다.
 * DB 에서 라벨/정렬/활성여부를 바꾸면 최대 1분 내 반영된다.
 */
@Slf4j
@Service
public class CommonCodeService {

	private static final Duration CACHE_TTL = Duration.ofMinutes(1);

	private final LoadingCache<String, List<CommonCodeResponse>> cache;

	public CommonCodeService(
		CommonCodeGroupRepository groupRepository,
		CommonCodeDetailRepository detailRepository
	) {
		CacheLoader<String, List<CommonCodeResponse>> loader = groupCode -> {
			boolean groupActive = groupRepository.findById(groupCode)
				.map(CommonCodeGroup::isActive)
				.orElse(false);
			if (!groupActive) {
				return List.of();
			}
			return detailRepository.findByGroupCodeAndActiveTrueOrderBySortOrderAsc(groupCode)
				.stream()
				.map(CommonCodeResponse::from)
				.toList();
		};

		this.cache = Caffeine.newBuilder()
			.expireAfterWrite(CACHE_TTL)
			.build(loader);
	}

	/**
	 * 그룹의 활성 코드 목록(셀렉트 박스용). 캐시 미스 시 DB 로더가 채운다.
	 */
	public List<CommonCodeResponse> getCodes(String groupCode) {
		return cache.get(groupCode);
	}

	/**
	 * 단건 표시명 조회. 코드가 없거나 비활성이면 코드 원본을 폴백으로 반환한다.
	 */
	public String getLabel(String groupCode, String code) {
		return getCodes(groupCode).stream()
			.filter(c -> c.code().equals(code))
			.map(CommonCodeResponse::name)
			.findFirst()
			.orElse(code);
	}

	/**
	 * 원본 테이블 저장 전 정합성 검증용. FK 대신 애플리케이션 레벨에서 유효 코드만 통과시킨다.
	 * (캐시 기반이라 매 검증마다 DB 조회/조인이 발생하지 않는다.)
	 */
	public boolean isValidCode(String groupCode, String code) {
		if (code == null) {
			return false;
		}
		return getCodes(groupCode).stream()
			.anyMatch(c -> c.code().equals(code));
	}

	/**
	 * 그룹 캐시 무효화. 관리자 변경(생성/수정) 후 호출해 다음 조회 시 DB 최신값을 반영한다.
	 */
	public void evict(String groupCode) {
		cache.invalidate(groupCode);
	}
}
