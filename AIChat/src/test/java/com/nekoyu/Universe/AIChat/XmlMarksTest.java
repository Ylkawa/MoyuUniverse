package com.nekoyu.Universe.AIChat;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import org.w3c.dom.Element;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Offline regression checks for XML markers and image references. */
public final class XmlMarksTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        AIChat agent = new AIChat();
        Map<String, MarkDecoupler> previous = Map.copyOf(AIChat.markDecouplers);
        try {
            AIChat.registerMarkDecoupler("xml_test", element ->
                    new MFChain("[" + XmlMarks.requiredAttribute(element, "value") + "]"));
            AIChat.registerMarkDecoupler("xml_throw", element -> { throw new IllegalStateException("test failure"); });
            var load = AIChat.class.getDeclaredMethod("loadInternalMarkDecoupler");
            load.setAccessible(true);
            load.invoke(agent);
            check(agent.decoupleMark("a < b & c <xml_test value='A&amp;B&gt;C'/>尾<xml_test value=\"2\" />")
                    .toString().equals("a < b & c [A&B>C]尾[2]"), "mixed prose and adjacent markers");
            check(agent.decoupleMark("<emoji name='不存在'/>").toString().equals("[不存在]"), "real emoji handler");
            String rawValue = "&<>\"'\n\r\t";
            check(XmlMarks.requiredAttribute(XmlMarks.parse(XmlMarks.marker("xml_test", "value", rawValue)), "value")
                    .equals(rawValue), "attribute round trip");
            for (String raw : List.of("<unknown id='1'/>", "<xml_test/>", "<xml_test value=''/>",
                    "<xml_test value=1/>", "<xml_test value='a&b'/>", "<xml_throw/>",
                    "<xml_test value='incomplete'", "<at:channel:1>", "<reply:m1>", "<emoji:开心>",
                    "<image_edit:123>", "<quote:1>", "<ReferenceId=1>",
                    "<xml_test value='1'></xml_test>",
                    "<graph><xml_test value='nested'/></graph>",
                    "<!-- <xml_test value='comment'/> -->", "<![CDATA[<xml_test value='literal'/>]]>")) {
                check(agent.decoupleMark(raw).toString().equals(raw), "preserve: " + raw);
            }
            check(agent.decoupleMark("<graph><xml_test value='nested'/></graph><xml_test value='outside'/>")
                    .toString().equals("<graph><xml_test value='nested'/></graph>[outside]"), "paired content opaque");
            check(agent.decoupleMark("<emoji:old><xml_test value='new'/>").toString()
                    .equals("<emoji:old>[new]"), "legacy marker cannot block a new marker");
            streaming(agent);
            paired(agent);
            registration();
            references();
            try {
                XmlMarks.parse("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///unavailable'>]><x value='&e;'/>");
                throw new AssertionError("DTD must be rejected");
            } catch (org.xml.sax.SAXException expected) { }
        } finally {
            AIChat.markDecouplers.clear();
            AIChat.markDecouplers.putAll(previous);
            agent.stop();
        }
        System.out.println("XmlMarksTest passed");
    }

    private static void paired(AIChat agent) throws Exception {
        List<Element> received = new ArrayList<>();
        AIChat.registerMarkDecoupler("graph", MarkDecoupler.Form.PAIRED, element -> {
            String type = XmlMarks.requiredAttribute(element, "type");
            received.add(element);
            return new MFChain("[graph:" + type + "]");
        });
        String graph = "<graph type='compare'><line icon='laptop'>笔记本：A&amp;B</line>"
                + "<line icon=\"desktop\"><![CDATA[台式机：<None>]]></line>"
                + "<!-- <xml_test value='comment'/> --><xml_test value='child'/></graph>";
        check(agent.decoupleMark("前" + graph + "后<xml_test value='outside'/>").toString()
                .equals("前[graph:compare]后[outside]"), "paired element dispatch with surrounding prose");
        check(received.size() == 1, "dispatch outer element exactly once");
        Element element = received.get(0);
        var lines = element.getElementsByTagName("line");
        check(lines.getLength() == 2 && ((Element) lines.item(0)).getAttribute("icon").equals("laptop"),
                "handler receives attributes and child elements");
        check(lines.item(0).getTextContent().equals("笔记本：A&B")
                && lines.item(1).getTextContent().equals("台式机：<None>"), "entities and CDATA text");
        check(element.getElementsByTagName("xml_test").getLength() == 1, "nested markers remain handler-owned");

        String nested = "<graph type='nested'><graph type='inner'><line icon='laptop'>x</line></graph></graph>";
        check(agent.decoupleMark(nested + "<graph type='list'></graph>").toString()
                .equals("[graph:nested][graph:list]"), "nested same-name element and adjacent graphs");
        check(received.get(1).getElementsByTagName("graph").getLength() == 1, "nested graph delivered intact");

        AIChat.registerMarkDecoupler("xml_both", MarkDecoupler.Form.BOTH,
                value -> new MFChain("[both:" + value.getTextContent() + "]"));
        check(agent.decoupleMark("<xml_both/><xml_both>文字</xml_both>").toString()
                .equals("[both:][both:文字]"), "handler can opt into both forms");
        AIChat.registerMarkDecoupler("xml_empty", MarkDecoupler.Form.PAIRED, value -> new MFChain());
        check(agent.decoupleMark("前<xml_empty></xml_empty>后").toString().equals("前后"), "empty output allowed");
        AIChat.registerMarkDecoupler("xml_null", MarkDecoupler.Form.BOTH, value -> null);
        AIChat.registerMarkDecoupler("xml_pair_throw", MarkDecoupler.Form.PAIRED,
                value -> { throw new IllegalStateException("paired test failure"); });
        AIChat.registerMarkDecoupler("xml_bad_form", new MarkDecoupler() {
            @Override public Form form() { throw new IllegalStateException("form test failure"); }
            @Override public MFChain process(Element value) { throw new AssertionError("must not execute"); }
        });

        for (String raw : List.of("<graph type='compare'/>", "<graph/>", "</graph>",
                "<graph type='compare'>", "<graph type='compare'><line>unfinished",
                "<graph type='compare'", "<graph></graph>", "<graph type='compare'><line></graph>",
                "<graph type='compare'><line>A&B</line></graph>",
                "<graph type='compare'><line>&unknown;</line></graph>",
                "<graph type='compare'><!DOCTYPE x></graph>",
                "<unknown>" + graph + "</unknown>", "<!-- " + nested + " -->", "<![CDATA[" + nested + "]]>",
                "<?example " + nested + "?>", "<xml_null/>", "<xml_null></xml_null>",
                "<xml_pair_throw></xml_pair_throw>", "<xml_bad_form/>")) {
            check(agent.decoupleMark(raw).toString().equals(raw), "paired preserve: " + raw);
        }
        int before = received.size();
        check(agent.decoupleMark("<graph type='compare'><line></graph><xml_test value='after'/>").toString()
                .equals("<graph type='compare'><line></graph>[after]"), "malformed nesting does not hide following marker");
        check(received.size() == before, "malformed element never reaches handler");
        String declaration = "<!DOCTYPE x [<!ENTITY e \"<xml_test value='hidden'/>\">]>";
        check(agent.decoupleMark(declaration + "<xml_test value='after'/>").toString()
                .equals(declaration + "[after]"), "declaration markup stays opaque");

        ImageField image = new ImageField(new URL("https://example.invalid/graph.png"));
        AIChat.registerMarkDecoupler("graph_image", MarkDecoupler.Form.PAIRED, value -> new MFChain(image));
        MFChain imageResult = agent.decoupleMark("前<graph_image type='compare'><line>x</line></graph_image>后");
        check(imageResult.size() == 3 && imageResult.get(1) == image, "paired decoupler returns real image field");

        String answer = "前段\n\n<graph\n\n type='compare'><line icon='laptop'>笔记本\n\n400MHz</line>"
                + "<!-- blank\n\n --><?example blank\n\n ?><line icon='desktop'><![CDATA[台式机\n\n]]></line>"
                + "</graph><graph type='list'></graph>\n\n尾段";
        List<String> expected = List.of("前段", "[graph:compare][graph:list]", "尾段");
        for (int split = 0; split <= answer.length(); split++) {
            check(stream(agent, List.of(answer.substring(0, split), answer.substring(split))).equals(expected),
                    "paired streaming boundary " + split);
        }
        List<String> characters = new ArrayList<>();
        for (int i = 0; i < answer.length(); i++) characters.add(answer.substring(i, i + 1));
        check(stream(agent, characters).equals(expected), "paired streaming one character at a time");
        String unfinished = "<graph type='compare'><line>x\n\ny</line>";
        before = received.size();
        check(stream(agent, List.of(unfinished)).equals(List.of(unfinished)) && received.size() == before,
                "unfinished final graph remains literal and never executes");
        for (int i = 1; i < nested.length(); i++) {
            check(XmlMarks.scan(nested.substring(0, i)).stream().noneMatch(XmlMarks.Span::complete),
                    "partial paired element cannot be marked complete " + i);
        }
    }

    private static List<String> stream(AIChat agent, List<String> chunks) {
        List<String> sent = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        for (String chunk : chunks) {
            pending.append(chunk);
            int end;
            while ((end = XmlMarks.paragraphEnd(pending.toString())) >= 0) {
                if (end > 0) sent.add(agent.decoupleMark(pending.substring(0, end)).toString());
                pending.delete(0, end + 2);
            }
        }
        if (!pending.isEmpty()) sent.add(agent.decoupleMark(pending.toString()).toString());
        return sent;
    }

    private static void registration() {
        for (String name : List.of("", "has space", "a:b", "<graph>", "1graph")) {
            try {
                AIChat.registerMarkDecoupler(name, element -> new MFChain());
                throw new AssertionError("invalid marker name accepted: " + name);
            } catch (IllegalArgumentException expected) { }
        }
        try {
            AIChat.registerMarkDecoupler("xml_null_handler", null);
            throw new AssertionError("null handler accepted");
        } catch (NullPointerException expected) { }
        try {
            AIChat.registerMarkDecoupler("xml_null_form", null, element -> new MFChain());
            throw new AssertionError("null form accepted");
        } catch (NullPointerException expected) { }
    }

    private static void streaming(AIChat agent) {
        String answer = "前段\n\n<xml_test\n\n value=\"a&gt;b\"/>\n\n<graph>\n\n<xml_test value='inner'/>\n\n</graph>\n\n尾段";
        StringBuilder pending = new StringBuilder();
        java.util.ArrayList<String> sent = new java.util.ArrayList<>();
        for (char c : answer.toCharArray()) {
            pending.append(c);
            int end;
            while ((end = XmlMarks.paragraphEnd(pending.toString())) >= 0) {
                if (end > 0) sent.add(agent.decoupleMark(pending.substring(0, end)).toString());
                pending.delete(0, end + 2);
            }
        }
        if (!pending.isEmpty()) sent.add(agent.decoupleMark(pending.toString()).toString());
        check(sent.equals(List.of("前段", "[a>b]", "<graph>\n\n<xml_test value='inner'/>\n\n</graph>", "尾段")),
                "streaming must not split markup or execute paired content");
        check(XmlMarks.paragraphEnd("<xml_test value='incomplete\n\n") == -1, "incomplete attribute buffered");
    }

    private static void references() throws Exception {
        var resolve = ChatContext.class.getDeclaredMethod("resolveImageQuotes", JsonElement.class, Map.class);
        resolve.setAccessible(true);
        Map<Integer, String> urls = Map.of(1, "https://example.invalid/a?a=1&b=2", 2, "https://example.invalid/b");
        JsonElement original = JsonParser.parseString("{\"value\":\"<ref id='1'/>\",\"nested\":[\"<ref id='2'/>\"],\"<ref id='1'/>\":7}");
        String before = original.toString();
        JsonElement resolved = (JsonElement) resolve.invoke(null, original, urls);
        check(resolved.getAsJsonObject().get("value").getAsString().equals(urls.get(1)), "first reference");
        check(resolved.getAsJsonObject().getAsJsonArray("nested").get(0).getAsString().equals(urls.get(2)), "nested reference");
        check(resolved.getAsJsonObject().has("<ref id='1'/>") && original.toString().equals(before), "keys and source unchanged");
        for (String raw : List.of("<ref/>", "<ref id=''/>", "<ref id='0'/>", "<ref id='-1'/>",
                "<ref id='abc'/>", "<ref id='999999999999999'/>", "<ref id='999'/>",
                "<ref id=1/>", "<ref id='1'", "<ref id='1'></ref>")) {
            try {
                resolve.invoke(null, new com.google.gson.JsonPrimitive(raw), urls);
                throw new AssertionError("invalid reference accepted: " + raw);
            } catch (InvocationTargetException e) {
                check(e.getCause() instanceof IllegalArgumentException, "invalid reference fails before callback");
            }
        }
        String old = "<quote:1> <quoteId:1> <ReferenceId=1>";
        check(((JsonElement) resolve.invoke(null, new com.google.gson.JsonPrimitive(old), urls)).getAsString().equals(old),
                "old syntax and reference labels are not tool references");
        check(((JsonElement) resolve.invoke(null, new com.google.gson.JsonPrimitive("<ref id=\"1\" />"), urls))
                .getAsString().equals(urls.get(1)), "double quoted reference");
    }
}
