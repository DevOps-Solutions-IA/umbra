package app.umbra.ui.model;

import java.util.Arrays;

/**
 * One-use pairing code entry/display (PAIRING_PRODUCT_V1: 16 symbols, four groups of four). Only ASCII case,
 * spaces and hyphens are normalized, exactly like the domain; ambiguous characters are NEVER corrected (no O→0,
 * I→1): the domain alone decides validity. Works on char[] so callers can wipe buffers in finally.
 */
public final class HumanCodeInput {
    private HumanCodeInput() {}
    public static final int GROUPS = 4, GROUP = 4, LENGTH = GROUPS * GROUP;

    /** Uppercases ASCII letters and drops spaces/hyphens. Other characters are kept as typed. Caller wipes result. */
    public static char[] normalize(CharSequence typed) {
        char[] out = new char[typed.length()]; int n = 0;
        for (int i = 0; i < typed.length(); i++) {
            char c = typed.charAt(i);
            if (c == ' ' || c == '-') continue;
            out[n++] = c >= 'a' && c <= 'z' ? (char) (c - 32) : c;
        }
        char[] exact = Arrays.copyOf(out, n); Arrays.fill(out, '\0');
        return exact;
    }

    /** True when the normalized code has the domain length (validity is still decided by the domain). */
    public static boolean complete(char[] normalized) { return normalized != null && normalized.length == LENGTH; }

    /** "ABCD-EFGH-JKLM-NPQR" as a new char[] for TextView.setText(char[],int,int); caller wipes it. */
    public static char[] grouped(char[] code) {
        if (code == null || code.length == 0) return new char[0];
        int separators = (code.length - 1) / GROUP;
        char[] out = new char[code.length + separators]; int j = 0;
        for (int i = 0; i < code.length; i++) { if (i > 0 && i % GROUP == 0) out[j++] = '-'; out[j++] = code[i]; }
        return out;
    }

    /** Spoken form for TalkBack: groups separated by pauses, letters one by one ("A B C D, E F G H, …"). */
    public static char[] spoken(char[] code) {
        if (code == null || code.length == 0) return new char[0];
        char[] out = new char[code.length * 2 + (code.length / GROUP) * 2]; int j = 0;
        for (int i = 0; i < code.length; i++) {
            if (i > 0) { if (i % GROUP == 0) { out[j++] = ','; out[j++] = ' '; } else out[j++] = ' '; }
            out[j++] = code[i];
        }
        char[] exact = Arrays.copyOf(out, j); Arrays.fill(out, '\0');
        return exact;
    }

    public static void wipe(char[] buffer) { if (buffer != null) Arrays.fill(buffer, '\0'); }
}
