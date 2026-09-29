import config.Config;
import labeling.BuggyLabeler;
import labeling.HistoricalSzzClassResolver;
import labeling.SzzAnalyzer;
import labeling.SzzClassEvidence;
import labeling.SzzDiagnosticResult;
import manager.checkoutManager.CheckoutManager;
import manager.commitManager.CommitManager;
import manager.csvManager.CsvManager;
import manager.javaClassScannerManager.JavaClassScanner;
import manager.metricsManager.CKMetrics;
import manager.metricsManager.ClassMetricsCollector;
import manager.metricsManager.JGitMetrics;
import manager.metricsManager.PMDMetrics;
import manager.releaseManager.ReleaseManager;
import manager.ticketManager.TicketManager;
import model.ClassChanges;
import model.CsvRow;
import model.Release;
import model.Ticket;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.OptionalInt;
import java.util.stream.Collectors;

/**
 * Costruisce il dataset finale del progetto.
 *
 * La pipeline comprende:
 * - recupero delle release;
 * - recupero dei ticket Jira fixed;
 * - individuazione dei fixing commit;
 * - applicazione di SZZ;
 * - stima della Injected Version tramite Proportion quando necessaria;
 * - labeling delle classi;
 * - calcolo delle metriche CK;
 * - calcolo delle metriche storiche JGit;
 * - calcolo dei code smell con PMD;
 * - scrittura del dataset CSV.
 */
public final class DatasetBuilder {

    private DatasetBuilder() {
    }

    public static void main(String[] args)
            throws Exception {

        /*
         * ============================================================
         * COMPONENTI
         * ============================================================
         */

        ReleaseManager releaseManager =
                new ReleaseManager();

        TicketManager ticketManager =
                new TicketManager();

        CheckoutManager checkoutManager =
                new CheckoutManager();

        JavaClassScanner scanner =
                new JavaClassScanner();

        CKMetrics ckMetrics =
                new CKMetrics();

        CommitManager commitManager =
                new CommitManager();

        JGitMetrics jGitMetrics =
                new JGitMetrics();

        PMDMetrics pmdMetrics =
                new PMDMetrics();

        ClassMetricsCollector metrics =
                new ClassMetricsCollector(
                        checkoutManager,
                        scanner,
                        ckMetrics,
                        jGitMetrics,
                        pmdMetrics
                );

        /*
         * ============================================================
         * RELEASE
         * ============================================================
         */

        List<Release> releases =
                releaseManager.getReleases();

        List<Release> trainingReleases =
                releaseManager.getTrainingReleases(
                        releases);

        OptionalInt releaseLimit = readReleaseLimit(args);
        List<Release> releasesToProcess = releaseLimit.isPresent()
                ? trainingReleases.stream().limit(releaseLimit.getAsInt()).toList()
                : trainingReleases;

        System.out.println(
                "Release totali: "
                        + releases.size());

        System.out.println(
                "Release usate nel dataset: "
                        + releasesToProcess.size());

        /*
         * ============================================================
         * TICKET JIRA FIXED
         * ============================================================
         */

        /*
         * TicketManager deve prima recuperare
         * i ticket da Jira.
         */
        ticketManager.getTickets();

        List<Ticket> fixedTickets =
                ticketManager.getFixedTickets();

        System.out.println(
                "Ticket fixed Jira: "
                        + fixedTickets.size());

        Set<String> ticketKeys =
                fixedTickets
                        .stream()
                        .map(Ticket::key)
                        .collect(
                                Collectors.toCollection(
                                        LinkedHashSet::new));

        /*
         * ============================================================
         * FIXING COMMIT
         * ============================================================
         */

        Map<String, List<RevCommit>> fixCommitsByTicket =
                commitManager.findFixCommits(
                        ticketKeys);

        /*
         * Insieme globale degli ID dei fixing commit.
         *
         * Verrà usato da CommitManager per impostare
         * ClassChanges.fix = true.
         */
        Set<String> fixCommitIds =
                fixCommitsByTicket
                        .values()
                        .stream()
                        .flatMap(List::stream)
                        .map(RevCommit::getName)
                        .collect(
                                Collectors.toCollection(
                                        LinkedHashSet::new));

        System.out.println(
                "Fix commit Git trovati: "
                        + fixCommitIds.size());

        /*
         * ============================================================
         * SZZ
         * ============================================================
         *
         * inducingCommits:
         *
         * ticket -> classe -> bug-inducing commit
         */

        Map<String, Map<String, SzzClassEvidence>> szzEvidenceByTicket =
                new LinkedHashMap<>();

        try (Repository repository =
                     Utils.GitRepositoryUtils.openRepository()) {

            SzzAnalyzer szzAnalyzer =
                    new SzzAnalyzer(
                            repository);

            int processedTickets = 0;

            for (Ticket ticket :
                    fixedTickets) {

                List<RevCommit> fixingCommits =
                        fixCommitsByTicket
                                .getOrDefault(
                                        ticket.key(),
                                        List.of());

                /*
                 * Risultati SZZ relativi al singolo ticket.
                 *
                 * classe -> inducing commits
                 */
                Map<String, SzzClassEvidence> ticketEvidence =
                        new LinkedHashMap<>();

                /*
                 * Uno stesso ticket può essere associato
                 * a più fixing commit.
                 */
                for (RevCommit fixingCommit :
                        fixingCommits) {

                    /*
                     * Oltre ai commit inducing conserviamo il source path
                     * della riga blamed. Il blame segue i rename soltanto per
                     * ottenere questa evidenza storica: la decisione finale
                     * resta prudente e viene verificata sul tag della release.
                     */
                    SzzDiagnosticResult result = szzAnalyzer
                            .analyseFixingCommitWithSourcePaths(fixingCommit);

                    Map<String, Set<String>> sourcePathsByClass = new LinkedHashMap<>();
                    result.analysedLines().forEach(line -> {
                        if (line.inducingCommitId().equals("<nessun commit>")
                                || line.sourcePath() == null
                                || line.sourcePath().isBlank()) {
                            return;
                        }
                        sourcePathsByClass.computeIfAbsent(line.filePath(), ignored -> new LinkedHashSet<>())
                                .add(line.sourcePath());
                    });

                    /*
                     * Uniamo i risultati senza perdere
                     * l'associazione con la singola classe.
                     */
                    for (Map.Entry<String, Set<RevCommit>> entry : result.inducingCommitsByClass().entrySet()) {
                        SzzClassEvidence previous = ticketEvidence.get(entry.getKey());
                        Set<RevCommit> inducing = new LinkedHashSet<>(entry.getValue());
                        Set<String> sourcePaths = new LinkedHashSet<>(
                                sourcePathsByClass.getOrDefault(entry.getKey(), Set.of()));
                        if (previous != null) {
                            inducing.addAll(previous.inducingCommits());
                            sourcePaths.addAll(previous.sourcePaths());
                        }
                        ticketEvidence.put(entry.getKey(), new SzzClassEvidence(
                                entry.getKey(), inducing, sourcePaths));
                    }
                }

                szzEvidenceByTicket.put(
                        ticket.key(),
                        ticketEvidence);

                processedTickets++;

                if (processedTickets % 25 == 0) {

                    System.out.println(
                            "SZZ: "
                                    + processedTickets
                                    + "/"
                                    + fixedTickets.size());
                }
            }
        }

        /*
         * ============================================================
         * LABELING
         * ============================================================
         *
         * BuggyLabeler utilizza:
         *
         * 1. SZZ per individuare le classi coinvolte;
         * 2. Affected Version Jira per determinare la IV;
         * 3. Proportion Total quando AV manca;
         * 4. Fixed Version per chiudere l'intervallo.
         *
         * Una classe è buggy nell'intervallo:
         *
         * IV <= release < FV
         */

        BuggyLabeler buggyLabeler =
                new BuggyLabeler();

        buggyLabeler.labelBugsWithEvidence(
                fixedTickets,
                releases,
                szzEvidenceByTicket
        );

        /*
         * ============================================================
         * COSTRUZIONE CSV
         * ============================================================
         */

        try (CsvManager csvManager = new CsvManager(Config.DATASET_CSV);
             Repository repository = Utils.GitRepositoryUtils.openRepository();
             HistoricalSzzClassResolver historicalResolver =
                     new HistoricalSzzClassResolver(repository)) {

            csvManager.createCsv();
            csvManager.writeHeader();

            for (Release release :
                    releasesToProcess) {

                System.out.println();
                System.out.println(
                        "========================================");

                System.out.println(
                        "Elaborazione release "
                                + release.name());

                /*
                 * Commit associato al tag
                 * della release corrente.
                 */
                RevCommit releaseCommit =
                        CommitManager
                                .getReleaseCommit(
                                        release);

                /*
                 * Recupera tutta la storia delle modifiche
                 * alle classi fino alla release.
                 *
                 * I fixing commit vengono marcati usando
                 * l'insieme fixCommitIds.
                 */
                Map<String, List<ClassChanges>> allChanges =
                        commitManager
                                .getClassChanges(
                                        releaseCommit,
                                        fixCommitIds);

                /*
                 * Controllo diagnostico sui fixing commit
                 * presenti nella storia fino alla release.
                 */
                long fixChanges =
                        allChanges
                                .values()
                                .stream()
                                .flatMap(List::stream)
                                .filter(ClassChanges::fix)
                                .count();

                long distinctFixCommits =
                        allChanges
                                .values()
                                .stream()
                                .flatMap(List::stream)
                                .filter(ClassChanges::fix)
                                .map(ClassChanges::commitId)
                                .distinct()
                                .count();

                System.out.println(
                        "ClassChanges marcati FIX: "
                                + fixChanges);

                System.out.println(
                        "Fix commit distinti fino alla release: "
                                + distinctFixCommits);

                /*
                 * Calcolo delle feature:
                 *
                 * - CK
                 * - JGit
                 * - PMD
                 *
                 * Il collector crea inizialmente le righe
                 * con buggy=false.
                 */
                List<CsvRow> rows =
                        metrics.collect(
                                release,
                                allChanges);

                /*
                 * Applichiamo ora il labeling corretto
                 * classe per classe.
                 */
                Set<String> productionPaths = rows.stream()
                        .map(CsvRow::classPath)
                        .collect(Collectors.toCollection(LinkedHashSet::new));

                HistoricalSzzClassResolver.ResolutionResult resolved =
                        historicalResolver.resolve(
                                release,
                                releaseCommit,
                                productionPaths,
                                buggyLabeler.getCandidates(release));

                rows = rows.stream()
                        .map(row -> row.withBuggy(
                                resolved.buggyPaths().contains(row.classPath())))
                        .toList();

                HistoricalSzzClassResolver.ResolutionStatistics resolutionStats =
                        resolved.statistics();
                System.out.println("Risoluzione storica classi SZZ: candidati="
                        + resolutionStats.totalCandidates()
                        + " | PRE_INDUCING=" + resolutionStats.preInducing()
                        + " | INDUCING_NON_RAGGIUNGIBILE=" + resolutionStats.inducingNotReachable()
                        + " | EXACT=" + resolutionStats.exactPath()
                        + " | SOURCE=" + resolutionStats.sourcePath()
                        + " | UNIQUE_SIMPLE_NAME=" + resolutionStats.uniqueSimpleName()
                        + " | AMBIGUOUS=" + resolutionStats.ambiguous()
                        + " | NOT_FOUND=" + resolutionStats.notFound());

                /*
                 * Controlliamo quante classi vengono
                 * effettivamente marcate buggy.
                 */
                long buggyRows =
                        rows.stream()
                                .filter(CsvRow::buggy)
                                .count();

                System.out.println(
                        "Classi buggy nella release: "
                                + buggyRows);

                /*
                 * Scriviamo immediatamente le righe.
                 *
                 * Non è necessario mantenere l'intero
                 * dataset in memoria.
                 */
                for (CsvRow row :
                        rows) {

                    csvManager.appendRow(
                            row);
                }

                System.out.println(
                        "Righe CSV: "
                                + rows.size());

                System.out.println(
                        "Completata release "
                                + release.name());

                System.out.println(
                        "========================================");
            }
        }

        System.out.println();

        System.out.println(
                "Dataset completato: "
                        + Config.DATASET_CSV);
    }

    /**
     * Consente una prova ripetibile, per esempio --release-limit=3, senza
     * modificare sorgenti e senza rischiare di dimenticare un limite nel run
     * definitivo. Se il parametro manca, vengono elaborate tutte le release.
     */
    private static OptionalInt readReleaseLimit(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--release-limit=")) {
                try {
                    int value = Integer.parseInt(arg.substring("--release-limit=".length()));
                    if (value > 0) return OptionalInt.of(value);
                } catch (NumberFormatException ignored) {
                    // Il messaggio sotto spiega il formato atteso.
                }
                throw new IllegalArgumentException("Usa un limite positivo, ad esempio --release-limit=3");
            }
        }
        return OptionalInt.empty();
    }
}
