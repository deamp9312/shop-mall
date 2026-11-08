package com.example.shop.product;

import org.springframework.data.domain.Sort;

/** Listing order (REQ-F-003 2). The id tie-breaker keeps pages stable when timestamps or prices are equal. */
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
