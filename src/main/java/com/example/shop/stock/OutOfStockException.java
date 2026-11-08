package com.example.shop.stock;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.ShopException;

import java.util.List;

/** The Redis reservation found too little available stock for these products; nothing was decremented. */
public class OutOfStockException extends ShopException {

    private final List<Long> productIds;

    public OutOfStockException(List<Long> productIds) {
        super(ErrorCode.OUT_OF_STOCK);
        this.productIds = List.copyOf(productIds);
    }

    public List<Long> getProductIds() {
        return productIds;
    }
}
