package kr.kro.airbob.domain.payment.entity;

// 결제 거래 원장(PaymentTransaction)의 이벤트 종류. PG 상태(PaymentStatus)와 별개로 "무슨 사건이었나"를 표현.
public enum PaymentTransactionType {
	CONFIRM,         // 결제 승인 성공
	FAIL,            // 결제 승인 실패
	VIRTUAL_ISSUED,  // 가상계좌 발급
	CANCEL,          // 전체 취소
	PARTIAL_CANCEL   // 부분 취소
}
