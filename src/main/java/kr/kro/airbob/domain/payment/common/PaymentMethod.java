package kr.kro.airbob.domain.payment.common;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum PaymentMethod {
	CARD("카드"),
	VIRTUAL_ACCOUNT("가상계좌"),
	EASY_PAY("간편결제"),
	MOBILE_PHONE("휴대폰"),
	BANK_TRANSFER("계좌이체"),
	CULTURE_GIFT_CARD("문화상품권"),
	BOOK_GIFT_CARD("도서문화상품권"),
	GAME_GIFT_CARD("게임문화상품권");

	private final String description;


}
