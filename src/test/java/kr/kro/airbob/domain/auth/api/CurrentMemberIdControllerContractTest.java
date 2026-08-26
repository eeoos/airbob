package kr.kro.airbob.domain.auth.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import kr.kro.airbob.domain.accommodation.api.AccommodationAmenityDeleteBenchmarkController;
import kr.kro.airbob.domain.accommodation.api.AccommodationController;
import kr.kro.airbob.domain.accommodation.api.AccommodationDetailBenchmarkController;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.coupon.api.CouponBenchmarkController;
import kr.kro.airbob.domain.coupon.api.CouponController;
import kr.kro.airbob.domain.payment.api.PaymentController;
import kr.kro.airbob.domain.payment.api.PaymentOperationController;
import kr.kro.airbob.domain.recentlyViewed.api.RecentlyViewedBenchmarkController;
import kr.kro.airbob.domain.recentlyViewed.api.RecentlyViewedController;
import kr.kro.airbob.domain.reservation.api.ReservationController;
import kr.kro.airbob.domain.review.api.ReviewController;
import kr.kro.airbob.domain.settlement.api.SettlementController;
import kr.kro.airbob.domain.wishlist.api.WishlistBenchmarkController;
import kr.kro.airbob.domain.wishlist.api.WishlistController;
import kr.kro.airbob.domain.wishlist.api.WishlistDeleteBenchmarkController;
import kr.kro.airbob.search.api.AccommodationSearchController;

class CurrentMemberIdControllerContractTest {

	@ParameterizedTest(name = "{0}")
	@MethodSource("currentMemberHandlers")
	void currentMemberId를_컨트롤러_인자로_명시한다(HandlerContract contract) {
		List<Method> matchingMethods = Arrays.stream(contract.controllerType().getDeclaredMethods())
			.filter(candidate -> candidate.getName().equals(contract.methodName()))
			.toList();
		assertThat(matchingMethods).hasSize(1);
		Method method = matchingMethods.getFirst();

		List<Parameter> currentMemberParameters = Arrays.stream(method.getParameters())
			.filter(parameter -> parameter.isAnnotationPresent(CurrentMemberId.class))
			.toList();

		assertThat(currentMemberParameters).hasSize(1);
		Parameter parameter = currentMemberParameters.getFirst();
		assertThat(parameter.getType()).isEqualTo(Long.class);
		assertThat(parameter.getAnnotation(CurrentMemberId.class).required())
			.isEqualTo(contract.required());
	}

	private static Stream<HandlerContract> currentMemberHandlers() {
		return Stream.of(
			required(AccommodationAmenityDeleteBenchmarkController.class, "run"),
			optional(AccommodationDetailBenchmarkController.class, "findAccommodationBefore"),
			required(AccommodationController.class, "registerAccommodation"),
			required(AccommodationController.class, "updateAccommodation"),
			required(AccommodationController.class, "publishAccommodation"),
			required(AccommodationController.class, "unpublishAccommodation"),
			required(AccommodationController.class, "deleteAccommodation"),
			required(AccommodationController.class, "uploadAccommodationImages"),
			required(AccommodationController.class, "deleteAccommodationImage"),
			optional(AccommodationController.class, "getAccommodation"),
			required(AccommodationController.class, "getHostAccommodations"),
			required(AccommodationController.class, "getHostAccommodationDetail"),
			required(AuthController.class, "getMyInfo"),
			required(CouponBenchmarkController.class, "issueCouponWithLock"),
			required(CouponController.class, "issueCoupon"),
			required(CouponController.class, "findMyCoupons"),
			required(PaymentController.class, "confirmPayment"),
			required(PaymentController.class, "getPaymentByPaymentKey"),
			required(PaymentController.class, "getPaymentByOrderId"),
			required(PaymentOperationController.class, "find"),
			required(RecentlyViewedBenchmarkController.class, "replaceRecentlyViewedFixture"),
			required(RecentlyViewedBenchmarkController.class, "getRecentlyViewedBefore"),
			required(RecentlyViewedController.class, "addRecentlyViewed"),
			required(RecentlyViewedController.class, "removeRecentlyViewed"),
			required(RecentlyViewedController.class, "getRecentlyViewed"),
			required(ReservationController.class, "createQuote"),
			required(ReservationController.class, "checkout"),
			required(ReservationController.class, "releaseHold"),
			required(ReservationController.class, "beginPaymentAttempt"),
			required(ReservationController.class, "cancelReservation"),
			required(ReservationController.class, "getGuestReservationDetail"),
			required(ReservationController.class, "getGuestReservations"),
			required(ReservationController.class, "getHostReservations"),
			required(ReservationController.class, "getHostReservationDetail"),
			required(ReviewController.class, "createReview"),
			required(ReviewController.class, "updateReview"),
			required(ReviewController.class, "deleteReview"),
			required(ReviewController.class, "uploadReviewImages"),
			required(ReviewController.class, "deleteReviewImage"),
			required(SettlementController.class, "getHostSettlements"),
			required(SettlementController.class, "getHostSummary"),
			required(SettlementController.class, "getHostSettlementDetail"),
			required(WishlistBenchmarkController.class, "findWishlistsBefore"),
			required(WishlistController.class, "createWishlist"),
			required(WishlistController.class, "updateWishlist"),
			required(WishlistController.class, "deleteWishlist"),
			required(WishlistController.class, "findWishlists"),
			required(WishlistController.class, "createWishlistAccommodation"),
			required(WishlistController.class, "updateWishlistAccommodation"),
			required(WishlistController.class, "deleteWishlistAccommodation"),
			required(WishlistController.class, "findWishlistAccommodations"),
			required(WishlistDeleteBenchmarkController.class, "run"),
			optional(AccommodationSearchController.class, "searchAccommodations")
		);
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
