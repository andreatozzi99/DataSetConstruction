package manager.javaClassScannerManager;

import config.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Individua tutte le classi Java presenti nella release corrente.
 */
public final class JavaClassScanner {

    /**
     * Restituisce tutti i file .java presenti nella repository.
     */
    public List<Path> findJavaFiles(Path repositoryPath) {

        try (Stream<Path> stream = Files.walk(repositoryPath)) {

            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.naturalOrder())
                    .toList();

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Errore durante la scansione della repository.", e);
        }
    }

    /**
     * Restituisce il nome della classe nel formato scelto nel Config.
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

    /**
     * Rimuove la parte iniziale del repository fino alla cartella del package.
     * Se non viene trovata "src", restituisce comunque il percorso relativo.
     */
    private String buildPackageRelative(Path repositoryPath, Path javaFile) {

        Path relative = repositoryPath.relativize(javaFile);

        int srcIndex = -1;

        for (int i = 0; i < relative.getNameCount(); i++) {
            if (relative.getName(i).toString().equals("src")) {
                srcIndex = i;
                break;
            }
        }

        if (srcIndex == -1) {
            return relative.toString().replace('\\', '/');
        }

        /*
         * salta:
         * src
         * main / jvm / test / java ...
         */

        int start = Math.min(srcIndex + 3, relative.getNameCount() - 1);

        return relative.subpath(start, relative.getNameCount())
                .toString()
                .replace('\\', '/');
    }

}