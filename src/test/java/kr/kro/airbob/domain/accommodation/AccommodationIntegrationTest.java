package kr.kro.airbob.domain.accommodation;

import static kr.kro.airbob.domain.accommodation.common.AmenityType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import jakarta.transaction.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import kr.kro.airbob.domain.accommodation.common.AccommodationType;
import kr.kro.airbob.domain.accommodation.common.AmenityType;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest.*;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse.AccommodationSearchResponseDto;
import kr.kro.airbob.domain.accommodation.entity.*;
import kr.kro.airbob.domain.accommodation.repository.*;
import kr.kro.airbob.domain.accommodation.service.AccommodationService;
import kr.kro.airbob.domain.member.Member;
import kr.kro.airbob.domain.member.MemberRepository;
import kr.kro.airbob.domain.reservation.entity.ReservedDate;
import kr.kro.airbob.domain.reservation.repository.ReservedDateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Transactional
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public class AccommodationIntegrationTest {

    @Autowired
    private AccommodationService accommodationService;

    @Autowired
    private AccommodationRepository accommodationRepository;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private AmenityRepository amenityRepository;
    @Autowired
    private AccommodationAmenityRepository accommodationAmenityRepository;
    @Autowired
    private OccupancyPolicyRepository occupancyPolicyRepository;
    @Autowired
    private AddressRepository addressRepository;
    @Autowired
    private ReservedDateRepository reservedDateRepository;

    private Member testMember;

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("testdb")
            .withUsername("user")
            .withPassword("pass");

    @DynamicPropertySource
    static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);

        registry.add("spring.flyway.url", mysql::getJdbcUrl);
        registry.add("spring.flyway.user", mysql::getUsername);
        registry.add("spring.flyway.password", mysql::getPassword);
    }

    @BeforeEach
    void setupDummyData() {
        Member member = Member.builder()
                .nickname("테스트 사용자")
                .email("test@example.com")
                .build();
        testMember = memberRepository.save(member);

        amenityRepository.save(Amenity.builder().name(WIFI).build());
        amenityRepository.save(Amenity.builder().name(TV).build());
        amenityRepository.save(Amenity.builder().name(PARKING).build());
        amenityRepository.save(Amenity.builder().name(IRON).build());
    }

    @Test
    @DisplayName("중복된 이름의 Amenity가 들어오면 count가 합산된다")
    void countDuplicatedAmenity() {
        // given
        List<AmenityInfo> amenities = List.of(
                new AmenityInfo("WIFI", 1),
                new AmenityInfo("wifi", 2)
        );

        OccupancyPolicyInfo policyInfo = OccupancyPolicyInfo.builder()
                .maxOccupancy(6)
                .build();

        CreateAccommodationDto request = CreateAccommodationDto.builder()
                .name("테스트 숙소")
                .description("설명")
                .basePrice(100000)
                .hostId(testMember.getId())
                .type("HOSTEL")
                .amenityInfos(amenities)
                .addressInfo(mock(AddressInfo.class))
                .occupancyPolicyInfo(policyInfo)
                .build();

        // when
        Long accommodationId = accommodationService.createAccommodation(request);

        // then
        List<AccommodationAmenity> savedAmenities = accommodationAmenityRepository.findAllByAccommodationId(
                accommodationId);

        assertThat(savedAmenities).hasSize(1);

        AccommodationAmenity amenity = savedAmenities.get(0);
        assertThat(amenity.getAmenity().getName()).isEqualTo(WIFI);
        assertThat(amenity.getCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("모든 정보가 주어졌을 때 숙소, 주소, 정책, 어메니티가 모두 정상적으로 업데이트된다")
    void updateAccommodationSuccess() {
        // given - 기존 엔티티 저장
        Address originalAddress = addressRepository.save(createAddress("Seoul"));
        OccupancyPolicy originalPolicy = occupancyPolicyRepository.save(createPolicy());
        Accommodation accommodation = accommodationRepository.save(
                createAccommodation(originalAddress, originalPolicy));

        Amenity wifi = Amenity.builder().name(WIFI).build();
        Amenity tv = Amenity.builder().name(TV).build();
        Amenity shampoo = Amenity.builder().name(SHAMPOO).build();
        Amenity microwave = Amenity.builder().name(MICROWAVE).build();
        amenityRepository.saveAll(List.of(wifi, tv, shampoo, microwave));

        List<AccommodationAmenity> amenities = List.of(
                AccommodationAmenity.builder().accommodation(accommodation).amenity(wifi).build(),
                AccommodationAmenity.builder().accommodation(accommodation).amenity(tv).build(),
                AccommodationAmenity.builder().accommodation(accommodation).amenity(shampoo).build(),
                AccommodationAmenity.builder().accommodation(accommodation).amenity(microwave).build()
        );
        accommodationAmenityRepository.saveAll(amenities);

        Long accommodationId = accommodation.getId();

        // 업데이트 요청
        AccommodationRequest.UpdateAccommodationDto request = AccommodationRequest.UpdateAccommodationDto.builder()
                .name("Updated Name")
                .description("Updated Description")
                .basePrice(180000)
                .type("GUESTHOUSE")
                .addressInfo(AccommodationRequest.AddressInfo.builder()
                        .postalCode("54321")
                        .city("Busan")
                        .country("KR")
                        .district("Haeundae")
                        .street("Beach-ro")
                        .detail("202")
                        .build())
                .occupancyPolicyInfo(AccommodationRequest.OccupancyPolicyInfo.builder()
                        .maxOccupancy(6)
                        .adultOccupancy(4)
                        .childOccupancy(1)
                        .infantOccupancy(1)
                        .petOccupancy(0)
                        .build())
                .amenityInfos(List.of(
                        AmenityInfo.builder().name("SHAMPOO").count(2).build(),
                        AmenityInfo.builder().name("MICROWAVE").count(1).build()))
                .build();

        // when
        accommodationService.updateAccommodation(accommodationId, request);

        // then
        Accommodation updated = accommodationRepository.findById(accommodationId).get();

        // 숙소 정보 확인
        assertThat(updated.getName()).isEqualTo("Updated Name");
        assertThat(updated.getDescription()).isEqualTo("Updated Description");
        assertThat(updated.getBasePrice()).isEqualTo(180000);
        assertThat(updated.getType().name()).isEqualTo("GUESTHOUSE");

        // 주소 정보 확인
        Address newAddress = updated.getAddress();
        assertThat(newAddress.getCity()).isEqualTo("Busan");
        assertThat(newAddress.getPostalCode()).isEqualTo(54321);
        assertThat(newAddress.getDetail()).isEqualTo("202");

        // 점유 정책 정보 확인
        OccupancyPolicy policy = updated.getOccupancyPolicy();
        assertThat(policy.getMaxOccupancy()).isEqualTo(6);
        assertThat(policy.getAdultOccupancy()).isEqualTo(4);
        assertThat(policy.getInfantOccupancy()).isEqualTo(1);

        // 어메니티 정보 확인
        List<AccommodationAmenity> accommodationAmenityList = accommodationAmenityRepository.findAllByAccommodationId(
                accommodationId);
        assertThat(accommodationAmenityList).hasSize(2);
        assertThat(accommodationAmenityList).extracting("amenity")
                .extracting("name").containsExactlyInAnyOrder(SHAMPOO, MICROWAVE);
    }

    @Test
    @DisplayName("주소 정보가 null이면 기존 주소가 유지된다")
    void ifAddressNullShouldNotChangeAddress() {
        // given
        Address originalAddress = addressRepository.save(createAddress("Seoul"));
        OccupancyPolicy policy = occupancyPolicyRepository.save(createPolicy());
        Accommodation accommodation = accommodationRepository.save(createAccommodation(originalAddress, policy));
        Long id = accommodation.getId();

        UpdateAccommodationDto updateRequest = UpdateAccommodationDto.builder()
                .name("New Name")
                .addressInfo(null)
                .build();

        // when
        accommodationService.updateAccommodation(id, updateRequest);

        // then
        Accommodation updated = accommodationRepository.findById(id).get();
        assertThat(updated.getAddress().getCity()).isEqualTo("Seoul");
    }

    @Test
    @DisplayName("인원 정책 정보가 null이면 기존 정책이 유지된다")
    void ifPolicyNullShouldNotChangePolicy() {
        // given
        Address address = addressRepository.save(createAddress("Busan"));
        OccupancyPolicy originalPolicy = occupancyPolicyRepository.save(createPolicy());
        Accommodation accommodation = accommodationRepository.save(createAccommodation(address, originalPolicy));
        Long id = accommodation.getId();

        UpdateAccommodationDto updateRequest = UpdateAccommodationDto.builder()
                .name("Updated Name")
                .occupancyPolicyInfo(null)
                .build();

        // when
        accommodationService.updateAccommodation(id, updateRequest);

        // then
        Accommodation updated = accommodationRepository.findById(id).get();
        assertThat(updated.getOccupancyPolicy().getMaxOccupancy()).isEqualTo(5);
        assertThat(updated.getOccupancyPolicy().getAdultOccupancy()).isEqualTo(3);
    }

    @Test
    @DisplayName("어메니티 리스트가 비어있으면 기존 어메니티가 유지된다")
    void emptyAmenityListShouldNotDeleteAmenities() {
        // given
        Address address = addressRepository.save(createAddress("Incheon"));
        OccupancyPolicy policy = occupancyPolicyRepository.save(createPolicy());
        Accommodation accommodation = accommodationRepository.save(createAccommodation(address, policy));

        Amenity wifi = Amenity.builder().name(WIFI).build();
        Amenity tv = Amenity.builder().name(TV).build();
        amenityRepository.saveAll(List.of(wifi, tv));

        List<AccommodationAmenity> amenities = List.of(
                AccommodationAmenity.builder().accommodation(accommodation).amenity(wifi).build(),
                AccommodationAmenity.builder().accommodation(accommodation).amenity(tv).build()
        );
        accommodationAmenityRepository.saveAll(amenities);

        Long accommodationId = accommodation.getId();

        var updateRequest = AccommodationRequest.UpdateAccommodationDto.builder()
                .name("Updated")
                .amenityInfos(List.of()) // 핵심
                .build();

        // when
        accommodationService.updateAccommodation(accommodationId, updateRequest);

        // then
        List<AccommodationAmenity> accommodationAmenityList = accommodationAmenityRepository.findAllByAccommodationId(
                accommodationId);
        assertThat(accommodationAmenityList).hasSize(2);
        assertThat(accommodationAmenityList).extracting("amenity")
                .extracting("name").containsExactlyInAnyOrder(WIFI, TV);
    }

    @Test
    @DisplayName("숙소가 삭제될때 숙소의 어메니티도 함께 삭제된다")
    void deleteAccommodationSuccess() {
        // given
        List<AmenityInfo> amenities = List.of(
                new AmenityInfo("WIFI", 1),
                new AmenityInfo("wifi", 2)
        );

        OccupancyPolicyInfo policyInfo = OccupancyPolicyInfo.builder()
                .maxOccupancy(6)
                .build();

        CreateAccommodationDto request = CreateAccommodationDto.builder()
                .name("테스트 숙소")
                .description("설명")
                .basePrice(100000)
                .hostId(testMember.getId())
                .type("HOSTEL")
                .amenityInfos(amenities)
                .occupancyPolicyInfo(policyInfo)
                .addressInfo(mock(AddressInfo.class))
                .build();

        Long id = accommodationService.createAccommodation(request);

        // when
        accommodationService.deleteAccommodation(id);

        // then
        assertThat(accommodationRepository.findById(id)).isEmpty();
        assertThat(accommodationAmenityRepository.existsByAccommodationId(id)).isFalse();
    }

    @Test
    @DisplayName("존재하지 않는 숙소를 삭제하려고 하면 예외가 발생한다")
    void deleteAccommodationThrowsExceptionIfNotFound() {
        // given
        Long nonExistentId = 999L;

        // when & then
        assertThatThrownBy(() -> accommodationService.deleteAccommodation(nonExistentId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("존재하지 않는 숙소입니다.");
    }

    @Test
    @DisplayName("어메니티가 없는 숙소는 숙소만 삭제된다")
    void deleteAccommodationSucceedsEvenIfNoAmenities() {
        // given
        OccupancyPolicyInfo policyInfo = OccupancyPolicyInfo.builder()
                .maxOccupancy(6)
                .build();

        CreateAccommodationDto request = CreateAccommodationDto.builder()
                .name("테스트 숙소")
                .description("설명")
                .basePrice(100000)
                .hostId(testMember.getId())
                .type("HOSTEL")
                .amenityInfos(null)
                .addressInfo(mock(AddressInfo.class))
                .occupancyPolicyInfo(policyInfo)
                .build();
        Long id = accommodationService.createAccommodation(request);

        // when
        accommodationService.deleteAccommodation(id);

        // then
        assertThat(accommodationRepository.existsById(id)).isFalse();
    }



    private Address createAddress(String city) {
        return Address.builder()
                .postalCode("12345")
                .city(city)
                .country("KR")
                .district("District")
                .street("Street")
                .detail("Detail")
                .build();
    }

    private OccupancyPolicy createPolicy() {
        return OccupancyPolicy.builder()
                .maxOccupancy(5)
                .adultOccupancy(3)
                .childOccupancy(1)
                .infantOccupancy(0)
                .petOccupancy(0)
                .build();
    }

    private Accommodation createAccommodation(Address address, OccupancyPolicy policy) {
        return Accommodation.builder()
                .name("Test House")
                .description("desc")
                .basePrice(100000)
                .thumbnailUrl("url")
                .type(AccommodationType.HOUSE)
                .address(address)
                .occupancyPolicy(policy)
                .member(null)
                .build();
    }

    @ParameterizedTest
    @MethodSource("provideSearchConditions")
    @DisplayName("여러 필터 조합으로 숙소를 검색할 수 있다")
    void searchByFilterWithVariousConditions(AccommodationSearchConditionDto condition, int expectedSize, List<String> expectedNames) {
        //given
        Accommodation acc1 = saveAccommodation("숙소1", "서울", 100000, "HOUSE", 4, List.of("WIFI", "TV"));
        Accommodation acc2 = saveAccommodation("숙소2", "서울", 80000, "APARTMENT", 2, List.of("WIFI"));
        Accommodation acc3 = saveAccommodation("숙소3", "부산", 150000, "VILLA", 6, List.of("PARKING"));
        Accommodation acc4 = saveAccommodation("숙소4", "대전", 120000, "HOUSE", 5, List.of());
        Accommodation acc5 = saveAccommodation("숙소5", "제주", 90000, "HOUSE", 3, List.of("TV"));

        saveReservedDates(acc2, LocalDate.of(2025, 7, 10), LocalDate.of(2025, 7, 12));

        // when
        List<AccommodationSearchResponseDto> results = accommodationRepository.searchByFilter(condition,
                PageRequest.of(0, 10));

        // then
        assertThat(results).hasSize(expectedSize);
        assertThat(results).extracting("name").containsExactlyInAnyOrderElementsOf(expectedNames);
    }

    private Accommodation saveAccommodation(String name, String city, int price, String type, int maxOccupancy, List<String> amenityNames) {
        Address address = addressRepository.save(Address.builder()
                .city(city)
                .country("KR")
                .street("테스트로")
                .build());

        OccupancyPolicy policy = occupancyPolicyRepository.save(OccupancyPolicy.builder()
                .maxOccupancy(maxOccupancy)
                .adultOccupancy(maxOccupancy)
                .childOccupancy(0)
                .infantOccupancy(0)
                .petOccupancy(0)
                .build());

        Accommodation acc = accommodationRepository.save(Accommodation.builder()
                .name(name)
                .basePrice(price)
                .type(AccommodationType.valueOf(type))
                .address(address)
                .occupancyPolicy(policy)
                .build());

        for (String nameStr : amenityNames) {
            Amenity amenity = amenityRepository.findByName(AmenityType.valueOf(nameStr)).get();
            accommodationAmenityRepository.save(
                    AccommodationAmenity.createAccommodationAmenity(acc, amenity, 1)
            );
        }

        return acc;
    }

    private void saveReservedDates(Accommodation accommodation, LocalDate start, LocalDate end) {
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            reservedDateRepository.save(ReservedDate.builder()
                    .accommodation(accommodation)
                    .reservedAt(date)
                    .build());
        }
    }

    static Stream<Arguments> provideSearchConditions() {
        return Stream.of(
                // 1. 도시만 검색
                Arguments.of(
                        createCondition("서울", null, null, null, null,
                                null, null, null),
                        2,
                        List.of("숙소1", "숙소2")
                ),

                // 2. 최소/최대 가격만 지정
                Arguments.of(
                        createCondition(null, 50000, 100000, null, null,
                                null, null, null),
                        3,
                        List.of("숙소1", "숙소2", "숙소5")
                ),

                // 3. 도시 + 타입
                Arguments.of(
                        createCondition("부산", null, null, null, null,
                                null, null, List.of("VILLA")),
                        1,
                        List.of("숙소3")
                ),

                // 4. 어메니티 필터만 적용
                Arguments.of(
                        createCondition(null, null, null, null, null,
                                null, List.of("WIFI"), null),
                        2,
                        List.of("숙소1", "숙소2")
                ),

                // 5. 도시 + 날짜 필터
                Arguments.of(
                        createCondition("제주", null, null, LocalDate.of(2025, 7, 1),
                                LocalDate.of(2025, 7, 3), null, null, null),
                        1,
                        List.of("숙소5")
                ),

                // 6. 날짜 필터 (예약된 숙소 제외)
                Arguments.of(
                        createCondition(null, null, null, LocalDate.of(2025, 7, 10),
                                LocalDate.of(2025, 7, 12), null, null, null),
                        4,
                        List.of("숙소1", "숙소3", "숙소4", "숙소5") // 숙소2 제외
                ),

                // 7. 인원 수 제한
                Arguments.of(
                        createCondition(null, null, null, null, null,
                                5, null, null),
                        2,
                        List.of("숙소3", "숙소4")
                ),

                // 8. 전체 조건 조합
                Arguments.of(
                        createCondition("서울", 50000, 200000, LocalDate.of(2025, 8, 1),
                                LocalDate.of(2025, 8, 3), 4, List.of("WIFI", "TV"), List.of("HOUSE")),
                        1,
                        List.of("숙소1")
                )
        );
    }

    private static AccommodationSearchConditionDto createCondition(
            String city,
            Integer minPrice,
            Integer maxPrice,
            LocalDate checkIn,
            LocalDate checkOut,
            Integer guestCount,
            List<String> amenityTypes,
            List<String> accommodationTypes
    ) {
        return AccommodationSearchConditionDto.builder()
                .city(city)
                .minPrice(minPrice)
                .maxPrice(maxPrice)
                .checkIn(checkIn)
                .checkOut(checkOut)
                .guestCount(guestCount)
                .amenityTypes(amenityTypes)
                .accommodationTypes(accommodationTypes)
                .build();
    }
}
