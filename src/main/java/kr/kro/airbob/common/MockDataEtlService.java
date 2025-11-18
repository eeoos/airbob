package kr.kro.airbob.common;

import static java.lang.Integer.*;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
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
import kr.kro.airbob.domain.review.entity.Review;
import kr.kro.airbob.domain.review.entity.ReviewStatus;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;
import kr.kro.airbob.domain.review.repository.ReviewRepository;
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
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MockDataEtlService {

	private final AccommodationRepository accommodationRepository;
	private final MemberRepository memberRepository;
	private final ReviewRepository reviewRepository;
	private final AccommodationImageRepository accommodationImageRepository;
	private final OccupancyPolicyRepository occupancyPolicyRepository;
	private final AmenityRepository amenityRepository;
	private final AccommodationAmenityRepository accommodationAmenityRepository;
	private final AccommodationReviewSummaryRepository reviewSummaryRepository;

	private final ElasticsearchOperations elasticsearchOperations;
	private final AccommodationDocumentBuilder documentBuilder;

	private final ResourcePatternResolver resourceResolver;
	private final JdbcTemplate jdbcTemplate;
	private final CsvMapper csvMapper = new CsvMapper();
	private final CsvSchema schema = CsvSchema.emptySchema().withHeader();

	// 2025/11/18 기준 1달러 환율
	private static final double USD_TO_KRW_RATE = 1460.90;
	private static final int REVIEW_BATCH_SIZE = 5000;
	private static final int MEMBER_BATCH_SIZE = 1000;
	private static final int ES_PAGE_SIZE = 1000;

	private final Map<Long, Member> memberTranslatorMap = new HashMap<>(1000000);
	private final Map<Long, Accommodation> accommodationTranslatorMap = new HashMap<>(2000000);

	private final Map<Accommodation, String> tempImageUrlMap = new HashMap<>(2000000);
	private final List<Member> membersToSave = new ArrayList<>(1000000);
	private final List<Accommodation> accommodationsToSave = new ArrayList<>(2000000);

	private List<Amenity> defaultAmenities;

	public boolean isDataAlreadyLoaded() {
		return accommodationRepository.count() > 0;
	}

	@Transactional
	public void initializeDefaultValues() {

		this.defaultAmenities = amenityRepository.findAllById(Arrays.asList(1L, 2L));
		if (this.defaultAmenities.size() < 2) {
			this.defaultAmenities = List.of(
				amenityRepository.save(Amenity.builder().name(AmenityType.WIFI).build()),
				amenityRepository.save(Amenity.builder().name(AmenityType.KITCHEN).build())
			);
		}
		log.info("Initialized default Amenities.");
	}

	/**
	 * [Phase 1] 숙소 관련 엔티티(숙소, 호스트, 이미지, 편의시설)를 RDBMS에 저장
	 */
	@Transactional
	public void runPhase1_SaveAccommodations() throws Exception {
		processListingFiles();
		log.info("Phase 1: Saving {} Members (Hosts)...", membersToSave.size());
		memberRepository.saveAll(membersToSave);
		membersToSave.clear();
		log.info("Phase 1: Saving {} Accommodations to RDBMS...", accommodationsToSave.size());
		accommodationRepository.saveAll(accommodationsToSave);
		log.info("Phase 1.5: Saving {} Accommodation Images...", tempImageUrlMap.size());
		processAndSaveImages();
		tempImageUrlMap.clear();
		log.info("Phase 1.6: Saving fixed Amenities for {} accommodations...", accommodationsToSave.size());
		processAndSaveFixedAmenities();
		accommodationsToSave.clear();
	}

	/**
	 * listings CSV를 읽어 RDBMS 엔티티를 메모리 List/Map에 준비
	 */
	private void processListingFiles() throws Exception {
		Resource[] listingResources = resourceResolver.getResources("classpath:mock-data/listings/**/*.csv");
		log.info("Found {} listing CSV files.", listingResources.length);
		for (Resource resource : listingResources) {
			try (InputStream stream = resource.getInputStream()) {
				MappingIterator<Map<String, String>> it = csvMapper.readerFor(Map.class).with(schema).readValues(stream);
				while (it.hasNext()) {
					Map<String, String> row = it.next();
					try {
						Long originalHostId = parseLong(row.get("host_id"));
						Long originalListingId = parseLong(row.get("id"));
						if (originalHostId == null || originalListingId == null) continue;

						Member host = memberTranslatorMap.computeIfAbsent(originalHostId, id -> {
							Member newHost = createMockMember(row.get("host_name"), id + "@mock-host.com", row.get("host_picture_url"));
							membersToSave.add(newHost);
							return newHost;
						});
						Address address = Address.builder()
							.country("Mock Country").city("Mock City").district("Mock District")
							.street("Mock Street").detail("Mock Detail")
							.latitude(parseDouble(row.get("latitude"))).longitude(parseDouble(row.get("longitude")))
							.postalCode("00000").build();
						String imageUrl = row.get("picture_url");

						int maxOccupancy = parseInt(row.get("accommodates"), 2); // 기본 2명

						// ★★★ 2. 'originalListingId'로 pet/infant 할당 (id % 2) ★★★
						int petInfantFlag = (originalListingId % 2 == 0) ? 1 : 0; // 0 또는 1

						OccupancyPolicy newPolicy = OccupancyPolicy.builder()
							.maxOccupancy(maxOccupancy)
							.infantOccupancy(petInfantFlag) // 0 또는 1
							.petOccupancy(petInfantFlag) // 0 또는 1
							.build();

						Accommodation accommodation = Accommodation.builder()
							.accommodationUid(UUID.randomUUID())
							.member(host).name(row.get("name")).description(row.get("description"))
							.address(address).status(AccommodationStatus.PUBLISHED)
							.basePrice(parsePriceToKrw(row.get("price"))).currency("KRW")
							.thumbnailUrl(imageUrl).type(AccommodationType.ENTIRE_PLACE)
							.checkInTime(LocalTime.of(15, 0))
							.checkOutTime(LocalTime.of(11, 0))
							.occupancyPolicy(newPolicy).build();

						accommodationsToSave.add(accommodation);
						accommodationTranslatorMap.put(originalListingId, accommodation);
						if (imageUrl != null && !imageUrl.isBlank()) {
							tempImageUrlMap.put(accommodation, imageUrl);
						}
					} catch (Exception e) { log.warn("Failed to parse listing row: {}", row, e); }
				}
			}
		}
	}

	private void processAndSaveImages() {
		List<AccommodationImage> imagesToSave = new ArrayList<>(tempImageUrlMap.size());
		for (Map.Entry<Accommodation, String> entry : tempImageUrlMap.entrySet()) {
			imagesToSave.add(AccommodationImage.builder()
				.imageUrl(entry.getValue()).accommodation(entry.getKey()).build());
		}
		accommodationImageRepository.saveAll(imagesToSave);
	}

	private void processAndSaveFixedAmenities() {
		List<AccommodationAmenity> amenitiesToSave = new ArrayList<>(accommodationsToSave.size() * defaultAmenities.size());
		for (Accommodation accommodation : accommodationsToSave) {
			for (Amenity amenity : defaultAmenities) {
				amenitiesToSave.add(AccommodationAmenity.builder()
					.accommodation(accommodation).amenity(amenity).build());
			}
		}
		accommodationAmenityRepository.saveAll(amenitiesToSave);
	}

	/**
	 * [Phase 2] RDBMS에 리뷰 데이터 배치 저장 (OOM 방지)
	 */
	@Transactional
	public void runPhase2_SaveReviews() throws Exception {
		Resource[] reviewResources = resourceResolver.getResources("classpath:mock-data/reviews/**/*.csv");
		log.info("Found {} review CSV files for batch processing.", reviewResources.length);
		List<Review> reviewsBatch = new ArrayList<>(REVIEW_BATCH_SIZE);
		List<Member> newReviewersBatch = new ArrayList<>(MEMBER_BATCH_SIZE);

		for (Resource resource : reviewResources) {
			try (InputStream stream = resource.getInputStream()) {
				MappingIterator<Map<String, String>> it = csvMapper.readerFor(Map.class).with(schema).readValues(stream);
				while (it.hasNext()) {
					Map<String, String> row = it.next();
					try {
						Long originalListingId = parseLong(row.get("listing_id"));
						Long originalReviewerId = parseLong(row.get("reviewer_id"));
						if (originalListingId == null || originalReviewerId == null) continue;

						Accommodation accommodation = accommodationTranslatorMap.get(originalListingId);
						if (accommodation == null) continue;

						Member reviewer = memberTranslatorMap.computeIfAbsent(originalReviewerId, id -> {
							Member newReviewer = createMockMember(row.get("reviewer_name"), id + "@mock-guest.com", null);
							newReviewersBatch.add(newReviewer);
							return newReviewer;
						});
						int syntheticRating = (int) (originalReviewerId % 5) + 1;

						Review review = Review.builder()
							.accommodation(accommodation).author(reviewer)
							.content(row.get("comments")).rating(syntheticRating)
							.status(ReviewStatus.PUBLISHED).build();
						reviewsBatch.add(review);

						if (reviewsBatch.size() >= REVIEW_BATCH_SIZE) {
							reviewRepository.saveAll(reviewsBatch);
							reviewsBatch.clear();
						}
						if (newReviewersBatch.size() >= MEMBER_BATCH_SIZE) {
							memberRepository.saveAll(newReviewersBatch);
							newReviewersBatch.clear();
						}
					} catch (Exception e) { log.warn("Failed to parse review row: {}", row, e); }
				}
			}
		}
		if (!reviewsBatch.isEmpty()) reviewRepository.saveAll(reviewsBatch);
		if (!newReviewersBatch.isEmpty()) memberRepository.saveAll(newReviewersBatch);

		// 메모리 해제
		accommodationTranslatorMap.clear();
	}

	/**
	 * [Phase 3] RDBMS 내부에서 리뷰 통계 집계 (1회 쿼리)
	 */
	@Transactional
	public void runPhase3_AggregateReviewSummaries() {
		String sql = "INSERT INTO accommodation_review_summary (accommodation_id, total_review_count, average_rating, version) " +
			"SELECT accommodation_id, COUNT(*), AVG(rating), 0 FROM review GROUP BY accommodation_id";
		try {
			int insertedRows = jdbcTemplate.update(sql);
			log.info("Phase 3: Aggregated and inserted {} rows into accommodation_review_summary.", insertedRows);
		} catch (Exception e) {
			log.error("Phase 3: FAILED to aggregate review summaries.", e);
			throw new RuntimeException("Failed to populate ReviewSummary table", e);
		}
	}

	/**
	 * [Phase 4] RDBMS -> Elasticsearch로 데이터 색인 (페이징, (1+1+1) 쿼리 최적화)
	 */
	@Transactional(readOnly = true)
	public void runPhase4_IndexToElasticsearch() {
		Pageable pageable = PageRequest.of(0, ES_PAGE_SIZE);
		Page<Accommodation> accommodationPage;
		List<AccommodationAmenity> emptyAmenityList = Collections.emptyList();

		do {
			log.info("Phase 4: Loading page {} for ES indexing...", pageable.getPageNumber());

			// 1. (쿼리 1) Accommodation 1000개 (+ Member, Policy) - QueryDSL
			accommodationPage = accommodationRepository.findForIndexing(pageable);
			List<Accommodation> accommodations = accommodationPage.getContent();
			if (accommodations.isEmpty()) break;
			List<Long> accommodationIds = accommodations.stream().map(Accommodation::getId).toList();

			// 2. (쿼리 2) ReviewSummary 1000개 (IN 쿼리)
			List<AccommodationReviewSummary> summaries = reviewSummaryRepository.findAllByAccommodationIdIn(accommodationIds);
			Map<Long, AccommodationReviewSummary> summaryMap = summaries.stream()
				.collect(Collectors.toMap(summary -> summary.getAccommodation().getId(), summary -> summary));

			// 3. (쿼리 3) Amenity 1000개 (IN 쿼리) - QueryDSL
			List<AccommodationAmenity> amenities = accommodationAmenityRepository.findAllByAccommodationIdIn(accommodationIds);
			Map<Long, List<AccommodationAmenity>> amenityMap = amenities.stream()
				.collect(Collectors.groupingBy(amenity -> amenity.getAccommodation().getId()));

			// 4. (메모리 작업) ES 문서 리스트 조립
			List<AccommodationDocument> documents = new ArrayList<>(accommodations.size());
			for (Accommodation accommodation : accommodations) {
				try {
					AccommodationReviewSummary summary = summaryMap.get(accommodation.getId());
					List<AccommodationAmenity> accommodationAmenities = amenityMap.getOrDefault(accommodation.getId(), emptyAmenityList);

					AccommodationDocument document = documentBuilder.build(
						accommodation, summary, accommodationAmenities
					);
					documents.add(document);
				} catch (Exception e) {
					log.warn("Failed to build AccommodationDocument for ID {}: {}", accommodation.getId(), e.getMessage(), e);
				}
			}
			if (documents.isEmpty()) {
				log.warn("No documents to index for page {}", pageable.getPageNumber());
				pageable = pageable.next();
				continue;
			}

			// 5. ES Bulk 저장
			try {
				elasticsearchOperations.save(documents);
			} catch (Exception e) {
				log.error("Failed to index ES page {}: {}", pageable.getPageNumber(), e.getMessage());
			}
			pageable = pageable.next();
		} while (accommodationPage.hasNext());
		log.info("Phase 4: Elasticsearch indexing finished.");
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
			// "4.0" 같은 소수점 데이터가 있을 수 있음
			return (int) Double.parseDouble(value);
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}
}
