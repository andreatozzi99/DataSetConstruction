package manager.checkoutManager;

import config.Config;
import model.Release;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Gestisce il checkout temporaneo di una release.
 */
public final class CheckoutManager {

    private final Path repositoryPath;
    private String originalReference;

    /**
     * Usa il repository configurato in Config.
     */
    public CheckoutManager() {
        this(Config.REPOSITORY);
    }

    /**
     * Permette di specificare un repository alternativo.
     */
    public CheckoutManager(Path repositoryPath) {
        this.repositoryPath = repositoryPath;
    }

    /**
     * Posiziona il repository sul tag della release.
     */
    public void checkoutRelease(Release release) {

        try (Repository repository = openRepository();
             Git git = new Git(repository)) {

            if (!git.status().call().isClean()) {
                throw new IllegalStateException(
                        "Il clone contiene modifiche locali: "
                                + "salvale prima del checkout.");
            }

            if (originalReference == null) {
                originalReference = repository.getFullBranch();
            }

            String tag =
                    findTag(git, release.name())
                            .orElseThrow(() ->
                                    new IllegalArgumentException(
                                            "Nessun tag Git trovato per la release "
                                                    + release.name()));

            git.checkout()
                    .setName(tag)
                    .call();

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Checkout non riuscito per "
                            + release.name(),
                    exception);
        }
    }

    /**
     * Restituisce la directory del repository.
     */
    public File getWorkingDirectory() {
        return repositoryPath.toFile();
    }

    /**
     * Ripristina il riferimento originale.
     */
    public void cleanRepository() {

        if (originalReference == null) {
            return;
        }

        try (Repository repository = openRepository();
             Git git = new Git(repository)) {

            git.checkout()
                    .setName(originalReference)
                    .call();

            originalReference = null;

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Impossibile ripristinare il riferimento "
                            + "iniziale del clone.",
                    exception);
        }
    }

    public Path getRepositoryPath() {
        return repositoryPath;
    }

    /**
     * Apre il repository locale.
     */
    private Repository openRepository() throws Exception {

        return new org.eclipse.jgit.storage.file
                .FileRepositoryBuilder()
                .setGitDir(
                        repositoryPath
                                .resolve(".git")
                                .toFile())
                .readEnvironment()
                .findGitDir()
                .build();
    }

    /**
     * Cerca il tag della release.
     *
     * Supporta:
     * 0.9.0.1
     * v0.9.0.1
     */
    public static Optional<String> findTag(
            Git git,
            String releaseName) throws Exception {

        for (Ref ref : git.tagList().call()) {

            String tag =
                    Repository.shortenRefName(
                            ref.getName());

            if (tag.equals(releaseName)
                    || tag.equals("v" + releaseName)) {

                return Optional.of(tag);
            }
        }

        return Optional.empty();
    }
}