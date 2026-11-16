package ru.privateserver.ksl.intellij.inspection;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/** Wraps the current statement in runSync as a starting point for the common async-thread mistake. */
public final class KslRunSyncIntentionAction implements IntentionAction {
    private final int offset;
    public KslRunSyncIntentionAction(int offset) { this.offset = offset; }
    @Override public @NotNull String getText() { return "Wrap this line in runSync { ... }"; }
    @Override public @NotNull String getFamilyName() { return "KSL thread-safety fixes"; }
    @Override public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return editor != null && file.getName().endsWith(".kts")
            && offset >= 0 && offset < editor.getDocument().getTextLength();
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
