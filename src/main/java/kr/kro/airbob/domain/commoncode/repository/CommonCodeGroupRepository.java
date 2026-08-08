package kr.kro.airbob.domain.commoncode.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.domain.commoncode.entity.CommonCodeGroup;

public interface CommonCodeGroupRepository
	extends JpaRepository<CommonCodeGroup, String> {
}
