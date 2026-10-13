package com.nekoyu.Universe.AIChat.graph;

public final class GraphParseException extends IllegalArgumentException {
    private final String path;
    public GraphParseException(String path, String message) { this(path, message, null); }
    public GraphParseException(String path, String message, Throwable cause) {
        super(path + ": " + message, cause);
        this.path = path;
    }
    public String path() { return path; }
}
