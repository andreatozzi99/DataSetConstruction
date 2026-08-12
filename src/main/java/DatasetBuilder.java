import config.Config;
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
import org.eclipse.jgit.revwalk.RevCommit;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Costruisce il dataset a partire dalle release selezionate.
 */
public final class DatasetBuilder {

    public static void main(String[] args) throws Exception {

        ReleaseManager releaseManager =
                new ReleaseManager();

        List<Release> trainingReleases =
                releaseManager.getTrainingReleases(
                        releaseManager.getReleases());

        JavaClassScanner scanner =
                new JavaClassScanner();

        CheckoutManager checkoutManager =
                new CheckoutManager();

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
                        commitManager,
                        jGitMetrics,
                        pmdMetrics);

        /*
         * Recuperiamo i ticket Jira risolti/fixed.
         */
        TicketManager ticketManager =
                new TicketManager();

        ticketManager.getTickets();

        List<Ticket> fixedTickets =
                ticketManager.getFixedTickets();

        Set<String> ticketKeys =
                fixedTickets.stream()
                        .map(Ticket::key)
                        .collect(Collectors.toSet());

        System.out.println(
                "Ticket fixed Jira: "
                        + fixedTickets.size());

        /*
         * Cerca una sola volta i commit Git associati
         * alle chiavi Jira.
         */
        Map<String, List<RevCommit>> fixCommitsByTicket =
                commitManager.findFixCommits(ticketKeys);

        /*
         * Otteniamo l'insieme degli SHA dei fixing commit.
         */
        Set<String> fixCommitIds =
                fixCommitsByTicket.values()
                        .stream()
                        .flatMap(List::stream)
                        .map(RevCommit::getName)
                        .collect(Collectors.toSet());

        System.out.println(
                "Fix commit Git trovati: "
                        + fixCommitIds.size());

        try (CsvManager csv =
                     new CsvManager(Config.DATASET_CSV)) {

            csv.createCsv();
            csv.writeHeader();

            for (Release release : trainingReleases.stream().limit(1).toList()) {

                System.out.println();
                System.out.println(
                        "========================================");

                System.out.println(
                        "Elaborazione release "
                                + release.name());

                /*
                 * Recupera il commit corrispondente
                 * al tag della release.
                 */
                RevCommit releaseCommit =
                        CommitManager.getReleaseCommit(
                                release);

                /*
                 * Recupera la storia delle classi fino
                 * alla release e marca i fixing commit.
                 */
                Map<String, List<ClassChanges>> allChanges =
                        commitManager.getClassChanges(
                                releaseCommit,
                                fixCommitIds);

                /*
                 * Controllo temporaneo:
                 * quante modifiche sono state riconosciute
                 * come appartenenti a fixing commit.
                 */
                long fixChanges =
                        allChanges.values()
                                .stream()
                                .flatMap(List::stream)
                                .filter(ClassChanges::fix)
                                .count();

                long distinctFixCommits =
                        allChanges.values()
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
                 * CK + metriche storiche Git.
                 *
                 * Il checkout della release viene gestito
                 * dal CheckoutManager attraverso il collector.
                 */
                List<CsvRow> rows =
                        metrics.collect(
                                release,
                                allChanges);

                for (CsvRow row : rows) {
                    csv.appendRow(row);
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
}