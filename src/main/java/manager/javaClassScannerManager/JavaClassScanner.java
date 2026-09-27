package manager.javaClassScannerManager;

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
 * Il manager si occupa esclusivamente della scansione dei
 * file Java di produzione e della costruzione del loro percorso
 * identificativo.
 */
public final class JavaClassScanner {

    /**
     * Restituisce tutti i file Java di produzione presenti nel repository.
     * I file di test sono esclusi dall'intera pipeline.
     */
    public List<Path> findJavaFiles(Path repositoryPath) {

        try (Stream<Path> files = Files.walk(repositoryPath)) {

            return files
                    .filter(Files::isRegularFile)
                    .filter(path ->
                            isJavaProductionFile(
                                    path.toString()))
                    .sorted(Comparator.naturalOrder())
                    .toList();

        } catch (IOException exception) {

            throw new IllegalStateException(
                    "Errore durante la scansione della repository.",
                    exception);
        }
    }

    /**
     * Restituisce il percorso relativo alla radice del repository.
     * Questo è il formato usato anche da Git e SZZ per associare la
     * storia delle modifiche alle righe del dataset.
     */
    public String getClassName(
            Path repositoryPath,
            Path javaFile) {

        return repositoryPath
                .relativize(javaFile)
                .toString()
                .replace('\\', '/');
    }

    /**
     * Controlla che il path appartenga a un file Java di produzione.
     * Il test è basato sulle directory denominate {@code test}, sia per
     * path relativi Git sia per path assoluti del filesystem.
     */
    public static boolean isJavaProductionFile(
            String path) {

        if (path == null) {
            return false;
        }

        String normalized =
                path.replace('\\', '/');

        return normalized.endsWith(".java")
                && !normalized.startsWith("test/")
                && !normalized.contains("/test/");
    }
}
