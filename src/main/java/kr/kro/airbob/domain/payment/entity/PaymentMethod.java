package kr.kro.airbob.domain.payment.entity;

import java.util.Arrays;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PaymentMethod {
	CARD("카드"),
	VIRTUAL_ACCOUNT("가상계좌"),
	EASY_PAY("간편결제"),
	MOBILE_PHONE("휴대폰"),
	BANK_TRANSFER("계좌이체"),
	CULTURE_GIFT_CARD("문화상품권"),
	BOOK_GIFT_CARD("도서문화상품권"),
	GAME_GIFT_CARD("게임문화상품권"),
	UNKNOWN("알수없음");

	private final String description;

	public static PaymentMethod fromDescription(String description) {
		return Arrays.stream(PaymentMethod.values())
			.filter(method -> method.getDescription().equals(description))
			.findFirst()
			.orElse(UNKNOWN);
	}

}
