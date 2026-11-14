package com.example.shop.product;

/**
 * Listing filter (REQ-F-003 2). A blank keyword means "no name filter".
 *
 * 목록 필터 (REQ-F-003 2). 검색어가 비어 있으면 "이름 필터 없음"이다.
 */
public record ProductSearchCondition(Long categoryId, String keyword, ProductSort sort) {

    public static final int PAGE_SIZE = 12;

    /**
     * LIKE pattern with %, _ and the escape character escaped, or null when there is no keyword.
     *
     * %, _, 이스케이프 문자를 이스케이프한 LIKE 패턴. 검색어가 없으면 null.
     */
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
