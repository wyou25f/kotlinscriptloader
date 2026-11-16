package ru.privateserver.ksl.intellij.inspection;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KslRunSyncIntentionActionTest {
    @Test
    void doesNotOfferFixForVariableDeclarations() {
        String source = "val player = Bukkit.getPlayer(\"Alex\")";
        assertFalse(KslRunSyncIntentionAction.canWrapLine(source, source.indexOf("getPlayer")));
    }

    @Test
    void offersFixForStandaloneApiStatements() {
        String source = "player.sendMessage(\"Hello\")";
        assertTrue(KslRunSyncIntentionAction.canWrapLine(source, source.indexOf("sendMessage")));
    }
}
