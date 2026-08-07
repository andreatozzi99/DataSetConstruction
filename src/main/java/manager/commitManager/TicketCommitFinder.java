package manager.commitManager;

import config.Config;
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
public final class TicketCommitFinder {
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
}
