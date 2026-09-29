package manager.commitManager;

import config.Config;
import model.ClassChanges;
import model.Release;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static manager.checkoutManager.CheckoutManager.findTag;
import static manager.javaClassScannerManager.JavaClassScanner.isJavaProductionFile;

/**
 * Gestisce l'analisi della storia Git del progetto.
 * -
 * Responsabilità:
 * - trovare i commit associati a ticket Jira;
 * - individuare le classi Java modificate dai commit;
 * - recuperare il commit associato a una release;
 * - costruire la storia delle modifiche delle classi fino a una release.
 * -
 * La gestione fisica del repository è delegata a GitRepositoryUtils.
 */
public final class CommitManager {

    /**
     * Cerca i commit che contengono nel messaggio
     * una delle chiavi Jira specificate.
     */
    public Map<String, List<RevCommit>> findFixCommits(
            Set<String> ticketKeys) throws Exception {

        Utils.GitRepositoryUtils.requireRepository();

        Map<String, List<RevCommit>> result =
                new LinkedHashMap<>();

        for (String key : ticketKeys) {
            result.put(key, new ArrayList<>());
        }

        /*
         * Estrae dal messaggio solamente chiavi Jira complete,
         * evitando match parziali come:
         *
         * PROJECT-1 -> PROJECT-123
         */
        java.util.regex.Pattern jiraPattern =
                java.util.regex.Pattern.compile(
                        "\\b"
                                + java.util.regex.Pattern.quote(
                                        Config.PROJECT_KEY)
                                + "-\\d+\\b",
                        java.util.regex.Pattern.CASE_INSENSITIVE
                );

        try (Repository repository =
                     Utils.GitRepositoryUtils.openRepository();
             Git git = new Git(repository)) {

            for (RevCommit commit : git.log().call()) {

                String message =
                        commit.getFullMessage();

                java.util.regex.Matcher matcher =
                        jiraPattern.matcher(message);

                Set<String> keysFound =
                        new LinkedHashSet<>();

                while (matcher.find()) {
                    keysFound.add(
                            matcher.group().toUpperCase());
                }

                for (String key : keysFound) {

                    if (ticketKeys.contains(key)) {
                        result.get(key).add(commit);
                    }
                }
            }
        }

        return result;
    }

    /**
     * Restituisce i path delle classi Java
     * modificate dai commit indicati.
     */
    public Set<String> findTouchedJavaClasses(
            List<RevCommit> commits) throws Exception {

        Utils.GitRepositoryUtils.requireRepository();

        Set<String> classes =
                new LinkedHashSet<>();

        try (Repository repository =
                     Utils.GitRepositoryUtils.openRepository()) {

            for (RevCommit commit : commits) {

                if (commit.getParentCount() == 0) {
                    continue;
                }

                try (RevWalk walk =
                             new RevWalk(repository);
                     DiffFormatter diff =
                             new DiffFormatter(
                                     DisabledOutputStream.INSTANCE)) {

                    RevCommit current =
                            walk.parseCommit(
                                    commit.getId());

                    RevCommit parent =
                            walk.parseCommit(
                                    current
                                            .getParent(0)
                                            .getId());

                    diff.setRepository(repository);

                    for (DiffEntry entry :
                            diff.scan(
                                    parent.getTree(),
                                    current.getTree())) {

                        String path =
                                entry.getChangeType()
                                        == DiffEntry.ChangeType.DELETE
                                        ? entry.getOldPath()
                                        : entry.getNewPath();

                        if (isJavaProductionFile(path)) {
                            classes.add(path);
                        }
                    }
                }
            }
        }

        return classes;
    }

    /**
     * Variante utilizzata quando non interessa
     * distinguere i fixing commit.
     * -
     * Mantiene compatibili i test che usano ancora:
     * getClassChanges(releaseCommit)
     */
    public Map<String, List<ClassChanges>> getClassChanges(
            RevCommit releaseCommit) throws Exception {

        return getClassChanges(
                releaseCommit,
                Set.of());
    }

    /**
     * Restituisce tutte le modifiche alle classi Java
     * presenti nella storia fino al commit della release.
     * -
     * I commit presenti in fixCommitIds vengono marcati
     * come fixing commit nei ClassChanges.
     * -
     * La chiave della mappa è il path della classe.
     */
    public Map<String, List<ClassChanges>> getClassChanges(
            RevCommit releaseCommit,
            Set<String> fixCommitIds) throws Exception {

        Utils.GitRepositoryUtils.requireRepository();

        Map<String, List<ClassChanges>> result =
                new LinkedHashMap<>();

        try (Repository repository =
                     Utils.GitRepositoryUtils.openRepository()) {

            List<RevCommit> commits =
                    getHistory(
                            repository,
                            releaseCommit);

            // Questo e' l'intero storico raggiungibile dal tag della release:
            // il ciclo successivo ne terra' soltanto i commit che modificano
            // almeno un file Java di produzione.
            System.out.println(
                    "Commit Git raggiungibili fino alla release: "
                            + commits.size());

            for (RevCommit commit : commits) {

                if (commit.getParentCount() == 0) {
                    continue;
                }

                RevCommit parent;

                try (RevWalk walk =
                             new RevWalk(repository)) {

                    parent =
                            walk.parseCommit(
                                    commit
                                            .getParent(0)
                                            .getId());
                }

                try (DiffFormatter diff =
                             new DiffFormatter(
                                     DisabledOutputStream.INSTANCE)) {

                    diff.setRepository(repository);

                    List<DiffEntry> entries =
                            diff.scan(
                                    parent.getTree(),
                                    commit.getTree());

                    int changeSetSize =
                            entries.size();

                    if (!containsJavaProductionFile(entries)) {
                        continue;
                    }

                    boolean fixCommit =
                            fixCommitIds.contains(
                                    commit.getName());

                    for (DiffEntry entry : entries) {

                        String path =
                                entry.getChangeType()
                                        == DiffEntry.ChangeType.DELETE
                                        ? entry.getOldPath()
                                        : entry.getNewPath();

                        if (!isJavaProductionFile(path)) {
                            continue;
                        }

                        int[] lines =
                                countChangedLines(
                                        diff,
                                        entry);

                        ClassChanges change =
                                new ClassChanges(
                                        path,
                                        commit.getName(),
                                        commit
                                                .getAuthorIdent()
                                                .getName(),
                                        commit
                                                .getAuthorIdent()
                                                .getWhen()
                                                .toInstant()
                                                .atZone(
                                                        java.time.ZoneId
                                                                .systemDefault())
                                                .toLocalDateTime(),
                                        lines[0],
                                        lines[1],
                                        changeSetSize,
                                        fixCommit
                                );

                        result.computeIfAbsent(
                                        path,
                                        ignored ->
                                                new ArrayList<>())
                                .add(change);
                    }
                }
            }
        }

        return result;
    }

    /**
     * Restituisce il commit associato
     * al tag Git della release.
     */
    public static RevCommit getReleaseCommit(
            Release release) throws Exception {

        Utils.GitRepositoryUtils.requireRepository();

        try (Repository repository =
                     Utils.GitRepositoryUtils.openRepository();
             Git git = new Git(repository);
             RevWalk walk =
                     new RevWalk(repository)) {

            String tag =
                    findTag(
                            git,
                            release.name())
                            .orElseThrow(() ->
                                    new IllegalArgumentException(
                                            "Nessun tag trovato per la release "
                                                    + release.name()));

            Ref ref =
                    repository.findRef(tag);

            if (ref == null) {

                throw new IllegalStateException(
                        "Riferimento Git non trovato: "
                                + tag);
            }

            return walk.parseCommit(
                    repository.resolve(
                            ref.getName()));
        }
    }

    /**
     * Conta le linee aggiunte e cancellate
     * da una singola modifica.
     * -
     * [0] = linee aggiunte
     * [1] = linee cancellate
     */
    private static int[] countChangedLines(
            DiffFormatter diff,
            DiffEntry entry) throws Exception {

        var fileHeader =
                diff.toFileHeader(entry);

        int added = 0;
        int deleted = 0;

        for (var edit :
                fileHeader.toEditList()) {

            switch (edit.getType()) {

                case INSERT:
                    added += edit.getLengthB();
                    break;

                case DELETE:
                    deleted += edit.getLengthA();
                    break;

                case REPLACE:
                    deleted += edit.getLengthA();
                    added += edit.getLengthB();
                    break;

                default:
                    break;
            }
        }

        return new int[]{
                added,
                deleted
        };
    }

    /**
     * Verifica se un commit modifica almeno un file Java di produzione.
     * La dimensione del changeset, invece, comprende tutti i file del commit.
     */
    private static boolean containsJavaProductionFile(
            List<DiffEntry> entries) {

        for (DiffEntry entry : entries) {

            String path =
                    entry.getChangeType()
                            == DiffEntry.ChangeType.DELETE
                            ? entry.getOldPath()
                            : entry.getNewPath();

            if (isJavaProductionFile(path)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Restituisce la storia dei commit
     * raggiungibili dal commit della release.
     */
    private static List<RevCommit> getHistory(
            Repository repository,
            RevCommit start) throws Exception {

        List<RevCommit> commits =
                new ArrayList<>();

        try (RevWalk walk =
                     new RevWalk(repository)) {

            RevCommit parsed =
                    walk.parseCommit(
                            start.getId());

            walk.markStart(parsed);

            for (RevCommit commit : walk) {
                commits.add(commit);
            }
        }

        return commits;
    }
}
