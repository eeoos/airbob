package kr.kro.airbob.domain.accommodation.repository.querydsl;

import static kr.kro.airbob.domain.accommodation.entity.QAccommodation.*;
import static kr.kro.airbob.domain.accommodation.entity.QAccommodationAmenity.*;
import static kr.kro.airbob.domain.accommodation.entity.QAddress.*;
import static kr.kro.airbob.domain.accommodation.entity.QAmenity.*;
import static kr.kro.airbob.domain.accommodation.entity.QOccupancyPolicy.*;
import static kr.kro.airbob.domain.member.entity.QMember.*;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
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
import kr.kro.airbob.domain.accommodation.entity.AccommodationAmenity;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class AccommodationAmenityRepositoryImpl implements AccommodationAmenityRepositoryCustom {

    private final JPAQueryFactory jpaQueryFactory;

    @Override
    public List<AccommodationAmenity> findAllByAccommodationIdIn(Collection<Long> accommodationIds) {
        if (accommodationIds == null || accommodationIds.isEmpty()) {
            return Collections.emptyList();
        }

        return jpaQueryFactory
            .selectFrom(accommodationAmenity)
            .join(accommodationAmenity.amenity, amenity).fetchJoin()
            .where(accommodationAmenity.accommodation.id.in(accommodationIds))
            .fetch();
    }
}
