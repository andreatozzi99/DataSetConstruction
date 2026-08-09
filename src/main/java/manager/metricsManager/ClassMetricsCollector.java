package manager.metricsManager;

import config.Config;
import manager.checkoutManager.CheckoutManager;
import manager.javaClassScannerManager.JavaClassScanner;
import model.CKClassMetrics;
import model.CsvRow;
import model.Release;
import manager.commitManager.CommitManager;
import model.ClassChanges;
import org.eclipse.jgit.revwalk.RevCommit;

import java.nio.file.Files;
import java.util.Set;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/**
 * Costruisce le informazioni iniziali delle classi presenti in una release.
 */
public final class ClassMetricsCollector {

    private final CheckoutManager checkoutManager;
    private final JavaClassScanner classScanner;
    private final CKMetrics ckMetrics;

    private final CommitManager commitManager;

    public ClassMetricsCollector(
            CheckoutManager checkoutManager, JavaClassScanner classScanner, CKMetrics ckMetrics,
            CommitManager commitManager) {

        this.checkoutManager = checkoutManager;
        this.classScanner = classScanner;
        this.ckMetrics = ckMetrics;
        this.commitManager = commitManager;
    }


    /**
     * Esegue il checkout della release, individua i file Java
     * e calcola le metriche CK corrispondenti.
     */
    public List<CsvRow> collect(Release release) {

        checkoutManager.checkoutRelease(release);

        try {

            Path repository =
                    checkoutManager
                            .getWorkingDirectory()
                            .toPath();

            List<Path> javaFiles =
                    classScanner.findJavaFiles(repository);

            Map<String, CKClassMetrics> metrics =
                    ckMetrics.calculate(
                            repository,
                            javaFiles);

            List<CsvRow> rows =
                    new ArrayList<>();

            for (Path javaFile : javaFiles) {

                String absolutePath =
                        javaFile.toAbsolutePath()
                                .normalize()
                                .toString();

                CKClassMetrics classMetrics =
                        metrics.get(absolutePath);

                if (classMetrics == null) {
                    System.err.println(
                            "Nessuna metrica CK trovata per "
                                    + javaFile);

                    continue;
                }

                String relativePath =
                        repository
                                .relativize(javaFile)
                                .toString()
                                .replace('\\', '/');

                rows.add(
                        toRow(
                                release,
                                relativePath,
                                classMetrics));
            }

            return rows;

        } finally {

            checkoutManager.cleanRepository();
        }
    }

    private CsvRow toRow(
            Release release,
            String classPath,
            CKClassMetrics metrics) {

        return new CsvRow(
                Config.PROJECT_KEY,
                release.index(),
                release.name(),
                classPath,
                metrics.loc(),
                false
        );
    }

    /**
     * Recupera la storia Git delle classi presenti nella release.
     */
    public List<ClassChanges> collectClassChanges(
            Release release,
            List<Path> javaFiles) throws Exception {

        RevCommit releaseCommit =
                checkoutManager.getReleaseCommit(release);

        Map<String, List<ClassChanges>> allChanges =
                commitManager.getClassChanges(releaseCommit);

        List<ClassChanges> changes =
                new ArrayList<>();

        for (Path file : javaFiles) {

            String classPath =
                    Config.REPOSITORY
                            .relativize(file)
                            .toString()
                            .replace('\\', '/');

            List<ClassChanges> classChanges =
                    allChanges.get(classPath);

            if (classChanges != null) {
                changes.addAll(classChanges);
            }
        }

        return changes;
    }
    /**
     * Restituisce i file Java presenti nella release.
     */
    public List<Path> getJavaFiles(Release release)
            throws Exception {

        checkoutManager.checkoutRelease(release);

        try (var files =
                     Files.walk(
                             checkoutManager
                                     .getWorkingDirectory()
                                     .toPath())) {

            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p ->
                            Config.INCLUDE_TESTS
                                    || !isTestFile(p))
                    .toList();

        } finally {
            checkoutManager.cleanRepository();
        }
    }
    private static boolean isTestFile(Path path) {
        String p = path.toString().replace('\\', '/');
        return p.contains("/src/test/");
    }
}