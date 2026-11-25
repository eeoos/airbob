package kr.kro.airbob.core.domain.commoncode;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Ticker;

import kr.kro.airbob.core.domain.commoncode.repository.CommonCodeGroupRepository;
import kr.kro.airbob.core.domain.commoncode.repository.CommonCodeRepository;

/**
 * 공통 코드 조회 구현 컴포넌트. 다른 도메인도 코드 검증·표시명에 이 Reader를 쓴다.
 *
 * 활성 코드 목록은 로컬 캐시 + TTL(1분): group_code 단위로 Caffeine에 적재한다.
 * DB에서 라벨/정렬/활성여부를 바꾸면 인스턴스마다 최대 1분 안에 반영된다. 관리자 조회는 캐시를 쓰지 않는다.
 */
@Component
public class CommonCodeReader {

	private static final Duration CACHE_TTL = Duration.ofMinutes(1);

	private final CommonCodeGroupRepository groupRepository;
	private final CommonCodeRepository commonCodeRepository;
	private final LoadingCache<String, CachedGroupCodes> cache;

	@Autowired
	public CommonCodeReader(
		CommonCodeGroupRepository groupRepository,
		CommonCodeRepository commonCodeRepository) {
		this(groupRepository, commonCodeRepository, Ticker.systemTicker());
	}

	static CommonCodeReader withTicker(
		CommonCodeGroupRepository groupRepository,
		CommonCodeRepository commonCodeRepository,
		Ticker ticker) {
		return new CommonCodeReader(groupRepository, commonCodeRepository, ticker);
	}

	private CommonCodeReader(
		CommonCodeGroupRepository groupRepository,
		CommonCodeRepository commonCodeRepository,
		Ticker ticker) {
		this.groupRepository = groupRepository;
		this.commonCodeRepository = commonCodeRepository;

		CacheLoader<String, CachedGroupCodes> loader = groupCode -> {
			Optional<CommonCodeGroup> group = groupRepository.findById(groupCode);
			if (group.isEmpty()) {
				return CachedGroupCodes.notFound();
			}
			if (!group.get().isActive()) {
				return CachedGroupCodes.found(List.of());
			}
			List<CommonCodeDetail> codes = commonCodeRepository
				.findByGroupCodeAndActiveTrueOrderBySortOrderAscCodeAsc(groupCode)
				.stream()
				.map(CommonCodeDetail::from)
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
	 * 그룹의 활성 코드 목록(셀렉트 박스용). 비활성 그룹은 빈 목록이다.
	 * 캐시 미스 시 DB 로더가 적재하며, 없는 그룹도 TTL 동안 캐시한다.
	 */
	public List<CommonCodeDetail> readActiveCodes(String groupCode) {
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
	public String readLabel(String groupCode, String code) {
		return readActiveCodes(groupCode).stream()
			.filter(c -> c.code().equals(code))
			.map(CommonCodeDetail::name)
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
		return readActiveCodes(groupCode).stream()
			.anyMatch(c -> c.code().equals(code));
	}

	/** 관리자용: 전체 그룹을 그룹 코드 오름차순으로 조회 */
	public List<CommonCodeGroup> readGroups() {
		return groupRepository.findAllByOrderByGroupCodeAsc();
	}

	public CommonCodeGroup readGroup(String groupCode) {
		return groupRepository.findById(CommonCodeGroup.normalizeGroupCode(groupCode))
			.orElseThrow(CommonCodeGroupNotFoundException::new);
	}

	public void checkGroupExists(String groupCode) {
		if (!groupRepository.existsById(groupCode)) {
			throw new CommonCodeGroupNotFoundException();
		}
	}

	/** 관리자용: 비활성 포함 전체 코드를 sortOrder, code 오름차순으로 조회 */
	public List<CommonCode> readAllCodes(String groupCode) {
		return commonCodeRepository.findByGroupCodeOrderBySortOrderAscCodeAsc(groupCode);
	}

	public CommonCode readCode(String groupCode, String code) {
		return commonCodeRepository.findById(new CommonCodeId(groupCode, CommonCode.normalizeCode(code)))
			.orElseThrow(CommonCodeNotFoundException::new);
	}

	private record CachedGroupCodes(boolean exists, List<CommonCodeDetail> codes) {

		private static CachedGroupCodes notFound() {
			return new CachedGroupCodes(false, List.of());
		}

		private static CachedGroupCodes found(List<CommonCodeDetail> codes) {
			return new CachedGroupCodes(true, List.copyOf(codes));
		}
	}
}
