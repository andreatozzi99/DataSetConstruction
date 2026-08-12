package manager.metricsManager;

import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.document.TextFile;
import net.sourceforge.pmd.reporting.FileAnalysisListener;
import net.sourceforge.pmd.reporting.GlobalAnalysisListener;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calcola il numero di code smell individuati da PMD
 * per ciascun file Java della release analizzata.
 * -
 * La classe ha una sola responsabilità:
 * eseguire l'analisi statica PMD sui file ricevuti e
 * restituire, per ogni file, il numero di violazioni trovate.
 * -
 * Nel dataset la metrica viene chiamata NSmells.
 * -
 * Una violazione PMD corrisponde all'applicazione di una regola
 * del ruleset configurato. In questo progetto viene utilizzato
 * il ruleset Java "quickstart", che contiene regole generali
 * fornite direttamente da PMD.
 * -
 * La classe non esegue checkout, non cerca file Java e non
 * scrive il CSV: queste responsabilità appartengono agli altri
 * componenti della pipeline.
 */
public final class PMDMetrics {

    /**
     * Ruleset PMD utilizzato per individuare le violazioni.
     * -
     * Mantenere il ruleset come costante permette di sapere
     * esattamente con quale configurazione è stato costruito
     * il dataset.
     */
    private static final String RULESET =
            "rulesets/java/quickstart.xml";

    /**
     * Versione Java utilizzata dal parser PMD.
     * -
     * Le release di Storm che stiamo utilizzando sono storiche;
     * Java 8 è sufficientemente compatibile con il codice delle
     * release considerate nella parte iniziale del progetto.
     * -
     * Se durante l'analisi dovessero emergere errori di parsing
     * dovuti alla versione del linguaggio, questo valore può
     * essere modificato in un solo punto.
     */
    private static final String JAVA_VERSION = "8";

    /**
     * Esegue PMD sui file Java ricevuti e restituisce il numero
     * di smell rilevati per ciascun file.
     * -
     * La chiave della mappa è il path assoluto normalizzato del
     * file. Utilizziamo lo stesso formato adottato da CKMetrics
     * così che ClassMetricsCollector possa associare facilmente
     * le metriche provenienti dai diversi strumenti.
     * -
     * Ogni file viene inserito inizialmente con valore zero.
     * In questo modo anche una classe senza violazioni PMD
     * compare nella mappa con NSmells = 0.
     *
     * @param javaFiles file Java della release corrente
     * @return mappa file -> numero di smell PMD
     */
    public Map<String, Integer> calculate(
            List<Path> javaFiles) {

        /*
         * PMD può analizzare file in parallelo.
         * Usiamo quindi una ConcurrentHashMap durante
         * l'esecuzione dell'analisi.
         */
        Map<String, Integer> smellCounts =
                new ConcurrentHashMap<>();

        /*
         * Registriamo subito tutti i file con zero smell.
         *
         * Se PMD non trova nessuna violazione per un file,
         * quel file deve comunque comparire nel risultato.
         */
        for (Path javaFile : javaFiles) {

            smellCounts.put(
                    normalize(javaFile),
                    0);
        }

        PMDConfiguration configuration =
                createConfiguration();

        SmellCountingListener countingListener =
                new SmellCountingListener(
                        smellCounts);

        /*
         * PmdAnalysis rappresenta una singola esecuzione
         * dell'analizzatore PMD.
         *
         * Il try-with-resources garantisce che tutte le
         * risorse interne di PMD vengano chiuse correttamente.
         */
        try (PmdAnalysis pmd =
                     PmdAnalysis.create(configuration)) {

            /*
             * Il listener riceve una notifica ogni volta
             * che PMD trova una violazione.
             */
            pmd.addListener(
                    countingListener);

            /*
             * Se PMD non riesce realmente ad analizzare
             * un file, preferiamo far fallire l'esecuzione
             * invece di assegnargli silenziosamente zero smell.
             */
            pmd.addListener(
                    GlobalAnalysisListener
                            .exceptionThrower());

            /*
             * Aggiungiamo esclusivamente i file già trovati
             * dal JavaClassScanner.
             *
             * In questo modo vengono rispettate anche le
             * nostre regole, ad esempio l'esclusione dei test.
             */
            for (Path javaFile : javaFiles) {
                pmd.files().addFile(javaFile);
            }

            /*
             * Avvia l'analisi vera e propria.
             */
            pmd.performAnalysis();
        }

        /*
         * Restituiamo una LinkedHashMap nell'ordine dei file
         * ricevuti. Non è necessario per il calcolo, ma rende
         * output e test deterministici e più leggibili.
         */
        Map<String, Integer> result =
                new LinkedHashMap<>();

        for (Path javaFile : javaFiles) {

            String path =
                    normalize(javaFile);

            result.put(
                    path,
                    smellCounts.getOrDefault(
                            path,
                            0));
        }

        return result;
    }

    /**
     * Costruisce la configurazione utilizzata da PMD.
     * -
     * Separare la configurazione dall'analisi rende evidente
     * quali parametri influenzano NSmells e permette di
     * modificarli senza toccare la logica di conteggio.
     */
    private PMDConfiguration createConfiguration() {

        PMDConfiguration configuration =
                new PMDConfiguration();

        /*
         * Imposta esplicitamente la versione Java usata
         * per interpretare i sorgenti.
         */
        configuration.setDefaultLanguageVersion(
                LanguageRegistry.PMD
                        .getLanguageById("java")
                        .getVersion(JAVA_VERSION));

        /*
         * Carica il ruleset ufficiale PMD utilizzato
         * per definire cosa viene considerato violazione.
         */
        configuration.addRuleSet(
                RULESET);

        return configuration;
    }

    /**
     * Converte un Path nel formato usato come chiave
     * delle mappe delle metriche.
     * -
     * normalize() elimina componenti ridondanti del path
     * mentre toAbsolutePath() evita ambiguità tra path
     * relativi provenienti da componenti diversi.
     */
    private static String normalize(
            Path path) {

        return path
                .toAbsolutePath()
                .normalize()
                .toString();
    }

    /**
     * Listener interno utilizzato per contare le violazioni
     * PMD file per file.
     * -
     * PMD crea un FileAnalysisListener per ogni sorgente
     * analizzato. Ogni volta che una regola viene violata,
     * onRuleViolation viene chiamato e il contatore del
     * relativo file viene incrementato.
     */
    private static final class SmellCountingListener
            implements GlobalAnalysisListener {

        private final Map<String, Integer> smellCounts;

        private SmellCountingListener(
                Map<String, Integer> smellCounts) {

            this.smellCounts =
                    smellCounts;
        }

        /**
         * Viene chiamato da PMD quando sta per iniziare
         * l'analisi di un singolo file.
         * -
         * Dal TextFile recuperiamo il path assoluto del
         * sorgente e restituiamo un listener che incrementa
         * il numero di smell ogni volta che PMD segnala
         * una violazione.
         */
        @Override
        public FileAnalysisListener startFileAnalysis(
                TextFile file) {

            String path =
                    normalize(
                            Path.of(
                                    file.getFileId()
                                            .getAbsolutePath()));

            smellCounts.putIfAbsent(
                    path,
                    0);

            return violation ->
                    smellCounts.merge(
                            path,
                            1,
                            Integer::sum);
        }

        /**
         * Non possediamo risorse aggiuntive da chiudere.
         * PMD richiede comunque l'implementazione del metodo
         * perché GlobalAnalysisListener estende AutoCloseable.
         */
        @Override
        public void close() {
            // Nessuna risorsa aggiuntiva da rilasciare.
        }
    }
}