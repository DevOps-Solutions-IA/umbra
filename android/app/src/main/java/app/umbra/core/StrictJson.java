package app.umbra.core;

import java.util.HashSet;
import java.util.Set;

/** Bounded RFC 8259 syntax validation before Android's more permissive JSONObject parser. */
public final class StrictJson {
    private static final int MAX_DEPTH = 12, MAX_NODES = 4096;
    private final String text;
    private int at, nodes;
    private StrictJson(String text) { this.text = text; }
    public static String object(byte[] bytes, int limit) {
        if (bytes == null || limit < 2 || bytes.length < 2 || bytes.length > limit)
            throw new IllegalArgumentException("JSON size limit");
        String text = Bytes.text(bytes); // Decoder rejects malformed UTF-8.
        StrictJson parser = new StrictJson(text);
        parser.space();
        if (!parser.peek('{')) throw parser.error();
        parser.value(0); parser.space();
        if (parser.at != text.length()) throw parser.error();
        return text;
    }
    private IllegalArgumentException error() { return new IllegalArgumentException("Invalid JSON encoding or limits"); }
    private boolean peek(char c) { return at < text.length() && text.charAt(at) == c; }
    private void expect(char c) { if (!peek(c)) throw error(); at++; }
    private void space() { while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++; }
    private void value(int depth) {
        if (depth > MAX_DEPTH || ++nodes > MAX_NODES || at >= text.length()) throw error();
        char c = text.charAt(at);
        if (c == '{') {
            at++; space(); Set<String> keys = new HashSet<>();
            if (peek('}')) { at++; return; }
            while (true) {
                String key = string(); if (key.length() > 128 || !keys.add(key)) throw error();
                space(); expect(':'); space(); value(depth + 1); space();
                if (peek('}')) { at++; return; }
                expect(','); space();
            }
        } else if (c == '[') {
            at++; space(); if (peek(']')) { at++; return; }
            while (true) {
                value(depth + 1); space(); if (peek(']')) { at++; return; }
                expect(','); space();
            }
        } else if (c == '"') { string(); }
        else if (c == 't') { literal("true"); }
        else if (c == 'f') { literal("false"); }
        else if (c == 'n') { literal("null"); }
        else { number(); }
    }
    private void literal(String expected) {
        if (!text.startsWith(expected, at)) throw error(); at += expected.length();
    }
    private boolean digit() { return at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9'; }
    private void number() {
        int start = at; if (peek('-')) at++;
        if (peek('0')) { at++; if (digit()) throw error(); }
        else { if (!digit()) throw error(); while (digit()) at++; }
        if (peek('.')) { at++; if (!digit()) throw error(); while (digit()) at++; }
        if (peek('e') || peek('E')) { at++; if (peek('+') || peek('-')) at++; if (!digit()) throw error(); while (digit()) at++; }
        if (at == start || at - start > 32) throw error();
    }
    private char hexChar() {
        if (at + 4 > text.length()) throw error();
        int value = 0;
        for (int i = 0; i < 4; i++) {
            char c = text.charAt(at++);
            int digit = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
            if (digit < 0) throw error(); value = value * 16 + digit;
        }
        return (char) value;
    }
    private String string() {
        expect('"'); StringBuilder decoded = new StringBuilder();
        while (at < text.length()) {
            char c = text.charAt(at++);
            if (c == '"') {
                for (int i = 0; i < decoded.length(); i++) {
                    char ch = decoded.charAt(i);
                    if (Character.isHighSurrogate(ch)) {
                        if (++i >= decoded.length() || !Character.isLowSurrogate(decoded.charAt(i))) throw error();
                    } else if (Character.isLowSurrogate(ch)) throw error();
                }
                return decoded.toString();
            }
            if (c < 32) throw error();
            if (c == '\\') {
                if (at >= text.length()) throw error();
                char esc = text.charAt(at++);
                c = switch (esc) {
                    case '"', '\\', '/' -> esc;
                    case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                    case 'u' -> hexChar(); default -> throw error();
                };
            }
            decoded.append(c);
        }
        throw error();
    }
}
