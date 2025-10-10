package kr.kro.airbob.domain.accommodation.repository.querydsl;

import static kr.kro.airbob.domain.accommodation.entity.QAccommodation.*;
import static kr.kro.airbob.domain.accommodation.entity.QAccommodationAmenity.*;
import static kr.kro.airbob.domain.accommodation.entity.QAmenity.*;
import static kr.kro.airbob.domain.accommodation.entity.QOccupancyPolicy.*;
import static kr.kro.airbob.domain.reservation.entity.QReservation.*;
import static kr.kro.airbob.domain.review.entity.QReview.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Pageable;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;

import kr.kro.airbob.domain.accommodation.common.AccommodationType;
import kr.kro.airbob.domain.accommodation.common.AmenityType;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest.AccommodationSearchConditionDto;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest.AmenityInfo;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse.AccommodationSearchResponseDto;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.Amenity;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.accommodation.entity.QAddress;
import kr.kro.airbob.domain.member.entity.QMember;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class AccommodationRepositoryImpl implements AccommodationRepositoryCustom {
    private final JPAQueryFactory jpaQueryFactory;

    @Override
    public List<AccommodationSearchResponseDto> searchByFilter(AccommodationSearchConditionDto condition, Pageable pageable) {
        BooleanBuilder builder = new BooleanBuilder();

        //동적 쿼리 생성
        addFilterClause(condition, builder);
        //필터링 조건으로 결과 생성
        List<Tuple> resultList = makeResultByFilter(builder, pageable);
        //이슈별 리뷰 정보 맵 생성
        Map<Long, Tuple> reviewMap = makeReviewMapByAccommodation();

        return new ArrayList<>(getSearchResults(resultList, reviewMap).values());
    }

    @Override
    public Optional<Accommodation> findWithDetailsByAccommodationUid(UUID accommodationUid) {
        Accommodation result = jpaQueryFactory.
            selectFrom(accommodation)
            .leftJoin(accommodation.address, QAddress.address).fetchJoin()
            .leftJoin(accommodation.occupancyPolicy, occupancyPolicy).fetchJoin()
            .leftJoin(accommodation.member, QMember.member).fetchJoin()
            .where(accommodation.accommodationUid.eq(accommodationUid))
            .fetchOne();

        return Optional.ofNullable(result);
    }

    private Map<Long, Tuple> makeReviewMapByAccommodation() {
        return jpaQueryFactory
                .select(review.accommodation.id, review.rating.avg(), review.count())
                .from(review)
                .groupBy(review.accommodation.id)
                .fetch()
                .stream()
                .collect(Collectors.toMap(
                        t -> t.get(review.accommodation.id),
                        t -> t
                ));
    }

    private List<Tuple> makeResultByFilter(BooleanBuilder builder, Pageable pageable) {
        return jpaQueryFactory
                .select(accommodation, occupancyPolicy, amenity, accommodationAmenity.count)
                .from(accommodation)
                .join(accommodation.occupancyPolicy, occupancyPolicy)
                .leftJoin(accommodationAmenity).on(accommodationAmenity.accommodation.eq(accommodation))
                .leftJoin(accommodationAmenity.amenity, amenity)
                .where(builder)
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
    }

    private Map<Long, AccommodationSearchResponseDto> getSearchResults(List<Tuple> results, Map<Long, Tuple> reviewMap) {
        Map<Long, AccommodationSearchResponseDto> dtoMap = new LinkedHashMap<>();

        for (Tuple tuple : results) {
            Accommodation accommodationValue = tuple.get(accommodation);
            OccupancyPolicy policyValue = tuple.get(occupancyPolicy);
            Amenity amenityValue = tuple.get(amenity);
            Integer count = tuple.get(accommodationAmenity.count);

            dtoMap.computeIfAbsent(accommodationValue.getId(), id -> {
                Tuple reviewTuple = reviewMap.get(id);
                Double avg = reviewTuple != null ? reviewTuple.get(review.rating.avg()) : 0.0;
                Long reviewCnt = reviewTuple != null ? reviewTuple.get(review.count()) : 0L;

                return AccommodationSearchResponseDto.builder()
                        .name(accommodationValue.getName())
                        .thumbnailUrl(accommodationValue.getThumbnailUrl())
                        .pricePerNight(accommodationValue.getBasePrice())
                        .maxOccupancy(policyValue.getMaxOccupancy())
                        .amenityInfos(new ArrayList<>())
                        .averageRating(avg)
                        .reviewCount(reviewCnt.intValue())
                        .build();
            });

            if (amenityValue != null) {
                dtoMap.get(accommodationValue.getId()).getAmenityInfos().add(
                        new AmenityInfo(amenityValue.getName().name(), count)
                );
            }
        }

        return dtoMap;
    }

    private void addFilterClause(AccommodationSearchConditionDto condition, BooleanBuilder builder) {
        if (condition.getCity() != null) {
            builder.and(accommodation.address.city.eq(condition.getCity()));
        }
        if (condition.getMinPrice() != null) {
            builder.and(accommodation.basePrice.goe(condition.getMinPrice()));
        }
        if (condition.getMaxPrice() != null) {
            builder.and(accommodation.basePrice.loe(condition.getMaxPrice()));
        }
        if (condition.getAccommodationTypes() != null && !condition.getAccommodationTypes().isEmpty()) {
            builder.and(accommodation.type.in(AccommodationType.valuesOf(condition.getAccommodationTypes())));
        }
        if (condition.getGuestCount() != null) {
            builder.and(occupancyPolicy.maxOccupancy.goe(condition.getGuestCount()));
        }
        if (condition.getAmenityTypes() != null && !condition.getAmenityTypes().isEmpty()) {
            builder.and(amenity.name.in(AmenityType.valuesOf(condition.getAmenityTypes())));
        }
        if (condition.getCheckIn() != null && condition.getCheckOut() != null) {
            builder.and(availableDateFilter(condition));
        }
    }

    private BooleanExpression availableDateFilter(AccommodationSearchConditionDto condition) {
        return jpaQueryFactory
            .select(reservation.id)
            .from(reservation)
            .where(
                reservation.accommodation.eq(accommodation),
                reservation.status.eq(ReservationStatus.CONFIRMED),
                reservation.checkIn.lt(condition.getCheckOut().atStartOfDay()),
                reservation.checkOut.gt(condition.getCheckIn().atStartOfDay())
            )
            .notExists();
    }
}
