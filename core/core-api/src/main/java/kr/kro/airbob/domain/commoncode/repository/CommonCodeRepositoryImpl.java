package kr.kro.airbob.domain.commoncode.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import kr.kro.airbob.domain.commoncode.entity.CommonCode;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class CommonCodeRepositoryImpl implements CommonCodeRepositoryCustom {

	private final EntityManager entityManager;

	@Override
	@Transactional
	public void insert(CommonCode code) {
		entityManager.persist(code);
		entityManager.flush();
	}
}
