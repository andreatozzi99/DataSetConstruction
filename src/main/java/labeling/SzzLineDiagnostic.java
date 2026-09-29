package labeling;

import java.time.LocalDate;

/**
 * Una singola riga della versione precedente al fix osservata da SZZ.
 *
 * Questo oggetto non influisce sull'algoritmo: conserva solo le informazioni
 * che consentono al check di mostrare, in modo verificabile, quale riga del
 * parent e quale commit siano stati restituiti da JGit Blame.
 */
public record SzzLineDiagnostic(
        String filePath,
        int oldLineNumber,
        String editType,
        String inducingCommitId,
        LocalDate inducingCommitDate,
        String sourcePath) {
}
