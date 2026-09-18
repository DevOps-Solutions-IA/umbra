package app.umbra.core;

/** Display/export names only; never used to construct private filesystem paths. */
public final class FileNames {
    private FileNames() {}
    public static String sanitize(String name) {
        if (name == null) return "archivo.bin";
        Bytes.utf8(name); // Never silently replace an unpaired UTF-16 surrogate.
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < name.length();) {
            int point = name.codePointAt(i); i += Character.charCount(point);
            if (Character.isISOControl(point) || Character.getType(point) == Character.FORMAT || point == '/' || point == '\\') point = '_';
            if (result.length() + Character.charCount(point) > 120) break;
            result.appendCodePoint(point);
        }
        String safe = result.toString().trim();
        return safe.isEmpty() || safe.equals(".") || safe.equals("..") ? "archivo.bin" : safe;
    }
    public static boolean safe(String name) {
        return name != null && !name.isEmpty() && name.equals(sanitize(name));
    }
}
