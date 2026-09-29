package app.umbra.privacy;

import app.umbra.content.ContentException;
import org.junit.Test;
import static org.junit.Assert.*;

public class OperationFailureTest {
    @Test public void restrictedFailuresHaveStableDistinctPresentationWithoutMessageParsing() {
        var expected=new OperationFailure[]{OperationFailure.INVALID_CONTENT,OperationFailure.CONTENT_EXPIRED,
            OperationFailure.CONTENT_CONSUMED,OperationFailure.CONTENT_BUSY,OperationFailure.RESOURCE_LIMIT,
            OperationFailure.EXPORT_FORBIDDEN,OperationFailure.CONSENT_REQUIRED};
        assertEquals(expected.length,ContentException.Code.values().length);
        for(var code:ContentException.Code.values()) {
            var failure=new ContentException(code);
            assertEquals("Restricted content unavailable",failure.getMessage());
            assertEquals(expected[code.ordinal()],OperationFailure.classify(failure));
        }
        // A foreign exception, including one with a convincing message/cause, is not authorization.
        assertEquals(OperationFailure.UNAVAILABLE,OperationFailure.classify(new SecurityException("CONSUMED")));
        assertEquals(OperationFailure.UNAVAILABLE,OperationFailure.classify(new RuntimeException(new ContentException(ContentException.Code.CONSUMED))));
    }
}
