package kr.kro.airbob.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 애플리케이션 시작 시 Mock Data ETL 파이프라인을 실행하는 Orchestrator.
 * 이 클래스는 트랜잭션 로직을 갖지 않고, MockDataEtlService의
 * @Transactional 메서드들을 순차적으로 호출하여 Self-Invocation을 방지합니다.
 */
@Slf4j
@Profile({"dev", "prob"})
@Component
@RequiredArgsConstructor
public class MockDataRunner implements ApplicationRunner {

	private final MockDataEtlService mockDataEtlService; // ★ 새로 만든 Service 주입

	@Override
	public void run(ApplicationArguments args) throws Exception {

		// 1. 데이터 존재 여부 확인 (Service 호출)
		if (mockDataEtlService.isDataAlreadyLoaded()) {
			log.info("Mock data already exists. Skipping.");
			return;
		}

		log.info("Starting ETL mock data load (RDBMS + Elasticsearch)...");
		long startTime = System.currentTimeMillis();

		try {
			// 2. 각 Phase를 Service를 통해 호출 (프록시 작동, 트랜잭션 적용)

			// [선행 작업]
			mockDataEtlService.initializeDefaultValues();

			// [Phase 1]
			log.info("Phase 1: Saving Accommodation related data...");
			mockDataEtlService.runPhase1_SaveAccommodations();

			// [Phase 2]
			log.info("Phase 2: Saving Review data (with synthetic rating)...");
			mockDataEtlService.runPhase2_SaveReviews();

			// [Phase 3]
			log.info("Phase 3: Aggregating Review data into ReviewSummary table...");
			mockDataEtlService.runPhase3_AggregateReviewSummaries();

			// [Phase 4]
			log.info("Phase 4: Indexing AccommodationDocuments to Elasticsearch (Paging)...");
			mockDataEtlService.runPhase4_IndexToElasticsearch();

			long endTime = System.currentTimeMillis();
			log.info("Mock data ETL finished successfully. Total time: {} ms", (endTime - startTime));

		} catch (Exception e) {
			log.error("Mock data ETL FAILED.", e);
			// @Transactional이 각 Phase에 걸려있으므로, 실패한 Phase는 롤백됩니다.
			// (단, Phase 4는 readOnly이거나 트랜잭션이 없음)
		}
	}
}
