package kr.kro.airbob.domain.commoncode.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import kr.kro.airbob.domain.commoncode.entity.CommonCodeGroup;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class CommonCodeGroupRepositoryImpl implements CommonCodeGroupRepositoryCustom {

	private final EntityManager entityManager;

	@Override
	@Transactional
	public void insert(CommonCodeGroup group) {
		entityManager.persist(group);
		entityManager.flush();
	}
}
