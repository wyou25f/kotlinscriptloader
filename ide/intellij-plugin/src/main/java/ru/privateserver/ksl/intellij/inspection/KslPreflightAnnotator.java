package ru.privateserver.ksl.intellij.inspection;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import java.nio.charset.StandardCharsets;

/** Local-only diagnostics; it never executes scripts or resolves remote artifacts. */
public final class KslPreflightAnnotator implements Annotator {
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof PsiFile file)) return;
        String configuration = readBuildConfiguration(file.getProject());
        boolean scriptFile = file.getName().endsWith(".kts");
        boolean addonSource = file.getName().endsWith(".kt")
            && KslPreflightAnalyzer.isKslAddonSource(file.getText(), configuration);
        if (!scriptFile && !addonSource) return;
        for (KslPreflightAnalyzer.Diagnostic diagnostic :
            KslPreflightAnalyzer.analyze(file.getText(), configuration)) {
            var builder = holder.newAnnotation(
                diagnostic.error() ? HighlightSeverity.ERROR : HighlightSeverity.WARNING,
                diagnostic.message())
                .range(new com.intellij.openapi.util.TextRange(diagnostic.startOffset(), diagnostic.endOffset()))
                .tooltip("KSL preflight · " + diagnostic.code());
            if (diagnostic.code().startsWith("missing-")) {
                builder.withFix(new KslOpenGradleIntentionAction());
            }
            if (diagnostic.code().equals("unstructured-log") && file.getName().endsWith(".kts")) {
                builder.withFix(new KslStructuredLoggerIntentionAction(diagnostic.startOffset()));
            }
            builder.create();
        }
    }

    public static String readBuildConfiguration(Project project) {
        String base = project.getBasePath();
        if (base == null) return "";
        StringBuilder result = new StringBuilder();
        for (String relative : new String[]{"build.gradle.kts", "build.gradle", "gradle/libs.versions.toml"}) {
            VirtualFile file = LocalFileSystem.getInstance().findFileByPath(base + "/" + relative);
            if (file == null || file.getLength() > 1024 * 1024) continue;
            try {
                result.append(new String(file.contentsToByteArray(), StandardCharsets.UTF_8)).append('\n');
            } catch (Exception ignored) {
                // An unreadable optional build file must not break editor analysis.
            }
        }
        return result.toString();
    }
}
