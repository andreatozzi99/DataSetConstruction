package labeling;

import org.eclipse.jgit.revwalk.RevCommit;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dati di osservazione prodotti durante SZZ per rendere il risultato verificabile. */
public record SzzDiagnosticResult(
        Map<String, Set<RevCommit>> inducingCommitsByClass,
        int changedFiles,
        int productionJavaFiles,
        int edits,
        int ignoredInsertEdits,
        int analysedParentLines,
        List<SzzLineDiagnostic> analysedLines) {
}
