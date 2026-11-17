package ru.privateserver.ksl.intellij.inspection;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Replaces a bare print call in a KSL script with the structured, admin-aware script logger. */
public final class KslStructuredLoggerIntentionAction implements IntentionAction {
    private static final Pattern CALL = Pattern.compile("(println|print)\\s*\\(");
    private final int offset;

    public KslStructuredLoggerIntentionAction(int offset) {
        this.offset = offset;
    }

    public static String replacementFor(String source, int offset) {
        Matcher matcher = matcherAt(source, offset);
        return matcher == null ? null : "scriptLogger().info";
    }

    public static int methodLengthAt(String source, int offset) {
        Matcher matcher = matcherAt(source, offset);
        return matcher == null ? 0 : matcher.end(1) - matcher.start(1);
    }

    private static Matcher matcherAt(String source, int offset) {
        if (source == null || offset < 0 || offset >= source.length()) return null;
        Matcher matcher = CALL.matcher(source);
        matcher.region(offset, source.length());
        return matcher.lookingAt() ? matcher : null;
    }

    @Override public @NotNull String getText() { return "Replace with scriptLogger().info(...)"; }
    @Override public @NotNull String getFamilyName() { return "KSL structured logging"; }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return editor != null && file.getName().endsWith(".kts")
            && methodLengthAt(file.getText(), offset) > 0
            && offset + methodLengthAt(file.getText(), offset) <= editor.getDocument().getTextLength();
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
        Document document = editor.getDocument();
        String text = document.getText();
        int length = methodLengthAt(text, offset);
        if (length == 0 || offset + length > text.length()) return;
        document.replaceString(offset, offset + length, "scriptLogger().info");
    }

    @Override public boolean startInWriteAction() { return true; }
}
