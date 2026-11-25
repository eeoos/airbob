package kr.kro.airbob.core.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedMethod;

import kr.kro.airbob.core.api.controller.v1.accommodation.AccommodationController;
import kr.kro.airbob.core.api.controller.v1.accommodation.AccommodationHostController;
import kr.kro.airbob.core.api.controller.v1.auth.AuthController;
import kr.kro.airbob.core.api.controller.v1.coupon.CouponController;
import kr.kro.airbob.core.api.controller.v1.payment.PaymentController;
import kr.kro.airbob.core.api.controller.v1.payment.PaymentOperationController;
import kr.kro.airbob.core.api.controller.v1.recentlyviewed.RecentlyViewedController;
import kr.kro.airbob.core.api.controller.v1.reservation.ReservationCommandController;
import kr.kro.airbob.core.api.controller.v1.reservation.ReservationGuestController;
import kr.kro.airbob.core.api.controller.v1.reservation.ReservationHostController;
import kr.kro.airbob.core.api.controller.v1.review.ReviewController;
import kr.kro.airbob.core.api.controller.v1.search.AccommodationSearchController;
import kr.kro.airbob.core.api.controller.v1.settlement.SettlementController;
import kr.kro.airbob.core.api.controller.v1.wishlist.WishlistAccommodationController;
import kr.kro.airbob.core.api.controller.v1.wishlist.WishlistController;

class CurrentMemberIdControllerContractTest {

	@ParameterizedTest(name = "{0}")
	@MethodSource("currentMemberHandlers")
	@DisplayName("currentMemberId를 컨트롤러 인자로 명시한다")
	void declaresCurrentMemberIdAsControllerArgument(HandlerContract contract) {
		List<Method> matchingMethods = Arrays.stream(contract.controllerType().getDeclaredMethods())
			.filter(candidate -> candidate.getName().equals(contract.methodName()))
			.toList();
		assertThat(matchingMethods).hasSize(1);
		Method method = matchingMethods.getFirst();

		List<MethodParameter> currentMemberParameters = Arrays.stream(new AnnotatedMethod(method).getMethodParameters())
			.filter(parameter -> parameter.hasParameterAnnotation(CurrentMemberId.class))
			.toList();

		assertThat(currentMemberParameters).hasSize(1);
		MethodParameter parameter = currentMemberParameters.getFirst();
		assertThat(parameter.getParameterType()).isEqualTo(Long.class);
		assertThat(parameter.getParameterAnnotation(CurrentMemberId.class).required())
			.isEqualTo(contract.required());
	}

	private static Stream<HandlerContract> currentMemberHandlers() {
		return Stream.of(
			required(AccommodationHostController.class, "registerAccommodation"),
			required(AccommodationHostController.class, "updateAccommodation"),
			required(AccommodationHostController.class, "publishAccommodation"),
			required(AccommodationHostController.class, "unpublishAccommodation"),
			required(AccommodationHostController.class, "deleteAccommodation"),
			required(AccommodationHostController.class, "uploadAccommodationImages"),
			required(AccommodationHostController.class, "deleteAccommodationImage"),
			optional(AccommodationController.class, "getAccommodation"),
			required(AccommodationHostController.class, "getHostAccommodations"),
			required(AccommodationHostController.class, "getHostAccommodationDetail"),
			required(AuthController.class, "getMyInfo"),
			required(CouponController.class, "issueCoupon"),
			required(CouponController.class, "getMyCoupons"),
			required(PaymentController.class, "confirmPayment"),
			required(PaymentController.class, "getPaymentByPaymentKey"),
			required(PaymentController.class, "getPaymentByOrderId"),
			required(PaymentOperationController.class, "get"),
			required(RecentlyViewedController.class, "addRecentlyViewed"),
			required(RecentlyViewedController.class, "removeRecentlyViewed"),
			required(RecentlyViewedController.class, "getRecentlyViewed"),
			required(ReservationCommandController.class, "createQuote"),
			required(ReservationCommandController.class, "checkout"),
			required(ReservationCommandController.class, "releaseHold"),
			required(ReservationCommandController.class, "beginPaymentAttempt"),
			required(ReservationCommandController.class, "cancelReservation"),
			required(ReservationGuestController.class, "getGuestReservationDetail"),
			required(ReservationGuestController.class, "getGuestReservations"),
			required(ReservationHostController.class, "getHostReservations"),
			required(ReservationHostController.class, "getHostReservationDetail"),
			required(ReviewController.class, "createReview"),
			required(ReviewController.class, "updateReview"),
			required(ReviewController.class, "deleteReview"),
			required(ReviewController.class, "uploadReviewImages"),
			required(ReviewController.class, "deleteReviewImage"),
			required(SettlementController.class, "getHostSettlements"),
			required(SettlementController.class, "getHostSummary"),
			required(SettlementController.class, "getHostSettlementDetail"),
			required(WishlistController.class, "createWishlist"),
			required(WishlistController.class, "updateWishlist"),
			required(WishlistController.class, "deleteWishlist"),
			required(WishlistController.class, "getWishlists"),
			required(WishlistAccommodationController.class, "createWishlistAccommodation"),
			required(WishlistAccommodationController.class, "updateWishlistAccommodation"),
			required(WishlistAccommodationController.class, "deleteWishlistAccommodation"),
			required(WishlistAccommodationController.class, "getWishlistAccommodations"),
			optional(AccommodationSearchController.class, "getSearchResults"));
	}

	private static HandlerContract required(Class<?> controllerType, String methodName) {
		return new HandlerContract(controllerType, methodName, true);
	}

	private static HandlerContract optional(Class<?> controllerType, String methodName) {
		return new HandlerContract(controllerType, methodName, false);
	}

	private record HandlerContract(Class<?> controllerType, String methodName, boolean required) {
		@Override
		public String toString() {
			return controllerType.getSimpleName() + "." + methodName;
		}
	}
}
