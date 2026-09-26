package app.umbra.ui.model;

import java.util.Locale;

/** Display helpers for public identifiers and safety codes. Never used with private material. */
public final class Fingerprints {
    private Fingerprints() {}

    /** Groups a code in blocks of four for comparison ("ABCD EFGH …"). */
    public static String group(String code) {
        if (code == null) return "";
        return code.replaceAll("\\s+", "").replaceAll("(.{4})(?!$)", "$1 ");
    }
    /** Groups into lines of {@code blocksPerLine} blocks for large-text layouts. */
    public static String lines(String code, int blocksPerLine) {
        String[] blocks = group(code).split(" ");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < blocks.length; i++) {
            if (i > 0) b.append(i % blocksPerLine == 0 ? "\n" : " ");
            b.append(blocks[i]);
        }
        return b.toString();
    }
    public static String shortId(String id) {
        if (id == null || id.isEmpty()) return "—";
        String clean = id.replaceAll("[^A-Za-z0-9]", "");
        return clean.length() <= 8 ? clean.toUpperCase(Locale.ROOT) : clean.substring(0, 8).toUpperCase(Locale.ROOT);
    }
    /** TalkBack reads each block character by character ("A B C D, E F G H"). */
    public static String spoken(String code) {
        String[] blocks = group(code).split(" ");
        StringBuilder b = new StringBuilder();
        for (String block : blocks) {
            if (block.isEmpty()) continue;
            if (b.length() > 0) b.append(", ");
            b.append(String.join(" ", block.split("")));
        }
        return b.toString();
    }
    public static String initial(String name) {
        if (name == null || name.isBlank()) return "?";
        return new String(Character.toChars(name.trim().codePointAt(0))).toUpperCase(Locale.ROOT);
    }
}
