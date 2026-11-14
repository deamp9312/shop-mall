package com.example.shop.product;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Sample image files under static/images/products; image upload is out of scope (REQ-F-013).
 *
 * static/images/products 아래의 샘플 이미지 파일. 이미지 업로드는 범위 밖이다 (REQ-F-013).
 */
@Component
public class SampleImages {

    private static final String URL_PREFIX = "/images/products/";

    private final List<String> fileNames;

    public SampleImages() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:static/images/products/*");
            this.fileNames = Arrays.stream(resources).map(Resource::getFilename).filter(Objects::nonNull)
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list sample images", e);
        }
    }

    public List<String> fileNames() {
        return fileNames;
    }

    public boolean exists(String fileName) {
        return fileNames.contains(fileName);
    }

    public String urlOf(String fileName) {
        return URL_PREFIX + fileName;
    }

    public String fileNameOf(String imageUrl) {
        return imageUrl.startsWith(URL_PREFIX) ? imageUrl.substring(URL_PREFIX.length()) : imageUrl;
    }
}
