package manager.javaClassScannerManager;

import config.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Cerca i file Java presenti nella repository della release corrente.
 */
public final class JavaClassScanner {

    /**
     * Restituisce tutti i file Java presenti nella repository.
     */
    public List<Path> findJavaFiles(Path repositoryPath) {

        try (Stream<Path> files = Files.walk(repositoryPath)) {

            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> Config.INCLUDE_TESTS || !isTestFile(path))
                    .sorted(Comparator.naturalOrder())
                    .toList();

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Errore durante la scansione della repository.",
                    exception);
        }
    }

    /**
     * Restituisce il percorso della classe relativo alla repository.
     */
    public String getClassName(Path repositoryPath, Path javaFile) {

        return switch (Config.CLASS_NAME_FORMAT) {

            case REPOSITORY_RELATIVE ->
                    repositoryPath.relativize(javaFile)
                            .toString()
                            .replace('\\', '/');

            case PACKAGE_RELATIVE ->
                    buildPackageRelative(repositoryPath, javaFile);
        };
    }

    private boolean isTestFile(Path path) {

        String normalized =
                path.toString().replace('\\', '/');

        return normalized.contains("/src/test/");
    }

    private String buildPackageRelative(
            Path repositoryPath,
            Path javaFile) {

        Path relative = repositoryPath.relativize(javaFile);

        int srcIndex = -1;

        for (int i = 0; i < relative.getNameCount(); i++) {
            if ("src".equals(relative.getName(i).toString())) {
                srcIndex = i;
                break;
            }
        }

        if (srcIndex == -1) {
            return relative.toString().replace('\\', '/');
        }

        int start = Math.min(
                srcIndex + 3,
                relative.getNameCount() - 1);

        return relative.subpath(start, relative.getNameCount())
                .toString()
                .replace('\\', '/');
    }
}