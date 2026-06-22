package kr.kro.airbob.common.code;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CommonCodeDetailRepository
	extends JpaRepository<CommonCodeDetail, CommonCodeDetailId> {

	/**
	 * 활성 그룹 전제하에, 활성 상세 코드를 정렬 순서대로 조회.
	 * (그룹 자체의 활성 여부는 서비스 캐시 로더에서 확인한다.)
	 */
	List<CommonCodeDetail> findByGroupCodeAndActiveTrueOrderBySortOrderAsc(String groupCode);

	// 관리자용: 비활성 포함 전체 조회
	List<CommonCodeDetail> findByGroupCodeOrderBySortOrderAsc(String groupCode);
}
