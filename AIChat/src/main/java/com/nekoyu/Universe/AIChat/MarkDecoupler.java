package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.MessageChannel.MFChain;

import org.w3c.dom.Element;
import java.util.Objects;

/** Receives one complete XML element, including its attributes, text and child elements. */
@FunctionalInterface
public interface MarkDecoupler {
    MFChain process(Element element);

    enum Form {
        SELF_CLOSING, PAIRED, BOTH;

        public boolean accepts(boolean selfClosing) {
            return this == BOTH || (selfClosing ? this == SELF_CLOSING : this == PAIRED);
        }
    }

    /** Existing lambda registrations continue to accept only self-closing markers. */
    default Form form() { return Form.SELF_CLOSING; }

    static MarkDecoupler withForm(Form form, MarkDecoupler handler) {
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(handler, "handler");
        return new MarkDecoupler() {
            @Override public Form form() { return form; }
            @Override public MFChain process(Element element) { return handler.process(element); }
        };
    }
}
