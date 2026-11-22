package kr.kro.airbob.core.domain.commoncode.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.core.domain.commoncode.CommonCodeGroup;

public interface CommonCodeGroupRepository
	extends JpaRepository<CommonCodeGroup, String>, CommonCodeGroupRepositoryCustom {

	List<CommonCodeGroup> findAllByOrderByGroupCodeAsc();
}
