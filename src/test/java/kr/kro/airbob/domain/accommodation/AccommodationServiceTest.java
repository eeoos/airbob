package kr.kro.airbob.domain.accommodation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import kr.kro.airbob.domain.accommodation.common.AccommodationType;
import kr.kro.airbob.domain.accommodation.common.AmenityType;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest.AddressInfo;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest.AmenityInfo;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest.CreateAccommodationDto;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest.OccupancyPolicyInfo;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationAmenity;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.accommodation.entity.Amenity;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.AddressRepository;
import kr.kro.airbob.domain.accommodation.repository.AmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.OccupancyPolicyRepository;
import kr.kro.airbob.domain.accommodation.service.AccommodationService;
import kr.kro.airbob.domain.member.Member;
import kr.kro.airbob.domain.member.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccommodationServiceTest {

    @InjectMocks
    private AccommodationService accommodationService;
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private OccupancyPolicyRepository occupancyPolicyRepository;
    @Mock
    private AddressRepository addressRepository;
    @Mock
    private AccommodationRepository accommodationRepository;
    @Mock
    private AmenityRepository amenityRepository;
    @Mock
    private AccommodationAmenityRepository accommodationAmenityRepository;

    @Test
    @DisplayName("모든 정보가 정상적으로 주어졌을 때 Accommodation, Address, OccupancyPolicy, Amenity까지 모두 저장된다")
    void createAccommodationSuccess() {
        // given
        Long hostId = 1L;
        Long expectedAccommodationId = 100L;

        Member host = mock(Member.class);
        CreateAccommodationDto request = mock(CreateAccommodationDto.class);
        OccupancyPolicyInfo policyInfo = mock(OccupancyPolicyInfo.class);
        AddressInfo addressInfo = mock(AddressInfo.class);

        AmenityInfo wifiInfo = new AmenityInfo("WIFI", 2);
        AmenityInfo tvInfo = new AmenityInfo("TV", 1);

        List<AmenityInfo> amenityInfos = List.of(wifiInfo, tvInfo);

        Amenity wifiAmenity = Amenity.builder().id(1L).name(AmenityType.WIFI).build();
        Amenity tvAmenity = Amenity.builder().id(2L).name(AmenityType.TV).build();

        given(request.getHostId()).willReturn(hostId);
        given(memberRepository.findById(hostId)).willReturn(Optional.of(host));
        given(request.getOccupancyPolicyInfo()).willReturn(policyInfo);
        given(request.getAddressInfo()).willReturn(addressInfo);
        given(request.getAmenityInfos()).willReturn(amenityInfos);
        given(request.getType()).willReturn(AccommodationType.APARTMENT.name());

        OccupancyPolicy occupancyPolicy = mock(OccupancyPolicy.class);
        given(occupancyPolicyRepository.save(any())).willReturn(occupancyPolicy);

        Address address = mock(Address.class);
        given(addressRepository.save(any())).willReturn(address);

        Accommodation accommodation = mock(Accommodation.class);
        Accommodation savedAccommodation = mock(Accommodation.class);

        given(accommodationRepository.save(any())).willReturn(savedAccommodation);
        given(savedAccommodation.getId()).willReturn(expectedAccommodationId);

        given(amenityRepository.findByNameIn(Set.of(AmenityType.WIFI, AmenityType.TV)))
                .willReturn(List.of(wifiAmenity, tvAmenity));

        // when
        Long accommodationId = accommodationService.createAccommodation(request);

        // then
        assertEquals(expectedAccommodationId, accommodationId);
        verify(memberRepository).findById(hostId);
        verify(occupancyPolicyRepository).save(any());
        verify(addressRepository).save(any());
        verify(accommodationRepository).save(any());

        ArgumentCaptor<List<AccommodationAmenity>> captor = ArgumentCaptor.forClass(List.class);
        verify(accommodationAmenityRepository, times(1)).saveAll(captor.capture());

        List<AccommodationAmenity> savedAmenities = captor.getValue();
        Map<String, Integer> countMap = savedAmenities.stream()
                .collect(Collectors.toMap(a -> a.getAmenity().getName().toString(), AccommodationAmenity::getCount));

        assertEquals(2, countMap.get("WIFI"));
        assertEquals(1, countMap.get("TV"));
    }

    @Test
    @DisplayName("존재하지 않는 회원일 때 예외가 발생힌다")
    void createAccommodationWhenMemberNotFound() {
        // given
        CreateAccommodationDto request = mock(CreateAccommodationDto.class);
        given(request.getHostId()).willReturn(999L);
        given(memberRepository.findById(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> accommodationService.createAccommodation(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("존재하지 않는 회원입니다.");
    }


    @Test
    @DisplayName("DB에 사전에 등록되지 않은 어메니티는 등록할 수 없다.")
    void ignoresInvalidAmenityNames() {
        // given
        List<AmenityInfo> amenities = List.of(
                new AmenityInfo("WIFI", 1),
                new AmenityInfo("UNKNOWN", 1),
                new AmenityInfo("POOL", 1)
        );

        OccupancyPolicyInfo policyInfo = OccupancyPolicyInfo.builder()
                .maxOccupancy(6)
                .build();

        CreateAccommodationDto request = CreateAccommodationDto.builder()
                .hostId(1L)
                .amenityInfos(amenities)
                .type("HOTEL_ROOM")
                .addressInfo(mock(AddressInfo.class))
                .occupancyPolicyInfo(policyInfo)
                .build();

        Member member = Member.builder()
                .id(1L)
                .nickname("testMember")
                .build();
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        List<Amenity> amenityList = List.of(
                Amenity.builder().id(1L).name(AmenityType.WIFI).build(),
                Amenity.builder().id(2L).name(AmenityType.POOL).build());
        given(amenityRepository.findByNameIn(any())).willReturn(amenityList);

        Accommodation accommodation = Accommodation.builder().id(1L).build();
        given(accommodationRepository.save(any())). willReturn(accommodation);

        ArgumentCaptor<List<AccommodationAmenity>> captor = ArgumentCaptor.forClass(List.class);

        // when
        accommodationService.createAccommodation(request);

        // then
        verify(accommodationAmenityRepository).saveAll(captor.capture());

        List<AccommodationAmenity> saved = captor.getValue();
        List<AmenityType> types = saved.stream()
                .map(AccommodationAmenity::getAmenity)
                .map(Amenity::getName)
                .toList();

        assertThat(types).containsExactlyInAnyOrder(AmenityType.WIFI, AmenityType.POOL);
        assertThat(types).doesNotContain(AmenityType.UNKNOWN);
    }

    @Test
    @DisplayName("어메니티 갯수가 0개 이하면 저장되지 않는다 ")
    void ignoresAmenitiesWithNonPositiveCount() {
        // given
        List<AmenityInfo> amenities = List.of(
                new AmenityInfo("WIFI", 1),
                new AmenityInfo("POOL", 0),       // 무시됨
                new AmenityInfo("PARKING", -1)    // 무시됨
        );
        OccupancyPolicyInfo policyInfo = OccupancyPolicyInfo.builder()
                .maxOccupancy(6)
                .build();

        CreateAccommodationDto request = CreateAccommodationDto.builder()
                .hostId(1L)
                .amenityInfos(amenities)
                .type("HOTEL_ROOM")
                .addressInfo(mock(AddressInfo.class))
                .occupancyPolicyInfo(policyInfo)
                .build();

        Member member = Member.builder()
                .id(1L)
                .nickname("testMember")
                .build();
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        given(amenityRepository.findByNameIn(Set.of(AmenityType.WIFI)))
                .willReturn(List.of(Amenity.builder().id(1L).name(AmenityType.WIFI).build()));

        Accommodation accommodation = Accommodation.builder().id(1L).build();
        given(accommodationRepository.save(any())). willReturn(accommodation);

        // when
        accommodationService.createAccommodation(request);

        // then
        ArgumentCaptor<List<AccommodationAmenity>> captor = ArgumentCaptor.forClass(List.class);
        verify(accommodationAmenityRepository).saveAll(captor.capture());

        List<AccommodationAmenity> savedAmenities = captor.getValue();
        assertThat(savedAmenities)
                .hasSize(1)
                .extracting(AccommodationAmenity::getAmenity)
                .extracting(Amenity::getName)
                .containsOnly(AmenityType.WIFI);
    }

    @Test
    @DisplayName("모든 정보가 정상적으로 주어졌을 때 Accommodation, Address, OccupancyPolicy, Amenity까지 모두 수정된다")
    void updateAccommodationSuccess() {
        // given
        Long accommodationId = 1L;

        AccommodationRequest.AddressInfo addressInfo = AccommodationRequest.AddressInfo.builder()
                .postalCode("12345")
                .city("Seoul")
                .country("KR")
                .district("Gangnam")
                .street("Teheran-ro")
                .detail("101")
                .build();

        AccommodationRequest.OccupancyPolicyInfo occupancyPolicyInfo = AccommodationRequest.OccupancyPolicyInfo.builder()
                .maxOccupancy(5)
                .adultOccupancy(3)
                .childOccupancy(1)
                .infantOccupancy(1)
                .petOccupancy(0)
                .build();

        List<AccommodationRequest.AmenityInfo> amenityInfos = List.of(
                new AccommodationRequest.AmenityInfo("WIFI", 2),
                new AccommodationRequest.AmenityInfo("TV", 1)
        );

        AccommodationRequest.UpdateAccommodationDto request = AccommodationRequest.UpdateAccommodationDto.builder()
                .name("Updated Name")
                .description("Updated Description")
                .basePrice(150000)
                .addressInfo(addressInfo)
                .occupancyPolicyInfo(occupancyPolicyInfo)
                .type("GUESTHOUSE")
                .amenityInfos(amenityInfos)
                .build();

        Accommodation accommodation = Accommodation.builder()
                .name("Old Name")
                .description("Old Description")
                .basePrice(100000)
                .type(AccommodationType.APARTMENT)
                .member(mock(Member.class))
                .build();

        given(accommodationRepository.findById(accommodationId)).willReturn(Optional.of(accommodation));

        // Address, Policy 저장 mock
        given(addressRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(occupancyPolicyRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        // when
        accommodationService.updateAccommodation(accommodationId, request);

        // then
        assertThat(accommodation.getName()).isEqualTo("Updated Name");
        assertThat(accommodation.getDescription()).isEqualTo("Updated Description");
        assertThat(accommodation.getBasePrice()).isEqualTo(150000);
        assertThat(accommodation.getType()).isEqualTo(AccommodationType.GUESTHOUSE);

        assertThat(accommodation.getAddress().getCity()).isEqualTo("Seoul");
        assertThat(accommodation.getAddress().getPostalCode()).isEqualTo(12345);

        assertThat(accommodation.getOccupancyPolicy().getMaxOccupancy()).isEqualTo(5);
        assertThat(accommodation.getOccupancyPolicy().getAdultOccupancy()).isEqualTo(3);

        verify(accommodationAmenityRepository).deleteAllByAccommodationId(accommodationId);
    }

    @Test
    @DisplayName("존재하지 않는 숙소 ID로 업데이트 시 예외가 발생한다")
    void updateAccommodationNonexistentAccommodation() {
        // given
        Long invalidAccommodationId = 999L;

        AccommodationRequest.UpdateAccommodationDto request = AccommodationRequest.UpdateAccommodationDto.builder()
                .name("Test")
                .build();

        given(accommodationRepository.findById(invalidAccommodationId))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> accommodationService.updateAccommodation(invalidAccommodationId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("존재하지 않는 숙소입니다.");
    }

    @ParameterizedTest
    @CsvSource({
            "'Updated Name',,,",                          // name만 있음
            ", 'Updated Desc',,",                         // description만 있음
            ",, 200000,",                                 // basePrice만 있음
            ",,, 'GUESTHOUSE'",                           // type만 있음
            "'Updated Name','Updated Desc',,",            // name + description
            "'Updated Name',,200000,",                    // name + basePrice
            "'Updated Name',,200000,'GUESTHOUSE'",        // name + basePrice + type
            ", 'Updated Desc',200000,'HOTEL'",            // description + basePrice + type
            "'Updated Name','Updated Desc',200000,'HOTEL'", // name + description + basePrice + type
            ",,,",                                        // 모두 null (변경 없음)
    })
    @DisplayName("일부 필드만 주어진 경우 해당 필드만 업데이트된다")
    void updateAccommodation_partialFields(String name, String description, Integer basePrice) {
        // given
        Long accommodationId = 1L;

        AccommodationRequest.UpdateAccommodationDto request = AccommodationRequest.UpdateAccommodationDto.builder()
                .name(name)
                .description(description)
                .basePrice(basePrice)
                .build();

        Accommodation accommodation = Accommodation.builder()
                .name("Old Name")
                .description("Old Desc")
                .basePrice(100000)
                .build();

        given(accommodationRepository.findById(accommodationId)).willReturn(Optional.of(accommodation));

        // when
        accommodationService.updateAccommodation(accommodationId, request);

        // then
        if (name != null) assertThat(accommodation.getName()).isEqualTo(name);
        else assertThat(accommodation.getName()).isEqualTo("Old Name");

        if (description != null) assertThat(accommodation.getDescription()).isEqualTo(description);
        else assertThat(accommodation.getDescription()).isEqualTo("Old Desc");

        if (basePrice != null) assertThat(accommodation.getBasePrice()).isEqualTo(basePrice);
        else assertThat(accommodation.getBasePrice()).isEqualTo(100000);
    }

}
