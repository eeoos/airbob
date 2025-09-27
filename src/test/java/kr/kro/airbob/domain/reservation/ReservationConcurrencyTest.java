package kr.kro.airbob.domain.reservation;

import kr.kro.airbob.domain.accommodation.common.AccommodationType;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.AddressRepository;
import kr.kro.airbob.domain.accommodation.repository.OccupancyPolicyRepository;
import kr.kro.airbob.domain.member.Member;
import kr.kro.airbob.domain.member.MemberRepository;
import kr.kro.airbob.domain.member.common.MemberRole;
import kr.kro.airbob.domain.reservation.dto.ReservationRequestDto;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.repository.ReservedDateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public class ReservationConcurrencyTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private AccommodationRepository accommodationRepository;

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private OccupancyPolicyRepository occupancyPolicyRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservedDateRepository reservedDateRepository;

    @Autowired
    private RedissonClient redissonClient;

    private Long savedAccommodationId;

    // MySQL 컨테이너
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("testdb")
            .withUsername("user")
            .withPassword("pass");

    // Redis 컨테이너
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.2.0")
            .withExposedPorts(6379);

    // 동적으로 Spring 속성 등록
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // MySQL
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);

        // Flyway
        registry.add("spring.flyway.url", mysql::getJdbcUrl);
        registry.add("spring.flyway.user", mysql::getUsername);
        registry.add("spring.flyway.password", mysql::getPassword);

        // Redis
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @BeforeEach
    void setUp() {
        // 1. 10명의 회원 등록
        for (long i = 1; i <= 10; i++) {
            Member member = Member.builder()
                    .email("user" + i + "@test.com")
                    .password("hashed-password")
                    .nickname("유저" + i)
                    .role(MemberRole.MEMBER)
                    .thumbnailImageUrl("https://example.com/profile" + i + ".jpg")
                    .build();
            memberRepository.save(member);
        }

        // 2. 숙소에 필요한 Address, OccupancyPolicy 저장
        Address address = Address.builder()
                .country("대한민국")
                .city("서울특별시")
                .district("종로구")
                .street("세종대로")
                .detail("101호")
                .postalCode("1536")
                .latitude(37.5665)
                .longitude(126.9780)
                .build();
        addressRepository.save(address);

        OccupancyPolicy policy = OccupancyPolicy.builder()
                .maxOccupancy(4)
                .adultOccupancy(2)
                .childOccupancy(1)
                .infantOccupancy(1)
                .petOccupancy(0)
                .build();
        occupancyPolicyRepository.save(policy);

        // 3. 숙소 생성
        Member host = memberRepository.findAll().getFirst(); // 첫 번째 멤버를 호스트로
        Accommodation accommodation = Accommodation.builder()
                        .name("테스트 숙소")
                        .description("편안한 숙소입니다")
                        .basePrice(10000)
                        .thumbnailUrl("https://example.com/thumb.jpg")
                        .type(AccommodationType.APARTMENT)
                        .address(address)
                        .occupancyPolicy(policy)
                        .member(host)
                        .build();
        Accommodation savedAccommodation = accommodationRepository.save(accommodation);
        savedAccommodationId = savedAccommodation.getId();
    }

    @Test
    @DisplayName("동시에 같은 숙소/날짜로 예약 요청 시 한 명만 성공해야 한다")
    void concurrentReservation_shouldAllowOnlyOneSuccess() throws InterruptedException {
        Long accommodationId = accommodationRepository.findById(savedAccommodationId).get().getId();
        LocalDate checkIn = LocalDate.of(2025, 6, 20);
        LocalDate checkOut = LocalDate.of(2025, 6, 22);

        int threadCount = 200;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        List<Future<Boolean>> resultFutures = new ArrayList<>();

        for (long i = 1; i <= threadCount; i++) {
            final long memberId = i;
            resultFutures.add(executorService.submit(() -> {
                try {
                    ReservationRequestDto.CreateReservationDto dto =
                            ReservationRequestDto.CreateReservationDto.builder()
                                    .checkInDate(checkIn)
                                    .checkOutDate(checkOut)
                                    .message("동시성 테스트")
                                    .build();

                    boolean reserved = reservationService.preReserveDates(memberId, accommodationId, dto);

                    if (reserved) {
                        reservationService.createReservation(memberId, accommodationId, dto);
                        return true;
                    }

                    return false;

                } finally {
                    latch.countDown();
                }
            }));
        }

        latch.await();

        long successCount = resultFutures.stream().filter(future -> {
            try {
                return future.get(); // 성공한 예약인지
            } catch (Exception e) {
                return false;
            }
        }).count();

        // then: 단 한 명만 성공해야 함
        assertThat(successCount)
                .as("동시에 요청해도 성공한 예약은 한 건이어야 한다")
                .isEqualTo(1);

        assertThat(reservationRepository.count())
                .as("예약 테이블에는 단 하나의 예약만 존재해야 한다")
                .isEqualTo(1);
    }

}
