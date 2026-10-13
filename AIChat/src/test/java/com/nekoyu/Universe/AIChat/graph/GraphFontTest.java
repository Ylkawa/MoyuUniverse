package com.nekoyu.Universe.AIChat.graph;

import com.nekoyu.Universe.AIChat.graph.render.GraphRenderContext;
import org.junit.jupiter.api.Test;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class GraphFontTest {
    @Test void bundledOriginalRegularAndBoldFontsCoverChineseWithoutSystemFallback() throws Exception {
        assertResourceHash("HarmonyOS_Sans_SC_Regular.ttf", "297b088424be212207df2ce8b98e335468b782aa6b96832af0b8b773d711e2b1");
        assertResourceHash("HarmonyOS_Sans_SC_Bold.ttf", "43a424b85e47fb53a17b3b32026a71801f86f8e022ca6798d186b47d39fa5f01");
        assertNotNull(GraphRenderContext.class.getResource("/fonts/harmonyos-sans/LICENSE.txt"));
        assertNotNull(GraphRenderContext.class.getResource("/META-INF/NOTICE.txt"));
        try (var first = new GraphRenderContext(new GraphSettings()); var second = new GraphRenderContext(new GraphSettings())) {
            assertEquals("HarmonyOS Sans SC", first.font(24, false).getFamily(java.util.Locale.ROOT));
            assertTrue(first.font(24, true).getFontName(java.util.Locale.ROOT).contains("Bold"));
            assertNotEquals(first.font(24, false).getFontName(), first.font(24, true).getFontName());
            assertEquals(-1, first.font(24, false).canDisplayUpTo("图表：层级展开、关系索引 English 123 →"));
            assertEquals(-1, first.font(24, true).canDisplayUpTo("中文粗体标题"));
            first.close();
            assertFalse(second.text("图表", 300, 24, true).isEmpty());
        }
    }
    private static void assertResourceHash(String name, String expected) throws Exception {
        try (var input = GraphRenderContext.class.getResourceAsStream("/fonts/harmonyos-sans/" + name)) {
            assertNotNull(input, "Bundled font was omitted from resources");
            assertEquals(expected, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
        }
    }
}
