package ru.privateserver.ksl.intellij.inspection;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KslStructuredLoggerIntentionActionTest {
    @Test
    void rewritesPrintlnAndPrintCallsWithoutTouchingTheirArguments() {
        String source = "println(\"ready\")";
        assertEquals("scriptLogger().info", KslStructuredLoggerIntentionAction.replacementFor(source, 0));
        assertEquals(7, KslStructuredLoggerIntentionAction.methodLengthAt(source, 0));
        assertEquals("scriptLogger().info(value)", 
            KslStructuredLoggerIntentionAction.replacementFor("print(value)", 0) + "(value)");
    }

    @Test
    void doesNotRewriteUnrelatedOffsetsOrText() {
        assertNull(KslStructuredLoggerIntentionAction.replacementFor("val value = 1", 0));
        assertEquals(0, KslStructuredLoggerIntentionAction.methodLengthAt("println(value)", 2));
    }
}
