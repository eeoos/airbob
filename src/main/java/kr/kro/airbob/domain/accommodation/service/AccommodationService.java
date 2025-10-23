package kr.kro.airbob.domain.accommodation.service;

import static kr.kro.airbob.search.event.AccommodationIndexingEvents.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.domain.accommodation.common.AmenityType;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest.AmenityInfo;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest.CreateAccommodationDto;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationAmenity;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.accommodation.entity.Amenity;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.accommodation.exception.AccommodationAccessDeniedException;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationImageRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.AddressRepository;
import kr.kro.airbob.domain.accommodation.repository.AmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.OccupancyPolicyRepository;
import kr.kro.airbob.domain.image.AccommodationImage;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import kr.kro.airbob.domain.review.entity.AccommodationReviewSummary;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;
import kr.kro.airbob.domain.wishlist.repository.WishlistAccommodationRepository;
import kr.kro.airbob.geo.GeocodingService;
import kr.kro.airbob.geo.dto.GeocodeResult;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccommodationService {

    private final AccommodationReviewSummaryRepository reviewSummaryRepository;
    private final WishlistAccommodationRepository wishlistAccommodationRepository;
    private final AccommodationAmenityRepository accommodationAmenityRepository;
    private final AccommodationImageRepository accommodationImageRepository;
    private final OccupancyPolicyRepository occupancyPolicyRepository;
    private final AccommodationRepository accommodationRepository;
    private final ReservationRepository reservationRepository;
    private final AmenityRepository amenityRepository;
    private final AddressRepository addressRepository;
    private final MemberRepository memberRepository;

    private final OutboxEventPublisher outboxEventPublisher;
    private final GeocodingService geocodingService;

    @Transactional
    public AccommodationResponse.Create createAccommodation(CreateAccommodationDto request) {

        Member member = memberRepository.findByIdAndStatus(request.getHostId(), MemberStatus.ACTIVE)
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


        return new AccommodationResponse.Create(savedAccommodation.getId());
    }

    @Transactional
    public void updateAccommodation(Long accommodationId, AccommodationRequest.UpdateAccommodationDto request, Long memberId) {
        Accommodation accommodation = findAccommodationById(accommodationId);

        validateOwner(memberId, accommodation);

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
    public void deleteAccommodation(Long accommodationId, Long memberId) {
        Accommodation accommodation = findAccommodationById(accommodationId);

        validateOwner(memberId, accommodation);

        accommodation.delete();

        outboxEventPublisher.save(
            EventType.ACCOMMODATION_DELETED,
            new AccommodationDeletedEvent(accommodation.getAccommodationUid().toString())
        );
    }

    @Transactional(readOnly = true)
    public AccommodationResponse.DetailInfo findAccommodation(Long accommodationId) {
        Accommodation accommodation = accommodationRepository.findWithDetailsByAccommodationIdAndStatus(accommodationId, AccommodationStatus.PUBLISHED)
            .orElseThrow(AccommodationNotFoundException::new);

        Address address = accommodation.getAddress();
        OccupancyPolicy policy = accommodation.getOccupancyPolicy();
        Member host = accommodation.getMember();

        // 편의 시설
        List<AccommodationResponse.AmenityInfo> amenities = getAmenities(accommodationId);

        // 이미지
        List<String> imageUrls = getImageUrls(accommodation.getAccommodationUid());

        // 리뷰
        ReviewResponse.ReviewSummary reviewSummary = getReviewSummary(accommodationId);

        // 예약 불가 날짜
        List<LocalDate> unavailableDates = getUnavailableDates(accommodation.getAccommodationUid());

        // 위시리스트 포함 여부 - 로그인 사용자만
        Boolean isInWishlist = checkWishlistStatus(accommodationId);

        return AccommodationResponse.DetailInfo.builder()
            .id(accommodation.getId())
            .name(accommodation.getName())
            .description(accommodation.getDescription())
            .type(accommodation.getType())
            .basePrice(accommodation.getBasePrice())
            .checkInTime(accommodation.getCheckInTime())
            .checkOutTime(accommodation.getCheckOutTime())
            .address(AccommodationResponse.AddressInfo.builder()
                .country(address.getCountry())
                .city(address.getCity())
                .district(address.getDistrict())
                .street(address.getStreet())
                .detail(address.getDetail())
                .postalCode(address.getPostalCode())
                .fullAddress(buildFullAddress(address))
                .build())
            .coordinate(AccommodationResponse.Coordinate.builder()
                .latitude(address.getLatitude())
                .longitude(address.getLongitude())
                .build())
            .host(AccommodationResponse.HostInfo.builder()
                .id(host.getId())
                .nickname(host.getNickname())
                .profileImageUrl(host.getThumbnailImageUrl())
                .build())
            .policyInfo(AccommodationResponse.PolicyInfo.builder()
                .maxOccupancy(policy.getMaxOccupancy())
                .adultOccupancy(policy.getAdultOccupancy())
                .childOccupancy(policy.getChildOccupancy())
                .infantOccupancy(policy.getInfantOccupancy())
                .petOccupancy(policy.getPetOccupancy())
                .build())
            .amenities(amenities)
            .imageUrls(imageUrls)
            .reviewSummary(reviewSummary)
            .unavailableDates(unavailableDates)
            .isInWishlist(isInWishlist)
            .build();
    }

    private List<AccommodationResponse.AmenityInfo> getAmenities(Long accommodationId) {
        return accommodationAmenityRepository.findAllByAccommodationId(accommodationId)
            .stream()
            .map(aa -> AccommodationResponse.AmenityInfo.builder()
                .type(aa.getAmenity().getName())
                .count(aa.getCount())
                .build())
            .toList();
    }

    private List<String> getImageUrls(UUID accommodationUid) {
        return accommodationImageRepository.findImagesByAccommodationUid(accommodationUid)
            .stream()
            .map(AccommodationImage::getImageUrl)
            .toList();
    }

    private ReviewResponse.ReviewSummary getReviewSummary(Long accommodationId) {
        Optional<AccommodationReviewSummary> summaryOpt = reviewSummaryRepository.findByAccommodationId(
            accommodationId);
        return ReviewResponse.ReviewSummary.of(summaryOpt.orElse(null));
    }

    private List<LocalDate> getUnavailableDates(UUID accommodationUid) {
        List<Reservation> futureReservations = reservationRepository.findFutureCompletedReservations(
            accommodationUid);

        return futureReservations.stream()
            .flatMap(reservation -> {
                LocalDate checkIn = reservation.getCheckIn().toLocalDate();
                LocalDate checkOut = reservation.getCheckOut().toLocalDate();

                return checkIn.datesUntil(checkOut);
            })
            .distinct()
            .sorted()
            .toList();
    }

    private Boolean checkWishlistStatus(Long accommodationId) {
        UserInfo userInfo = UserContext.get();
        if (userInfo == null || userInfo.id() == null) {
            return false; // 비로그인은 false
        }

        Long memberId = userInfo.id();
        return wishlistAccommodationRepository.existsByMemberIdAndAccommodationId(memberId, accommodationId);
    }

    private String buildFullAddress(Address address) {
        return Stream.of(address.getCountry(), address.getCity(), address.getDistrict(), address.getStreet(), address.getDetail())
            .filter(s -> s != null && !s.isBlank())
            .collect(Collectors.joining(" "));
    }

    private void validateOwner(Long memberId, Accommodation accommodation) {
        if (!accommodation.getMember().getId().equals(memberId)) {
            throw new AccommodationAccessDeniedException();
        }
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

    private Accommodation findAccommodationById(Long accommodationId) {
        return accommodationRepository.findById(accommodationId).orElseThrow(AccommodationNotFoundException::new);
    }

    private Accommodation findAccommodationByIdAndStatus(Long accommodationId, AccommodationStatus status) {
        return accommodationRepository.findByIdAndStatus(accommodationId, status).orElseThrow(AccommodationNotFoundException::new);
    }
}
