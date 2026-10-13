package com.nekoyu.Universe.AIChat.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class GraphParserTest {
    private final GraphParser parser = new GraphParser();
    static Stream<Arguments> forms() {
        return Stream.of(
                Arguments.of("<graph type='list'><line icon='laptop'>笔记本</line></graph>", GraphSpec.Type.LIST),
                Arguments.of("<graph type='compare'><line>笔记本</line><line>台式机</line></graph>", GraphSpec.Type.COMPARE),
                Arguments.of("<graph type='timeline'><line time='昨天'>第一天</line></graph>", GraphSpec.Type.TIMELINE),
                Arguments.of("<graph type='tree'><node id='root' label='系统'><node id='client' label='客户端'/></node></graph>", GraphSpec.Type.TREE),
                Arguments.of("<graph type='flow'><edge from='a' to='b'/><node id='a' label='开始'/><node id='b' label='处理'/><edge from='b' to='a'/></graph>", GraphSpec.Type.FLOW),
                Arguments.of("<graph type='relation'><node id='db' label='数据库'/><node id='server' label='服务'/><edge from='db' to='server' label='读取'/></graph>", GraphSpec.Type.RELATION));
    }
    @ParameterizedTest @MethodSource("forms")
    void supportsSixTypes(String xml, GraphSpec.Type type) { assertEquals(type, parser.parse(xml).type()); }

    @Test void preservesTextAttributesAndOrder() {
        var graph = (GraphSpec.CompareGraph) parser.parse("<graph type='compare' title='A&amp;B'>"
                + "<line label='笔记本' icon='打开的笔记本电脑'> 400MHz\nA&amp;B </line>"
                + "<line label='台式机'><![CDATA[<None>]]></line></graph>");
        assertEquals("A&B", graph.title());
        assertEquals(" 400MHz\nA&B ", graph.items().get(0).text());
        assertEquals("<None>", graph.items().get(1).text());
        assertEquals("台式机", graph.items().get(1).label());
        assertEquals(java.util.List.of(new GraphSpec.IconSlot("line[1]", "打开的笔记本电脑")), graph.iconSlots());
        assertThrows(UnsupportedOperationException.class, () -> graph.items().clear());
    }
    @Test void timelineIsNotResortedAndTreeLabelsDoNotIncludeChildren() {
        var timeline = (GraphSpec.TimelineGraph) parser.parse("<graph type='timeline'><line time='明天'>B</line><line time='昨天'>A</line></graph>");
        assertEquals("明天", timeline.items().get(0).time());
        var tree = (GraphSpec.TreeGraph) parser.parse("<graph type='tree'><node id='r' label='根' icon='folder'>"
                + "<node id='c' label='孩子' icon='file'/></node></graph>");
        assertEquals("根", tree.root().label());
        assertEquals("c", tree.root().children().get(0).id());
        assertEquals(java.util.List.of("node:r", "node:c"), tree.iconSlots().stream().map(GraphSpec.IconSlot::key).toList());
    }
    @Test void relationDirectionIsExplicit() {
        assertFalse(((GraphSpec.RelationGraph) parser.parse("<graph type='relation'><node id='a' label='A'/></graph>")).directed());
        assertTrue(((GraphSpec.RelationGraph) parser.parse("<graph type='relation' directed='true'><node id='a' label='A'/></graph>")).directed());
    }
    static Stream<String> invalid() {
        return Stream.of("<other/>", "<graph/>", "<graph type='List'/>", "<graph type='bar'/>",
                "<graph type='list'/>", "<graph type='compare'><line>only one</line></graph>",
                "<graph type='timeline'><line>missing time</line></graph>",
                "<graph type='list'><line icon=' '>x</line></graph>", "<graph type='list'><line> </line></graph>",
                "<graph type='list'><line><node/></line></graph>", "<graph type='list'><node id='a' label='A'/></graph>",
                "<graph type='list'>unexpected<line>x</line></graph>", "<graph type='list' unknown='x'><line>x</line></graph>",
                "<graph type='tree'><node id='a' label='A'/><node id='b' label='B'/></graph>",
                "<graph type='tree'><node id='a' label='A'><node id='a' label='B'/></node></graph>",
                "<graph type='tree'><node id='a'/></graph>",
                "<graph type='flow'><node id='a' label='A'/><node id='a' label='A'/></graph>",
                "<graph type='flow'><node id='a' label='A'/><edge from='a' to='missing'/></graph>",
                "<graph type='flow'><node id='a' label='A'><node id='b' label='B'/></node></graph>",
                "<graph type='flow'><node id='a' label='A'/><edge from='a' to='a'>bad</edge></graph>",
                "<graph type='relation' directed='yes'><node id='a' label='A'/></graph>",
                "<graph type='flow' directed='false'><node id='a' label='A'/></graph>",
                "<graph type='list'><line>A&B</line></graph>", "<graph type='list'><line>unfinished",
                "<!DOCTYPE graph [<!ENTITY secret SYSTEM 'file:///unavailable'>]><graph type='list'><line>&secret;</line></graph>");
    }
    @ParameterizedTest @MethodSource("invalid")
    void rejectsInvalidStructureWithLocation(String xml) {
        var error = assertThrows(GraphParseException.class, () -> parser.parse(xml));
        assertTrue(error.path().startsWith("/"));
        assertTrue(error.getMessage().startsWith(error.path() + ":"));
    }
    @Test void errorLocatesTheSpecificReference() {
        var error = assertThrows(GraphParseException.class, () -> parser.parse("<graph type='flow'><node id='a' label='A'/><edge from='a' to='b'/></graph>"));
        assertEquals("/graph/edge[1]/@to", error.path());
    }
}
