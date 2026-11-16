package com.example.shop.product;

import org.springframework.data.domain.Sort;

/** 목록 정렬 순서(REQ-F-003 2). 시각이나 가격이 같을 때 id를 보조 정렬 키로 써서 페이지를 안정적으로 유지한다. */
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
