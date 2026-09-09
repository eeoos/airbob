package kr.kro.airbob.domain.wishlist.api;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import jakarta.servlet.http.Cookie;
import kr.kro.airbob.domain.auth.filter.SessionAuthFilter;
import kr.kro.airbob.domain.auth.resolver.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.domain.wishlist.dto.WishlistResponse;
import kr.kro.airbob.domain.wishlist.service.WishlistService;

@ExtendWith(MockitoExtension.class)
class WishlistMembershipApiTest {

	private static final String PATH = "/api/v1/members/wishlists/membership";

	@Mock private WishlistService service;
	@Mock private RedisTemplate<String, Object> redisTemplate;
	@Mock private ValueOperations<String, Object> valueOperations;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		ObjectMapper objectMapper = new ObjectMapper()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		mockMvc = MockMvcBuilders.standaloneSetup(new WishlistController(service))
			.setCustomArgumentResolvers(new CurrentMemberIdArgumentResolver())
			.setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
			.addFilters(new SessionAuthFilter(redisTemplate, objectMapper))
			.build();
	}

	@Test
	@DisplayName("찜 상태 조회는 익명 요청을 거부한다")
	void requiresSession() throws Exception {
		mockMvc.perform(get(PATH).param("accommodationId", "31"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("M004"));
		then(service).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("요청한 memberId 대신 세션 소유자로 찜 상태를 조회한다")
	void usesSessionOwnerAndReturnsTargetSnapshot() throws Exception {
		authenticate();
		given(service.findMembership(1L, 31L, 42L))
			.willReturn(new WishlistResponse.Membership(true, false, true));
		mockMvc.perform(get(PATH).cookie(new Cookie("SESSION_ID", "wishlist-test-session"))
				.param("accommodationId", "31").param("wishlistId", "42").param("memberId", "999"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.is_in_any_wishlist").value(true))
			.andExpect(jsonPath("$.data.target_wishlist_contains").value(false))
			.andExpect(jsonPath("$.data.target_wishlist_found").value(true));
		then(service).should().findMembership(1L, 31L, 42L);
		then(service).shouldHaveNoMoreInteractions();
	}

	@Test
	@DisplayName("대상 위시리스트를 생략하면 전체 저장 상태와 명시적인 null을 반환한다")
	void acceptsOptionalTargetWishlist() throws Exception {
		authenticate();
		given(service.findMembership(1L, 31L, null))
			.willReturn(new WishlistResponse.Membership(true, null, false));
		mockMvc.perform(get(PATH).cookie(new Cookie("SESSION_ID", "wishlist-test-session"))
				.param("accommodationId", "31"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.is_in_any_wishlist").value(true))
			.andExpect(jsonPath("$.data.target_wishlist_contains").value(nullValue()))
			.andExpect(jsonPath("$.data.target_wishlist_found").value(false));
		then(service).should().findMembership(1L, 31L, null);
	}

	private void authenticate() {
		given(redisTemplate.hasKey("MEMBER_SESSION_ACTIVE:1")).willReturn(true);
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.get("SESSION:wishlist-test-session")).willReturn(1L);
	}
}
