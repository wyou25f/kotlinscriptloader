package ru.privateserver.ksl.intellij.inspection;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/** Opens the build file so the developer can add the exact artifact and version. */
public final class KslOpenGradleIntentionAction implements IntentionAction {
    @Override public @NotNull String getText() { return "Open Gradle build file to add the missing dependency"; }
    @Override public @NotNull String getFamilyName() { return "KSL dependency fixes"; }
    @Override public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        String base = project.getBasePath();
        return base != null && (find(base, "build.gradle.kts") != null || find(base, "build.gradle") != null);
    }
    @Override public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
        String base = project.getBasePath();
        if (base == null) return;
        VirtualFile build = find(base, "build.gradle.kts");
        if (build == null) build = find(base, "build.gradle");
        if (build != null) FileEditorManager.getInstance(project).openFile(build, true);
    }
    private static VirtualFile find(String base, String name) {
        return LocalFileSystem.getInstance().findFileByPath(base + "/" + name);
    }
    @Override public boolean startInWriteAction() { return false; }
}
