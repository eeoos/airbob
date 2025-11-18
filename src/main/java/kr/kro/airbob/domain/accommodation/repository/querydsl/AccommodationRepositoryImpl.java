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

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.support.PageableExecutionUtils;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
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
        LocalDateTime lastCreatedAt, AccommodationStatus status, Pageable pageable) {

        List<Accommodation> content = jpaQueryFactory
            .select(accommodation)
            .from(accommodation)
            .leftJoin(accommodation.address, address).fetchJoin()
            // .leftJoin(accommodationReviewSummary).on(accommodationReviewSummary.accommodation.id.eq(accommodation.id))
            .where(
                accommodation.member.id.eq(hostId),
                accommodation.status.ne(AccommodationStatus.DELETED),
                buildAccommodationStatusFilter(status),
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
    public Optional<Accommodation> findWithDetailsExceptHostAndDeletedById(Long accommodationId, Long hostId) {
        Accommodation result = jpaQueryFactory
            .selectFrom(accommodation)
            .leftJoin(accommodation.address, address).fetchJoin()
            .leftJoin(accommodation.occupancyPolicy, occupancyPolicy).fetchJoin()
            .where(
                accommodation.id.eq(accommodationId),
                accommodation.member.id.eq(hostId),
                accommodation.status.ne(AccommodationStatus.DELETED)
            )
            .fetchOne();

        return Optional.ofNullable(result);
    }

    @Override
    public Page<Accommodation> findForIndexing(Pageable pageable) {

        // 1. Content Query: N+1 방지를 위해 Member, Policy를 fetchJoin
        List<Accommodation> content = jpaQueryFactory
            .selectFrom(accommodation)
            .leftJoin(accommodation.member, member).fetchJoin()
            .leftJoin(accommodation.occupancyPolicy, occupancyPolicy).fetchJoin()
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            // ★ 경고: 이 쿼리는 Pageable의 'sort'를 동적으로 처리하지 않습니다.
            // (이전 @Query는 자동으로 처리해줬지만, QueryDSL은 수동 구현이 필요합니다)
            // (정렬이 꼭 필요하다면 기존 search() 메서드의 applySorting 로직을 참고해야 합니다)
            .fetch();

        // 2. Count Query: 전체 개수 조회 (Join 불필요)
        JPAQuery<Long> countQuery = jpaQueryFactory
            .select(accommodation.count())
            .from(accommodation);

        // 3. Spring Data 유틸리티로 Page 객체 생성
        // (countQuery::fetchOne은 content가 비어있을 때 등 필요할 때만 호출됨)
        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    private BooleanExpression buildAccommodationStatusFilter(AccommodationStatus status) {
        if (status == null) {
            return null;
        }

        return accommodation.status.eq(status);
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
