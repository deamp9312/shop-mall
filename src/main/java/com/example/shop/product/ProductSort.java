package com.example.shop.product;

import org.springframework.data.domain.Sort;

/**
 * Listing order (REQ-F-003 2). The id tie-breaker keeps pages stable when timestamps or prices are equal.
 *
 * 목록 정렬 (REQ-F-003 2). 시각이나 가격이 같을 때 id를 보조 기준으로 써서 페이지가 흔들리지 않게 한다.
 */
public enum ProductSort {
    LATEST(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),
    PRICE_ASC(Sort.by(Sort.Order.asc("price"), Sort.Order.asc("id"))),
    PRICE_DESC(Sort.by(Sort.Order.desc("price"), Sort.Order.asc("id")));

    private final Sort sort;

    ProductSort(Sort sort) {
        this.sort = sort;
    }

    public Sort sort() {
        return sort;
    }
}
