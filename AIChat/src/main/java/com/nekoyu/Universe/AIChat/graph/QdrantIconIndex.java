package com.nekoyu.Universe.AIChat.graph;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.qdrant.client.VectorFactory.vector;
import static io.qdrant.client.VectorsFactory.namedVectors;

/** Caller owns the Qdrant client. This index never deletes/recreates an existing collection. */
public final class QdrantIconIndex implements IconIndex {
    private final QdrantClient client;
    private final String collection;
    private final IndexDescriptor descriptor;
    private final Duration timeout;

    public QdrantIconIndex(QdrantClient client, IndexDescriptor descriptor) {
        this(client, descriptor.defaultCollection(), descriptor, Duration.ofSeconds(10));
    }
    public QdrantIconIndex(QdrantClient client, String collection, IndexDescriptor descriptor, Duration timeout) {
        this.client = Objects.requireNonNull(client); this.descriptor = Objects.requireNonNull(descriptor);
        if (collection == null || !collection.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid collection name");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Positive timeout required");
        this.collection = collection; this.timeout = timeout;
    }
    public String collection() { return collection; }
    @Override public IndexDescriptor descriptor() { return descriptor; }
    @Override public void initialize() throws IOException {
        if (!await(client.collectionExistsAsync(collection, timeout))) {
            await(client.createCollectionAsync(collection, Map.of(descriptor.vectorName(), Collections.VectorParams.newBuilder()
                    .setSize(descriptor.space().dimension()).setDistance(Collections.Distance.Cosine).build()), timeout));
        }
        verify();
    }
    @Override public void verify() throws IOException {
        Collections.CollectionInfo info = await(client.getCollectionInfoAsync(collection, timeout));
        Map<String, Collections.VectorParams> parameters = info.getConfig().getParams().getVectorsConfig().getParamsMap().getMapMap();
        Collections.VectorParams params = parameters.get(descriptor.vectorName());
        if (parameters.size() != 1 || params == null || params.getSize() != descriptor.space().dimension()
                || params.getDistance() != Collections.Distance.Cosine) {
            throw new IOException("Incompatible icon index " + collection + "; model/catalog/preprocessing identity or vector configuration differs");
        }
    }
    @Override public void upsert(List<IndexedIcon> icons) throws IOException {
        List<Points.PointStruct> points = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (IndexedIcon entry : icons) {
            IconResource resource = entry.resource();
            if (!resource.library().equals(descriptor.library()) || !resource.libraryVersion().equals(descriptor.libraryVersion()) || !seen.add(resource.id()))
                throw new IOException("Inconsistent or duplicate indexed icon: " + resource.id());
            Points.PointStruct.Builder point = Points.PointStruct.newBuilder()
                    .setId(Common.PointId.newBuilder().setUuid(UUID.nameUUIDFromBytes(
                            (descriptor.fingerprint() + ":" + resource.id()).getBytes(StandardCharsets.UTF_8)).toString()))
                    .setVectors(namedVectors(Map.of(descriptor.vectorName(), vector(Vectors.floats(entry.vector(), descriptor.space().dimension())))));
            Map<String, String> payload = Map.of("fingerprint", descriptor.fingerprint(), "icon_id", resource.id(),
                    "name", resource.name(), "library", resource.library(), "library_version", resource.libraryVersion(),
                    "sha256", resource.sha256(), "svg", resource.svgPath().toString(), "preview", resource.previewPath().toString());
            payload.forEach((key, value) -> point.putPayload(key, JsonWithInt.Value.newBuilder().setStringValue(value).build()));
            point.putPayload("model", JsonWithInt.Value.newBuilder().setStringValue(descriptor.space().model()).build());
            point.putPayload("model_revision", JsonWithInt.Value.newBuilder().setStringValue(descriptor.space().revision()).build());
            point.putPayload("dimension", JsonWithInt.Value.newBuilder().setIntegerValue(descriptor.space().dimension()).build());
            point.putPayload("catalog_digest", JsonWithInt.Value.newBuilder().setStringValue(descriptor.catalogDigest()).build());
            point.putPayload("preprocessing_version", JsonWithInt.Value.newBuilder().setStringValue(descriptor.preprocessingVersion()).build());
            points.add(point.build());
        }
        if (points.isEmpty()) return;
        verify();
        // Wait for application, not just acknowledgement, before a subsequent search.
        await(client.upsertAsync(Points.UpsertPoints.newBuilder().setCollectionName(collection).setWait(true).addAllPoints(points).build(), timeout));
    }
    @Override public List<IconCandidate> search(double[] query, int limit) throws IOException {
        if (limit < 1) throw new IllegalArgumentException("Limit must be positive");
        List<Float> values = Vectors.floats(query, descriptor.space().dimension());
        verify();
        List<Points.ScoredPoint> result = await(client.searchAsync(Points.SearchPoints.newBuilder()
                .setCollectionName(collection).setVectorName(descriptor.vectorName()).addAllVector(values).setLimit(limit)
                .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true)).build(), timeout));
        List<IconCandidate> candidates = new ArrayList<>();
        for (Points.ScoredPoint point : result) {
            try {
                Map<String, JsonWithInt.Value> payload = point.getPayloadMap();
                if (!field(payload, "fingerprint").equals(descriptor.fingerprint())) throw new IOException("Icon payload belongs to another index identity");
                if (!field(payload, "model").equals(descriptor.space().model())
                        || !field(payload, "model_revision").equals(descriptor.space().revision())
                        || !field(payload, "catalog_digest").equals(descriptor.catalogDigest())
                        || !field(payload, "preprocessing_version").equals(descriptor.preprocessingVersion())
                        || !payload.containsKey("dimension") || !payload.get("dimension").hasIntegerValue()
                        || payload.get("dimension").getIntegerValue() != descriptor.space().dimension()) {
                    throw new IOException("Inconsistent icon payload metadata");
                }
                IconResource resource = new IconResource(field(payload, "icon_id"), field(payload, "name"), field(payload, "library"),
                        field(payload, "library_version"), field(payload, "sha256"), Path.of(field(payload, "svg")), Path.of(field(payload, "preview")));
                candidates.add(new IconCandidate(resource, point.getScore()));
            } catch (RuntimeException e) { throw new IOException("Malformed icon index payload", e); }
        }
        return List.copyOf(candidates);
    }
    private static String field(Map<String, JsonWithInt.Value> payload, String key) throws IOException {
        JsonWithInt.Value value = payload.get(key);
        if (value == null || !value.hasStringValue() || value.getStringValue().isEmpty()) throw new IOException("Missing icon payload field: " + key);
        return value.getStringValue();
    }
    private <T> T await(Future<T> future) throws IOException {
        try { return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); future.cancel(true); throw new IOException("Qdrant operation interrupted", e); }
        catch (ExecutionException e) { throw new IOException("Qdrant operation failed for " + collection, e.getCause()); }
        catch (TimeoutException e) { future.cancel(true); throw new IOException("Qdrant operation timed out for " + collection, e); }
    }
}
