package kr.kro.airbob.domain.reservation;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.dto.ReservationRequestDto;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservedDate;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.repository.ReservedDateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ReservationServiceTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private AccommodationRepository accommodationRepository;

    @Mock
    private ReservedDateRepository reservedDateRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @InjectMocks
    private ReservationService reservationService;

    @Test
    @DisplayName("예약하려는 숙소, 날짜에 lock을 획득하지 못하면 임시 예약이 실패해야 한다.")
    void preReserveDates_lockAcquisitionFail() {
        // given
        Long userId = 1L;
        Long accommodationId = 1L;
        LocalDate checkIn = LocalDate.of(2025, 6, 20);
        LocalDate checkOut = LocalDate.of(2025, 6, 23);

        ReservationRequestDto.CreateReservationDto dto =
                ReservationRequestDto.CreateReservationDto.builder()
                        .checkInDate(checkIn)
                        .checkOutDate(checkOut)
                        .build();

        RBucket lockBucket = mock(RBucket.class);

        // 첫번째 락은 잡히고, 두번째 락 잡기 실패 시뮬레이션
        // redissonClient.getBucket() 호출 시 항상 같은 mock 리턴
        given(redissonClient.getBucket(anyString())).willReturn(lockBucket);

        // 첫 번째 호출 true, 두 번째 호출 false (락 획득 실패)
        given(lockBucket.setIfAbsent(anyString(), any(Duration.class)))
                .willReturn(true)
                .willReturn(false);

        // when
        boolean result = reservationService.preReserveDates(userId, accommodationId, dto);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("예약하려는 날짜에 예약이 되어있으면 임시 예약이 실패해야 한다.")
    void preReserveDates_alreadyReservedDatesExist() {
        // given
        Long userId = 1L;
        Long accommodationId = 1L;
        LocalDate checkIn = LocalDate.of(2025, 6, 20);
        LocalDate checkOut = LocalDate.of(2025, 6, 23);

        ReservationRequestDto.CreateReservationDto dto =
                ReservationRequestDto.CreateReservationDto.builder()
                        .checkInDate(checkIn)
                        .checkOutDate(checkOut)
                        .build();

        RBucket lockBucket = mock(RBucket.class);

        given(redissonClient.getBucket(anyString())).willReturn(lockBucket);
        given(lockBucket.setIfAbsent(anyString(), any(Duration.class)))
                .willReturn(true)
                .willReturn(true);

        // 이미 예약된 날짜가 존재하는 상황
        given(reservedDateRepository.findReservedDates(accommodationId, checkIn, checkOut))
                .willReturn(List.of(mock(ReservedDate.class)));

        // when
        boolean result = reservationService.preReserveDates(userId, accommodationId, dto);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("예약하려는 숙소와 날짜에 lock이 없고 예약되어 있지 않으면 임시 예약에 성공해야 한다.")
    void preReserveDates_success() {
        // given
        Long userId = 1L;
        Long accommodationId = 1L;
        LocalDate checkIn = LocalDate.of(2025, 6, 20);
        LocalDate checkOut = LocalDate.of(2025, 6, 23);

        ReservationRequestDto.CreateReservationDto dto =
                ReservationRequestDto.CreateReservationDto.builder()
                        .checkInDate(checkIn)
                        .checkOutDate(checkOut)
                        .build();

        Accommodation accommodation = mock(Accommodation.class);
        RBucket lockBucket = mock(RBucket.class);

        given(accommodationRepository.findById(accommodationId)).willReturn(Optional.of(accommodation));

        given(redissonClient.getBucket(anyString())).willReturn(lockBucket);
        given(lockBucket.setIfAbsent(anyString(), any(Duration.class)))
                .willReturn(true)
                .willReturn(true);

        // DB에 예약된 날짜 없음
        given(reservedDateRepository.findReservedDates(accommodationId, checkIn, checkOut)).willReturn(Collections.emptyList());

        // when
        boolean result = reservationService.preReserveDates(userId, accommodationId, dto);

        // then
        assertThat(result).isTrue();
    }

    @Test
    void createReservation_shouldSaveReservation_andUpdateReservedDatesToCompleted() {
        // given
        Long memberId = 1L;
        Long accommodationId = 1L;
        Long reservationId = 1L;

        LocalDate checkIn = LocalDate.of(2025, 6, 20);
        LocalDate checkOut = LocalDate.of(2025, 6, 22); // 2박

        ReservationRequestDto.CreateReservationDto dto = ReservationRequestDto.CreateReservationDto.builder()
                .checkInDate(checkIn)
                .checkOutDate(checkOut)
                .message("테스트 예약")
                .build();

        Member guest = mock(Member.class);
        Accommodation accommodation = mock(Accommodation.class);
        Reservation reservation = mock(Reservation.class);

        // stub basePrice와 reservation ID
        given(accommodation.getBasePrice()).willReturn(10000);
        given(reservation.getId()).willReturn(reservationId);

        // repository stub 설정
        given(memberRepository.findById(memberId)).willReturn(Optional.of(guest));
        given(accommodationRepository.findById(accommodationId)).willReturn(Optional.of(accommodation));
        given(reservationRepository.save(any())).willReturn(reservation);

        // 예약된 날짜(PENDING) 상태 미리 존재
        ReservedDate reservedDate1 = spy(ReservedDate.builder()
                .reservedAt(checkIn)
                .status(ReservationStatus.PENDING)
                .accommodation(accommodation)
                .build());

        ReservedDate reservedDate2 = spy(ReservedDate.builder()
                .reservedAt(checkIn.plusDays(1))
                .status(ReservationStatus.PENDING)
                .accommodation(accommodation)
                .build());

        given(reservedDateRepository.findReservedDates(accommodationId, checkIn, checkOut))
                .willReturn(List.of(reservedDate1, reservedDate2));

        // when
        Long result = reservationService.createReservation(memberId, accommodationId, dto);

        // then
        assertThat(result).isEqualTo(reservationId);
        assertThat(reservedDate1.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservedDate2.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }


    @Test
    @DisplayName("존재하지 않는 유저가 예약하려고 하면 예외가 발생해야 한다.")
    void createReservation_shouldThrowException_whenMemberNotFound()  {
        // given
        Long memberId = 1L;
        Long accommodationId = 1L;
        ReservationRequestDto.CreateReservationDto request = mock(ReservationRequestDto.CreateReservationDto.class);

        given(memberRepository.findById(memberId))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reservationService.createReservation(memberId, accommodationId, request))
                .isInstanceOf(MemberNotFoundException.class)
                .hasMessage("존재하지 않는 사용자입니다.");
    }

    @Test
    @DisplayName("존재하지 않는 숙소를 예약하려고 하면 예외가 발생해야 한다.")
    void createReservation_shouldThrowException_whenAccommodationNotFound()  {
        // given
        Long memberId = 1L;
        Long accommodationId = 1L;
        ReservationRequestDto.CreateReservationDto request = mock(ReservationRequestDto.CreateReservationDto.class);

        Member member = mock(Member.class);

        given(memberRepository.findById(memberId))
                .willReturn(Optional.of(member));

        given(accommodationRepository.findById(accommodationId))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reservationService.createReservation(memberId, accommodationId, request))
                .isInstanceOf(AccommodationNotFoundException.class)
                .hasMessage("존재하지 않는 숙소입니다.");
    }



}
