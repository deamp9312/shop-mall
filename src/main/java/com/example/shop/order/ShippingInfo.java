package com.example.shop.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * 수령인과 주소 텍스트 (REQ-F-006). 주소가 절대 로그에 남지 않도록 의도적으로 toString을 두지 않는다
 * (REQ-SE-003).
 */
@Embeddable
public class ShippingInfo {

    @Column(name = "receiver_name", nullable = false, length = 50)
    private String receiverName;

    @Column(name = "receiver_phone", nullable = false, length = 20)
    private String receiverPhone;

    @Column(name = "shipping_address", nullable = false)
    private String address;

    protected ShippingInfo() {
    }

    public ShippingInfo(String receiverName, String receiverPhone, String address) {
        this.receiverName = receiverName;
        this.receiverPhone = receiverPhone;
        this.address = address;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public String getReceiverPhone() {
        return receiverPhone;
    }

    public String getAddress() {
        return address;
    }
}
