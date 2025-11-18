package kr.kro.airbob.common;

import static java.lang.Integer.*;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kr.kro.airbob.domain.accommodation.common.AccommodationType;
import kr.kro.airbob.domain.accommodation.common.AmenityType;
import kr.kro.airbob.domain.accommodation.entity.*;
import kr.kro.airbob.domain.accommodation.repository.*;
import kr.kro.airbob.domain.image.entity.AccommodationImage;
import kr.kro.airbob.domain.member.common.MemberRole;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.review.entity.AccommodationReviewSummary;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;
import kr.kro.airbob.search.document.AccommodationDocument;
import kr.kro.airbob.search.service.AccommodationDocumentBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MockDataEtlService {

	private final AccommodationRepository accommodationRepository;
	private final MemberRepository memberRepository;
	// ReviewRepository, ImageRepository 제거 (JDBC 사용으로 불필요)
	private final AmenityRepository amenityRepository;
	private final AccommodationAmenityRepository accommodationAmenityRepository;
	private final AccommodationReviewSummaryRepository reviewSummaryRepository;

	private final ElasticsearchOperations elasticsearchOperations;
	private final AccommodationDocumentBuilder documentBuilder;

	private final ResourcePatternResolver resourceResolver;
	private final JdbcTemplate jdbcTemplate;
	private final CsvMapper csvMapper = new CsvMapper();
	private final CsvSchema schema = CsvSchema.emptySchema().withHeader();

	@PersistenceContext
	private final EntityManager entityManager;

	private static final double USD_TO_KRW_RATE = 1460.90;
	private static final int BATCH_SIZE = 10000;
	private static final int ES_PAGE_SIZE = 1000;

	// 메모리 최적화: 엔티티 대신 ID(Long)만 매핑
	private final Map<Long, Long> memberIdMap = new HashMap<>(1000000);
	private final Map<Long, Long> accommodationIdMap = new HashMap<>(2000000);

	// [최적화 2] 엔티티 대신 ID 리스트만 보유 (Detached 문제 해결)
	private List<Long> defaultAmenityIds;

	public boolean isDataAlreadyLoaded() {
		return accommodationRepository.count() > 0;
	}

	@Transactional
	public void initializeDefaultValues() {
		// ID 1, 2번 편의시설 확인 및 생성
		List<Amenity> amenities = amenityRepository.findAllById(Arrays.asList(1L, 2L));
		if (amenities.size() < 2) {
			amenities = List.of(
				amenityRepository.save(Amenity.builder().name(AmenityType.WIFI).build()),
				amenityRepository.save(Amenity.builder().name(AmenityType.KITCHEN).build())
			);
		}
		// ID만 추출하여 저장
		this.defaultAmenityIds = amenities.stream().map(Amenity::getId).toList();
		log.info("Initialized default Amenity IDs: {}", defaultAmenityIds);
	}

	/**
	 * [Phase 1] 숙소 데이터 저장
	 * - Member/Accommodation: JPA (ID 생성 및 연관관계 편의성)
	 * - Image/Amenity: JDBC Bulk Insert (성능 최적화)
	 */
	@Transactional
	public void runPhase1_SaveAccommodations() throws Exception {
		Resource[] listingResources = resourceResolver.getResources("classpath:mock-data/listings/**/*.csv");
		log.info("Phase 1: Found {} listing CSV files.", listingResources.length);

		// [최적화 1] List 대신 Map을 사용하여 배치 내 중복 호스트 검색 속도 향상 (O(N) -> O(1))
		Map<Long, Member> membersInBatchMap = new HashMap<>(BATCH_SIZE);

		List<Accommodation> accommodationsBatch = new ArrayList<>(BATCH_SIZE);
		// Image 처리를 위한 임시 저장소 (Index -> URL)
		Map<Integer, String> imageMap = new HashMap<>();
		List<Long> originalListingIds = new ArrayList<>(BATCH_SIZE);

		for (Resource resource : listingResources) {
			try (InputStream stream = resource.getInputStream()) {
				MappingIterator<Map<String, String>> it = csvMapper.readerFor(Map.class).with(schema).readValues(stream);

				while (it.hasNext()) {
					Map<String, String> row = it.next();
					try {
						Long originalHostId = parseLong(row.get("host_id"));
						Long originalListingId = parseLong(row.get("id"));
						if (originalHostId == null || originalListingId == null) continue;

						// 1. Member 처리 (Map Lookup 최적화)
						Member host;
						if (memberIdMap.containsKey(originalHostId)) {
							// 이미 DB에 저장된 멤버 (이전 배치에서 처리됨) -> 프록시 조회
							host = memberRepository.getReferenceById(memberIdMap.get(originalHostId));
						} else {
							// 현재 배치에서 처리 중인 멤버인지 확인
							host = membersInBatchMap.get(originalHostId);
							if (host == null) {
								// 새로 생성
								host = createMockMember(row.get("host_name"), originalHostId + "@mock-host.com", row.get("host_picture_url"));
								membersInBatchMap.put(originalHostId, host);
							}
						}

						// 2. Accommodation 생성
						Address address = Address.builder()
							.country("Mock Country").city("Mock City").district("Mock District")
							.street("Mock Street").detail("Mock Detail")
							.latitude(parseDouble(row.get("latitude"))).longitude(parseDouble(row.get("longitude")))
							.postalCode("00000").build();

						OccupancyPolicy newPolicy = OccupancyPolicy.builder()
							.maxOccupancy(parseInt(row.get("accommodates"), 2))
							.infantOccupancy(0).petOccupancy(0)
							.build();

						Accommodation accommodation = Accommodation.builder()
							.accommodationUid(UUID.randomUUID())
							.member(host) // 생성된 혹은 찾아낸 호스트 주입
							.name(row.get("name")).description(row.get("description"))
							.address(address).status(AccommodationStatus.PUBLISHED)
							.basePrice(parsePriceToKrw(row.get("price"))).currency("KRW")
							.thumbnailUrl(row.get("picture_url"))
							.type(AccommodationType.ENTIRE_PLACE)
							.checkInTime(LocalTime.of(15, 0))
							.checkOutTime(LocalTime.of(11, 0))
							.occupancyPolicy(newPolicy).build();

						accommodationsBatch.add(accommodation);
						originalListingIds.add(originalListingId);

						// 이미지 URL 임시 저장
						String imageUrl = row.get("picture_url");
						if (imageUrl != null && !imageUrl.isBlank()) {
							imageMap.put(accommodationsBatch.size() - 1, imageUrl);
						}

						if (accommodationsBatch.size() >= BATCH_SIZE) {
							flushPhase1Batch(membersInBatchMap, accommodationsBatch, imageMap, originalListingIds);
						}

					} catch (Exception e) {
						log.warn("Failed to parse listing row: {}", row, e);
					}
				}
			}
		}
		if (!accommodationsBatch.isEmpty()) {
			flushPhase1Batch(membersInBatchMap, accommodationsBatch, imageMap, originalListingIds);
		}
		log.info("Phase 1 Finished. Total Accommodations: {}", accommodationRepository.count());
	}

	private void flushPhase1Batch(Map<Long, Member> membersMap, List<Accommodation> accommodations,
		Map<Integer, String> imageMap, List<Long> originalListingIds) {

		// 1. Member 저장 (JPA)
		if (!membersMap.isEmpty()) {
			List<Member> membersToSave = new ArrayList<>(membersMap.values());
			memberRepository.saveAll(membersToSave);
			// 저장된 Member ID를 전역 맵에 등록
			for (Map.Entry<Long, Member> entry : membersMap.entrySet()) {
				memberIdMap.put(entry.getKey(), entry.getValue().getId());
			}
		}

		// 2. Accommodation 저장 (JPA - ID 생성)
		accommodationRepository.saveAll(accommodations);

		// 3. ID 매핑 업데이트
		for (int i = 0; i < accommodations.size(); i++) {
			accommodationIdMap.put(originalListingIds.get(i), accommodations.get(i).getId());
		}

		// [최적화 3] 4. 이미지 저장 (JDBC Bulk Insert)
		bulkInsertImages(accommodations, imageMap);

		// 5. 편의시설 저장 (JDBC Bulk Insert)
		bulkInsertAmenities(accommodations);

		// 6. 메모리 정리
		entityManager.flush();
		entityManager.clear();

		membersMap.clear();
		accommodations.clear();
		imageMap.clear();
		originalListingIds.clear();
	}

	// 이미지 JDBC Batch
	private void bulkInsertImages(List<Accommodation> accommodations, Map<Integer, String> imageMap) {
		if (imageMap.isEmpty()) return;

		String sql = "INSERT INTO accommodation_image (accommodation_id, image_url, created_at) VALUES (?, ?, ?)";
		List<Object[]> batchArgs = new ArrayList<>(imageMap.size());
		Timestamp timestamp = Timestamp.valueOf(LocalDateTime.now());

		for (Map.Entry<Integer, String> entry : imageMap.entrySet()) {
			Accommodation acc = accommodations.get(entry.getKey());
			batchArgs.add(new Object[]{
				acc.getId(),
				entry.getValue(),
				timestamp
			});
		}
		jdbcTemplate.batchUpdate(sql, batchArgs);
	}

	// 편의시설 JDBC Batch (ID 리스트 사용)
	private void bulkInsertAmenities(List<Accommodation> accommodations) {
		String sql = "INSERT INTO accommodation_amenity (accommodation_id, amenity_id, count, created_at) VALUES (?, ?, ?, ?)";
		List<Object[]> batchArgs = new ArrayList<>(accommodations.size() * defaultAmenityIds.size());
		Timestamp timestamp = Timestamp.valueOf(LocalDateTime.now());

		for (Accommodation acc : accommodations) {
			// [최적화 2 반영] 엔티티가 아닌 ID 리스트 순회 (Detached 문제 해결)
			for (Long amenityId : defaultAmenityIds) {
				batchArgs.add(new Object[]{
					acc.getId(),
					amenityId,
					1,
					timestamp
				});
			}
		}
		jdbcTemplate.batchUpdate(sql, batchArgs);
	}

	/**
	 * [Phase 2] 리뷰 저장
	 * [최적화 4] JPA Entity 생성 없이 JDBC Bulk Insert로 직접 처리 (메모리 절약 극대화)
	 */
	@Transactional
	public void runPhase2_SaveReviews() throws Exception {
		Resource[] reviewResources = resourceResolver.getResources("classpath:mock-data/reviews/**/*.csv");
		log.info("Phase 2: Found {} review CSV files.", reviewResources.length);

		List<Object[]> reviewsBatchArgs = new ArrayList<>(BATCH_SIZE);
		Map<Long, Member> newReviewersMap = new HashMap<>();

		String reviewSql = "INSERT INTO review (accommodation_id, member_id, content, rating, status, created_at, updated_at) VALUES (?, ?, ?, ?, 'PUBLISHED', ?, ?)";
		Timestamp timestamp = Timestamp.valueOf(LocalDateTime.now());

		for (Resource resource : reviewResources) {
			try (InputStream stream = resource.getInputStream()) {
				MappingIterator<Map<String, String>> it = csvMapper.readerFor(Map.class).with(schema).readValues(stream);
				while (it.hasNext()) {
					Map<String, String> row = it.next();
					try {
						Long originalListingId = parseLong(row.get("listing_id"));
						Long originalReviewerId = parseLong(row.get("reviewer_id"));
						if (originalListingId == null || originalReviewerId == null) continue;

						Long dbAccommodationId = accommodationIdMap.get(originalListingId);
						if (dbAccommodationId == null) continue;

						Long dbReviewerId;
						if (memberIdMap.containsKey(originalReviewerId)) {
							dbReviewerId = memberIdMap.get(originalReviewerId);
						} else {
							Member newMember = newReviewersMap.get(originalReviewerId);
							if (newMember == null) {
								newMember = createMockMember(row.get("reviewer_name"), originalReviewerId + "@mock-guest.com", null);
								newReviewersMap.put(originalReviewerId, newMember);
							}
							dbReviewerId = null; // flush 때 채움
						}

						int rating = (int) (originalReviewerId % 5) + 1;
						String content = row.get("comments");
						if(content != null && content.length() > 1000) content = content.substring(0, 1000);

						reviewsBatchArgs.add(new Object[]{
							dbAccommodationId,
							originalReviewerId, // 임시 ID
							content,
							rating,
							timestamp,
							timestamp
						});

						if (reviewsBatchArgs.size() >= BATCH_SIZE) {
							flushPhase2Batch(newReviewersMap, reviewsBatchArgs, reviewSql);
						}
					} catch (Exception e) { /* ignore */ }
				}
			}
		}
		if (!reviewsBatchArgs.isEmpty()) {
			flushPhase2Batch(newReviewersMap, reviewsBatchArgs, reviewSql);
		}

		accommodationIdMap.clear();
		memberIdMap.clear();
		log.info("Phase 2 Finished.");
	}

	private void flushPhase2Batch(Map<Long, Member> newReviewersMap,
		List<Object[]> reviewsBatchArgs,
		String sql) {
		if (!newReviewersMap.isEmpty()) {
			List<Member> membersToSave = new ArrayList<>(newReviewersMap.values());
			memberRepository.saveAll(membersToSave);
			for (Map.Entry<Long, Member> entry : newReviewersMap.entrySet()) {
				memberIdMap.put(entry.getKey(), entry.getValue().getId());
			}
			newReviewersMap.clear();
		}

		Iterator<Object[]> iterator = reviewsBatchArgs.iterator();
		while (iterator.hasNext()) {
			Object[] args = iterator.next();
			Long originalReviewerId = (Long) args[1];
			Long realDbId = memberIdMap.get(originalReviewerId);
			if (realDbId != null) {
				args[1] = realDbId;
			} else {
				iterator.remove();
			}
		}

		if (!reviewsBatchArgs.isEmpty()) {
			jdbcTemplate.batchUpdate(sql, reviewsBatchArgs);
		}
		reviewsBatchArgs.clear();
		entityManager.flush();
		entityManager.clear();
	}


	/**
	 * [Phase 3] RDBMS 내부 집계
	 */
	@Transactional
	public void runPhase3_AggregateReviewSummaries() {
		String sql = "INSERT INTO accommodation_review_summary (accommodation_id, total_review_count, average_rating, version, created_at, updated_at) " +
			"SELECT accommodation_id, COUNT(*), AVG(rating), 0, NOW(), NOW() FROM review GROUP BY accommodation_id";
		try {
			int insertedRows = jdbcTemplate.update(sql);
			log.info("Phase 3: Aggregated {} rows.", insertedRows);
		} catch (Exception e) {
			log.error("Phase 3 FAILED.", e);
			throw new RuntimeException(e);
		}
	}

	/**
	 * [Phase 4] ES Indexing (Paging)
	 */
	@Transactional(readOnly = true)
	public void runPhase4_IndexToElasticsearch() {
		Pageable pageable = PageRequest.of(0, ES_PAGE_SIZE);
		Page<Accommodation> accommodationPage;
		List<AccommodationAmenity> emptyAmenityList = Collections.emptyList();

		do {
			log.info("Phase 4: Indexing page {}...", pageable.getPageNumber());

			accommodationPage = accommodationRepository.findForIndexing(pageable);
			List<Accommodation> accommodations = accommodationPage.getContent();
			if (accommodations.isEmpty()) break;

			List<Long> accommodationIds = accommodations.stream().map(Accommodation::getId).toList();

			List<AccommodationReviewSummary> summaries = reviewSummaryRepository.findAllByAccommodationIdIn(accommodationIds);
			Map<Long, AccommodationReviewSummary> summaryMap = summaries.stream()
				.collect(Collectors.toMap(s -> s.getAccommodation().getId(), s -> s));

			List<AccommodationAmenity> amenities = accommodationAmenityRepository.findAllByAccommodationIdIn(accommodationIds);
			Map<Long, List<AccommodationAmenity>> amenityMap = amenities.stream()
				.collect(Collectors.groupingBy(a -> a.getAccommodation().getId()));

			List<AccommodationDocument> documents = new ArrayList<>(accommodations.size());
			for (Accommodation accommodation : accommodations) {
				try {
					AccommodationReviewSummary summary = summaryMap.get(accommodation.getId());
					List<AccommodationAmenity> accAmenities = amenityMap.getOrDefault(accommodation.getId(), emptyAmenityList);

					AccommodationDocument doc = documentBuilder.build(accommodation, summary, accAmenities);
					documents.add(doc);
				} catch (Exception e) {
					log.warn("Build failed for ID {}: {}", accommodation.getId(), e.getMessage());
				}
			}

			if (!documents.isEmpty()) {
				try {
					elasticsearchOperations.save(documents);
				} catch (Exception e) {
					log.error("ES Indexing failed for page {}", pageable.getPageNumber(), e);
				}
			}

			entityManager.clear();
			pageable = pageable.next();
		} while (accommodationPage.hasNext());

		log.info("Phase 4 Finished.");
	}

	// --- Helper Methods ---

	private Member createMockMember(String nickname, String email, String profileImageUrl) {
		return Member.builder()
			.nickname(Objects.requireNonNullElse(nickname, "Unknown User"))
			.email(email).thumbnailImageUrl(profileImageUrl).password("mock-data")
			.status(MemberStatus.ACTIVE).role(MemberRole.MEMBER).build();
	}

	private Long parsePriceToKrw(String priceStr) {
		if (priceStr == null || priceStr.isBlank()) return null;
		try {
			String cleanPrice = priceStr.replace("$", "").replace(",", "");
			double priceUsd = Double.parseDouble(cleanPrice);
			return (long) (priceUsd * USD_TO_KRW_RATE);
		} catch (NumberFormatException e) { return null; }
	}

	private Long parseLong(String value) {
		try { return Long.parseLong(value); } catch (NumberFormatException e) { return null; }
	}

	private Double parseDouble(String value) {
		try { return Double.parseDouble(value); } catch (NumberFormatException e) { return 0.0; }
	}

	private int parseInt(String value, int defaultValue) {
		if (value == null || value.isBlank()) return defaultValue;
		try {
			return (int) Double.parseDouble(value);
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}
}
