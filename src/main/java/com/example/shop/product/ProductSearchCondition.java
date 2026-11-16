package com.example.shop.product;

/** 목록 필터(REQ-F-003 2). 빈 keyword는 "상품명 필터 없음"을 뜻한다. */
public record ProductSearchCondition(Long categoryId, String keyword, ProductSort sort) {

    public static final int PAGE_SIZE = 12;

    /** %, _, 이스케이프 문자를 이스케이프한 LIKE 패턴. keyword가 없으면 null이다. */
    String likePattern() {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String escape = String.valueOf(ProductRepository.LIKE_ESCAPE);
        String escaped = keyword.trim()
                .replace(escape, escape + escape)
                .replace("%", escape + "%")
                .replace("_", escape + "_");
        return "%" + escaped + "%";
    }
}
