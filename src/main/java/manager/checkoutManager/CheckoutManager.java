package manager.checkoutManager;

import config.Config;
import model.Release;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Gestisce il checkout temporaneo di una release senza usare comandi di shell. */
public final class CheckoutManager {
    private final Path repositoryPath;
    private String originalReference;

    /** Il costruttore normale usa il clone Storm previsto in Config. */
    public CheckoutManager() {
        this(Config.REPOSITORY);
    }

    /** Il costruttore alternativo rende la classe testabile con un clone di prova. */
    public CheckoutManager(Path repositoryPath) {
        this.repositoryPath = repositoryPath;
    }

    /**
     * Posiziona il clone sul tag della release. Viene rifiutato un repository
     * con modifiche locali: un checkout non deve mai cancellare lavoro umano.
     */
    public void checkoutRelease(Release release) {
        requireRepository();
        try (Repository repository = openRepository(); Git git = new Git(repository)) {
            if (!git.status().call().isClean()) {
                throw new IllegalStateException("Il clone contiene modifiche locali: salvale prima del checkout.");
            }
            if (originalReference == null) {
                originalReference = repository.getFullBranch();
            }

            String tag = findTag(git, release.name()).orElseThrow(() ->
                    new IllegalArgumentException("Nessun tag Git trovato per la release " + release.name()));
            // Il checkout sul tag e' volutamente detached: stiamo leggendo una fotografia storica.
            git.checkout().setName(tag).call();
        } catch (Exception exception) {
            throw new IllegalStateException("Checkout non riuscito per " + release.name(), exception);
        }
    }

    /** Restituisce la directory su cui gli estrattori possono eseguire la scansione dei file. */
    public File getWorkingDirectory() {
        requireRepository();
        return repositoryPath.toFile();
    }

    /**
     * Riporta il clone al riferimento da cui siamo partiti. Non esegue reset o
     * clean: quei comandi eliminerebbero file dell'utente e non sono necessari.
     */
    public void cleanRepository() {
        if (originalReference == null) {
            return;
        }
        try (Repository repository = openRepository(); Git git = new Git(repository)) {
            git.checkout().setName(originalReference).call();
            originalReference = null;
        } catch (Exception exception) {
            throw new IllegalStateException("Impossibile ripristinare il riferimento iniziale del clone.", exception);
        }
    }

    private Repository openRepository() throws IOException {
        return new FileRepositoryBuilder().setGitDir(repositoryPath.resolve(".git").toFile()).build();
    }

    private void requireRepository() {
        if (!Files.isDirectory(repositoryPath.resolve(".git"))) {
            throw new IllegalStateException("Clone Storm non trovato in " + repositoryPath.toAbsolutePath());
        }
    }

    public Path getRepositoryPath() {
        return repositoryPath;
    }
    private static Optional<String> findTag(Git git, String releaseName) throws Exception {
        for (Ref ref : git.tagList().call()) {
            String tag = Repository.shortenRefName(ref.getName());
            if (tag.equals(releaseName) || tag.equals("v" + releaseName)) {
                return Optional.of(tag);
            }
        }
        return Optional.empty();
    }

    public void printTags() {
        try (Repository repository = openRepository();
             Git git = new Git(repository)) {

            System.out.println("=== TAG GIT ===");

            for (Ref ref : git.tagList().call()) {
                System.out.println(Repository.shortenRefName(ref.getName()));
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
