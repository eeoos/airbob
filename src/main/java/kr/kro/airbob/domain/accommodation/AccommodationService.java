package kr.kro.airbob.domain.accommodation;

import static kr.kro.airbob.search.event.AccommodationIndexingEvents.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.kro.airbob.domain.accommodation.common.AmenityType;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest.AccommodationSearchConditionDto;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest.AmenityInfo;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest.CreateAccommodationDto;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse.AccommodationSearchResponseDto;
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
import kr.kro.airbob.domain.member.Member;
import kr.kro.airbob.domain.member.MemberRepository;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.geo.GeocodingService;
import kr.kro.airbob.geo.dto.GeocodeResult;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccommodationService {

    private final AccommodationRepository accommodationRepository;
    private final MemberRepository memberRepository;
    private final AmenityRepository amenityRepository;
    private final AccommodationAmenityRepository accommodationAmenityRepository;
    private final OccupancyPolicyRepository occupancyPolicyRepository;
    private final AddressRepository addressRepository;

    private final OutboxEventPublisher outboxEventPublisher;
    private final GeocodingService geocodingService;

    @Transactional
    public Long createAccommodation(CreateAccommodationDto request) {

        Member member = memberRepository.findById(request.getHostId())
                .orElseThrow(MemberNotFoundException::new);

        OccupancyPolicy occupancyPolicy = OccupancyPolicy.createOccupancyPolicy(request.getOccupancyPolicyInfo());
        occupancyPolicyRepository.save(occupancyPolicy);

        String addressStr = geocodingService.buildAddressString(request.getAddressInfo());
        GeocodeResult geocodeResult = geocodingService.getCoordinates(addressStr);

        Address address = Address.createAddress(request.getAddressInfo(), geocodeResult);
        addressRepository.save(address);

        Accommodation accommodation = Accommodation.createAccommodation(request, address, occupancyPolicy, member);
        Accommodation savedAccommodation = accommodationRepository.save(accommodation);

        //사전에 정의해둔 어메니티만 저장 가능
        if (request.getAmenityInfos() != null) {
            saveValidAmenities(request.getAmenityInfos(), savedAccommodation);
        }

        outboxEventPublisher.save(
            EventType.ACCOMMODATION_CREATED,
            new AccommodationCreatedEvent(savedAccommodation.getAccommodationUid().toString())
        );

        return savedAccommodation.getId();
    }

    private void saveValidAmenities(List<AmenityInfo> request, Accommodation savedAccommodation) {
        Map<AmenityType, Integer> amenityCountMap = getAmenityCountMap(request);

        List<Amenity> amenities = amenityRepository.findByNameIn(amenityCountMap.keySet());

        saveAccommodationAmenity(savedAccommodation, amenities, amenityCountMap);
    }

    private void saveAccommodationAmenity(Accommodation savedAccommodation, List<Amenity> amenities,
                           Map<AmenityType, Integer> amenityCountMap) {

        List<AccommodationAmenity> accommodationAmenityList = new ArrayList<>();
        for (Amenity amenity : amenities) {
            int count = amenityCountMap.get(amenity.getName());

            AccommodationAmenity accommodationAmenity = AccommodationAmenity.createAccommodationAmenity(
                    savedAccommodation, amenity, count);
            accommodationAmenityList.add(accommodationAmenity);
        }
        accommodationAmenityRepository.saveAll(accommodationAmenityList);
    }

    private Map<AmenityType, Integer> getAmenityCountMap(List<AmenityInfo> request) {
        return request.stream()
                .filter(info -> AmenityType.isValid(info.getName()))
                .filter(info -> info.getCount() > 0)
                .collect(Collectors.toMap(
                        info -> AmenityType.valueOf(info.getName().toUpperCase()),
                        AmenityInfo::getCount,
                        Integer::sum
                ));
    }

    @Transactional
    public void updateAccommodation(Long accommodationId, AccommodationRequest.UpdateAccommodationDto request) {
        Accommodation accommodation = accommodationRepository.findById(accommodationId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 숙소입니다."));

        accommodation.updateAccommodation(request);

        if (request.getAddressInfo() != null && accommodation.getAddress().isChanged(request.getAddressInfo())) {
            String addressStr = geocodingService.buildAddressString(request.getAddressInfo());
            GeocodeResult geocodeResult = geocodingService.getCoordinates(addressStr);

            Address newAddress = Address.createAddress(request.getAddressInfo(), geocodeResult);
            Address savedAddress = addressRepository.save(newAddress);

            accommodation.updateAddress(savedAddress);
        }

        if (request.getOccupancyPolicyInfo() != null) {
            OccupancyPolicy occupancyPolicy = OccupancyPolicy.createOccupancyPolicy(request.getOccupancyPolicyInfo());
            OccupancyPolicy savedOccupancyPolicy = occupancyPolicyRepository.save(occupancyPolicy);
            accommodation.updateOccupancyPolicy(savedOccupancyPolicy);
        }

        if (request.getAmenityInfos() != null && !request.getAmenityInfos().isEmpty()){
            accommodationAmenityRepository.deleteAllByAccommodationId(accommodationId);
            saveValidAmenities(request.getAmenityInfos(), accommodation);
        }

        outboxEventPublisher.save(
            EventType.ACCOMMODATION_UPDATED,
            new AccommodationUpdatedEvent(accommodation.getAccommodationUid().toString())
        );
    }

    @Transactional
    public void deleteAccommodation(Long accommodationId) {
        Accommodation accommodation = accommodationRepository.findById(accommodationId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 숙소입니다."));

        accommodationAmenityRepository.deleteByAccommodationId(accommodationId);
        accommodationRepository.delete(accommodation);

        outboxEventPublisher.save(
            EventType.ACCOMMODATION_DELETED,
            new AccommodationDeletedEvent(accommodation.getAccommodationUid().toString())
        );
    }

    public List<AccommodationSearchResponseDto> searchAccommodations(AccommodationSearchConditionDto request, Pageable pageable) {
        return accommodationRepository.searchByFilter(request, pageable);
    }
}
