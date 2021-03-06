package com.sleazyweasel.s3db.storage;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.google.common.io.CharStreams;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import lombok.Value;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;

public class S3Store {
    private final AmazonS3 amazonS3;
    private final String bucketName;
    private final boolean enableEncryption;
    private final Gson gson;
    private final ForkJoinPool forkJoinPool = new ForkJoinPool(10);
    private final Executor executor = Executors.newFixedThreadPool(10);
    private final JsonParser jsonParser = new JsonParser();

    public S3Store(AmazonS3 amazonS3, String bucketName, boolean enableEncryption, Gson gson) {
        this.amazonS3 = amazonS3;
        this.bucketName = bucketName;
        this.enableEncryption = enableEncryption;
        this.gson = gson;
    }

    //todo: turn into Optional<String>
    public String getObject(String collection, String id) {
        S3Object object;
        try {
            object = amazonS3.getObject(new GetObjectRequest(bucketName, collection + "/" + id));
        } catch (AmazonS3Exception e) {
            if (404 == e.getStatusCode()) {
                return null;
            }
            throw e;
        }
        return getAsString(object);
    }

    private String getAsString(S3Object object) {
        try {
            return CharStreams.toString(new InputStreamReader(object.getObjectContent()));
        } catch (IOException e) {
            throw Throwables.propagate(e);
        }
    }

    public void putObject(String collection, String id, String contentType, String content) {
        String previousContent = getObject(collection, id);
        rawPutObject(collection, id, contentType, content);
        updateIndexes(collection, id, content, previousContent);
        //todo: consider fetching & validating the content is the same, to make sure the eventual consistency has resolved
    }

    private void rawPutObject(String collection, String id, String contentType, String content) {
        byte[] bytes = content.getBytes(Charsets.UTF_8);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(bytes.length);
        metadata.setContentType(contentType);
        if (enableEncryption) {
            metadata.setServerSideEncryption(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION);
        }
        amazonS3.putObject(new PutObjectRequest(bucketName, collection + "/" + id, new ByteArrayInputStream(bytes), metadata));
    }

    /*
       index entry structure:
       ::db::/indexData/<collection>/<field>/<fieldValue>/<objectId>    [no payload stored here]
     */

    private void updateIndexes(String collection, String id, String content, String previousContent) {
        executor.execute(() -> {
            Indexes indexes = getIndexes(collection);
            JsonElement element = jsonParser.parse(content);
            JsonElement previousElement = previousContent == null ? JsonNull.INSTANCE : jsonParser.parse(previousContent);
            forkJoinPool.execute(() -> indexes.getFields().stream().parallel().forEach(field -> {
                JsonElement previousValue = nullSafeFieldValue(previousElement, field);
                JsonElement newValue = nullSafeFieldValue(element, field);
                if (!previousValue.equals(newValue)) {
                    if (!previousValue.isJsonNull()) {
                        amazonS3.deleteObject(bucketName, "::db::/indexData/" + collection + "/" + field + "/" + previousValue.getAsString() + "/" + id);
                    }
                    writeIndexEntry(collection, id, field, newValue);
                }
            }));
        });
    }

    private void writeIndexEntry(String collection, String id, String field, JsonElement fieldValue) {
        if (!fieldValue.isJsonNull()) {
            rawPutObject("::db::/indexData", collection + "/" + field + "/" + fieldValue.getAsString() + "/" + id, "text/plain", "");
        }
    }

    private JsonElement nullSafeFieldValue(JsonElement previousElement, String field) {
        JsonElement previousValue = previousElement.isJsonNull() ? previousElement : previousElement.getAsJsonObject().get(field);
        previousValue = previousValue == null ? JsonNull.INSTANCE : previousValue;
        return previousValue;
    }

    private void updateAllIndexEntries(String collection, String field) {
        ObjectListing objectListing = amazonS3.listObjects(bucketName, collection + "/");
        while(true) {
            List<S3ObjectSummary> summaries = objectListing.getObjectSummaries();
            forkJoinPool.execute(() -> summaries.parallelStream()
                    .map(summary -> amazonS3.getObject(bucketName, summary.getKey()))
                    .forEach(object -> {
                        String key = object.getKey();
                        String id = key.substring(key.lastIndexOf("/") + 1);
                        JsonElement element = jsonParser.parse(getAsString(object));
                        JsonElement fieldValue = nullSafeFieldValue(element, field);
                        writeIndexEntry(collection, id, field, fieldValue);
                    }));
            if (objectListing.isTruncated()) {
                objectListing = amazonS3.listNextBatchOfObjects(objectListing);
            }
            else {
                break;
            }
        }
    }

    public void ensureIndex(String collection, String field) {
        try {
            Indexes indexes = getIndexes(collection);
            if (indexes.contains(field)) {
                return;
            }
            indexes = indexes.add(field);
            saveIndexes(collection, indexes);
            updateAllIndexEntries(collection, field);
        } catch (RuntimeException e) {
            e.printStackTrace();
            throw e;
        }
    }

    private Indexes getIndexes(String collection) {
        String object = getObject("::db::/indexes", collection);
        if (object == null) {
            return new Indexes(Collections.emptySet());
        }
        return gson.fromJson(object, Indexes.class);
    }

    private void saveIndexes(String collection, Indexes indexes) {
        rawPutObject("::db::/indexes", collection, "application/json", gson.toJson(indexes));
    }

    @Value
    public static class Indexes {
        Set<String> fields;

        public boolean contains(String field) {
            return fields.contains(field);
        }

        public Indexes add(String field) {
            Set<String> updatedFields = new HashSet<>(fields);
            updatedFields.add(field);
            return new Indexes(updatedFields);
        }

        public Set<String> getFields() {
            return Collections.unmodifiableSet(fields);
        }

    }

}
