package ru.privateserver.ksl.intellij.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import ru.privateserver.ksl.intellij.inspection.KslPreflightAnalyzer;
import ru.privateserver.ksl.intellij.inspection.KslPreflightAnnotator;
import java.util.List;

/** Explicit command to run safe static checks over the current .kts file. */
public final class KslPreflightAction extends AnAction {
    @Override public void update(@NotNull AnActionEvent event) {
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        event.getPresentation().setEnabledAndVisible(file != null && file.getName().endsWith(".kts"));
    }
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        if (file == null || event.getProject() == null) return;
        List<KslPreflightAnalyzer.Diagnostic> diagnostics = KslPreflightAnalyzer.analyze(
            file.getText(), KslPreflightAnnotator.readBuildConfiguration(event.getProject()));
        if (diagnostics.isEmpty()) {
            Messages.showInfoMessage(event.getProject(),
                "No issues were found by KSL static preflight checks. This is not a full Kotlin compilation.",
                "KSL Preflight");
            return;
        }
        StringBuilder message = new StringBuilder();
        long errors = diagnostics.stream().filter(KslPreflightAnalyzer.Diagnostic::error).count();
        message.append("Found ").append(diagnostics.size()).append(" item(s), ")
            .append(errors).append(" error(s).\n\n");
        for (KslPreflightAnalyzer.Diagnostic diagnostic : diagnostics) {
            message.append(diagnostic.error() ? "ERROR" : "WARNING")
                .append(" · ").append(diagnostic.message()).append('\n');
        }
        Messages.showWarningDialog(event.getProject(), message.toString(), "KSL Preflight");
    }
}
