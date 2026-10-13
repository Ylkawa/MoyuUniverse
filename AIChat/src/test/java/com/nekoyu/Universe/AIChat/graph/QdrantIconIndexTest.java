package com.nekoyu.Universe.AIChat.graph;

import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.CollectionsGrpc;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.PointsGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real Qdrant Java client and gRPC wire against a deterministic test service. */
class QdrantIconIndexTest {
    @TempDir Path directory;
    Server server;
    QdrantClient client;
    final Map<String, Collections.VectorsConfig> configs = new LinkedHashMap<>();
    final Map<String, Map<String, Points.PointStruct>> data = new LinkedHashMap<>();
    volatile boolean failSearch;
    volatile Points.UpsertPoints lastUpsert;
    volatile Points.SearchPoints lastSearch;

    @BeforeEach void start() throws Exception {
        server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .addService(new CollectionsGrpc.CollectionsImplBase() {
                    @Override public void collectionExists(Collections.CollectionExistsRequest request, StreamObserver<Collections.CollectionExistsResponse> observer) {
                        complete(observer, Collections.CollectionExistsResponse.newBuilder().setResult(Collections.CollectionExists.newBuilder()
                                .setExists(configs.containsKey(request.getCollectionName()))).build());
                    }
                    @Override public void create(Collections.CreateCollection request, StreamObserver<Collections.CollectionOperationResponse> observer) {
                        configs.put(request.getCollectionName(), request.getVectorsConfig()); data.put(request.getCollectionName(), new LinkedHashMap<>());
                        complete(observer, Collections.CollectionOperationResponse.newBuilder().setResult(true).build());
                    }
                    @Override public void get(Collections.GetCollectionInfoRequest request, StreamObserver<Collections.GetCollectionInfoResponse> observer) {
                        var config = configs.get(request.getCollectionName());
                        if (config == null) { observer.onError(Status.NOT_FOUND.asRuntimeException()); return; }
                        complete(observer, Collections.GetCollectionInfoResponse.newBuilder().setResult(Collections.CollectionInfo.newBuilder()
                                .setConfig(Collections.CollectionConfig.newBuilder().setParams(Collections.CollectionParams.newBuilder().setVectorsConfig(config)))).build());
                    }
                }).addService(new PointsGrpc.PointsImplBase() {
                    @Override public void upsert(Points.UpsertPoints request, StreamObserver<Points.PointsOperationResponse> observer) {
                        lastUpsert = request;
                        for (Points.PointStruct point : request.getPointsList()) data.get(request.getCollectionName()).put(point.getId().getUuid(), point);
                        complete(observer, Points.PointsOperationResponse.newBuilder().setResult(Points.UpdateResult.newBuilder()
                                .setStatus(Points.UpdateStatus.Completed)).build());
                    }
                    @Override public void search(Points.SearchPoints request, StreamObserver<Points.SearchResponse> observer) {
                        lastSearch = request;
                        if (failSearch) { observer.onError(Status.UNAVAILABLE.asRuntimeException()); return; }
                        var response = Points.SearchResponse.newBuilder();
                        data.get(request.getCollectionName()).values().stream().map(point -> {
                            List<Float> vector = point.getVectors().getVectors().getVectorsMap().get(request.getVectorName()).getDense().getDataList();
                            double score = (request.getVector(0) * vector.get(0) + request.getVector(1) * vector.get(1))
                                    / (Math.hypot(request.getVector(0), request.getVector(1)) * Math.hypot(vector.get(0), vector.get(1)));
                            return Points.ScoredPoint.newBuilder().setId(point.getId()).putAllPayload(point.getPayloadMap()).setScore((float) score).build();
                        }).sorted(java.util.Comparator.comparingDouble(Points.ScoredPoint::getScore).reversed())
                                .limit(request.getLimit()).forEach(response::addResult);
                        complete(observer, response.build());
                    }
                }).build().start();
        client = new QdrantClient(QdrantGrpcClient.newBuilder("127.0.0.1", server.getPort(), false, false).build());
    }
    @AfterEach void stop() throws Exception { client.close(); server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); }
    private static <T> void complete(StreamObserver<T> observer, T result) { observer.onNext(result); observer.onCompleted(); }

    @Test void createsIndependentCollectionUpsertsIdempotentlyAndSearchesPayload() throws Exception {
        IconCatalog catalog = GraphTestFixtures.catalog(directory);
        var provider = new GraphTestFixtures.Provider();
        var descriptor = IndexDescriptor.of(provider.space(), catalog);
        var index = new QdrantIconIndex(client, descriptor);
        var indexer = new IconIndexer(provider, index);
        assertEquals(2, indexer.index(catalog)); assertEquals(2, indexer.index(catalog));
        assertEquals(1, configs.size()); assertEquals(2, data.get(index.collection()).size());
        assertTrue(lastUpsert.getWait());
        var result = new GraphMatchService(provider, index, catalog).resolve(new GraphParser().parse("<graph type='list'><line icon='laptop'>400MHz</line></graph>"));
        assertEquals("lucide/laptop", result.matches().get(0).preferred().orElseThrow().resource().id());
        assertEquals(5, lastSearch.getLimit()); assertEquals(descriptor.vectorName(), lastSearch.getVectorName());
        assertTrue(lastSearch.getWithPayload().getEnable()); assertFalse(lastSearch.hasScoreThreshold());
    }
    @Test void refusesIncompatibleExistingIndexWithoutRecreatingIt() throws Exception {
        IconCatalog catalog = GraphTestFixtures.catalog(directory);
        var provider = new GraphTestFixtures.Provider();
        var descriptor = IndexDescriptor.of(provider.space(), catalog);
        var index = new QdrantIconIndex(client, "graph_test_compat", descriptor, Duration.ofSeconds(2));
        index.initialize();
        var changed = new IndexDescriptor(new IconEmbeddingProvider.Space(provider.space().model(), "changed", 2),
                descriptor.library(), descriptor.libraryVersion(), descriptor.catalogDigest(), descriptor.preprocessingVersion());
        var configBefore = configs.get(index.collection());
        assertThrows(IOException.class, () -> new QdrantIconIndex(client, index.collection(), changed, Duration.ofSeconds(2)).initialize());
        assertSame(configBefore, configs.get(index.collection()));
        assertEquals(1, configs.size());
    }
    @Test void surfacesDatabaseFailuresAndDoesNotCreateCollectionsDuringSearch() throws Exception {
        IconCatalog catalog = GraphTestFixtures.catalog(directory);
        var descriptor = IndexDescriptor.of(new GraphTestFixtures.Provider().space(), catalog);
        var index = new QdrantIconIndex(client, descriptor);
        assertThrows(IOException.class, () -> index.search(new double[]{1, 0}, 5));
        assertTrue(configs.isEmpty());
        index.initialize(); assertTrue(index.search(new double[]{1, 0}, 5).isEmpty());
        failSearch = true;
        assertThrows(IOException.class, () -> index.search(new double[]{1, 0}, 5));
    }
}
