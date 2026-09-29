package labeling;

import org.eclipse.jgit.api.BlameCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.ZoneId;

import static manager.javaClassScannerManager.JavaClassScanner.isJavaProductionFile;

/**
 * Implementazione base dell'algoritmo SZZ.
 * -
 * A partire da un fixing commit:
 * - confronta il fixing commit con il suo primo parent;
 * - individua le righe rimosse o sostituite dal fix;
 * - esegue il blame sulla versione precedente del file;
 * - recupera i commit che avevano introdotto quelle righe.
 * -
 * Il risultato mantiene l'associazione tra classe Java e
 * possibili bug-inducing commit.
 */
public final class SzzAnalyzer {

    private final Repository repository;

    public SzzAnalyzer(
            Repository repository) {

        this.repository =
                repository;
    }

    /**
     * Trova i possibili bug-inducing commit relativi
     * a un fixing commit.
     *
     * @param fixingCommit commit che corregge il bug
     * @return mappa classPath -> bug-inducing commits
     */
    public Map<String, Set<RevCommit>> findInducingCommits(
            RevCommit fixingCommit) throws Exception {

        return analyseFixingCommit(fixingCommit).inducingCommitsByClass();
    }

    /**
     * Variante conservata per i check: delega al metodo che include i source
     * path storici, cosi' il report puo' mostrare esattamente l'evidenza usata
     * anche dal resolver del dataset.
     */
    public SzzDiagnosticResult analyseFixingCommitForDiagnostics(
            RevCommit fixingCommit) throws Exception {

        return analyseFixingCommitWithSourcePaths(fixingCommit);
    }

    /**
     * Esegue SZZ mantenendo anche i source path restituiti dal blame con
     * rename-following. DatasetBuilder usa questa evidenza per il resolver
     * storico: non basta infatti che l'intervallo Jira includa una release,
     * la classe deve anche appartenere alla sua ancestry Git.
     */
    public SzzDiagnosticResult analyseFixingCommitWithSourcePaths(
            RevCommit fixingCommit) throws Exception {

        return analyseFixingCommit(fixingCommit, true);
    }

    /**
     * Variante diagnostica di SZZ. Mantiene i contatori necessari a capire
     * perche' un fixing commit genera, oppure non genera, commit inducing.
     */
    public SzzDiagnosticResult analyseFixingCommit(
            RevCommit fixingCommit) throws Exception {

        return analyseFixingCommit(fixingCommit, false);
    }

    private SzzDiagnosticResult analyseFixingCommit(
            RevCommit fixingCommit,
            boolean followFileRenames) throws Exception {

        Map<String, Set<RevCommit>> result =
                new LinkedHashMap<>();

        int changedFiles = 0;
        int productionJavaFiles = 0;
        int edits = 0;
        int ignoredInsertEdits = 0;
        int analysedParentLines = 0;
        List<SzzLineDiagnostic> analysedLines = new ArrayList<>();

        /*
         * Un commit senza parent non può essere analizzato
         * con SZZ perché non esiste una versione precedente.
         */
        if (fixingCommit.getParentCount() == 0) {
            return new SzzDiagnosticResult(result, changedFiles, productionJavaFiles,
                    edits, ignoredInsertEdits, analysedParentLines, analysedLines);
        }

        try (RevWalk walk =
                     new RevWalk(repository);

             DiffFormatter diff =
                     new DiffFormatter(
                             DisabledOutputStream.INSTANCE)) {

            RevCommit current =
                    walk.parseCommit(
                            fixingCommit.getId());

            RevCommit parent =
                    walk.parseCommit(
                            current
                                    .getParent(0)
                                    .getId());

            diff.setRepository(repository);

            List<DiffEntry> entries =
                    diff.scan(
                            parent.getTree(),
                            current.getTree());

            for (DiffEntry entry : entries) {

                changedFiles++;

                String oldPath =
                        entry.getOldPath();

                /*
                 * SZZ viene applicato solo ai file Java
                 * di produzione.
                 */
                if (!isJavaProductionFile(oldPath)) {
                    continue;
                }

                productionJavaFiles++;

                /*
                 * Un file appena aggiunto dal fixing commit
                 * non contiene righe precedenti sulle quali
                 * eseguire blame.
                 */
                if (entry.getChangeType()
                        == DiffEntry.ChangeType.ADD) {

                    continue;
                }

                List<Edit> fileEdits =
                        diff
                                .toFileHeader(entry)
                                .toEditList();

                if (fileEdits.isEmpty()) {
                    continue;
                }

                edits += fileEdits.size();

                /*
                 * Il blame deve essere eseguito sul parent
                 * del fixing commit, cioè sul codice prima
                 * della correzione.
                 */
                BlameResult blame =
                        createBlame(
                                parent,
                                oldPath,
                                followFileRenames);

                if (blame == null) {
                    continue;
                }

                for (Edit edit : fileEdits) {

                    /*
                     * SZZ considera le righe presenti nella
                     * vecchia versione che vengono eliminate
                     * o sostituite dal fixing commit.
                     *
                     * Un INSERT puro non possiede righe
                     * precedenti sulle quali fare blame.
                     */
                    if (edit.getType()
                            == Edit.Type.INSERT) {

                        ignoredInsertEdits++;

                        continue;
                    }

                    analysedParentLines += edit.getEndA() - edit.getBeginA();

                    analyseChangedLines(
                            oldPath,
                            edit,
                            blame,
                            result,
                            analysedLines);
                }
            }
        }

        return new SzzDiagnosticResult(result, changedFiles, productionJavaFiles,
                edits, ignoredInsertEdits, analysedParentLines, analysedLines);
    }

    /**
     * Esegue il blame di un file nello stato immediatamente
     * precedente al fixing commit.
     */
    private BlameResult createBlame(
            RevCommit parent,
            String path,
            boolean followFileRenames) throws Exception {

        BlameCommand blameCommand =
                new BlameCommand(repository);

        blameCommand.setStartCommit(
                parent);

        blameCommand.setFilePath(
                path);

        // Il source path storico viene raccolto per il resolver del labeling.
        // Non assegna da solo una label: DatasetBuilder controlla comunque
        // ancestry, presenza nella release e unicita' del possibile mapping.
        blameCommand.setFollowFileRenames(
                followFileRenames);

        return blameCommand.call();
    }

    /**
     * Recupera il commit responsabile di ogni riga
     * cancellata o sostituita dal fixing commit.
     */
    private void analyseChangedLines(
            String classPath,
            Edit edit,
            BlameResult blame,
            Map<String, Set<RevCommit>> result,
            List<SzzLineDiagnostic> analysedLines) {

        for (int line =
             edit.getBeginA();
             line < edit.getEndA();
             line++) {

            RevCommit inducingCommit =
                    blame.getSourceCommit(line);

            // JGit usa indici zero-based; nel report mostriamo il numero di
            // riga che si vede normalmente in un editor (quindi +1).
            analysedLines.add(new SzzLineDiagnostic(
                    classPath,
                    line + 1,
                    edit.getType().name(),
                    inducingCommit == null ? "<nessun commit>" : inducingCommit.getName(),
                    inducingCommit == null ? null : inducingCommit.getCommitterIdent().getWhen().toInstant()
                            .atZone(ZoneId.systemDefault()).toLocalDate(),
                    blame.getSourcePath(line)));

            if (inducingCommit == null) {
                continue;
            }

            result
                    .computeIfAbsent(
                            classPath,
                            ignored ->
                                    new LinkedHashSet<>())
                    .add(inducingCommit);
        }
    }
}
