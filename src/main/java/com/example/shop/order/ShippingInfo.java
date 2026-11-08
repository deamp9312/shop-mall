package com.example.shop.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Receiver and address text (REQ-F-006). Deliberately has no toString so that the address never reaches a log
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
