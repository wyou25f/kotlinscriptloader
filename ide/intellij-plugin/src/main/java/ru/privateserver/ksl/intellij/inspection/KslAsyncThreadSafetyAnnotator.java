package ru.privateserver.ksl.intellij.inspection;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/** Warns about common Bukkit API calls inside KSL async scheduler blocks. */
public final class KslAsyncThreadSafetyAnnotator implements Annotator {
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof PsiFile file) || !file.getName().endsWith(".kts")) return;
        String source = file.getText();
        for (KslAsyncThreadSafetyAnalyzer.Diagnostic diagnostic :
            KslAsyncThreadSafetyAnalyzer.analyze(source)) {
            var builder = holder.newAnnotation(
                    HighlightSeverity.WARNING,
                    diagnostic.method() + " is called inside " + diagnostic.asyncFunction()
                        + " { }. Bukkit/Paper world and player operations should run on the server thread.")
                .range(new TextRange(diagnostic.startOffset(), diagnostic.endOffset()))
                .tooltip("Move Bukkit/Paper API work into runSync { ... } after the asynchronous calculation.");
            if (KslRunSyncIntentionAction.canWrapLine(source, diagnostic.startOffset())) {
                builder.withFix(new KslRunSyncIntentionAction(diagnostic.startOffset()));
            }
            builder.create();
        }
    }
}
