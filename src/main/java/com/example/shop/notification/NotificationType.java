package com.example.shop.notification;

/**
 * Payment result notices (REQ-F-009 4). The message is stored instead of sending mail or SMS.
 *
 * 결제 결과 알림 (REQ-F-009 4). 메일이나 SMS를 보내는 대신 메시지를 저장한다.
 */
public enum NotificationType {
    PAYMENT_COMPLETED("결제가 완료되었습니다."),
    PAYMENT_FAILED("결제에 실패했습니다. 주문은 결제 실패로 종료되었습니다."),
    PAYMENT_CANCELLED("결제가 취소되었습니다.");

    private final String message;

    NotificationType(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
