package manager.javaClassScannerManager;

import config.Config;
import org.eclipse.jgit.diff.DiffEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Cerca e identifica i file Java presenti nella repository
 * della release corrente.
 *
 * Il manager si occupa esclusivamente della scansione delle
 * classi Java e della costruzione del loro percorso identificativo.
 */
public final class JavaClassScanner {

    /**
     * Restituisce tutti i file Java presenti nella repository.
     *
     * I test vengono esclusi se Config.INCLUDE_TESTS è false.
     */
    public List<Path> findJavaFiles(Path repositoryPath) {

        try (Stream<Path> files = Files.walk(repositoryPath)) {

            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path ->
                            Config.INCLUDE_TESTS || !isTestFile(path))
                    .sorted(Comparator.naturalOrder())
                    .toList();

        } catch (IOException exception) {

            throw new IllegalStateException(
                    "Errore durante la scansione della repository.",
                    exception);
        }
    }

    /**
     * Restituisce il nome/percorso identificativo della classe
     * secondo il formato configurato in Config.
     *
     * Il formato può essere modificato senza cambiare questo manager.
     */
    public String getClassName(
            Path repositoryPath,
            Path javaFile) {

        return switch (Config.CLASS_NAME_FORMAT) {

            case REPOSITORY_RELATIVE ->
                    repositoryPath
                            .relativize(javaFile)
                            .toString()
                            .replace('\\', '/');

            case PACKAGE_RELATIVE ->
                    buildPackageRelative(
                            repositoryPath,
                            javaFile);
        };
    }

    /**
     * Verifica se un file appartiene alla directory dei test.
     */
    private boolean isTestFile(Path path) {

        String normalized =
                path.toString().replace('\\', '/');

        return normalized.contains("/src/test/");
    }

    /**
     * Costruisce il percorso della classe relativo alla parte
     * di package del progetto.
     *
     * Esempio:
     *
     * storm-core/src/jvm/backtype/storm/Config.java
     *
     * diventa:
     *
     * backtype/storm/Config.java
     */
    private String buildPackageRelative(
            Path repositoryPath,
            Path javaFile) {

        Path relative =
                repositoryPath.relativize(javaFile);

        int srcIndex = -1;

        for (int i = 0;
             i < relative.getNameCount();
             i++) {

            if ("src".equals(
                    relative.getName(i).toString())) {

                srcIndex = i;
                break;
            }
        }

        /*
         * Se non troviamo una directory src,
         * manteniamo semplicemente il percorso relativo
         * alla repository.
         */
        if (srcIndex == -1) {

            return relative
                    .toString()
                    .replace('\\', '/');
        }

        /*
         * Dopo "src" normalmente abbiamo:
         *
         * src/jvm/backtype/storm/...
         *
         * quindi saltiamo:
         *
         * src + jvm
         *
         * e manteniamo il package.
         */
        int start = Math.min(
                srcIndex + 2,
                relative.getNameCount() - 1);

        return relative
                .subpath(
                        start,
                        relative.getNameCount())
                .toString()
                .replace('\\', '/');
    }

    /**
     * Controlla che il path appartenga a una classe
     * Java di produzione.
     */
    public static boolean isJavaProductionFile(
            String path) {

        if (path == null
                || DiffEntry.DEV_NULL.equals(path)) {

            return false;
        }

        String normalized =
                path.replace('\\', '/');

        return normalized.endsWith(".java")
                && !normalized.contains("/src/test/");
    }
}