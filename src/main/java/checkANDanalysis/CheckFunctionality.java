package checkANDanalysis;

import config.Config;
import manager.javaClassScannerManager.JavaClassScanner;
import manager.metricsManager.CKMetrics;
import manager.metricsManager.ClassMetricsCollector;
import manager.checkoutManager.CheckoutManager;
import manager.commitManager.CommitManager;
import manager.metricsManager.JGitMetrics;
import manager.metricsManager.PMDMetrics;
import manager.ticketManager.TicketManager;
import manager.releaseManager.ReleaseManager;
import model.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.lib.Repository;
import labeling.SzzAnalyzer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/**
 * Raccolta di verifiche manuali della pipeline. L'analisi principale produce
 * un report sui ticket, sui fixing commit e, se richiesto, sui risultati SZZ.
 */
public final class CheckFunctionality {
    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (command) {
            case "analysis" -> primaryAnalysis(Arrays.copyOfRange(args, 1, args.length));
            case "checkout" -> checkCheckout();
            case "checkouts" -> checkAllCheckouts();
            case "tags" -> checkGitTags();
            case "scanner" -> checkJavaScanner();
            case "release-commit" -> checkReleaseCommit();
            case "metrics" -> checkFirstReleaseMetrics();
            case "csv" -> checkFirstReleaseCsvData();
            default -> printUsage();
        }
    }

    /** Elenco esplicito dei controlli disponibili: nessun test costoso parte per errore. */
    private static void printUsage() {
        System.out.println("Comandi disponibili:");
        System.out.println("  analysis              report ticket, AV/FV, fixing commit e classi Java");
        System.out.println("  analysis --with-szz   aggiunge SZZ: puo' richiedere molto tempo");
        System.out.println("  checkout              prova il checkout della prima release");
        System.out.println("  checkouts             controlla i tag di tutte le release");
        System.out.println("  tags                  mostra i tag Git associabili alle release");
        System.out.println("  scanner               conta e mostra file Java della prima release");
        System.out.println("  release-commit        mostra il commit della prima release");
        System.out.println("  metrics               calcola CK, JGit e PMD sulla prima release");
        System.out.println("  csv                   genera e mostra le prime righe CSV della prima release");
    }

    public static void primaryAnalysis(String[] args) throws Exception {
        boolean runSzz = java.util.Arrays.asList(args).contains("--with-szz");
        TicketManager ticketManager = new TicketManager();
        ticketManager.getTickets();
        List<Ticket> fixedTickets = ticketManager.getFixedTickets();
        CommitManager finder = new CommitManager();
        Map<String, List<RevCommit>> commitsByTicket = finder.findFixCommits(fixedTickets.stream().map(Ticket::key)
                .collect(java.util.stream.Collectors.toSet()));
        Files.createDirectories(Config.OUTPUT);

        StringBuilder csv = new StringBuilder("ticket,av_present,affected_versions,fv_present,fixed_versions,matching_commits,fixing_java_file_count,fixing_java_file_paths,szz_inducing_file_count,szz_inducing_commits,szz_found,szz_eligible,notes\n");
        int withAv = 0, withFv = 0, withCommit = 0, szzEligible = 0, withSzzResult = 0;
        int processed = 0;
        // SZZ esegue il blame sul codice storico; resta opzionale per mantenere
        // veloce il report preliminare.
        try (Repository repository = runSzz
                ? Utils.GitRepositoryUtils.openRepository()
                : null) {

            SzzAnalyzer szz = runSzz
                    ? new SzzAnalyzer(repository)
                    : null;

            for (Ticket ticket : fixedTickets) {
                boolean hasAv = !ticket.affectedVersions().isEmpty();
                boolean hasFv = !ticket.fixVersions().isEmpty();
                List<RevCommit> commits = commitsByTicket.get(ticket.key());
                Set<String> classes = finder.findTouchedJavaClasses(commits);
                Map<String, Set<RevCommit>> inducingByClass = Map.of();

                if (runSzz) {
                    inducingByClass = new LinkedHashMap<>();

                    for (RevCommit fix : commits) {
                        Map<String, Set<RevCommit>> found =
                                szz.findInducingCommits(fix);

                        for (Map.Entry<String, Set<RevCommit>> entry :
                                found.entrySet()) {

                            inducingByClass
                                    .computeIfAbsent(
                                            entry.getKey(),
                                            ignored -> new LinkedHashSet<>())
                                    .addAll(entry.getValue());
                        }
                    }
                }

                long inducingCommitCount = inducingByClass.values().stream()
                        .flatMap(Set::stream)
                        .map(RevCommit::getName)
                        .distinct()
                        .count();
                boolean foundInducingCommits = !inducingByClass.isEmpty();
                boolean eligible = !classes.isEmpty();
                if (hasAv) withAv++;
                if (hasFv) withFv++;
                if (!commits.isEmpty()) withCommit++;
                if (eligible) szzEligible++;
                if (foundInducingCommits) withSzzResult++;
                String note = classes.isEmpty()
                        ? "nessun file Java di produzione nei fixing commit"
                        : (!runSzz
                        ? "SZZ non eseguito: avvia con --with-szz"
                        : (foundInducingCommits
                        ? "SZZ ha trovato commit inducing"
                        : "SZZ non ha trovato commit inducing"));
                csv.append(q(ticket.key())).append(',').append(hasAv).append(',').append(q(String.join("|", ticket.affectedVersions()))).append(',')
                        .append(hasFv).append(',').append(q(String.join("|", ticket.fixVersions()))).append(',')
                        .append(commits.size()).append(',').append(classes.size()).append(',').append(q(String.join("|", classes))).append(',')
                        .append(inducingByClass.size()).append(',').append(inducingCommitCount).append(',')
                        .append(foundInducingCommits).append(',')
                        .append(eligible).append(',').append(q(note)).append('\n');
                processed++;
                if (processed % 25 == 0)
                    System.out.println("Analizzati ticket: " + processed + "/" + fixedTickets.size());
            }
        }
        Files.writeString(Config.TICKET_ANALYSIS_CSV, csv, StandardCharsets.UTF_8);
        String report = "Ticket Fixed analizzati: " + fixedTickets.size() + "\nCon AV: " + withAv + "\nCon FV Jira: " + withFv
                + "\nCon almeno un fixing commit: " + withCommit + "\nCon commit inducing trovati da SZZ: " + withSzzResult + "\nAnalizzabili da SZZ: " + szzEligible + "\n";
        Files.writeString(Config.TICKET_ANALYSIS_REPORT, report, StandardCharsets.UTF_8);
        System.out.println(report);
    }

    private static String q(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    public static void checkCheckout() throws Exception {

        ReleaseManager releaseManager = new ReleaseManager();
        CheckoutManager checkoutManager = new CheckoutManager();

        List<Release> releases = releaseManager.getReleases();

        // Per il momento proviamo la prima release disponibile
        Release release = releases.get(0);

        System.out.println("Checkout della release: " + release.name());

        checkoutManager.checkoutRelease(release);

        try {
            System.out.println("Repository: " + checkoutManager.getWorkingDirectory());
        } finally {
            checkoutManager.cleanRepository();
        }
    }

    public static void checkAllCheckouts() throws Exception {

        ReleaseManager releaseManager = new ReleaseManager();
        CheckoutManager checkoutManager = new CheckoutManager();

        List<Release> releases = releaseManager.getReleases();

        try {
            for (Release release : releases) {

                System.out.print(release.name() + " -> ");

                try {

                    checkoutManager.checkoutRelease(release);

                    System.out.println("OK");

                } catch (Exception e) {

                    System.out.println("ERRORE");

                }
            }
        } finally {
            checkoutManager.cleanRepository();
        }
    }

    public static void checkGitTags() throws Exception {

        try (Repository repository = Utils.GitRepositoryUtils.openRepository();
             Git git = new Git(repository)) {

            System.out.println("=== TAG GIT ===");

            for (Ref ref : git.tagList().call()) {
                System.out.println(Repository.shortenRefName(ref.getName()));
            }
        }
    }

    public static void checkJavaScanner() throws Exception {

        ReleaseManager releaseManager = new ReleaseManager();
        CheckoutManager checkoutManager = new CheckoutManager();
        JavaClassScanner scanner = new JavaClassScanner();

        Release release = releaseManager.getReleases().get(0);

        checkoutManager.checkoutRelease(release);

        try {
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
        } finally {
            checkoutManager.cleanRepository();
        }
    }

    public static void testScannerOnAllTrainingReleases() throws Exception {

        ReleaseManager releaseManager = new ReleaseManager();
        CheckoutManager checkoutManager = new CheckoutManager();
        JavaClassScanner scanner = new JavaClassScanner();

        try {
            for (Release release : releaseManager.getTrainingReleases(
                    releaseManager.getReleases())) {

                checkoutManager.checkoutRelease(release);

                int count = scanner.findJavaFiles(
                        checkoutManager.getRepositoryPath()).size();

                System.out.printf("%-20s %5d file Java%n",
                        release.name(),
                        count);
            }
        } finally {
            checkoutManager.cleanRepository();
        }
    }

    // Test per verificare che il commit della release sia correttamente individuato
    public static void checkReleaseCommit() throws Exception {

        CommitManager commitManager = new CommitManager();

        Release release = new Release(
                1,
                "12326789",
                "0.9.0.1",
                LocalDate.of(2013, 12, 6),
                false
        );

        RevCommit commit = commitManager.getReleaseCommit(release);

        System.out.println("=== RELEASE COMMIT ===");
        System.out.println("Release: " + release.name());
        System.out.println("Data: " + release.releaseDate());
        System.out.println("Commit: " + commit.getName());
        System.out.println("Messaggio: " + commit.getFullMessage());
    }

    public static void checkFirstReleaseMetrics() throws Exception {

        System.out.println();
        System.out.println("========================================");
        System.out.println("=== TEST CK + JGIT PRIMA RELEASE ===");
        System.out.println("========================================");

        long totalStart = System.nanoTime();

        // ---------------------------------------------------------
        // 1. Recupero release
        // ---------------------------------------------------------

        ReleaseManager releaseManager = new ReleaseManager();

        List<Release> releases =
                releaseManager.getReleases();

        if (releases.isEmpty()) {
            throw new IllegalStateException(
                    "Nessuna release trovata.");
        }

        Release release = releases.get(0);

        System.out.println();
        System.out.println("Release scelta:");
        System.out.println("Nome: " + release.name());
        System.out.println("Data: " + release.releaseDate());

        // ---------------------------------------------------------
        // 2. Checkout
        // ---------------------------------------------------------

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

        ClassMetricsCollector collector =
                new ClassMetricsCollector(
                        checkoutManager,
                        scanner,
                        ckMetrics,
                        jGitMetrics,
                        pmdMetrics);

        // ---------------------------------------------------------
        // 3. Recupero file Java
        // ---------------------------------------------------------

        long startFiles =
                System.nanoTime();

        checkoutManager.checkoutRelease(release);

        List<Path> javaFiles;

        try {
            javaFiles =
                    scanner.findJavaFiles(
                            checkoutManager
                                    .getWorkingDirectory()
                                    .toPath());
        } finally {
            checkoutManager.cleanRepository();
        }

        long endFiles =
                System.nanoTime();

        System.out.println();
        System.out.println("=== FILE JAVA ===");
        System.out.println(
                "Java files: " + javaFiles.size());

        printElapsed(
                "Scansione file Java",
                endFiles - startFiles);

        // ---------------------------------------------------------
        // 4. CK
        // ---------------------------------------------------------

        long startCK =
                System.nanoTime();

        checkoutManager.checkoutRelease(release);

        Map<String, CKClassMetrics> ckResults;

        try {
            Path repository =
                    checkoutManager
                            .getWorkingDirectory()
                            .toPath();

            ckResults =
                    ckMetrics.calculate(
                            repository,
                            javaFiles);

        } finally {
            checkoutManager.cleanRepository();
        }

        long endCK =
                System.nanoTime();

        System.out.println();
        System.out.println("=== CK ===");
        System.out.println(
                "Classi con metriche CK: "
                        + ckResults.size());

        printElapsed(
                "Calcolo metriche CK",
                endCK - startCK);

        // ---------------------------------------------------------
        // 5. JGit
        // ---------------------------------------------------------

        long startJGit =
                System.nanoTime();

        RevCommit releaseCommit =
                commitManager.getReleaseCommit(
                        release);

        Map<String, List<ClassChanges>> allChanges =
                commitManager.getClassChanges(
                        releaseCommit);

        long endJGit =
                System.nanoTime();

        System.out.println();
        System.out.println("=== JGIT ===");

        System.out.println(
                "Commit analizzati: "
                        + allChanges.values()
                        .stream()
                        .flatMap(List::stream)
                        .map(ClassChanges::commitId)
                        .distinct()
                        .count());

        System.out.println(
                "Classi con modifiche: "
                        + allChanges.size());

        System.out.println(
                "ClassChanges totali: "
                        + allChanges.values()
                        .stream()
                        .mapToLong(List::size)
                        .sum());

        printElapsed(
                "Estrazione storia JGit",
                endJGit - startJGit);

        // ---------------------------------------------------------
        // 6. Integrazione CK + JGit
        // ---------------------------------------------------------

        System.out.println();
        System.out.println("=== INTEGRAZIONE ===");

        int classesWithBoth = 0;

        List<String> examples =
                new ArrayList<>();

        for (Path javaFile : javaFiles) {

            String classPath =
                    checkoutManager
                            .getRepositoryPath()
                            .relativize(javaFile)
                            .toString()
                            .replace('\\', '/');

            String absolutePath =
                    javaFile
                            .toAbsolutePath()
                            .normalize()
                            .toString();

            CKClassMetrics ck =
                    ckResults.get(absolutePath);

            List<ClassChanges> changes =
                    allChanges.getOrDefault(
                            classPath,
                            List.of());

            JGitClassMetrics git =
                    jGitMetrics.calculate(
                            changes,
                            release.releaseDate());

            if (ck != null) {

                classesWithBoth++;

                if (examples.size() < 10) {
                    examples.add(
                            classPath
                                    + " | LOC=" + ck.loc()
                                    + " | commits=" + git.commitCount()
                                    + " | fixes=" + git.fixCommitCount()
                                    + " | churn=" + git.churn()
                                    + " | authors=" + git.distinctAuthors());
                }
            }
        }

        System.out.println(
                "Classi con CK + JGit: "
                        + classesWithBoth);

        System.out.println();
        System.out.println("Prime 10 classi:");

        for (String example : examples) {
            System.out.println(example);
        }

        // ---------------------------------------------------------
        // 7. Tempo totale
        // ---------------------------------------------------------

        long totalEnd =
                System.nanoTime();

        System.out.println();
        printElapsed(
                "TEMPO TOTALE TEST",
                totalEnd - totalStart);

        System.out.println();
        System.out.println("=== TEST TERMINATO ===");
    }

    public static void checkFirstReleaseCsvData() throws Exception {

        System.out.println();
        System.out.println("========================================");
        System.out.println("=== TEST CSV PRIMA RELEASE ===");
        System.out.println("========================================");

        long start = System.nanoTime();

        // --------------------------------------------------
        // 1. Recupero release
        // --------------------------------------------------

        ReleaseManager releaseManager = new ReleaseManager();

        List<Release> releases =
                releaseManager.getReleases();

        if (releases.isEmpty()) {
            throw new IllegalStateException(
                    "Nessuna release trovata.");
        }

        Release release = releases.get(0);

        System.out.println();
        System.out.println("Release scelta:");
        System.out.println("Nome: " + release.name());
        System.out.println("Data: " + release.releaseDate());

        // --------------------------------------------------
        // 2. Inizializzazione componenti
        // --------------------------------------------------

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

        ClassMetricsCollector collector =
                new ClassMetricsCollector(
                        checkoutManager,
                        scanner,
                        ckMetrics,
                        jGitMetrics,
                        pmdMetrics
                );

        // --------------------------------------------------
        // 3. Checkout + file Java
        // --------------------------------------------------

        long filesStart = System.nanoTime();

        checkoutManager.checkoutRelease(release);

        List<Path> javaFiles;

        try {
            javaFiles =
                    scanner.findJavaFiles(
                            checkoutManager
                                    .getWorkingDirectory()
                                    .toPath()
                    );
        } finally {
            checkoutManager.cleanRepository();
        }

        long filesEnd = System.nanoTime();

        System.out.println();
        System.out.println("=== FILE JAVA ===");
        System.out.println(
                "Java files: " + javaFiles.size());

        System.out.printf(
                "Scansione file Java: %.3f secondi%n",
                (filesEnd - filesStart) / 1_000_000_000.0
        );

        // --------------------------------------------------
        // 4. Storia Git
        // --------------------------------------------------

        long gitStart = System.nanoTime();

        Map<String, List<ClassChanges>> allChanges =
                commitManager.getClassChanges(commitManager.getReleaseCommit(release));

        long gitEnd = System.nanoTime();

        long totalChanges =
                allChanges.values()
                        .stream()
                        .mapToLong(List::size)
                        .sum();

        System.out.println();
        System.out.println("=== JGIT ===");
        System.out.println(
                "Classi con modifiche: "
                        + allChanges.size());

        System.out.println(
                "ClassChanges totali: "
                        + totalChanges);

        System.out.printf(
                "Estrazione storia JGit: %.3f secondi%n",
                (gitEnd - gitStart) / 1_000_000_000.0
        );

        // --------------------------------------------------
        // 5. CK + JGit
        // --------------------------------------------------

        long metricsStart = System.nanoTime();

        List<CsvRow> rows =
                collector.collect(
                        release,
                        allChanges
                );

        long metricsEnd = System.nanoTime();

        System.out.println();
        System.out.println("=== CK + JGIT ===");

        System.out.println(
                "CsvRow generate: "
                        + rows.size());

        System.out.printf(
                "Calcolo CK + JGit: %.3f secondi%n",
                (metricsEnd - metricsStart)
                        / 1_000_000_000.0
        );

        // --------------------------------------------------
        // 6. Verifica
        // --------------------------------------------------

        if (rows.size() != javaFiles.size()) {

            System.err.println(
                    "ATTENZIONE: numero CsvRow diverso "
                            + "dal numero di file Java!"
            );

            System.err.println(
                    "Java files = "
                            + javaFiles.size());

            System.err.println(
                    "CsvRow = "
                            + rows.size());
        } else {

            System.out.println(
                    "OK: tutte le classi hanno "
                            + "CK + JGit."
            );
        }

        // --------------------------------------------------
        // 7. Prime 10 righe complete
        // --------------------------------------------------

        System.out.println();
        System.out.println("=== PRIME 10 RIGHE ===");

        rows.stream()
                .limit(10)
                .forEach(row -> {

                    System.out.println();
                    System.out.println(
                            "Classe: "
                                    + row.classPath());

                    System.out.println(
                            "  LOC = "
                                    + row.loc());

                    System.out.println(
                            "  WMC = "
                                    + row.wmc());

                    System.out.println(
                            "  CBO = "
                                    + row.cbo());

                    System.out.println(
                            "  RFC = "
                                    + row.rfc());

                    System.out.println(
                            "  LCOM = "
                                    + row.lcom());

                    System.out.println(
                            "  DIT = "
                                    + row.dit());

                    System.out.println(
                            "  NOC = "
                                    + row.noc());

                    System.out.println(
                            "  FANIN = "
                                    + row.fanin());

                    System.out.println(
                            "  FANOUT = "
                                    + row.fanout());

                    System.out.println(
                            "  commits = "
                                    + row.commitCount());

                    System.out.println(
                            "  fix commits = "
                                    + row.fixCommitCount());

                    System.out.println(
                            "  churn = "
                                    + row.churn());

                    System.out.println(
                            "  avg changeset = "
                                    + row.averageChangeSetSize());

                    System.out.println(
                            "  authors = "
                                    + row.distinctAuthors());

                    System.out.println(
                            "  days since last change = "
                                    + row.daysSinceLastChange());

                    System.out.println(
                            "  change frequency = "
                                    + row.changeFrequency());

                    System.out.println(
                            "  changes last 90 days = "
                                    + row.changeCountLast90Days());

                    System.out.println(
                            "  interval std dev = "
                                    + row.modificationIntervalsStdDev());

                    System.out.println(
                            "  author entropy = "
                                    + row.authorChangeEntropy());

                    System.out.println(
                            "  buggy = "
                                    + row.buggy());
                });

        // --------------------------------------------------
        // 8. Tempo totale
        // --------------------------------------------------

        long end = System.nanoTime();

        System.out.println();
        System.out.println("========================================");

        System.out.printf(
                "TEMPO TOTALE: %.3f secondi%n",
                (end - start) / 1_000_000_000.0
        );

        System.out.println(
                "=== TEST TERMINATO ===");

        System.out.println(
                "========================================");
    }

    private static void printElapsed(
            String operation,
            long nanos) {

        double seconds =
                nanos / 1_000_000_000.0;

        System.out.printf(
                "%s: %.3f secondi%n",
                operation,
                seconds);
    }

}
