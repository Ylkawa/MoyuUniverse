package com.nekoyu.Universe.AIChat.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphMatchServiceTest {
    @TempDir Path directory;
    @Test void importsIndexesAndResolvesOnlyIconDescriptions() throws Exception {
        IconCatalog catalog = GraphTestFixtures.catalog(directory);
        var provider = new GraphTestFixtures.Provider();
        var index = new GraphTestFixtures.MemoryIndex(IndexDescriptor.of(provider.space(), catalog));
        assertEquals(2, GraphTestFixtures.fill(provider, index, catalog));
        assertEquals(2, provider.imageCalls);
        assertEquals(2, GraphTestFixtures.fill(provider, index, catalog));
        assertEquals(2, index.points.size());
        GraphSpec graph = new GraphParser().parse("<graph type='compare' title='processor'>"
                + "<line icon='laptop'>OverClocked for 400MHz frequency</line>"
                + "<line icon='desktop'>None OverClock</line><line icon='laptop'>server</line></graph>");
        ResolvedGraph result = new GraphMatchService(provider, index, catalog).resolve(graph);
        assertSame(graph, result.graph());
        assertEquals(List.of("laptop", "desktop"), provider.texts);
        assertEquals(1, provider.textCalls);
        assertEquals(2, index.searches);
        assertEquals(3, result.matches().size());
        assertEquals("lucide/laptop", result.matches().get(0).preferred().orElseThrow().resource().id());
        assertEquals("lucide/monitor", result.matches().get(1).preferred().orElseThrow().resource().id());
        assertEquals(result.matches().get(0).candidates(), result.matches().get(2).candidates());
        assertEquals(1, result.matches().get(0).preferred().orElseThrow().score());
        assertEquals(ResolvedGraph.Status.CANDIDATES, result.matches().get(0).status());
    }
    @Test void sortsDeduplicatesAndLimitsCandidates() throws Exception {
        IconCatalog catalog = GraphTestFixtures.catalog(directory);
        var provider = new GraphTestFixtures.Provider();
        var index = new GraphTestFixtures.MemoryIndex(IndexDescriptor.of(provider.space(), catalog));
        index.override = List.of(new IconCandidate(catalog.icons().get(0), .1), new IconCandidate(catalog.icons().get(1), .7), new IconCandidate(catalog.icons().get(1), .8));
        var result = new GraphMatchService(provider, index, catalog, 1).resolve(new GraphParser().parse("<graph type='list'><line icon='laptop'>x</line></graph>"));
        assertEquals(1, result.matches().get(0).candidates().size());
        assertEquals(.8, result.matches().get(0).preferred().orElseThrow().score());
    }
    @Test void emptyIndexDoesNotInventAResourceAndMissingIconDoesNotQuery() throws Exception {
        IconCatalog catalog = GraphTestFixtures.catalog(directory);
        var provider = new GraphTestFixtures.Provider();
        var index = new GraphTestFixtures.MemoryIndex(IndexDescriptor.of(provider.space(), catalog));
        var service = new GraphMatchService(provider, index, catalog);
        var result = service.resolve(new GraphParser().parse("<graph type='list'><line icon='unknown'>x</line></graph>"));
        assertEquals(ResolvedGraph.Status.UNMATCHED, result.matches().get(0).status());
        assertTrue(result.matches().get(0).preferred().isEmpty());
        int calls = provider.textCalls;
        index.failure = new IOException("offline");
        assertTrue(service.resolve(new GraphParser().parse("<graph type='list'><line>x</line></graph>")).matches().isEmpty());
        assertEquals(calls, provider.textCalls);
        assertThrows(IOException.class, () -> service.resolve(new GraphParser().parse("<graph type='list'><line icon='laptop'>x</line></graph>")));
    }
    @Test void rejectsBadVectorsAndChangedSpaceBeforeSearch() throws Exception {
        IconCatalog catalog = GraphTestFixtures.catalog(directory);
        var provider = new GraphTestFixtures.Provider();
        var index = new GraphTestFixtures.MemoryIndex(IndexDescriptor.of(provider.space(), catalog));
        var service = new GraphMatchService(provider, index, catalog);
        var graph = new GraphParser().parse("<graph type='list'><line icon='laptop'>x</line></graph>");
        for (List<double[]> vectors : List.of(List.<double[]>of(), List.of(new double[]{1}),
                List.of(new double[]{0, 0}), List.of(new double[]{Double.NaN, 1}), List.of(new double[]{Double.MAX_VALUE, 1}))) {
            provider.override = vectors;
            assertEquals(ResolvedGraph.Status.UNAVAILABLE, service.resolve(graph).matches().get(0).status());
        }
        assertEquals(0, index.searches);
        provider.identity = new IconEmbeddingProvider.Space("test-multimodal", "2", 2);
        assertThrows(IOException.class, () -> service.resolve(graph));
        assertThrows(IllegalArgumentException.class, () -> new GraphMatchService(provider, index, catalog));
    }
    @Test void rejectsForeignPayloadAndIndexVersion() throws Exception {
        IconCatalog catalog = GraphTestFixtures.catalog(directory);
        var provider = new GraphTestFixtures.Provider();
        var index = new GraphTestFixtures.MemoryIndex(IndexDescriptor.of(provider.space(), catalog));
        IconResource original = catalog.icons().get(0);
        IconResource foreign = new IconResource(original.id(), original.name(), original.library(), original.libraryVersion(), "wrong-hash", original.svgPath(), original.previewPath());
        index.override = List.of(new IconCandidate(foreign, 1));
        assertThrows(IOException.class, () -> new GraphMatchService(provider, index, catalog)
                .resolve(new GraphParser().parse("<graph type='list'><line icon='laptop'>x</line></graph>")));
        var other = new IconCatalog(catalog.library(), catalog.version(), "different", catalog.preprocessingVersion(), catalog.licensePath(), catalog.icons());
        assertThrows(IOException.class, () -> GraphTestFixtures.fill(provider, index, other));
    }
}
