package com.example.shop.stock;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.ShopException;

import java.util.List;

/**
 * The Redis reservation found too little available stock for these products; nothing was decremented.
 *
 * Redis 선점에서 이 상품들의 가용 재고가 부족했다. 아무것도 차감하지 않았다.
 */
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
