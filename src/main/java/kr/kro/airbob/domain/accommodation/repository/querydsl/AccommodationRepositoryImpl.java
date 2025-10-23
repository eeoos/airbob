package kr.kro.airbob.domain.accommodation.repository.querydsl;

import static kr.kro.airbob.domain.accommodation.entity.QAccommodation.*;
import static kr.kro.airbob.domain.accommodation.entity.QOccupancyPolicy.*;

import java.util.Optional;
import java.util.UUID;

import com.querydsl.jpa.impl.JPAQueryFactory;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.entity.QAddress;
import kr.kro.airbob.domain.member.entity.QMember;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class AccommodationRepositoryImpl implements AccommodationRepositoryCustom {
    private final JPAQueryFactory jpaQueryFactory;

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

    @Override
    public Optional<Accommodation> findWithDetailsByAccommodationIdAndStatus(Long accommodationId, AccommodationStatus status) {
        Accommodation result = jpaQueryFactory.
            selectFrom(accommodation)
            .leftJoin(accommodation.address, QAddress.address).fetchJoin()
            .leftJoin(accommodation.occupancyPolicy, occupancyPolicy).fetchJoin()
            .leftJoin(accommodation.member, QMember.member).fetchJoin()
            .where(accommodation.id.eq(accommodationId)
                .and(accommodation.status.eq(status)))
            .fetchOne();

        return Optional.ofNullable(result);
    }


}
