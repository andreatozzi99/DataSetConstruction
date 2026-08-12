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
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        Map<String, Set<RevCommit>> result =
                new LinkedHashMap<>();

        /*
         * Un commit senza parent non può essere analizzato
         * con SZZ perché non esiste una versione precedente.
         */
        if (fixingCommit.getParentCount() == 0) {
            return result;
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

                String oldPath =
                        entry.getOldPath();

                /*
                 * SZZ viene applicato solo ai file Java
                 * di produzione.
                 */
                if (!isJavaProductionFile(oldPath)) {
                    continue;
                }

                /*
                 * Un file appena aggiunto dal fixing commit
                 * non contiene righe precedenti sulle quali
                 * eseguire blame.
                 */
                if (entry.getChangeType()
                        == DiffEntry.ChangeType.ADD) {

                    continue;
                }

                List<Edit> edits =
                        diff
                                .toFileHeader(entry)
                                .toEditList();

                if (edits.isEmpty()) {
                    continue;
                }

                /*
                 * Il blame deve essere eseguito sul parent
                 * del fixing commit, cioè sul codice prima
                 * della correzione.
                 */
                BlameResult blame =
                        createBlame(
                                parent,
                                oldPath);

                if (blame == null) {
                    continue;
                }

                for (Edit edit : edits) {

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

                        continue;
                    }

                    analyseChangedLines(
                            oldPath,
                            edit,
                            blame,
                            result);
                }
            }
        }

        return result;
    }

    /**
     * Esegue il blame di un file nello stato immediatamente
     * precedente al fixing commit.
     */
    private BlameResult createBlame(
            RevCommit parent,
            String path) throws Exception {

        BlameCommand blameCommand =
                new BlameCommand(repository);

        blameCommand.setStartCommit(
                parent);

        blameCommand.setFilePath(
                path);

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
            Map<String, Set<RevCommit>> result) {

        for (int line =
             edit.getBeginA();
             line < edit.getEndA();
             line++) {

            RevCommit inducingCommit =
                    blame.getSourceCommit(line);

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