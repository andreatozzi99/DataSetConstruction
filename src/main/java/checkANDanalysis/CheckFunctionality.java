package checkANDanalysis;

import com.github.mauricioaniche.ck.CKClassResult;
import config.Config;
import manager.javaClassScannerManager.JavaClassScanner;
import manager.metricsManager.CKMetrics;
import manager.metricsManager.ClassMetricsCollector;
import manager.checkoutManager.CheckoutManager;
import manager.commitManager.CommitManager;
import manager.ticketManager.TicketManager;
import manager.releaseManager.ReleaseManager;
import model.ClassChanges;
import model.CsvRow;
import model.Ticket;
import model.Release;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import szz.SzzAnalyzer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Map;

/**
 * Fotografia iniziale dei dati disponibili prima del labeling: dice in modo
 * esplicito quali ticket possono arrivare fino a SZZ e quali no.
 */
public final class CheckFunctionality {
    public static void main(String[] args) throws Exception {

        /*
        checkReleaseRetrieval();

        checkCheckout();

        checkGitTags();

        checkAllCheckouts();

        checkJavaScanner();

        testScannerOnAllTrainingReleases();

        testClassMetricsCollector();

        checkCK();

        checkClassChanges();

        checkReleaseCommit();
        */
        checkJGitChanges();
    }

    public static void primaryAnalysis(String[] args) throws Exception {
        boolean runSzz = java.util.Arrays.asList(args).contains("--with-szz");
        TicketManager ticketManager = new TicketManager();
        List<Ticket> fixedTickets = ticketManager.getTickets();
        fixedTickets = ticketManager.getFixedTickets();
        CommitManager finder = new CommitManager();
        List<Release> releases = new ReleaseManager().getReleases();
        Map<String, List<RevCommit>> commitsByTicket = finder.findFixCommits(fixedTickets.stream().map(Ticket::key)
                .collect(java.util.stream.Collectors.toSet()));
        Files.createDirectories(Config.OUTPUT);

        StringBuilder csv = new StringBuilder("ticket,av_present,affected_versions,fv_present,fixed_versions,matching_commits,java_classes,java_class_paths,inducing_commits,iv_found,earliest_iv,szz_eligible,notes\n");
        int withAv = 0, withFv = 0, withCommit = 0, szzEligible = 0, withIv = 0;
        int processed = 0;
        // SZZ fa blame sul codice storico ed e' volutamente opzionale: il primo
        // report deve arrivare velocemente e servire a scegliere i ticket migliori.
        try (Repository repository = runSzz ? new FileRepositoryBuilder().setGitDir(Config.REPOSITORY.resolve(".git").toFile()).build() : null) {
            SzzAnalyzer szz = new SzzAnalyzer(repository);
            for (Ticket ticket : fixedTickets) {
                boolean hasAv = !ticket.affectedVersions().isEmpty();
                boolean hasFv = !ticket.fixVersions().isEmpty();
                List<RevCommit> commits = commitsByTicket.get(ticket.key());
                Set<String> classes = finder.findTouchedJavaClasses(commits);
                Set<RevCommit> inducing = new LinkedHashSet<>();
                Optional<Release> injectedVersion = Optional.empty();
                if (runSzz) {
                    for (RevCommit fix : commits)
                        for (Set<RevCommit> found : szz.findInducingCommits(fix).values()) inducing.addAll(found);
                    injectedVersion = inducing.stream().map(commit -> szz.findInjectedVersion(commit, releases))
                            .flatMap(Optional::stream).min(java.util.Comparator.comparing(Release::releaseDate));
                }
                boolean eligible = !classes.isEmpty();
                if (hasAv) withAv++;
                if (hasFv) withFv++;
                if (!commits.isEmpty()) withCommit++;
                if (eligible) szzEligible++;
                if (injectedVersion.isPresent()) withIv++;
                String note = classes.isEmpty() ? "nessuna classe Java nei fixing commit" : (!runSzz ? "SZZ non eseguito: avvia con --with-szz" : (injectedVersion.isEmpty() ? "SZZ non ha trovato un commit introducente" : "pronto per labeling [IV,FV)"));
                csv.append(q(ticket.key())).append(',').append(hasAv).append(',').append(q(String.join("|", ticket.affectedVersions()))).append(',')
                        .append(hasFv).append(',').append(q(String.join("|", ticket.fixVersions()))).append(',')
                        .append(commits.size()).append(',').append(classes.size()).append(',').append(q(String.join("|", classes))).append(',').append(inducing.size()).append(',')
                        .append(injectedVersion.isPresent()).append(',').append(q(injectedVersion.map(Release::name).orElse(""))).append(',')
                        .append(eligible).append(',').append(q(note)).append('\n');
                processed++;
                if (processed % 25 == 0)
                    System.out.println("Analizzati ticket: " + processed + "/" + fixedTickets.size());
            }
        }
        Files.writeString(Config.TICKET_ANALYSIS_CSV, csv, StandardCharsets.UTF_8);
        String report = "Ticket Fixed analizzati: " + fixedTickets.size() + "\nCon AV: " + withAv + "\nCon FV Jira: " + withFv
                + "\nCon almeno un fixing commit: " + withCommit + "\nCon IV trovata da SZZ: " + withIv + "\nAnalizzabili da SZZ: " + szzEligible + "\n";
        Files.writeString(Config.TICKET_ANALYSIS_REPORT, report, StandardCharsets.UTF_8);
        System.out.println(report);
    }

    private static String q(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    public static void checkReleaseRetrieval() throws Exception {

        ReleaseManager releaseManager = new ReleaseManager();

        List<Release> releases = releaseManager.getReleases();

        System.out.println("Release trovate: " + releases.size());
        System.out.println();

        for (Release release : releases) {
            System.out.println(release);
        }
        // crea il csv con le release trovate
        releaseManager.generateReleaseCsv(releases);

    }

    public static void checkCheckout() throws Exception {

        ReleaseManager releaseManager = new ReleaseManager();
        CheckoutManager checkoutManager = new CheckoutManager();

        List<Release> releases = releaseManager.getReleases();

        // Per il momento proviamo la prima release disponibile
        Release release = releases.get(0);

        System.out.println("Checkout della release: " + release.name());

        checkoutManager.checkoutRelease(release);

        System.out.println("Repository: " + checkoutManager.getWorkingDirectory());
    }

    public static void checkAllCheckouts() throws Exception {

        ReleaseManager releaseManager = new ReleaseManager();
        CheckoutManager checkoutManager = new CheckoutManager();

        List<Release> releases = releaseManager.getReleases();

        for (Release release : releases) {

            System.out.print(release.name() + " -> ");

            try {

                checkoutManager.checkoutRelease(release);

                System.out.println("OK");

            } catch (Exception e) {

                System.out.println("ERRORE");

            }
        }
    }

    public static void checkGitTags() {

        CheckoutManager checkout = new CheckoutManager();

        checkout.printTags();

    }

    public static void checkJavaScanner() throws Exception {

        ReleaseManager releaseManager = new ReleaseManager();
        CheckoutManager checkoutManager = new CheckoutManager();
        JavaClassScanner scanner = new JavaClassScanner();

        Release release = releaseManager.getReleases().get(0);

        checkoutManager.checkoutRelease(release);

        List<Path> javaFiles =
                scanner.findJavaFiles(checkoutManager.getRepositoryPath());

        System.out.println("File Java trovati: " + javaFiles.size());
        System.out.println();

        javaFiles.stream()
                .limit(20)
                .forEach(file ->
                        System.out.println(
                                scanner.getClassName(
                                        checkoutManager.getRepositoryPath(),
                                        file)));

        checkoutManager.cleanRepository();
    }

    public static void testScannerOnAllTrainingReleases() throws Exception {

        ReleaseManager releaseManager = new ReleaseManager();
        CheckoutManager checkoutManager = new CheckoutManager();
        JavaClassScanner scanner = new JavaClassScanner();

        for (Release release : releaseManager.getTrainingReleases(
                releaseManager.getReleases())) {

            checkoutManager.checkoutRelease(release);

            int count = scanner.findJavaFiles(
                    checkoutManager.getRepositoryPath()).size();

            System.out.printf("%-20s %5d classi%n",
                    release.name(),
                    count);
        }

        checkoutManager.cleanRepository();
    }

    public static void checkReleaseCommit() throws Exception {

        ReleaseManager releaseManager = new ReleaseManager();
        CheckoutManager checkoutManager = new CheckoutManager();

        List<Release> releases =
                releaseManager.getReleases();

        Release release = releases.get(0);

        RevCommit commit =
                checkoutManager.getReleaseCommit(release);

        System.out.println("=== RELEASE COMMIT ===");
        System.out.println("Release: " + release.name());
        System.out.println("Data: " + release.releaseDate());
        System.out.println("Commit: " + commit.getName());
        System.out.println("Messaggio: " + commit.getShortMessage());
    }

    public static void checkJGitChanges() throws Exception {

        ReleaseManager releaseManager =
                new ReleaseManager();

        CheckoutManager checkoutManager =
                new CheckoutManager();

        JavaClassScanner scanner =
                new JavaClassScanner();

        CKMetrics ckMetrics =
                new CKMetrics();

        CommitManager commitManager =
                new CommitManager();

        ClassMetricsCollector collector =
                new ClassMetricsCollector(
                        checkoutManager,
                        scanner,
                        ckMetrics,
                        commitManager);

        Release release =
                releaseManager
                        .getReleases()
                        .get(0);

        List<Path> javaFiles =
                collector.getJavaFiles(release);

        System.out.println("=== JGIT CHANGES ===");
        System.out.println("Release: " + release.name());
        System.out.println("Java files: " + javaFiles.size());

        List<ClassChanges> changes =
                collector.collectClassChanges(
                        release,
                        javaFiles);

        System.out.println(
                "Class changes: " + changes.size());

        for (int i = 0;
             i < Math.min(20, changes.size());
             i++) {

            System.out.println(changes.get(i));
        }
    }

}