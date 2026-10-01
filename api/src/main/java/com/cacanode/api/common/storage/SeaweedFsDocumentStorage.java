package com.cacanode.api.common.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.cacanode.api.common.exception.custom.InternalServerErrorException;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
@RequiredArgsConstructor
public class SeaweedFsDocumentStorage implements DocumentStorage {

    private final S3Client seaweedFsS3Client;
    private final SeaweedFsProperties properties;
    private final AtomicBoolean bucketReady = new AtomicBoolean(false);

    @Override
    public void store(String key, MultipartFile file) {
        try {
            store(key, file.getBytes(), file.getContentType());
        } catch (IOException e) {
            throw new InternalServerErrorException("Unable to read uploaded document");
        }
    }

    @Override
    public void store(String key, byte[] content, String contentType) {
        try {
            ensureBucketExists();
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .contentType(contentType)
                    .contentLength((long) content.length)
                    .build();
            seaweedFsS3Client.putObject(request, RequestBody.fromBytes(content));
        } catch (RuntimeException e) {
            if (e instanceof InternalServerErrorException internal) {
                throw internal;
            }
            throw new InternalServerErrorException("Unable to store uploaded document", e);
        }
    }

    @Override
    public void store(String key,InputStream content,long contentLength,String contentType) {
        try {
            ensureBucketExists();PutObjectRequest request=PutObjectRequest.builder().bucket(properties.bucket())
                    .key(key).contentType(contentType).contentLength(contentLength).build();
            seaweedFsS3Client.putObject(request,RequestBody.fromInputStream(content,contentLength));
        } catch(RuntimeException exception) {
            throw new InternalServerErrorException("Unable to stream stored document",exception);
        }
    }

    @Override
    public StoredDocument load(String key) {
        try {
            ensureBucketExists();
            var response = seaweedFsS3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
            return new StoredDocument(response.asByteArray(), response.response().contentType());
        } catch (RuntimeException e) {
            throw new InternalServerErrorException("Unable to read document from object storage", e);
        }
    }

    @Override
    public StoredDocument loadRange(String key,long startInclusive,long endInclusive) {
        if(startInclusive<0||endInclusive<startInclusive)throw new IllegalArgumentException("Invalid object range");
        try {ensureBucketExists();var response=seaweedFsS3Client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(properties.bucket()).key(key).range("bytes="+startInclusive+"-"+endInclusive).build());
            return new StoredDocument(response.asByteArray(),response.response().contentType());
        } catch(RuntimeException exception){throw new InternalServerErrorException("Unable to read stored object range",exception);}
    }

    @Override
    public StoredObjectMetadata metadata(String key) {
        try {ensureBucketExists();var value=seaweedFsS3Client.headObject(HeadObjectRequest.builder()
                .bucket(properties.bucket()).key(key).build());return new StoredObjectMetadata(value.contentLength(),value.contentType(),value.eTag());}
        catch(RuntimeException exception){throw new InternalServerErrorException("Unable to read stored object metadata",exception);}
    }

    @Override
    public boolean exists(String key) {
        try {metadata(key);return true;}catch(InternalServerErrorException exception){Throwable cause=exception.getCause();
            if(cause instanceof NoSuchKeyException)return false;if(cause instanceof S3Exception s3&&s3.statusCode()==404)return false;throw exception;}
    }

    @Override
    public void delete(String key) {
        try {
            seaweedFsS3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
        } catch (RuntimeException e) {
            throw new InternalServerErrorException("Unable to delete stored document", e);
        }
    }

    private void ensureBucketExists() {
        if (bucketReady.get()) {
            return;
        }

        synchronized (bucketReady) {
            if (bucketReady.get()) {
                return;
            }

            try {
                seaweedFsS3Client.headBucket(HeadBucketRequest.builder()
                        .bucket(properties.bucket())
                        .build());
            } catch (S3Exception e) {
                if (e.statusCode() != 404) {
                    throw e;
                }
                seaweedFsS3Client.createBucket(CreateBucketRequest.builder()
                        .bucket(properties.bucket())
                        .build());
            }

            bucketReady.set(true);
        }
    }
}
