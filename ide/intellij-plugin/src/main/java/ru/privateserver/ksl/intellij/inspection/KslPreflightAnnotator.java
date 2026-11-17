package ru.privateserver.ksl.intellij.inspection;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Local-only diagnostics; it never executes scripts or resolves remote artifacts. */
public final class KslPreflightAnnotator implements Annotator {
    private static final long MAX_BUILD_FILE_BYTES = 1L * 1024 * 1024;
    private static final String[] BUILD_CONFIGURATION_PATHS = {
        "build.gradle.kts", "build.gradle", "gradle/libs.versions.toml"
    };
    private static final Map<Project, CachedBuildConfiguration> CONFIGURATION_CACHE =
        Collections.synchronizedMap(new WeakHashMap<>());

    private record FileStamp(String path, long modificationStamp, long length) {}
    private record CachedBuildConfiguration(String basePath, List<FileStamp> stamps, String content) {}
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof PsiFile file)) return;
        boolean scriptFile = file.getName().endsWith(".kts");
        boolean kotlinSource = scriptFile || file.getName().endsWith(".kt");
        if (!kotlinSource) return;

        String configuration = readBuildConfiguration(file.getProject());
        String source = file.getText();
        boolean addonSource = !scriptFile && KslPreflightAnalyzer.isKslAddonSource(source, configuration);
        if (!scriptFile && !addonSource) return;
        for (KslPreflightAnalyzer.Diagnostic diagnostic :
            KslPreflightAnalyzer.analyze(source, configuration)) {
            if (diagnostic.code().equals("unstructured-log") && !scriptFile) continue;
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
        if (project == null || project.isDisposed()) return "";
        String base = project.getBasePath();
        if (base == null || base.isBlank()) return "";

        synchronized (CONFIGURATION_CACHE) {
            List<VirtualFile> files = new ArrayList<>(BUILD_CONFIGURATION_PATHS.length);
            List<FileStamp> stamps = new ArrayList<>(BUILD_CONFIGURATION_PATHS.length);
            for (String relative : BUILD_CONFIGURATION_PATHS) {
                VirtualFile file = LocalFileSystem.getInstance().findFileByPath(base + "/" + relative);
                files.add(file);
                stamps.add(file == null || !file.isValid()
                    ? new FileStamp(relative, -1L, -1L)
                    : new FileStamp(relative, file.getModificationStamp(), file.getLength()));
            }

            CachedBuildConfiguration cached = CONFIGURATION_CACHE.get(project);
            if (cached != null && cached.basePath().equals(base) && cached.stamps().equals(stamps)) {
                return cached.content();
            }

            StringBuilder result = new StringBuilder();
            for (int i = 0; i < files.size(); i++) {
                VirtualFile file = files.get(i);
                if (file == null || !file.isValid() || file.getLength() > MAX_BUILD_FILE_BYTES) continue;
                try {
                    result.append(new String(file.contentsToByteArray(), StandardCharsets.UTF_8)).append('\n');
                } catch (ProcessCanceledException cancelled) {
                    throw cancelled;
                } catch (Exception ignored) {
                    // An unreadable optional build file must not break editor analysis.
                }
            }

            String content = result.toString();
            CONFIGURATION_CACHE.put(project,
                new CachedBuildConfiguration(base, List.copyOf(stamps), content));
            return content;
        }
    }
}
