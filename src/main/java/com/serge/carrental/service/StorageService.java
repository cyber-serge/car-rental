package com.serge.carrental.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StorageService {
    private static final Logger log = LoggerFactory.getLogger(StorageService.class);
    private final S3Client s3;
    @Value("${S3_BUCKET:car-rental}")
    private String bucket;

    public String uploadLicense(byte[] bytes, String originalFilename, String contentType) {
        ensureBucket();
        String key = "uploads/" + UUID.randomUUID() + "-" + sanitize(originalFilename);
        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();
        s3.putObject(req, RequestBody.fromBytes(bytes));
        log.debug("storage.uploadLicense bucket={} key={}", bucket, key);
        return "s3://" + bucket + "/" + key;
    }

    private String sanitize(String name) {
        if (name == null) name = "license.jpg";
        return URLEncoder.encode(name, StandardCharsets.UTF_8);
    }

    private void ensureBucket() {
        try {
            log.trace("storage.ensureBucket check bucket={}", bucket);
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            log.info("storage.ensureBucket.create bucket={}", bucket);
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (Exception ignored) {
            log.warn("storage.ensureBucket.ignored_exception bucket={} msg={}", bucket, ignored.getMessage());
        }
    }
}
