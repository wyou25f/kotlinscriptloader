package ru.privateserver.ksl.intellij.inspection;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/** Wraps a standalone statement in runSync; declaration lines are excluded to preserve scope. */
public final class KslRunSyncIntentionAction implements IntentionAction {
    private final int offset;
    public KslRunSyncIntentionAction(int offset) { this.offset = offset; }

    public static boolean canWrapLine(String source, int offset) {
        if (source == null || offset < 0 || offset >= source.length()) return false;
        int start = source.lastIndexOf('\n', offset);
        start = start < 0 ? 0 : start + 1;
        int end = source.indexOf('\n', offset);
        if (end < 0) end = source.length();
        String line = source.substring(start, end).stripLeading();
        return !line.matches("^(val|var)\\s+.*");
    }

    @Override public @NotNull String getText() { return "Wrap this line in runSync { ... }"; }
    @Override public @NotNull String getFamilyName() { return "KSL thread-safety fixes"; }
    @Override public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return editor != null && file.getName().endsWith(".kts")
            && canWrapLine(file.getText(), offset)
            && offset < editor.getDocument().getTextLength();
    }
    @Override public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
        Document document = editor.getDocument();
        int line = document.getLineNumber(Math.min(offset, document.getTextLength()));
        int start = document.getLineStartOffset(line);
        int end = document.getLineEndOffset(line);
        String original = document.getText().substring(start, end);
        if (original.contains("runSync")) return;
        String trimmed = original.strip();
        if (trimmed.isEmpty()) return;
        String indent = original.substring(0, original.length() - original.stripLeading().length());
        document.replaceString(start, end, indent + "runSync {\n" + indent + "    " + trimmed + "\n" + indent + "}");
    }
    @Override public boolean startInWriteAction() { return true; }
}
