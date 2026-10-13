package com.nekoyu.Universe.AIChat.graph.render;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;

/** Original bundled font files; shared between renderers without system font registration. */
final class GraphFonts {
    record Family(Font regular, Font bold) {}
    private static Family bundled;

    private GraphFonts() {}

    static synchronized Family bundled() throws IOException {
        if (bundled == null) {
            bundled = new Family(load("HarmonyOS_Sans_SC_Regular.ttf"), load("HarmonyOS_Sans_SC_Bold.ttf"));
        }
        return bundled;
    }

    private static Font load(String name) throws IOException {
        try (InputStream input = GraphFonts.class.getResourceAsStream("/fonts/harmonyos-sans/" + name)) {
            if (input == null) throw new IOException("Bundled HarmonyOS Sans font is missing: " + name);
            return Font.createFont(Font.TRUETYPE_FONT, input);
        } catch (FontFormatException e) {
            throw new IOException("Invalid bundled HarmonyOS Sans font: " + name, e);
        }
    }
}
