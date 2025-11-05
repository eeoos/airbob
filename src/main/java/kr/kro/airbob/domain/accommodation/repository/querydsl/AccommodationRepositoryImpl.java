package kr.kro.airbob.domain.accommodation.repository.querydsl;

import static kr.kro.airbob.domain.accommodation.entity.QAccommodation.*;
import static kr.kro.airbob.domain.accommodation.entity.QAddress.*;
import static kr.kro.airbob.domain.accommodation.entity.QOccupancyPolicy.*;
import static kr.kro.airbob.domain.member.entity.QMember.*;
import static kr.kro.airbob.domain.review.entity.QAccommodationReviewSummary.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

import com.querydsl.core.types.dsl.BooleanExpression;
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
            .leftJoin(accommodation.address, address).fetchJoin()
            .leftJoin(accommodation.occupancyPolicy, occupancyPolicy).fetchJoin()
            .leftJoin(accommodation.member, member).fetchJoin()
            .where(accommodation.accommodationUid.eq(accommodationUid))
            .fetchOne();

        return Optional.ofNullable(result);
    }

    @Override
    public Optional<Accommodation> findWithDetailsByAccommodationIdAndStatus(Long accommodationId, AccommodationStatus status) {
        Accommodation result = jpaQueryFactory.
            selectFrom(accommodation)
            .leftJoin(accommodation.address, address).fetchJoin()
            .leftJoin(accommodation.occupancyPolicy, occupancyPolicy).fetchJoin()
            .leftJoin(accommodation.member, member).fetchJoin()
            .where(accommodation.id.eq(accommodationId)
                .and(accommodation.status.eq(status)))
            .fetchOne();

        return Optional.ofNullable(result);
    }

    @Override
    public Slice<Accommodation> findMyAccommodationsByHostIdWithCursor(Long hostId, Long lastId,
        LocalDateTime lastCreatedAt, Pageable pageable) {

        List<Accommodation> content = jpaQueryFactory
            .select(accommodation)
            .from(accommodation)
            .leftJoin(accommodation.address, address).fetchJoin()
            // .leftJoin(accommodationReviewSummary).on(accommodationReviewSummary.accommodation.id.eq(accommodation.id))
            .where(
                accommodation.member.id.eq(hostId),
                accommodation.status.ne(AccommodationStatus.DELETED),
                cursorCondition(lastId, lastCreatedAt)
            )
            .orderBy(accommodation.createdAt.desc(), accommodation.id.desc())
            .limit(pageable.getPageSize() + 1)
            .fetch();

        boolean hasNext = content.size() > pageable.getPageSize();
        if (hasNext) {
            content.remove(pageable.getPageSize());
        }

        return new SliceImpl<>(content, pageable, hasNext);
    }

    @Override
    public Optional<Accommodation> findWithDetailsByIdAndHostId(Long accommodationId, Long hostId) {
        Accommodation result = jpaQueryFactory
            .selectFrom(accommodation)
            .leftJoin(accommodation.address, address).fetchJoin()
            .leftJoin(accommodation.occupancyPolicy, occupancyPolicy).fetchJoin()
            .leftJoin(accommodation.member, member).fetchJoin()
            .where(
                accommodation.id.eq(accommodationId),
                accommodation.member.id.eq(hostId)
            )
            .fetchOne();

        return Optional.ofNullable(result);
    }

    @Override
    public List<Accommodation> findWithAddressByIdAndStatusIn(List<Long> accommodationIds, AccommodationStatus status) {
        List<Accommodation> results = jpaQueryFactory.
            selectFrom(accommodation)
            .leftJoin(accommodation.address, address).fetchJoin()
            .where(
                accommodation.id.in(accommodationIds),
                accommodation.status.eq(status)
            )
            .fetch();

        return results;
    }

    @Override
    public Optional<Accommodation> findWithDetailsExceptHostById(Long accommodationId, Long hostId) {
        Accommodation result = jpaQueryFactory
            .selectFrom(accommodation)
            .leftJoin(accommodation.address, address).fetchJoin()
            .leftJoin(accommodation.occupancyPolicy, occupancyPolicy).fetchJoin()
            .where(
                accommodation.id.eq(accommodationId),
                accommodation.member.id.eq(hostId)
            )
            .fetchOne();

        return Optional.ofNullable(result);
    }

    private BooleanExpression cursorCondition(Long lastId, LocalDateTime lastCreatedAt) {
        if (lastId == null || lastCreatedAt == null) {
            return null;
        }

        return accommodation.createdAt.lt(lastCreatedAt)
            .or(accommodation.createdAt.eq(lastCreatedAt)
                .and(accommodation.id.lt(lastId)));
    }
}
