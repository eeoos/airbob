package kr.kro.airbob.domain.commoncode.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.domain.commoncode.entity.CommonCode;
import kr.kro.airbob.domain.commoncode.entity.CommonCodeId;

public interface CommonCodeRepository
	extends JpaRepository<CommonCode, CommonCodeId>, CommonCodeRepositoryCustom {

	/**
	 * 활성 그룹 전제하에, 활성 공통 코드를 sortOrder, code 오름차순으로 조회
	 * (그룹 자체의 활성 여부는 서비스 캐시 로더에서 확인)
	 */
	List<CommonCode> findByGroupCodeAndActiveTrueOrderBySortOrderAscCodeAsc(String groupCode);

	// 관리자용: 비활성 포함 전체를 sortOrder, code 오름차순으로 조회
	List<CommonCode> findByGroupCodeOrderBySortOrderAscCodeAsc(String groupCode);
}
