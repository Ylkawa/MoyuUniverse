package com.nekoyu.Universe.AIChat.graph;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real-server test. Only creates/deletes its own freshly generated collection. */
@EnabledIfEnvironmentVariable(named = "QDRANT_TEST_HOST", matches = ".+")
class QdrantIconIndexLiveTest {
    @TempDir Path directory;
    @Test void roundTripsOnAnIndependentRealCollection() throws Exception {
        String host = System.getenv("QDRANT_TEST_HOST");
        int port = Integer.parseInt(System.getenv().getOrDefault("QDRANT_TEST_PORT", "6334"));
        boolean tls = Boolean.parseBoolean(System.getenv().getOrDefault("QDRANT_TEST_TLS", "false"));
        var builder = QdrantGrpcClient.newBuilder(host, port, tls);
        String apiKey = System.getenv("QDRANT_TEST_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) builder.withApiKey(apiKey);
        try (QdrantClient client = new QdrantClient(builder.build())) {
            String collection = "graph_test_" + UUID.randomUUID().toString().replace("-", "");
            IconCatalog catalog = GraphTestFixtures.catalog(directory);
            var provider = new GraphTestFixtures.Provider();
            var index = new QdrantIconIndex(client, collection, IndexDescriptor.of(provider.space(), catalog), Duration.ofSeconds(10));
            boolean owned = false;
            try {
                assertFalse(client.collectionExistsAsync(collection).get(10, TimeUnit.SECONDS));
                index.initialize(); owned = true;
                assertEquals(2, new IconIndexer(provider, index).index(catalog));
                assertEquals(2, new IconIndexer(provider, index).index(catalog));
                var result = new GraphMatchService(provider, index, catalog).resolve(new GraphParser().parse("<graph type='list'><line icon='laptop'>x</line></graph>"));
                assertEquals("lucide/laptop", result.matches().get(0).preferred().orElseThrow().resource().id());
                assertEquals(2, result.matches().get(0).candidates().size());
            } finally {
                if (owned) client.deleteCollectionAsync(collection).get(10, TimeUnit.SECONDS);
            }
        }
    }
}
