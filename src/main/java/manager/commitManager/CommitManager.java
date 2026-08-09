package manager.commitManager;

import config.Config;
import model.ClassChanges;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;

/** Cerca i fixing commit con la chiave Jira e ne ricava le classi Java toccate. */
public final class CommitManager {
    /**
     * La ricerca usa il messaggio completo, non solo la prima riga: alcuni
     * sviluppatori inseriscono STORM-XXXX nel corpo del commit.
     */
    public List<RevCommit> findFixCommits(String ticketKey) throws Exception {
        requireRepository();
        try (Repository repository = openRepository(); Git git = new Git(repository)) {
            List<RevCommit> matching = new ArrayList<>();
            for (RevCommit commit : git.log().call()) {
                if (commit.getFullMessage().contains(ticketKey)) matching.add(commit);
            }
            return matching;
        }
    }

    /**
     * Variante pensata per l'analisi preliminare: percorre la storia una sola
     * volta. Con centinaia di ticket evita di moltiplicare inutilmente il costo
     * della stessa scansione Git.
     */
    public Map<String, List<RevCommit>> findFixCommits(Set<String> ticketKeys) throws Exception {
        requireRepository();
        Map<String, List<RevCommit>> result = new LinkedHashMap<>();
        for (String key : ticketKeys) result.put(key, new ArrayList<>());
        try (Repository repository = openRepository(); Git git = new Git(repository)) {
            for (RevCommit commit : git.log().call()) {
                String message = commit.getFullMessage();
                for (String key : ticketKeys) if (message.contains(key)) result.get(key).add(commit);
            }
        }
        return result;
    }

    /**
     * Restituisce i path Java cambiati in tutti i commit del ticket. Un ticket
     * senza classi Java non e' utile al dataset a livello di classe.
     */
    public Set<String> findTouchedJavaClasses(List<RevCommit> commits) throws Exception {
        requireRepository();
        Set<String> classes = new LinkedHashSet<>();
        try (Repository repository = openRepository()) {
            for (RevCommit commit : commits) {
                if (commit.getParentCount() == 0) continue; // Il commit iniziale non ha un confronto sensato.
                try (RevWalk walk = new RevWalk(repository);
                     DiffFormatter diff = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                    // I commit arrivano dalla scansione precedente, che usa un altro RevWalk.
                    // Li ricarichiamo nel repository corrente prima di leggere albero e genitore.
                    RevCommit current = walk.parseCommit(commit.getId());
                    RevCommit parent = walk.parseCommit(current.getParent(0));
                    diff.setRepository(repository);
                    for (DiffEntry entry : diff.scan(parent.getTree(), current.getTree())) {
                        String path = entry.getChangeType() == DiffEntry.ChangeType.DELETE ? entry.getOldPath() : entry.getNewPath();
                        if (path.endsWith(".java") && !path.contains("/src/test/")) classes.add(path);
                    }
                }
            }
        }
        return classes;
    }

    private static Repository openRepository() throws IOException {
        return new FileRepositoryBuilder().setGitDir(Config.REPOSITORY.resolve(".git").toFile()).build();
    }

    private static void requireRepository() {
        if (!Files.isDirectory(Config.REPOSITORY.resolve(".git")))
            throw new IllegalStateException("Manca il clone Storm in " + Config.REPOSITORY.toAbsolutePath());
    }

    // Per ottenere la storia di una release, restituendo i commit precedenti
    private static List<RevCommit> walkCommits(RevWalk walk, RevCommit start) throws Exception {

        List<RevCommit> commits = new ArrayList<>();

        walk.markStart(start);

        for (RevCommit commit : walk) {
            commits.add(commit);
        }

        return commits;
    }

    /**
     * Restituisce le modifiche di una classe nella storia Git fino alla release
     * indicata.
     */
    /**
     * Analizza la storia Git una sola volta e raccoglie le modifiche
     * per ogni classe Java.
     */
    public Map<String, List<ClassChanges>> getClassChanges(
            RevCommit releaseCommit) throws Exception {

        requireRepository();

        Map<String, List<ClassChanges>> result = new LinkedHashMap<>();

        try (Repository repository = openRepository()) {

            List<RevCommit> commits =
                    getHistory(repository, releaseCommit);

            System.out.println(
                    "Commit da analizzare: " + commits.size());

            for (RevCommit commit : commits) {

                if (commit.getParentCount() == 0) {
                    continue;
                }

                RevCommit parent;

                try (RevWalk walk = new RevWalk(repository)) {
                    parent =
                            walk.parseCommit(
                                    commit.getParent(0).getId());
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
                            countJavaFiles(entries);

                    for (DiffEntry entry : entries) {

                        String path =
                                entry.getChangeType()
                                        == DiffEntry.ChangeType.DELETE
                                        ? entry.getOldPath()
                                        : entry.getNewPath();

                        if (!path.endsWith(".java")
                                || path.contains("/src/test/")) {
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
                                        commit.getAuthorIdent()
                                                .getName(),
                                        commit.getAuthorIdent()
                                                .getWhen()
                                                .toInstant()
                                                .atZone(
                                                        java.time.ZoneId
                                                                .systemDefault())
                                                .toLocalDateTime(),
                                        lines[0],
                                        lines[1],
                                        changeSetSize,
                                        false);

                        result
                                .computeIfAbsent(
                                        path,
                                        key -> new ArrayList<>())
                                .add(change);
                    }
                }
            }
        }

        return result;
    }
    private static int[] countChangedLines(
            DiffFormatter diff,
            DiffEntry entry) throws Exception {

        var fileHeader = diff.toFileHeader(entry);

        int added = 0;
        int deleted = 0;

        for (var edit : fileHeader.toEditList()) {

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

        return new int[]{added, deleted};
    }

    private static int countJavaFiles(
            List<DiffEntry> entries) {

        int count = 0;

        for (DiffEntry entry : entries) {

            String path =
                    entry.getChangeType()
                            == DiffEntry.ChangeType.DELETE
                            ? entry.getOldPath()
                            : entry.getNewPath();

            if (path.endsWith(".java")
                    && !path.contains("/src/test/")) {
                count++;
            }
        }

        return count;
    }

    // Restituisce la storia dei commit fino al commit di partenza, in ordine cronologico inverso (dal più recente al più vecchio).
    private static List<RevCommit> getHistory(
            Repository repository,
            RevCommit start) throws Exception {

        List<RevCommit> commits = new ArrayList<>();

        try (RevWalk walk = new RevWalk(repository)) {

            RevCommit parsed =
                    walk.parseCommit(start.getId());

            walk.markStart(parsed);

            for (RevCommit commit : walk) {
                commits.add(commit);
            }
        }

        return commits;
    }
}

