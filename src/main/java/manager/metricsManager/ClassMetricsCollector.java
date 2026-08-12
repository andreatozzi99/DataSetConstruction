package manager.metricsManager;

import config.Config;
import manager.checkoutManager.CheckoutManager;
import manager.commitManager.CommitManager;
import manager.javaClassScannerManager.JavaClassScanner;
import model.CKClassMetrics;
import model.ClassChanges;
import model.CsvRow;
import model.JGitClassMetrics;
import model.Release;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestratore degli estrattori di metriche.
 *
 * Per ogni release coordina:
 * - checkout del codice;
 * - individuazione delle classi Java;
 * - metriche statiche CK;
 * - metriche storiche Git;
 * - code smell rilevati tramite PMD;
 * - costruzione delle righe del dataset.
 *
 * La logica di calcolo delle singole metriche rimane
 * all'interno dei rispettivi estrattori.
 */
public final class ClassMetricsCollector {

    private final CheckoutManager checkoutManager;
    private final JavaClassScanner classScanner;
    private final CKMetrics ckMetrics;
    private final JGitMetrics jGitMetrics;
    private final CommitManager commitManager;
    private final PMDMetrics pmdMetrics;

    public ClassMetricsCollector(
            CheckoutManager checkoutManager,
            JavaClassScanner classScanner,
            CKMetrics ckMetrics,
            CommitManager commitManager,
            JGitMetrics jGitMetrics,
            PMDMetrics pmdMetrics) {

        this.checkoutManager = checkoutManager;
        this.classScanner = classScanner;
        this.ckMetrics = ckMetrics;
        this.commitManager = commitManager;
        this.jGitMetrics = jGitMetrics;
        this.pmdMetrics = pmdMetrics;
    }

    /**
     * Costruisce tutte le righe del dataset relative
     * alla release indicata.
     *
     * Le modifiche Git vengono ricevute già estratte
     * dal CommitManager, in modo da non rileggere
     * la storia Git per ogni singola classe.
     *
     * @param release    release da analizzare
     * @param allChanges modifiche Git delle classi fino alla release
     */
    public List<CsvRow> collect(
            Release release,
            Map<String, List<ClassChanges>> allChanges) {

        checkoutManager.checkoutRelease(release);

        try {

            Path repository =
                    checkoutManager
                            .getWorkingDirectory()
                            .toPath();

            /*
             * 1. Individuiamo tutte le classi Java
             * presenti nella release.
             */
            List<Path> javaFiles =
                    classScanner.findJavaFiles(repository);

            /*
             * 2. Calcoliamo una sola volta le metriche CK
             * per tutte le classi della release.
             */
            Map<String, CKClassMetrics> ckMetricsByPath =
                    ckMetrics.calculate(
                            repository,
                            javaFiles);

            /*
             * 3. PMD analizza le stesse classi e restituisce
             * il numero di code smell rilevati per file.
             */
            Map<String, Integer> smellsByPath =
                    pmdMetrics.calculate(javaFiles);
            long classesWithSmells =
                    smellsByPath.values()
                            .stream()
                            .filter(value -> value > 0)
                            .count();

            long totalSmells =
                    smellsByPath.values()
                            .stream()
                            .mapToLong(Integer::longValue)
                            .sum();

            System.out.println(
                    "Classi analizzate PMD: "
                            + smellsByPath.size());

            System.out.println(
                    "Classi con almeno uno smell: "
                            + classesWithSmells);

            System.out.println(
                    "Smell PMD totali: "
                            + totalSmells);

            List<CsvRow> rows =
                    new ArrayList<>();

            /*
             * 4. Combiniamo tutte le metriche
             * classe per classe.
             */
            for (Path javaFile : javaFiles) {

                /*
                 * CK e PMD utilizzano il path assoluto
                 * normalizzato come chiave.
                 */
                String absolutePath =
                        javaFile
                                .toAbsolutePath()
                                .normalize()
                                .toString();

                CKClassMetrics ck =
                        ckMetricsByPath.get(absolutePath);

                /*
                 * Se CK non riesce ad analizzare una classe
                 * non possiamo costruire una riga completa.
                 */
                if (ck == null) {

                    System.err.println(
                            "Nessuna metrica CK trovata per "
                                    + javaFile);

                    continue;
                }

                /*
                 * Path che verrà scritto nel CSV.
                 * È anche il formato utilizzato dal CommitManager
                 * per identificare le classi nella storia Git.
                 */
                String classPath =
                        classScanner.getClassName(
                                repository,
                                javaFile);

                /*
                 * Recuperiamo tutte le modifiche storiche
                 * della classe fino alla release corrente.
                 */
                List<ClassChanges> changes =
                        allChanges.getOrDefault(
                                classPath,
                                List.of());

                /*
                 * 5. Le modifiche grezze vengono aggregate
                 * nelle feature JGit della classe.
                 */
                JGitClassMetrics git =
                        jGitMetrics.calculate(
                                changes,
                                release.releaseDate());

                /*
                 * Se PMD non segnala violazioni per il file,
                 * il numero di smell è zero.
                 */
                int nSmells =
                        smellsByPath.getOrDefault(
                                absolutePath,
                                0);

                /*
                 * 6. Costruiamo la riga finale del dataset.
                 */
                rows.add(
                        toRow(
                                release,
                                classPath,
                                ck,
                                git,
                                nSmells));
            }

            return rows;

        } finally {

            /*
             * Il checkout è temporaneo: anche in caso
             * di errore ripristiniamo il repository.
             */
            checkoutManager.cleanRepository();
        }
    }

    /**
     * Combina metriche CK, metriche Git e code smell
     * nella rappresentazione finale utilizzata dal CSV.
     *
     * Il campo buggy rimane inizialmente false:
     * verrà impostato durante la fase di labeling.
     */
    private CsvRow toRow(
            Release release,
            String classPath,
            CKClassMetrics ck,
            JGitClassMetrics git,
            int nSmells) {

        return new CsvRow(

                // Identificazione
                Config.PROJECT_KEY,
                release.index(),
                release.name(),
                classPath,

                // CK
                ck.loc(),
                ck.wmc(),
                ck.cbo(),
                ck.rfc(),
                ck.lcom(),
                ck.dit(),
                ck.noc(),
                ck.fanin(),
                ck.fanout(),

                // JGit
                git.commitCount(),
                git.fixCommitCount(),
                git.churn(),
                git.averageChangeSetSize(),
                git.distinctAuthors(),
                git.daysSinceLastChange(),
                git.changeFrequency(),
                git.changeCountLast90Days(),
                git.modificationIntervalsStdDev(),
                git.authorChangeEntropy(),

                // Code smell
                nSmells,

                // Target: verrà determinato dal labeling
                false
        );
    }
}