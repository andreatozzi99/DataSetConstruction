package Utils;

import config.Config;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.IOException;
import java.nio.file.Files;

public final class GitRepositoryUtils {

    private GitRepositoryUtils() {
    }

    public static Repository openRepository() throws IOException {
        requireRepository();

        return new FileRepositoryBuilder()
                .setGitDir(
                        Config.REPOSITORY
                                .resolve(".git")
                                .toFile())
                .build();
    }

    public static void requireRepository() {
        if (!Files.isDirectory(
                Config.REPOSITORY.resolve(".git"))) {

            throw new IllegalStateException(
                    "Manca il clone Storm in "
                            + Config.REPOSITORY.toAbsolutePath());
        }
    }
}