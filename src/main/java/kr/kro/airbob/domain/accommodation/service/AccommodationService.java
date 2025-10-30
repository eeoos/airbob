package kr.kro.airbob.domain.accommodation.service;

import static kr.kro.airbob.search.event.AccommodationIndexingEvents.*;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.accommodation.common.AmenityType;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest.AmenityInfo;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationAmenity;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.accommodation.entity.Amenity;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.exception.AccommodationStateException;
import kr.kro.airbob.domain.accommodation.exception.PublishingValidationException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationImageRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.AddressRepository;
import kr.kro.airbob.domain.accommodation.repository.AmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.OccupancyPolicyRepository;
import kr.kro.airbob.domain.image.entity.AccommodationImage;
import kr.kro.airbob.domain.image.exception.EmptyImageFileException;
import kr.kro.airbob.domain.image.exception.ImageFileSizeExceededException;
import kr.kro.airbob.domain.image.exception.ImageNotFoundException;
import kr.kro.airbob.domain.image.exception.ImageUploadException;
import kr.kro.airbob.domain.image.exception.InsufficientImageCountException;
import kr.kro.airbob.domain.image.exception.InvalidImageFormatException;
import kr.kro.airbob.domain.image.service.S3ImageUploader;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.member.repository.MemberRepository;
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
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccommodationService {

    public static final int MAX_IMAGE_SIZE = 10 * 1024 * 1024;
    public static final String IMAGE_JPEG = "image/jpeg";
    public static final String IMAGE_PNG = "image/png";

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

    private final CursorPageInfoCreator cursorPageInfoCreator;
    private final OutboxEventPublisher outboxEventPublisher;
    private final GeocodingService geocodingService;
    private final S3ImageUploader s3ImageUploader;

    @Transactional
    public AccommodationResponse.Create createAccommodation(Long memberId) {

        Member member = memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE)
                .orElseThrow(MemberNotFoundException::new);

        Accommodation accommodation = Accommodation.createAccommodation(member);
        Accommodation savedAccommodation = accommodationRepository.save(accommodation);

        return new AccommodationResponse.Create(savedAccommodation.getId());
    }

    @Transactional
    public void updateAccommodation(Long accommodationId, AccommodationRequest.Update request, Long memberId) {

        Accommodation accommodation = findByIdAndMemberIdAndStatusNot(accommodationId, memberId);

        accommodation.updateAccommodation(request);
        updateAddress(accommodation, request.addressInfo());
        updateOccupancyPolicy(accommodation, request.occupancyPolicyInfo());
        updateAmenities(accommodation, request.amenityInfos());

        if (accommodation.getStatus() == AccommodationStatus.PUBLISHED) { // 게시 상태인 경우 ES 색인 이벤트 발행
            outboxEventPublisher.save(
                EventType.ACCOMMODATION_UPDATED,
                new AccommodationUpdatedEvent(accommodation.getAccommodationUid().toString())
            );
        }
    }

    @Transactional
    public void deleteAccommodation(Long accommodationId, Long memberId) {
        Accommodation accommodation = findByIdAndMemberIdExceptDeleted(accommodationId, memberId);

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

    @Transactional(readOnly = true)
    public AccommodationResponse.MyAccommodationInfos findMyAccommodations(Long hostId, CursorRequest.CursorPageRequest cursorRequest) {
        Slice<Accommodation> accommodationSlice = accommodationRepository.findMyAccommodationsByHostIdWithCursor(
            hostId,
            cursorRequest.lastId(),
            cursorRequest.lastCreatedAt(),
            PageRequest.of(0, cursorRequest.size())
        );

        List<Accommodation> accommodations = accommodationSlice.getContent();
        if (accommodations.isEmpty()) {
            CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
                Collections.emptyList(), false, acc -> 0L, acc -> null
            );
            return AccommodationResponse.MyAccommodationInfos.builder()
                .accommodations(Collections.emptyList())
                .pageInfo(pageInfo)
                .build();
        }

        List<Long> accommodationIds = accommodations.stream()
            .map(Accommodation::getId)
            .toList();
        Map<Long, AccommodationReviewSummary> reviewSummaryMap = reviewSummaryRepository.findByAccommodationIdIn(
                accommodationIds)
            .stream()
            .collect(Collectors.toMap(AccommodationReviewSummary::getAccommodationId, Function.identity()));

        List<AccommodationResponse.MyAccommodationInfo> accommodationInfos = accommodations.stream()
            .map(acc -> {
                Address address = acc.getAddress();
                AccommodationReviewSummary reviewSummary = reviewSummaryMap.get(acc.getId());
                String location = (address.getCity() != null ? address.getCity() : "") +
                    (address.getDistrict() != null ? " " + address.getDistrict() : "");

                ReviewResponse.ReviewSummary reviewSummaryDto = ReviewResponse.ReviewSummary.of(reviewSummary);

                return AccommodationResponse.MyAccommodationInfo.builder()
                    .id(acc.getId())
                    .name(acc.getName())
                    .thumbnailUrl(acc.getThumbnailUrl())
                    .status(acc.getStatus())
                    .location(location.trim())
                    .basePrice(acc.getBasePrice())
                    .reviewSummary(reviewSummaryDto)
                    .createdAt(acc.getCreatedAt())
                    .build();

            }).toList();

        CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
            accommodations,
            accommodationSlice.hasNext(),
            Accommodation::getId,
            Accommodation::getCreatedAt
        );

        return AccommodationResponse.MyAccommodationInfos.builder()
            .accommodations(accommodationInfos)
            .pageInfo(pageInfo)
            .build();

    }

    @Transactional
    public AccommodationResponse.UploadImages uploadImages(Long accommodationId, List<MultipartFile> images,
        Long memberId) {

        // todo: 숙소 + 호스트 조회 -> 호스트인지 여부 검증으로 변경 필요
        Accommodation accommodation = findByIdAndMemberIdExceptDeleted(accommodationId, memberId);

        List<AccommodationResponse.ImageInfo> uploadedImages = new ArrayList<>();
        List<AccommodationImage> savedImages = new ArrayList<>();

        for (MultipartFile image : images) {
            validateImageFile(image);

            String imageUrl;
            try {
                String dirName = "accommodations/" + accommodationId;
                imageUrl = s3ImageUploader.upload(image, dirName);
            } catch (IOException e) {
                log.error("이미지 업로드 실패: accommodationId={}, fileName={}", accommodation.getId(),
                    image.getOriginalFilename(), e);
                throw new ImageUploadException(image.getOriginalFilename());
            }

            AccommodationImage accommodationImage = AccommodationImage.builder()
                .accommodation(accommodation)
                .imageUrl(imageUrl)
                .build();
            savedImages.add(accommodationImage);
        }

        List<AccommodationImage> actuallySavedImages = accommodationImageRepository.saveAll(savedImages);

        for (AccommodationImage savedImage : actuallySavedImages) {
            uploadedImages.add(AccommodationResponse.ImageInfo.builder()
                .id(savedImage.getId())
                .imageUrl(savedImage.getImageUrl())
                .build());
        }

        findAndUpdateThumbnail(accommodation);

        return AccommodationResponse.UploadImages.builder()
            .uploadedImages(uploadedImages)
            .build();
    }

    //todo: query 2번
    @Transactional
    public void deleteImage(Long accommodationId, Long imageId, Long memberId) {
        Accommodation accommodation = findByIdAndMemberIdExceptDeleted(accommodationId, memberId);

        // 숙소 이미지 + 숙소 함께 조회
        AccommodationImage image = accommodationImageRepository.findById(imageId)
            .orElseThrow(ImageNotFoundException::new);

        if (!image.getAccommodation().getId().equals(accommodationId)) {
            throw new InvalidInputException();
        }

        s3ImageUploader.delete(image.getImageUrl());

        accommodationImageRepository.delete(image);

        if (image.getImageUrl().equals(accommodation.getThumbnailUrl())) {
            // 삭제된 이미지가 현재 썸네일이었을 경우 새로운 썸네일 설정
            findAndUpdateThumbnail(accommodation);
        }
    }

    @Transactional(readOnly = true)
    public AccommodationResponse.DetailInfo findHostAccommodationDetail(Long accommodationId, Long hostId) {
        Accommodation accommodation = accommodationRepository.findWithDetailsByIdAndHostId(accommodationId, hostId)
            .orElseThrow(AccommodationNotFoundException::new);

        Address address = accommodation.getAddress();
        OccupancyPolicy policy = accommodation.getOccupancyPolicy();
        Member host = accommodation.getMember();

        List<AccommodationResponse.AmenityInfo> amenities = getAmenities(accommodationId);
        List<String> imageUrls = getImageUrls(accommodation.getAccommodationUid());
        ReviewResponse.ReviewSummary reviewSummary = getReviewSummary(accommodationId);
        List<LocalDate> unavailableDates = getUnavailableDates(accommodation.getAccommodationUid());

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
            .isInWishlist(null)
            .build();
    }

    @Transactional
    public void publishAccommodation(Long accommodationId, Long memberId) {
        Accommodation accommodation = findWithDetailsExceptHostById(accommodationId, memberId);

        validateAccommodationForPublishing(accommodation);

        accommodation.publish();

        outboxEventPublisher.save(
            EventType.ACCOMMODATION_UPDATED,
            new AccommodationUpdatedEvent(accommodation.getAccommodationUid().toString())
        );
    }

    @Transactional
    public void unpublishAccommodation(Long accommodationId, Long memberId) {
        Accommodation accommodation = findByIdAndMemberIdExceptDeleted(accommodationId, memberId);

        if (accommodation.getStatus() != AccommodationStatus.PUBLISHED) {
            throw new AccommodationStateException();
        }

        accommodation.unpublish();

        outboxEventPublisher.save(
            EventType.ACCOMMODATION_UPDATED,
            new AccommodationUpdatedEvent(accommodation.getAccommodationUid().toString())
        );
    }

    private void validateAccommodationForPublishing(Accommodation accommodation) {
        if (accommodation.getName() == null || accommodation.getName().isBlank()) {
            throw new PublishingValidationException("숙소 이름");
        }
        if (accommodation.getDescription() == null || accommodation.getDescription().isBlank()) {
            throw new PublishingValidationException("숙소 설명");
        }

        Address address = accommodation.getAddress();
        if (address == null || address.getCountry() == null || address.getCity() == null
            || address.getDistrict() == null || address.getStreet() == null || address.getDetail() == null
            || address.getPostalCode() == null || address.getLatitude() == null || address.getLongitude() == null) {

            throw new PublishingValidationException("숙소 주소");
        }

        if (accommodation.getBasePrice() == null || accommodation.getBasePrice() < 5000) { // 가격 검증 (5000원 이상)
            throw new PublishingValidationException(ErrorCode.ACCOMMODATION_INVALID_PRICE);
        }

        if (accommodation.getType() == null) {
            throw new PublishingValidationException("숙소 타입");
        }
        OccupancyPolicy policy = accommodation.getOccupancyPolicy();
        if (policy == null || policy.getMaxOccupancy() == null || policy.getAdultOccupancy() == null
            || policy.getChildOccupancy() == null || policy.getInfantOccupancy() == null
            || policy.getPetOccupancy() == null) {

            throw new PublishingValidationException("수용 인원");
        }

        if (accommodation.getCheckInTime() == null || accommodation.getCheckOutTime() == null) {
            throw new PublishingValidationException("체크인/체크아웃");
        }

        long imageCount = accommodationImageRepository.countByAccommodationId(accommodation.getId());
        int minImageCount = 5; // 최소 이미지 개수
        if (imageCount < minImageCount) {
            throw new InsufficientImageCountException("이미지는 최소 " + minImageCount + "개 이상 등록해야 게시할 수 있습니다. 현재: " + imageCount + "개");
        }
    }

    private void validateImageFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new EmptyImageFileException();
        }

        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new ImageFileSizeExceededException();
        }

        String contentType = file.getContentType();
        if (contentType == null || (!contentType.equals(IMAGE_JPEG) && !contentType.equals(IMAGE_PNG))) {
            throw new InvalidImageFormatException();
        }
    }

    private void findAndUpdateThumbnail(Accommodation accommodation) {

        List<AccommodationImage> remainingImages = accommodationImageRepository.findByAccommodationIdOrderByIdAsc(accommodation.getId());
        String newThumbnailUrl = remainingImages.isEmpty() ? null : remainingImages.getFirst().getImageUrl();

        if (!Objects.equals(accommodation.getThumbnailUrl(), newThumbnailUrl)) {
            accommodation.updateThumbnailUrl(newThumbnailUrl);
        }
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
        return wishlistAccommodationRepository.existsByWishlist_Member_IdAndAccommodation_Id(memberId, accommodationId);
    }

    private String buildFullAddress(Address address) {
        return Stream.of(address.getCountry(), address.getCity(), address.getDistrict(), address.getStreet(), address.getDetail())
            .filter(s -> s != null && !s.isBlank())
            .collect(Collectors.joining(" "));
    }

    private void saveValidAmenities(List<AmenityInfo> request, Accommodation savedAccommodation) {
        Map<AmenityType, Integer> amenityCountMap = getAmenityCountMap(request);

        if(amenityCountMap.isEmpty()) return;

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
            .filter(info -> AmenityType.isValid(info.name()))
            .filter(info -> info.count() > 0)
            .collect(Collectors.toMap(
                info -> AmenityType.valueOf(info.name().toUpperCase()),
                AmenityInfo::count,
                Integer::sum
            ));
    }

    private void updateOccupancyPolicy(Accommodation accommodation, AccommodationRequest.OccupancyPolicyInfo occupancyPolicyInfo) {
        if (occupancyPolicyInfo == null) {
            return;
        }

        OccupancyPolicy currentPolicy = accommodation.getOccupancyPolicy();
        if (currentPolicy == null) {
            OccupancyPolicy newPolicy = OccupancyPolicy.createOccupancyPolicy(occupancyPolicyInfo);
            OccupancyPolicy savedPolicy = occupancyPolicyRepository.save(newPolicy);
            accommodation.updateOccupancyPolicy(savedPolicy);
        }
    }

    private void updateAddress(Accommodation accommodation, AccommodationRequest.AddressInfo addressInfo) {
        if (addressInfo == null) {
            return;
        }

        Address currentAddress = accommodation.getAddress();

        if (currentAddress == null || currentAddress.isChanged(addressInfo)) {
            String addressStr = geocodingService.buildAddressString(addressInfo);
            GeocodeResult geocodeResult = geocodingService.getCoordinates(addressStr);
            Address newAddress = Address.createAddress(addressInfo, geocodeResult);
            Address savedAddress = addressRepository.save(newAddress);
            accommodation.updateAddress(savedAddress);
        }
    }

    private void updateAmenities(Accommodation accommodation, List<AmenityInfo> amenityInfos) {
        if (amenityInfos == null) {
            return;
        }

        accommodationAmenityRepository.deleteByAccommodationId(accommodation.getId());

        if (!amenityInfos.isEmpty()) {
            saveValidAmenities(amenityInfos, accommodation);
        }
    }

    private Accommodation findWithDetailsExceptHostById(Long accommodationId, Long memberId) {
        return accommodationRepository.findWithDetailsExceptHostById(accommodationId, memberId).orElseThrow(AccommodationNotFoundException::new);
    }

    private Accommodation findByIdAndMemberIdAndStatusNot(Long accommodationId, Long memberId) {
        return accommodationRepository.findByIdAndMemberIdAndStatusNot(accommodationId, memberId, AccommodationStatus.DELETED).orElseThrow(AccommodationNotFoundException::new);
    }

    private Accommodation findByIdAndStatus(Long accommodationId, AccommodationStatus status) {
        return accommodationRepository.findByIdAndStatus(accommodationId, status).orElseThrow(AccommodationNotFoundException::new);
    }

    private Accommodation findByIdAndMemberIdExceptDeleted(Long accommodationId, Long memberId) {
        return accommodationRepository.findByIdAndMemberIdAndStatusNot(accommodationId, memberId, AccommodationStatus.DELETED).orElseThrow(AccommodationNotFoundException::new);
    }
}
