/*
 * balatro-dualscreen-thor
 * Copyright (C) 2026  balatro-dualscreen-thor contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.balatro.dualscreen.mods;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small TOML reader, enough for Lovely patch files.
 *
 * Android ships no TOML parser and pulling a library in for a few hundred
 * lines of mod metadata is not worth the dependency. This covers what patch
 * files use: comments, [tables], [[arrays of tables]], dotted keys, basic and
 * literal strings in single- and multi-line forms, integers, floats,
 * booleans, arrays and inline tables. Dates are not supported.
 *
 * Tables come back as {@code Map<String,Object>} (insertion ordered), arrays
 * as {@code List<Object>}, strings as String, integers as Long, floats as
 * Double, booleans as Boolean.
 */
public final class Toml {

    public static final class TomlException extends Exception {
        TomlException(String msg) {
            super(msg);
        }
    }

    private final String src;
    private int pos;
    private int line = 1;

    private Toml(String src) {
        this.src = src;
    }

    public static Map<String, Object> parse(String text) throws TomlException {
        return new Toml(text).parseDocument();
    }

    // ------------------------------------------------------------------

    private Map<String, Object> parseDocument() throws TomlException {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> current = root;

        while (true) {
            skipWhitespaceAndComments(true);
            if (pos >= src.length()) {
                break;
            }
            char c = src.charAt(pos);
            if (c == '[') {
                boolean array = pos + 1 < src.length() && src.charAt(pos + 1) == '[';
                pos += array ? 2 : 1;
                skipInlineWhitespace();
                List<String> path = parseKeyPath();
                skipInlineWhitespace();
                expect(']');
                if (array) {
                    expect(']');
                }
                current = openTable(root, path, array);
                expectLineEnd();
            } else {
                List<String> path = parseKeyPath();
                skipInlineWhitespace();
                expect('=');
                skipInlineWhitespace();
                Object value = parseValue();
                assign(current, path, value);
                expectLineEnd();
            }
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> openTable(Map<String, Object> root,
                                          List<String> path, boolean array)
            throws TomlException {
        Map<String, Object> node = root;
        for (int i = 0; i < path.size(); i++) {
            String key = path.get(i);
            boolean last = i == path.size() - 1;
            Object existing = node.get(key);
            if (last && array) {
                List<Object> list;
                if (existing == null) {
                    list = new ArrayList<>();
                    node.put(key, list);
                } else if (existing instanceof List) {
                    list = (List<Object>) existing;
                } else {
                    throw error("key '" + key + "' is not an array of tables");
                }
                Map<String, Object> t = new LinkedHashMap<>();
                list.add(t);
                return t;
            }
            if (existing == null) {
                Map<String, Object> t = new LinkedHashMap<>();
                node.put(key, t);
                node = t;
            } else if (existing instanceof Map) {
                node = (Map<String, Object>) existing;
            } else if (existing instanceof List) {
                // [a.b] after [[a]]: descend into the last element.
                List<Object> list = (List<Object>) existing;
                if (list.isEmpty() || !(list.get(list.size() - 1) instanceof Map)) {
                    throw error("key '" + key + "' is not a table");
                }
                node = (Map<String, Object>) list.get(list.size() - 1);
            } else {
                throw error("key '" + key + "' is not a table");
            }
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private void assign(Map<String, Object> table, List<String> path, Object value)
            throws TomlException {
        Map<String, Object> node = table;
        for (int i = 0; i < path.size() - 1; i++) {
            Object existing = node.get(path.get(i));
            if (existing == null) {
                Map<String, Object> t = new LinkedHashMap<>();
                node.put(path.get(i), t);
                node = t;
            } else if (existing instanceof Map) {
                node = (Map<String, Object>) existing;
            } else {
                throw error("key '" + path.get(i) + "' is not a table");
            }
        }
        String last = path.get(path.size() - 1);
        if (node.containsKey(last)) {
            throw error("duplicate key '" + last + "'");
        }
        node.put(last, value);
    }

    // --- keys ------------------------------------------------------------

    private List<String> parseKeyPath() throws TomlException {
        List<String> path = new ArrayList<>();
        while (true) {
            skipInlineWhitespace();
            path.add(parseSimpleKey());
            skipInlineWhitespace();
            if (pos < src.length() && src.charAt(pos) == '.') {
                pos++;
                continue;
            }
            return path;
        }
    }

    private String parseSimpleKey() throws TomlException {
        if (pos >= src.length()) {
            throw error("expected a key");
        }
        char c = src.charAt(pos);
        if (c == '"') {
            return parseBasicString(false);
        }
        if (c == '\'') {
            return parseLiteralString(false);
        }
        int start = pos;
        while (pos < src.length() && isBareKeyChar(src.charAt(pos))) {
            pos++;
        }
        if (pos == start) {
            throw error("expected a key, found '" + c + "'");
        }
        return src.substring(start, pos);
    }

    private static boolean isBareKeyChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }

    // --- values ----------------------------------------------------------

    private Object parseValue() throws TomlException {
        if (pos >= src.length()) {
            throw error("expected a value");
        }
        char c = src.charAt(pos);
        if (c == '"') {
            if (src.startsWith("\"\"\"", pos)) {
                return parseBasicString(true);
            }
            return parseBasicString(false);
        }
        if (c == '\'') {
            if (src.startsWith("'''", pos)) {
                return parseLiteralString(true);
            }
            return parseLiteralString(false);
        }
        if (c == '[') {
            return parseArray();
        }
        if (c == '{') {
            return parseInlineTable();
        }
        if (src.startsWith("true", pos) && atTokenEnd(pos + 4)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos) && atTokenEnd(pos + 5)) {
            pos += 5;
            return Boolean.FALSE;
        }
        return parseNumber();
    }

    private boolean atTokenEnd(int p) {
        if (p >= src.length()) {
            return true;
        }
        char c = src.charAt(p);
        return !(isBareKeyChar(c));
    }

    private Object parseNumber() throws TomlException {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '_'
                    || c == '.' || c == 'e' || c == 'E' || c == 'x' || c == 'o'
                    || c == 'b' || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
                pos++;
            } else {
                break;
            }
        }
        String tok = src.substring(start, pos).replace("_", "");
        if (tok.isEmpty()) {
            throw error("expected a value");
        }
        try {
            if (tok.startsWith("0x")) {
                return Long.parseLong(tok.substring(2), 16);
            }
            if (tok.startsWith("0o")) {
                return Long.parseLong(tok.substring(2), 8);
            }
            if (tok.startsWith("0b")) {
                return Long.parseLong(tok.substring(2), 2);
            }
            if (tok.contains(".") || tok.contains("e") || tok.contains("E")) {
                return Double.parseDouble(tok);
            }
            return Long.parseLong(tok);
        } catch (NumberFormatException e) {
            throw error("bad number '" + tok + "'");
        }
    }

    private List<Object> parseArray() throws TomlException {
        expect('[');
        List<Object> out = new ArrayList<>();
        while (true) {
            skipWhitespaceAndComments(true);
            if (pos < src.length() && src.charAt(pos) == ']') {
                pos++;
                return out;
            }
            out.add(parseValue());
            skipWhitespaceAndComments(true);
            if (pos < src.length() && src.charAt(pos) == ',') {
                pos++;
                continue;
            }
            skipWhitespaceAndComments(true);
            expect(']');
            return out;
        }
    }

    private Map<String, Object> parseInlineTable() throws TomlException {
        expect('{');
        Map<String, Object> out = new LinkedHashMap<>();
        skipInlineWhitespace();
        if (pos < src.length() && src.charAt(pos) == '}') {
            pos++;
            return out;
        }
        while (true) {
            skipInlineWhitespace();
            List<String> path = parseKeyPath();
            skipInlineWhitespace();
            expect('=');
            skipInlineWhitespace();
            assign(out, path, parseValue());
            skipInlineWhitespace();
            if (pos < src.length() && src.charAt(pos) == ',') {
                pos++;
                continue;
            }
            expect('}');
            return out;
        }
    }

    // --- strings ---------------------------------------------------------

    private String parseBasicString(boolean multi) throws TomlException {
        pos += multi ? 3 : 1;
        if (multi) {
            trimFirstNewline();
        }
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw error("unterminated string");
            }
            char c = src.charAt(pos);
            if (multi) {
                if (src.startsWith("\"\"\"", pos)) {
                    // Up to two extra quotes may precede the closing delimiter.
                    int quotes = 3;
                    while (quotes < 5 && pos + quotes < src.length()
                            && src.charAt(pos + quotes) == '"') {
                        quotes++;
                    }
                    for (int i = 3; i < quotes; i++) {
                        sb.append('"');
                    }
                    pos += quotes;
                    return sb.toString();
                }
            } else if (c == '"') {
                pos++;
                return sb.toString();
            } else if (c == '\n') {
                throw error("newline in single-line string");
            }
            if (c == '\\') {
                pos++;
                if (pos >= src.length()) {
                    throw error("unterminated escape");
                }
                char e = src.charAt(pos);
                switch (e) {
                    case 'n': sb.append('\n'); pos++; break;
                    case 't': sb.append('\t'); pos++; break;
                    case 'r': sb.append('\r'); pos++; break;
                    case 'b': sb.append('\b'); pos++; break;
                    case 'f': sb.append('\f'); pos++; break;
                    case '"': sb.append('"'); pos++; break;
                    case '\\': sb.append('\\'); pos++; break;
                    case 'u': sb.appendCodePoint(parseHex(4)); break;
                    case 'U': sb.appendCodePoint(parseHex(8)); break;
                    default:
                        if (multi && (e == ' ' || e == '\t' || e == '\n' || e == '\r')) {
                            // Line-ending backslash: trim all whitespace that follows.
                            while (pos < src.length() && (src.charAt(pos) == ' '
                                    || src.charAt(pos) == '\t' || src.charAt(pos) == '\n'
                                    || src.charAt(pos) == '\r')) {
                                if (src.charAt(pos) == '\n') {
                                    line++;
                                }
                                pos++;
                            }
                            break;
                        }
                        throw error("bad escape '\\" + e + "'");
                }
                continue;
            }
            if (c == '\n') {
                line++;
            }
            sb.append(c);
            pos++;
        }
    }

    private int parseHex(int digits) throws TomlException {
        pos++;  // the u / U
        if (pos + digits > src.length()) {
            throw error("short unicode escape");
        }
        String h = src.substring(pos, pos + digits);
        pos += digits;
        try {
            return Integer.parseInt(h, 16);
        } catch (NumberFormatException e) {
            throw error("bad unicode escape '" + h + "'");
        }
    }

    private String parseLiteralString(boolean multi) throws TomlException {
        pos += multi ? 3 : 1;
        if (multi) {
            trimFirstNewline();
        }
        int start = pos;
        while (true) {
            if (pos >= src.length()) {
                throw error("unterminated literal string");
            }
            char c = src.charAt(pos);
            if (multi) {
                if (src.startsWith("'''", pos)) {
                    int quotes = 3;
                    while (quotes < 5 && pos + quotes < src.length()
                            && src.charAt(pos + quotes) == '\'') {
                        quotes++;
                    }
                    String body = src.substring(start, pos);
                    StringBuilder sb = new StringBuilder(body);
                    for (int i = 3; i < quotes; i++) {
                        sb.append('\'');
                    }
                    pos += quotes;
                    return sb.toString();
                }
            } else if (c == '\'') {
                String body = src.substring(start, pos);
                pos++;
                return body;
            } else if (c == '\n') {
                throw error("newline in single-line literal string");
            }
            if (c == '\n') {
                line++;
            }
            pos++;
        }
    }

    private void trimFirstNewline() {
        if (src.startsWith("\r\n", pos)) {
            pos += 2;
            line++;
        } else if (pos < src.length() && src.charAt(pos) == '\n') {
            pos++;
            line++;
        }
    }

    // --- whitespace --------------------------------------------------------

    private void skipInlineWhitespace() {
        while (pos < src.length() && (src.charAt(pos) == ' ' || src.charAt(pos) == '\t')) {
            pos++;
        }
    }

    private void skipWhitespaceAndComments(boolean newlines) {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r') {
                pos++;
            } else if (c == '\n' && newlines) {
                pos++;
                line++;
            } else if (c == '#') {
                while (pos < src.length() && src.charAt(pos) != '\n') {
                    pos++;
                }
            } else {
                break;
            }
        }
    }

    private void expectLineEnd() throws TomlException {
        skipWhitespaceAndComments(false);
        if (pos >= src.length()) {
            return;
        }
        if (src.charAt(pos) == '\n') {
            pos++;
            line++;
            return;
        }
        throw error("unexpected '" + src.charAt(pos) + "' after value");
    }

    private void expect(char c) throws TomlException {
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw error("expected '" + c + "'"
                    + (pos < src.length() ? ", found '" + src.charAt(pos) + "'" : ""));
        }
        pos++;
    }

    private TomlException error(String msg) {
        return new TomlException("line " + line + ": " + msg);
    }
}
