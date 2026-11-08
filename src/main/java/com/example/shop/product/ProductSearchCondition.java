package com.example.shop.product;

/** Listing filter (REQ-F-003 2). A blank keyword means "no name filter". */
public record ProductSearchCondition(Long categoryId, String keyword, ProductSort sort) {

    public static final int PAGE_SIZE = 12;

    /** LIKE pattern with %, _ and the escape character escaped, or null when there is no keyword. */
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
