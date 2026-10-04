package app.umbra;

import app.umbra.ui.model.HumanCodeInput;
import org.junit.Test;
import static org.junit.Assert.*;

/** One-use code field: same normalization as the domain, no correction of ambiguous characters. */
public class UiHumanCodeInputTest {
    @Test public void normalizesOnlyAsciiCaseSpacesAndHyphens() {
        assertArrayEquals("ABCDEFGHJKMNPQRS".toCharArray(), HumanCodeInput.normalize("abcd-efgh jkmn-pqrs"));
        assertArrayEquals("ABCD".toCharArray(), HumanCodeInput.normalize(" a-b c-d "));
    }
    @Test public void ambiguousCharactersAreNeverRewritten() {
        assertArrayEquals("O0IL1".toCharArray(), HumanCodeInput.normalize("o0il1"));
        assertArrayEquals("non-ASCII letters are not folded", "Ñ".toCharArray(), HumanCodeInput.normalize("Ñ"));
    }
    @Test public void completeMeansSixteenSymbolsOnly() {
        assertTrue(HumanCodeInput.complete(HumanCodeInput.normalize("AAAA-BBBB-CCCC-DDDD")));
        assertFalse(HumanCodeInput.complete(HumanCodeInput.normalize("AAAA-BBBB-CCCC-DDD")));
        assertFalse(HumanCodeInput.complete(null));
    }
    @Test public void groupedAndSpokenFormsKeepEverySymbol() {
        char[] code = "ABCDEFGHJKMNPQRS".toCharArray();
        assertEquals("ABCD-EFGH-JKMN-PQRS", new String(HumanCodeInput.grouped(code)));
        assertEquals("A B C D, E F G H, J K M N, P Q R S", new String(HumanCodeInput.spoken(code)));
        assertEquals(0, HumanCodeInput.grouped(null).length);
    }
    @Test public void wipeClearsTheBuffer() {
        char[] code = "ABCD".toCharArray(); HumanCodeInput.wipe(code);
        for (char c : code) assertEquals('\0', c);
        HumanCodeInput.wipe(null);
    }
}
