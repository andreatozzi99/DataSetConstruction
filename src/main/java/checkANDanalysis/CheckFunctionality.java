package checkANDanalysis;

import config.Config;
import labeling.ProportionCalculator;
import labeling.BuggyLabeler;
import labeling.HistoricalSzzClassResolver;
import labeling.SzzClassEvidence;
import labeling.SzzAnalyzer;
import labeling.SzzDiagnosticResult;
import labeling.SzzLineDiagnostic;
import manager.checkoutManager.CheckoutManager;
import manager.commitManager.CommitManager;
import manager.javaClassScannerManager.JavaClassScanner;
import manager.metricsManager.CKMetrics;
import manager.metricsManager.ClassMetricsCollector;
import manager.metricsManager.JGitMetrics;
import manager.metricsManager.PMDMetrics;
import manager.releaseManager.ReleaseManager;
import manager.ticketManager.TicketManager;
import model.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/**
 * Console diagnostica della pipeline. I controlli sono separati in comandi
 * espliciti: il comando "all" non avvia SZZ, perche' blame sull'intera storia
 * e' intenzionalmente costoso e deve essere scelto in modo consapevole.
 */
public final class CheckFunctionality {
    private static final int EXAMPLE_LIMIT = 10;

    private CheckFunctionality() { }

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        String[] options = Arrays.copyOfRange(args, Math.min(1, args.length), args.length);

        // "help" e' l'unico comando che non produce un risultato di analisi.
        // Ogni altro comando conserva l'ultima esecuzione in un file leggibile,
        // senza rinunciare all'output immediato nella console di IntelliJ/Maven.
        if (command.equals("help")) {
            printUsage();
            return;
        }

        runAndSaveResult(command, () -> executeCommand(command, options));
    }

    /** Esegue il comando richiesto, separato dalla gestione del file di output. */
    private static void executeCommand(String command, String[] options) throws Exception {
        switch (command) {
            case "all" -> runFastChecks();
            case "releases" -> checkReleaseManager();
            case "checkout" -> checkCheckout();
            case "checkouts" -> checkAllCheckouts(readLimit(options, Integer.MAX_VALUE));
            case "tags" -> checkGitTags();
            case "release-commit" -> checkReleaseCommit();
            case "release-commits" -> checkAllReleaseCommits();
            case "scanner" -> checkJavaScanner();
            case "scanner-all" -> checkJavaScannerAllTrainingReleases();
            case "class-names" -> checkClassNameUniqueness();
            case "tickets" -> checkTicketManager();
            case "versions" -> checkVersionMappings();
            case "opening-version" -> checkOpeningVersionMappings();
            case "commits" -> checkFixingCommits();
            case "proportion" -> checkProportionTotal();
            case "metrics" -> checkFirstReleaseMetrics();
            case "csv" -> checkCsv();
            case "szz" -> checkSzz(readLimit(options, 3));
            case "szz-class-resolution" -> checkSzzClassResolution();
            case "labels" -> checkLabeling(readLimit(options, 3));
            case "path-history" -> checkPathHistory();
            default -> printUsage();
        }
    }

    /**
     * Duplica stdout e stderr nella cartella CheckResults. Il file ha un nome
     * stabile (per esempio scanner.txt): rilanciando il controllo si conserva
     * sempre il risultato piu' recente, senza creare una cartella disordinata
     * di esecuzioni vecchie.
     */
    private static void runAndSaveResult(String command, CheckedRunnable action) throws Exception {
        Path resultsDirectory = Path.of("CheckResults");
        Files.createDirectories(resultsDirectory);
        Path resultFile = resultsDirectory.resolve(command.replaceAll("[^a-z0-9_-]", "_") + ".txt");

        PrintStream consoleOut = System.out;
        PrintStream consoleErr = System.err;
        try (PrintStream fileOut = new PrintStream(Files.newOutputStream(resultFile), true, StandardCharsets.UTF_8)) {
            System.setOut(new PrintStream(new TeeOutputStream(consoleOut, fileOut), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new TeeOutputStream(consoleErr, fileOut), true, StandardCharsets.UTF_8));
            System.out.println("Risultato salvato in: " + resultFile.toAbsolutePath());
            System.out.println("Comando: " + command + " | avvio: " + LocalDateTime.now());
            action.run();
        } finally {
            System.setOut(consoleOut);
            System.setErr(consoleErr);
        }
    }

    private static void printUsage() {
        section("CHECK FUNZIONALITA - COMANDI");
        System.out.println("all              controlli rapidi: release, tag, scanner, ticket, commit, P, metriche e CSV");
        System.out.println("releases         release Jira e selezione training");
        System.out.println("checkout         checkout dettagliato della prima release training");
        System.out.println("checkouts [--limit=N] checkout dettagliato di tutte le release (o di un campione)");
        System.out.println("tags             tag Git e associazione tag-release");
        System.out.println("release-commit   commit, autore e distanza temporale della prima release");
        System.out.println("release-commits  mapping temporale tag-commit-release per tutte le release");
        System.out.println("scanner          file Java production/test della prima release");
        System.out.println("scanner-all      scanner Java su tutte le release training");
        System.out.println("class-names      unicita' dei filename/simple class name nelle release training");
        System.out.println("tickets          distribuzione dei ticket Jira e campioni");
        System.out.println("versions         risoluzione Affected/Fix Version verso le release note");
        System.out.println("opening-version  mapping data apertura ticket verso Opening Version");
        System.out.println("commits          fixing commit e classi toccate");
        System.out.println("proportion       calcolo diagnostico della Proportion Total");
        System.out.println("metrics          CK, JGit, PMD e CsvRow della prima release");
        System.out.println("csv              validazione del CSV gia' generato");
        System.out.println("szz --limit=3    SZZ dettagliato su un campione di ticket (lento)");
        System.out.println("szz-class-resolution risoluzione diagnostica globale classi SZZ -> scanner training (molto lento)");
        System.out.println("labels --limit=3 labeling diagnostico sullo stesso campione SZZ (lento)");
        System.out.println("path-history     storia Git dei path SZZ campione, rename/move e presenza nelle 14 release training");
    }

    private static void runFastChecks() throws Exception {
        checkReleaseManager();
        checkGitTags();
        checkJavaScanner();
        checkTicketManager();
        checkFixingCommits();
        checkProportionTotal();
        checkFirstReleaseMetrics();
        checkCsv();
        warn("SZZ e labeling non eseguiti da 'all': usa 'szz' e 'labels' esplicitamente.");
    }

    /** Release e invarianti cronologiche. */
    public static void checkReleaseManager() throws Exception {
        section("RELEASE MANAGER");
        ReleaseManager manager = new ReleaseManager();
        List<Release> releases = manager.getReleases();
        List<Release> training = manager.getTrainingReleases(releases);
        ok("Release totali: " + releases.size());
        ok("Release training: " + training.size());
        boolean indexes = true, dates = true;
        Release previous = null;
        for (Release release : releases) {
            System.out.printf("  #%02d | %-16s | Jira=%-10s | %s%n", release.index(), release.name(), release.jiraId(), release.releaseDate());
            if (previous != null) {
                indexes &= release.index() > previous.index();
                dates &= !release.releaseDate().isBefore(previous.releaseDate());
            }
            previous = release;
        }
        status(indexes, "Indici strettamente crescenti", "Indici release non ordinati");
        status(dates, "Date non decrescenti", "Date release fuori ordine");
        if (!training.isEmpty()) {
            ok("Training: " + training.get(0).name() + " -> " + training.get(training.size() - 1).name());
        }
    }

    /** Tag Git, varianti del nome e ambiguita'. */
    public static void checkGitTags() throws Exception {
        section("GIT TAGS");
        List<Release> releases = new ReleaseManager().getReleases();
        try (Repository repository = Utils.GitRepositoryUtils.openRepository(); Git git = new Git(repository)) {
            List<String> tags = git.tagList().call().stream().map(ref -> Repository.shortenRefName(ref.getName())).sorted().toList();
            ok("Tag totali: " + tags.size());
            System.out.println("Elenco tag:");
            tags.forEach(tag -> System.out.println("  " + tag));
            Map<String, List<String>> releasesByTag = new LinkedHashMap<>();
            for (Release release : releases) {
                List<String> candidates = List.of(release.name(), "v" + release.name());
                List<String> found = candidates.stream().filter(tags::contains).toList();
                if (found.isEmpty()) warn("Release senza tag: " + release.name() + " (cercati " + candidates + ")");
                else {
                    System.out.println("  Release " + release.name() + " -> " + found);
                    for (String tag : found) releasesByTag.computeIfAbsent(tag, ignored -> new ArrayList<>()).add(release.name());
                    if (found.size() > 1) warn("Tag ambiguo per " + release.name() + ": " + found);
                }
            }
            releasesByTag.forEach((tag, names) -> { if (names.size() > 1) warn("Tag duplicato " + tag + " per release " + names); });
        }
    }

    /** Checkout singolo, HEAD e ripristino della copia di lavoro. */
    public static void checkCheckout() throws Exception {
        section("CHECKOUT MANAGER");
        Release release = firstTrainingRelease();
        CheckoutManager checkout = new CheckoutManager();
        System.out.println("Release richiesta: " + release.name());
        try (Repository repository = Utils.GitRepositoryUtils.openRepository(); Git git = new Git(repository)) {
            Optional<String> tag = CheckoutManager.findTag(git, release.name());
            status(tag.isPresent(), "Tag risolto: " + tag.orElse(""), "Nessun tag per la release");
        }
        try {
            checkout.checkoutRelease(release);
            try (Repository repository = Utils.GitRepositoryUtils.openRepository()) {
                ok("Repository: " + checkout.getWorkingDirectory());
                ok("HEAD: " + repository.resolve("HEAD").getName());
                ok("Branch/HEAD: " + repository.getFullBranch());
            }
            ok("Checkout completato");
        } catch (Exception exception) {
            error("Checkout fallito: " + exception.getMessage());
        } finally {
            checkout.cleanRepository();
            try (Repository repository = Utils.GitRepositoryUtils.openRepository(); Git git = new Git(repository)) {
                status(git.status().call().isClean(), "Repository pulito dopo cleanRepository()", "Repository non pulito dopo cleanRepository()");
            }
        }
    }

    /** Tutti i tag sono verificati uno alla volta, mantenendo l'output leggibile. */
    public static void checkAllCheckouts(int limit) throws Exception {
        section("CHECKOUT DI TUTTE LE RELEASE");
        CheckoutManager checkout = new CheckoutManager();
        List<Release> releases = new ReleaseManager().getReleases().stream().limit(limit).toList();
        try (Repository repository = Utils.GitRepositoryUtils.openRepository(); Git git = new Git(repository)) {
            if (!git.status().call().isClean()) {
                error("Clone Storm non pulito: il test non effettua checkout per non sovrascrivere file locali.");
                error("Ripristina o usa un clone pulito, poi rilancia il test.");
                return;
            }
        }
        int success = 0;
        try {
            for (Release release : releases) {
                try {
                    checkout.checkoutRelease(release);
                    try (Repository repository = Utils.GitRepositoryUtils.openRepository(); Git git = new Git(repository); RevWalk walk = new RevWalk(repository)) {
                        String tag = CheckoutManager.findTag(git, release.name()).orElse("<assente>");
                        RevCommit commit = walk.parseCommit(repository.resolve("HEAD"));
                        LocalDate commitDate = commit.getAuthorIdent().getWhen().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                        long delta = Duration.between(commitDate.atStartOfDay(), release.releaseDate().atStartOfDay()).toDays();
                        System.out.println("[OK] release=" + release.name() + " | tag=" + tag + " | HEAD=" + commit.getName()
                                + " | commitDate=" + commitDate + " | releaseDate=" + release.releaseDate() + " | delta=" + delta + " giorni");
                        if (delta < 0) warn("Commit successivo alla release per " + release.name());
                    }
                    success++;
                } catch (Exception exception) {
                    error(release.name() + " | " + rootMessage(exception));
                }
            }
        } finally {
            try {
                checkout.cleanRepository();
            } catch (Exception exception) {
                error("Ripristino finale del clone non riuscito: " + rootMessage(exception));
            }
        }
        status(success == releases.size(), "Checkout riusciti: " + success + "/" + releases.size(), "Checkout riusciti: " + success + "/" + releases.size());
    }

    public static void checkReleaseCommit() throws Exception {
        section("RELEASE COMMIT");
        Release release = firstTrainingRelease();
        RevCommit commit = CommitManager.getReleaseCommit(release);
        LocalDate commitDate = commit.getAuthorIdent().getWhen().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        try (Repository repository = Utils.GitRepositoryUtils.openRepository(); Git git = new Git(repository)) {
            System.out.println("Release: " + release.name() + " | data=" + release.releaseDate());
            System.out.println("Tag: " + CheckoutManager.findTag(git, release.name()).orElse("<assente>"));
            System.out.println("Commit: " + commit.getName());
            System.out.println("Autore: " + commit.getAuthorIdent().getName() + " <" + commit.getAuthorIdent().getEmailAddress() + ">");
            System.out.println("Data commit: " + commitDate);
            System.out.println("Messaggio: " + commit.getShortMessage());
            System.out.println("Differenza commit-release: " + Duration.between(commitDate.atStartOfDay(), release.releaseDate().atStartOfDay()).toDays() + " giorni");
        }
    }

    /** Stesso controllo temporale della singola release, applicato a tutte le release Jira. */
    public static void checkAllReleaseCommits() throws Exception {
        section("MAPPING TEMPORALE RELEASE - TAG - COMMIT");
        List<Release> releases = new ReleaseManager().getReleases();
        int mapped = 0;
        try (Repository repository = Utils.GitRepositoryUtils.openRepository(); Git git = new Git(repository)) {
            for (Release release : releases) {
                Optional<String> tag = CheckoutManager.findTag(git, release.name());
                if (tag.isEmpty()) { error("release=" + release.name() + " | tag assente"); continue; }
                RevCommit commit = CommitManager.getReleaseCommit(release);
                LocalDate commitDate = commit.getAuthorIdent().getWhen().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                long delta = Duration.between(commitDate.atStartOfDay(), release.releaseDate().atStartOfDay()).toDays();
                System.out.println("release=" + release.name() + " | tag=" + tag.get() + " | commit=" + commit.getName() + " | commitDate=" + commitDate + " | releaseDate=" + release.releaseDate() + " | delta=" + delta + " giorni");
                if (delta < 0) warn("Commit successivo alla release: " + release.name()); else ok("Coerenza temporale: " + release.name());
                mapped++;
            }
        }
        status(mapped == releases.size(), "Mapping tag-commit trovato per " + mapped + "/" + releases.size() + " release", "Mapping incompleto: " + mapped + "/" + releases.size());
    }

    public static void checkJavaScanner() throws Exception {
        section("JAVA CLASS SCANNER");
        CheckoutManager checkout = new CheckoutManager();
        JavaClassScanner scanner = new JavaClassScanner();
        checkout.checkoutRelease(firstTrainingRelease());
        try {
            Path repository = checkout.getRepositoryPath();
            List<Path> production = scanner.findJavaFiles(repository);
            List<Path> allJava;
            try (var paths = Files.walk(repository)) { allJava = paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList(); }
            // Questo secondo insieme NON usa il risultato dello scanner: riparte
            // da tutti i .java fisicamente presenti e applica il medesimo filtro
            // pubblico usato da scanner, SZZ e CommitManager. In questo modo il
            // test dimostra davvero quanti file vengono esclusi.
            List<Path> acceptedByProductionFilter = allJava.stream()
                    .filter(path -> JavaClassScanner.isJavaProductionFile(relative(repository, path)))
                    .toList();
            List<Path> excluded = allJava.stream()
                    .filter(path -> !JavaClassScanner.isJavaProductionFile(relative(repository, path)))
                    .toList();
            ok("File Java totali: " + allJava.size());
            ok("File Java production: " + production.size());
            ok("File Java esclusi come test: " + excluded.size());
            status(new TreeSet<>(production).equals(new TreeSet<>(acceptedByProductionFilter)),
                    "Scanner coerente con isJavaProductionFile(): " + production.size() + " file accettati",
                    "Scanner e isJavaProductionFile() producono insiemi diversi: scanner="
                            + production.size() + ", filtro=" + acceptedByProductionFilter.size());
            System.out.println("Primi 20 path production:");
            production.stream().limit(20).map(path -> relative(repository, path)).forEach(path -> System.out.println("  " + path));
            System.out.println("Esempi path test esclusi:");
            excluded.stream().limit(5).map(path -> relative(repository, path)).forEach(path -> System.out.println("  " + path));
            boolean valid = production.stream().map(path -> relative(repository, path)).allMatch(path -> JavaClassScanner.isJavaProductionFile(path) && !path.startsWith("/") && !path.contains("\\"));
            status(valid, "Path production relativi, normalizzati e senza test", "Scanner ha incluso path non validi");
        } finally { checkout.cleanRepository(); }
    }

    /**
     * Esegue lo stesso confronto fisico/filtro dello scanner su ogni release
     * del training set. Questo e' il test che esercita davvero versioni con
     * directory test non standard, non soltanto la prima release storica.
     */
    public static void checkJavaScannerAllTrainingReleases() throws Exception {
        section("JAVA CLASS SCANNER - TUTTE LE RELEASE TRAINING");
        ReleaseManager releaseManager = new ReleaseManager();
        List<Release> training = releaseManager.getTrainingReleases(releaseManager.getReleases());
        CheckoutManager checkout = new CheckoutManager();
        JavaClassScanner scanner = new JavaClassScanner();
        int coherent = 0;
        try {
            for (Release release : training) {
                checkout.checkoutRelease(release);
                ScannerCounts counts = scanJavaFiles(checkout.getRepositoryPath(), scanner);
                boolean sameSet = counts.scannerPaths().equals(counts.filteredPaths());
                System.out.println("release=" + release.name() + " | java totali=" + counts.totalJava()
                        + " | production=" + counts.production() + " | test esclusi=" + counts.excluded());
                status(sameSet, "Filtro e scanner coincidono per " + release.name(),
                        "Filtro e scanner diversi per " + release.name());
                if (!counts.excludedPaths().isEmpty()) {
                    System.out.println("  Esempi esclusi: " + counts.excludedPaths().stream().limit(5).toList());
                }
                if (sameSet) coherent++;
            }
        } finally {
            checkout.cleanRepository();
        }
        status(coherent == training.size(), "Scanner coerente su " + coherent + "/" + training.size() + " release training",
                "Scanner coerente su " + coherent + "/" + training.size() + " release training");
    }

    /**
     * Osserva soltanto il nome del file Java (simple class name) dopo che lo
     * scanner ha gia' escluso test e file non-production. Non deduce alcun
     * mapping: serve a quantificare quanto sia rischioso usare un filename.
     */
    public static void checkClassNameUniqueness() throws Exception {
        section("UNICITA' FILENAME / SIMPLE CLASS NAME - RELEASE TRAINING");
        ReleaseManager releaseManager = new ReleaseManager();
        List<Release> training = releaseManager.getTrainingReleases(releaseManager.getReleases());
        Map<String, Set<String>> pathsByRelease = scanTrainingPaths(training);
        if (pathsByRelease.isEmpty()) return;

        for (Release release : training) {
            Set<String> paths = pathsByRelease.getOrDefault(release.name(), Set.of());
            Map<String, List<String>> pathsBySimpleName = paths.stream().collect(Collectors.groupingBy(
                    CheckFunctionality::simpleClassName, TreeMap::new, Collectors.toList()));
            long uniqueNames = pathsBySimpleName.values().stream().filter(values -> values.size() == 1).count();
            Map<String, List<String>> duplicateNames = pathsBySimpleName.entrySet().stream()
                    .filter(entry -> entry.getValue().size() > 1)
                    .collect(Collectors.toMap(Map.Entry::getKey,
                            entry -> entry.getValue().stream().sorted().toList(),
                            (first, second) -> first, TreeMap::new));
            double uniquePercentage = paths.isEmpty() ? 0 : 100.0 * uniqueNames / paths.size();
            System.out.println("release=" + release.name() + " | totale classi=" + paths.size()
                    + " | nomi unici=" + uniqueNames + " | nomi duplicati=" + duplicateNames.size()
                    + " | % univoci=" + format(uniquePercentage) + "%");
            if (duplicateNames.isEmpty()) ok("Nessun simple class name duplicato in " + release.name());
            else duplicateNames.forEach((name, duplicates) -> System.out.println("  nome duplicato=" + name + " | path=" + duplicates));
        }
    }

    private static String simpleClassName(String path) {
        String filename = path.substring(path.lastIndexOf('/') + 1);
        return filename.endsWith(".java") ? filename.substring(0, filename.length() - ".java".length()) : filename;
    }

    /** AV/FV sono utili soltanto se i loro nomi corrispondono alle release Jira caricate. */
    public static void checkVersionMappings() throws Exception {
        section("MAPPING JIRA AFFECTED/FIX VERSION -> RELEASE");
        List<Release> releases = new ReleaseManager().getReleases();
        Set<String> knownNames = releases.stream().map(Release::name).collect(Collectors.toSet());
        List<Ticket> tickets = fixedTickets();
        VersionMappingCounts av = countVersionMappings(tickets, Ticket::affectedVersions, knownNames);
        VersionMappingCounts fv = countVersionMappings(tickets, Ticket::fixVersions, knownNames);
        ok("AV totali dichiarate: " + av.declared());
        ok("AV risolte: " + av.resolved());
        warn("AV non risolte nel corpus Jira: " + av.unresolved().size());
        ok("FV totali dichiarate: " + fv.declared());
        ok("FV risolte: " + fv.resolved());
        warn("FV non risolte nel corpus Jira: " + fv.unresolved().size());
        System.out.println("Esempi AV non risolte nel corpus Jira: " + av.unresolved().stream().limit(EXAMPLE_LIMIT).toList());
        System.out.println("Esempi FV non risolte nel corpus Jira: " + fv.unresolved().stream().limit(EXAMPLE_LIMIT).toList());
        System.out.println("Campioni ticket | AV dichiarate -> risolte | FV dichiarate -> risolte:");
        tickets.stream().limit(EXAMPLE_LIMIT).forEach(ticket -> System.out.println("  " + ticket.key()
                + " | AV=" + ticket.affectedVersions() + " -> " + resolvedVersions(ticket.affectedVersions(), knownNames)
                + " | FV=" + ticket.fixVersions() + " -> " + resolvedVersions(ticket.fixVersions(), knownNames)));
    }

    /** OV e' ottenuta dalla data di creazione, non dal testo delle versioni Jira. */
    public static void checkOpeningVersionMappings() throws Exception {
        section("MAPPING DATA APERTURA TICKET -> OPENING VERSION");
        List<Release> releases = new ReleaseManager().getReleases();
        ProportionCalculator calculator = new ProportionCalculator();
        List<Ticket> tickets = fixedTickets();
        LocalDate lastReleaseDate = releases.stream().map(Release::releaseDate).max(Comparator.naturalOrder()).orElse(null);
        List<OpeningVersionDiagnostic> diagnostics = new ArrayList<>();
        long afterCorpus = 0, noOv = 0, resolved = 0;
        for (Ticket ticket : tickets) {
            Optional<Release> ov = calculator.findOpeningVersion(ticket, releases);
            String status = "RESOLVED";
            if (ov.isEmpty() && ticket.creationDate() != null && lastReleaseDate != null && ticket.creationDate().isAfter(lastReleaseDate)) {
                status = "OV_AFTER_CORPUS";
                afterCorpus++;
            } else if (ov.isEmpty()) {
                status = "NO_OV";
                noOv++;
            } else {
                resolved++;
            }
            diagnostics.add(new OpeningVersionDiagnostic(ticket, ov, status));
        }
        ok("Ticket analizzati: " + tickets.size());
        ok("Ticket con OV determinata: " + resolved);
        if (afterCorpus > 0) warn("Ticket OV_AFTER_CORPUS: " + afterCorpus + " (creationDate successiva all'ultima release Jira)");
        else ok("Ticket OV_AFTER_CORPUS: 0");
        if (noOv > 0) warn("Ticket NO_OV: " + noOv);
        else ok("Ticket NO_OV: 0");

        System.out.println("Primi 10 esempi ticket | creationDate | OV scelta:");
        diagnostics.stream().limit(EXAMPLE_LIMIT).forEach(diagnostic -> System.out.println("  "
                + diagnostic.ticket().key() + " | " + diagnostic.ticket().creationDate() + " | "
                + diagnostic.openingVersion().map(release -> release.index() + "/" + release.name()).orElse("<assente>")
                + " | " + diagnostic.status()));

        /*
         * Non correggiamo qui i ticket FV_LE_OV. Questo campione replica
         * esattamente la condizione usata dal report Proportion e serve solo a
         * capire se l'anomalia dipende da date, ordinamento o dati Jira.
         */
        List<OpeningVersionDiagnostic> fvLeOv = diagnostics.stream()
                .filter(diagnostic -> !diagnostic.ticket().affectedVersions().isEmpty())
                .filter(diagnostic -> firstAffected(diagnostic.ticket(), releases).isPresent())
                .filter(diagnostic -> diagnostic.openingVersion().isPresent())
                .filter(diagnostic -> calculator.resolveFixedVersion(diagnostic.ticket(), releases).status()
                        == ProportionCalculator.FixedVersionStatus.RESOLVED)
                .filter(diagnostic -> calculator.resolveFixedVersion(diagnostic.ticket(), releases).release().orElseThrow().index()
                        <= diagnostic.openingVersion().orElseThrow().index())
                .toList();
        long fvEqOv = fvLeOv.stream().filter(diagnostic -> calculator.resolveFixedVersion(diagnostic.ticket(), releases)
                .release().orElseThrow().index() == diagnostic.openingVersion().orElseThrow().index()).count();
        List<OpeningVersionDiagnostic> fvLtOv = fvLeOv.stream().filter(diagnostic -> calculator.resolveFixedVersion(diagnostic.ticket(), releases)
                .release().orElseThrow().index() < diagnostic.openingVersion().orElseThrow().index()).toList();
        System.out.println("FV_EQ_OV: " + fvEqOv + " | FV_LT_OV: " + fvLtOv.size());
        System.out.println("Campione FV_LE_OV (massimo 15 su " + fvLeOv.size() + "):");
        fvLeOv.stream().limit(15).forEach(diagnostic -> {
            Release ov = diagnostic.openingVersion().orElseThrow();
            Release fv = calculator.resolveFixedVersion(diagnostic.ticket(), releases).release().orElseThrow();
            System.out.println("  " + diagnostic.ticket().key() + " | creationDate=" + diagnostic.ticket().creationDate()
                    + " | OV=" + ov.index() + "/" + ov.name() + " | FV=" + fv.index() + "/" + fv.name()
                    + " | AV=" + diagnostic.ticket().affectedVersions());
        });
        if (fvLeOv.size() < 15) warn("Campione FV_LE_OV con meno di 15 ticket disponibili: " + fvLeOv.size());
        if (!fvLtOv.isEmpty()) {
            System.out.println("Esempi FV_LT_OV (massimo 10 su " + fvLtOv.size() + "):");
            fvLtOv.stream().limit(EXAMPLE_LIMIT).forEach(diagnostic -> {
                Release ov = diagnostic.openingVersion().orElseThrow();
                Release fv = calculator.resolveFixedVersion(diagnostic.ticket(), releases).release().orElseThrow();
                System.out.println("  " + diagnostic.ticket().key() + " | creationDate=" + diagnostic.ticket().creationDate()
                        + " | OV=" + ov.index() + "/" + ov.name() + " | FV=" + fv.index() + "/" + fv.name()
                        + " | AV=" + diagnostic.ticket().affectedVersions());
            });
        }
    }

    public static void checkTicketManager() throws Exception {
        section("TICKET MANAGER");
        TicketManager manager = new TicketManager();
        ok("Issue totali progetto: " + manager.countIssues("project = " + Config.PROJECT_KEY));
        ok("Closed/Resolved (ogni tipo): " + manager.countIssues("project = " + Config.PROJECT_KEY + " AND status in (Closed, Resolved)"));
        List<Ticket> tickets = manager.getTickets();
        List<Ticket> fixed = manager.getFixedTickets();
        ok("Ticket dettagliati recuperati dalla query Bug + Closed/Resolved + Fixed: " + tickets.size());
        status(tickets.size() == fixed.size(), "Ogni ticket caricato rispetta il filtro Bug/Fixed", "Il filtro ticket non e' coerente");
        countAndPrint("Type", tickets, Ticket::type);
        countAndPrint("Status", tickets, Ticket::status);
        countAndPrint("Resolution", tickets, Ticket::resolution);
        long withAv = tickets.stream().filter(ticket -> !ticket.affectedVersions().isEmpty()).count();
        long withFv = tickets.stream().filter(ticket -> !ticket.fixVersions().isEmpty()).count();
        long both = tickets.stream().filter(ticket -> !ticket.affectedVersions().isEmpty() && !ticket.fixVersions().isEmpty()).count();
        System.out.println("AV presenti/assenti: " + withAv + "/" + (tickets.size() - withAv));
        System.out.println("FV presenti/assenti: " + withFv + "/" + (tickets.size() - withFv));
        System.out.println("AV e FV / nessuna delle due: " + both + "/" + tickets.stream().filter(ticket -> ticket.affectedVersions().isEmpty() && ticket.fixVersions().isEmpty()).count());
        System.out.println("Creation date nulle: " + tickets.stream().filter(ticket -> ticket.creationDate() == null).count());
        System.out.println("Resolution date nulle: " + tickets.stream().filter(ticket -> ticket.resolutionDate() == null).count());
        System.out.println("Primi 10 ticket:");
        tickets.stream().limit(EXAMPLE_LIMIT).forEach(ticket -> System.out.println("  " + ticket.key() + " | AV=" + ticket.affectedVersions() + " | FV=" + ticket.fixVersions() + " | created=" + ticket.creationDate() + " | resolved=" + ticket.resolutionDate()));
    }

    public static void checkFixingCommits() throws Exception {
        section("COMMIT MANAGER - FIXING COMMIT E CLASSI");
        List<Ticket> tickets = fixedTickets();
        CommitManager manager = new CommitManager();
        Map<String, List<RevCommit>> byTicket = manager.findFixCommits(ticketKeys(tickets));
        long withCommits = byTicket.values().stream().filter(commits -> !commits.isEmpty()).count();
        long totalCommits = byTicket.values().stream().mapToLong(List::size).sum();
        int max = byTicket.values().stream().mapToInt(List::size).max().orElse(0);
        ok("Ticket analizzati: " + tickets.size());
        ok("Ticket con fixing commit: " + withCommits);
        warn("Ticket senza fixing commit: " + (tickets.size() - withCommits));
        warn("Ticket esclusi dal labeling per assenza fixing commit: " + (tickets.size() - withCommits));
        System.out.println("Esempi ticket senza fixing commit:");
        byTicket.entrySet().stream().filter(entry -> entry.getValue().isEmpty()).limit(EXAMPLE_LIMIT)
                .forEach(entry -> System.out.println("  " + entry.getKey()));
        ok("Associazioni ticket -> fixing commit: " + totalCommits + " | media=" + format((double) totalCommits / Math.max(1, tickets.size())) + " | massimo=" + max);
        byTicket.entrySet().stream().filter(entry -> entry.getValue().size() == max && max > 0).limit(5).forEach(entry -> System.out.println("  Ticket con massimo: " + entry.getKey() + " -> " + entry.getValue().size()));
        Map<String, List<String>> ticketsByCommit = new LinkedHashMap<>();
        byTicket.forEach((ticket, commits) -> commits.forEach(commit -> ticketsByCommit.computeIfAbsent(commit.getName(), ignored -> new ArrayList<>()).add(ticket)));
        ticketsByCommit.forEach((commit, keys) -> { if (keys.size() > 1) warn("Commit associato a piu ticket: " + commit + " -> " + keys); });
        System.out.println("Esempi commit:");
        byTicket.entrySet().stream().filter(entry -> !entry.getValue().isEmpty()).limit(EXAMPLE_LIMIT).forEach(entry -> {
            RevCommit commit = entry.getValue().get(0);
            System.out.println("  " + entry.getKey() + " | " + commit.getName() + " | " + commit.getAuthorIdent().getWhen() + " | " + commit.getShortMessage());
        });
        byTicket.entrySet().stream().filter(entry -> !entry.getValue().isEmpty()).findFirst().ifPresent(entry -> {
            try { printTouchedClassDiagnostic(entry.getKey(), entry.getValue()); } catch (Exception exception) { error("Classi toccate: " + exception.getMessage()); }
        });
    }

    private static void printTouchedClassDiagnostic(String ticket, List<RevCommit> commits) throws Exception {
        section("CLASSI TOCCATE - CAMPIONE " + ticket);
        int totalFiles = 0, production = 0, test = 0;
        Set<String> productionPaths = new TreeSet<>();
        try (Repository repository = Utils.GitRepositoryUtils.openRepository()) {
            for (RevCommit commit : commits) {
                FileCounts counts = countChangedFiles(repository, commit);
                totalFiles += counts.total(); production += counts.production(); test += counts.test(); productionPaths.addAll(counts.productionPaths());
            }
        }
        System.out.println("Fixing commit: " + commits.size() + " | file toccati=" + totalFiles + " | Java production=" + production + " | Java test esclusi=" + test);
        productionPaths.forEach(path -> System.out.println("  " + path));
    }

    public static void checkProportionTotal() throws Exception {
        section("PROPORTION TOTAL");
        List<Release> releases = new ReleaseManager().getReleases();
        List<Ticket> tickets = fixedTickets();
        ProportionCalculator calculator = new ProportionCalculator();
        Map<String, Release> knownIv = new LinkedHashMap<>();
        Map<String, Integer> discarded = new TreeMap<>();
        List<ProportionSample> samples = new ArrayList<>();
        List<TemporalOrderDiagnostic> invalidIvFv = new ArrayList<>();
        for (Ticket ticket : tickets) {
            Optional<Release> iv = firstAffected(ticket, releases);
            Optional<Release> ov = calculator.findOpeningVersion(ticket, releases);
            ProportionCalculator.FixedVersionResolution fixedResolution = calculator.resolveFixedVersion(ticket, releases);
            Optional<Release> fv = fixedResolution.release();
            if (ticket.affectedVersions().isEmpty()) { increment(discarded, "NO_AV_DECLARED"); continue; }
            if (iv.isEmpty()) { increment(discarded, "AV_UNRESOLVED"); continue; }
            if (ov.isEmpty()) { increment(discarded, "NO_OV"); continue; }
            if (fixedResolution.status() == ProportionCalculator.FixedVersionStatus.NO_FV) { increment(discarded, "NO_FV"); continue; }
            if (fixedResolution.status() == ProportionCalculator.FixedVersionStatus.FV_UNRESOLVED) { increment(discarded, "FV_UNRESOLVED"); continue; }
            if (fixedResolution.status() == ProportionCalculator.FixedVersionStatus.FV_AFTER_CORPUS) { increment(discarded, "FV_AFTER_CORPUS"); continue; }
            OptionalDouble value = calculator.calculateSingleProportion(iv, fv, ov);
            if (value.isEmpty()) {
                if (fv.get().index() == ov.get().index()) increment(discarded, "FV_EQ_OV");
                else if (fv.get().index() < ov.get().index()) increment(discarded, "FV_LT_OV");
                else {
                    String reason = iv.get().index() == fv.get().index() ? "IV_EQ_FV" : "IV_GT_FV";
                    increment(discarded, reason);
                    invalidIvFv.add(new TemporalOrderDiagnostic(ticket, iv.get(), fv.get(), ov.get(), reason));
                }
                continue;
            }
            knownIv.put(ticket.key(), iv.get());
            samples.add(new ProportionSample(ticket, iv.get(), ov.get(), fv.get(), value.getAsDouble()));
        }
        OptionalDouble total = calculator.calculateProportionTotal(tickets, releases, knownIv);
        ok("Ticket usati per P: " + samples.size());
        discarded.forEach((reason, count) -> warn("Ticket scartati - " + reason + ": " + count));
        long ivEqFv = invalidIvFv.stream().filter(diagnostic -> diagnostic.reason().equals("IV_EQ_FV")).count();
        long ivGtFv = invalidIvFv.size() - ivEqFv;
        System.out.println("Esclusioni IV >= FV totali: " + invalidIvFv.size() + " | IV_EQ_FV: " + ivEqFv + " | IV_GT_FV: " + ivGtFv);
        if (!invalidIvFv.isEmpty()) {
            System.out.println("Esempi IV_EQ_FV (massimo 10 su " + ivEqFv + "):");
            invalidIvFv.stream().filter(diagnostic -> diagnostic.reason().equals("IV_EQ_FV")).limit(10).forEach(diagnostic -> System.out.println("  "
                    + diagnostic.ticket().key() + " | AV dichiarate=" + diagnostic.ticket().affectedVersions()
                    + " | IV scelta=" + diagnostic.iv().index() + "/" + diagnostic.iv().name()
                    + " | FV=" + diagnostic.fv().index() + "/" + diagnostic.fv().name()
                    + " | OV=" + diagnostic.ov().index() + "/" + diagnostic.ov().name()
                    + " | stato=" + diagnostic.reason()));
            System.out.println("Esempi IV_GT_FV (massimo 10 su " + ivGtFv + "):");
            invalidIvFv.stream().filter(diagnostic -> diagnostic.reason().equals("IV_GT_FV")).limit(10).forEach(diagnostic -> System.out.println("  "
                    + diagnostic.ticket().key() + " | AV dichiarate=" + diagnostic.ticket().affectedVersions()
                    + " | IV scelta=" + diagnostic.iv().index() + "/" + diagnostic.iv().name()
                    + " | FV=" + diagnostic.fv().index() + "/" + diagnostic.fv().name()
                    + " | OV=" + diagnostic.ov().index() + "/" + diagnostic.ov().name()
                    + " | stato=" + diagnostic.reason()));
        }
        List<ProportionSample> ivGtOvUsedForP = samples.stream()
                .filter(sample -> sample.iv().index() > sample.ov().index())
                .toList();
        printReleaseLineageDiagnostics(samples, ivGtOvUsedForP);
        System.out.println("IV_GT_OV_USED_FOR_P: " + ivGtOvUsedForP.size());
        if (!ivGtOvUsedForP.isEmpty()) {
            System.out.println("Esempi IV_GT_OV usati per P (massimo 10):");
            ivGtOvUsedForP.stream().limit(EXAMPLE_LIMIT).forEach(sample -> System.out.println("  "
                    + sample.ticket().key() + " | IV=" + sample.iv().index() + "/" + sample.iv().name()
                    + " | OV=" + sample.ov().index() + "/" + sample.ov().name()
                    + " | FV=" + sample.fv().index() + "/" + sample.fv().name()
                    + " | P=" + format(sample.value())));
        }
        // L'ancestry osserva tutti i ticket validi per P. Non restituisce
        // informazioni al ProportionCalculator: serve esclusivamente a capire
        // se gli indici cronologici Jira stanno mescolando rami Git distinti.
        printGitAncestryDiagnostics(samples, releases);
        if (total.isEmpty()) { error("P_total non calcolabile: nessun ticket valido"); return; }
        List<Double> values = samples.stream().map(ProportionSample::value).sorted().toList();
        double median = values.size() % 2 == 0 ? (values.get(values.size()/2-1)+values.get(values.size()/2))/2 : values.get(values.size()/2);
        System.out.println("P min=" + format(values.get(0)) + " | max=" + format(values.get(values.size()-1)) + " | mediana=" + format(median));
        ok("P_total (media): " + format(total.getAsDouble()));
        System.out.println("P > 1: " + values.stream().filter(value -> value > 1).count() + " | P == 0: " + values.stream().filter(value -> value == 0).count() + " | P negativi: " + values.stream().filter(value -> value < 0).count());
        System.out.println("Campioni P:");
        samples.stream().limit(EXAMPLE_LIMIT).forEach(sample -> System.out.println("  " + sample.ticket().key() + " | IV=" + sample.iv().index() + "/" + sample.iv().name() + " | OV=" + sample.ov().index() + "/" + sample.ov().name() + " | FV=" + sample.fv().index() + "/" + sample.fv().name() + " | P=" + format(sample.value())));
        System.out.println("Stime per ticket senza AV:");
        tickets.stream().filter(ticket -> firstAffected(ticket, releases).isEmpty()).limit(EXAMPLE_LIMIT).forEach(ticket -> printPrediction(ticket, releases, calculator, total.getAsDouble()));
    }

    public static void checkSzz(int limit) throws Exception {
        int requested = Math.max(3, limit);
        section("SZZ - CAMPIONE MIRATO SULLE RELEASE TRAINING (" + requested + " TICKET)");
        List<Release> releases = new ReleaseManager().getReleases();
        List<Release> training = new ReleaseManager().getTrainingReleases(releases);
        List<Ticket> tickets = fixedTickets();
        CommitManager manager = new CommitManager();
        Map<String, List<RevCommit>> commitsByTicket = manager.findFixCommits(ticketKeys(tickets));
        List<TicketDiagnosticContext> contexts = diagnosticContexts(tickets, releases);
        List<TicketDiagnosticContext> eligible = contexts.stream()
                .filter(context -> intersectsTraining(context, training))
                .filter(context -> !commitsByTicket.getOrDefault(context.ticket().key(), List.of()).isEmpty()).toList();
        List<String> requestedTickets = List.of("STORM-3052", "STORM-3132", "STORM-2950");
        LinkedHashSet<TicketDiagnosticContext> selectedSet = eligible.stream()
                .filter(context -> requestedTickets.contains(context.ticket().key()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        eligible.stream().filter(context -> context.source().equals("AFFECTED_VERSION"))
                .forEach(context -> { if (selectedSet.size() < requested) selectedSet.add(context); });
        List<TicketDiagnosticContext> selected = List.copyOf(selectedSet);
        System.out.println("Criterio campione: ticket richiesti STORM-3052/STORM-3132/STORM-2950, con intervallo [IV,FV) che interseca il training e fixing commit.");
        System.out.println("Blame diagnostico: primo parent del fixing commit, cioe' codice prima della correzione; follow rename JGit=true solo in questo check.");
        if (selected.size() < 3) warn("Trovati solo " + selected.size() + " ticket con tutti i criteri; il campione non puo' arrivare a tre.");
        int results = 0; long inducing = 0;
        boolean printedLineDetail = false;
        try (Repository repository = Utils.GitRepositoryUtils.openRepository()) {
            SzzAnalyzer analyzer = new SzzAnalyzer(repository);
            for (TicketDiagnosticContext context : selected) {
                List<RevCommit> fixingCommits = commitsByTicket.get(context.ticket().key());
                System.out.println("Ticket " + context.ticket().key() + " | IV=" + releaseDescription(context.iv())
                        + " | FV=" + releaseDescription(context.fv()) + " | fixing commit=" + fixingCommits.size());
                for (RevCommit fixing : fixingCommits) {
                    SzzDiagnosticResult result = analyzer.analyseFixingCommitForDiagnostics(fixing);
                    FileCounts fixingFiles = countChangedFiles(repository, fixing);
                    long distinct = result.inducingCommitsByClass().values().stream().flatMap(Set::stream).map(RevCommit::getName).distinct().count();
                    System.out.println("  Fix=" + fixing.getName() + " | parent=" + fixing.getParent(0).getName());
                    System.out.println("    file=" + result.changedFiles() + " | Java production=" + result.productionJavaFiles() + " | edit=" + result.edits() + " | INSERT ignorati=" + result.ignoredInsertEdits() + " | righe analizzate=" + result.analysedParentLines());
                    System.out.println("    fixing files: " + fixingFiles.productionPaths());
                    System.out.println("    szz files:    " + result.inducingCommitsByClass().keySet());
                    System.out.println("    classi SZZ=" + result.inducingCommitsByClass().size() + " | inducing distinti=" + distinct);
                    result.inducingCommitsByClass().forEach((path, commits) -> System.out.println("      " + path + " -> " + commits.size() + " inducing commit"));
                    if (!result.analysedLines().isEmpty()) {
                        System.out.println("    Dettaglio righe blamed (parent del fix " + fixing.getParent(0).getName() + "):");
                        result.analysedLines().forEach(line -> printBlamedLine(context, line));
                        printedLineDetail = true;
                    }
                    if (!result.inducingCommitsByClass().isEmpty()) results++;
                    inducing += distinct;
                }
            }
        }
        if (!printedLineDetail) warn("Nessuna riga DELETE/REPLACE analizzabile nel campione: dettaglio blame non disponibile.");
        System.out.println("Ticket con fixing commit nel campione: " + selected.size() + " | risultati SZZ non vuoti: " + results + " | inducing commit osservati: " + inducing);
    }

    /**
     * Misura quanto spesso una classe SZZ possa essere ritrovata, senza
     * applicare alcun mapping al dataset. Sono considerate soltanto le coppie
     * ticket/classe/release che il labeler potrebbe marcare nel training.
     */
    public static void checkSzzClassResolution() throws Exception {
        section("RISOLUZIONE GLOBALE CLASSI SZZ -> RELEASE TRAINING");
        List<Release> releases = new ReleaseManager().getReleases();
        List<Release> training = new ReleaseManager().getTrainingReleases(releases);
        List<Ticket> tickets = fixedTickets();
        ProportionCalculator calculator = new ProportionCalculator();
        List<TicketDiagnosticContext> eligibleContexts = diagnosticContexts(tickets, releases).stream()
                .filter(context -> touchesTrainingInActualLabeling(context, calculator, releases, training)).toList();
        Set<String> eligibleKeys = eligibleContexts.stream().map(context -> context.ticket().key())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        ok("Ticket con intervallo di labeling potenzialmente nel training: " + eligibleKeys.size());

        Map<String, Set<String>> scannerPathsByRelease = scanTrainingPaths(training);
        if (scannerPathsByRelease.isEmpty()) return;

        CommitManager commitManager = new CommitManager();
        Map<String, List<RevCommit>> fixesByTicket = commitManager.findFixCommits(eligibleKeys);
        Map<String, SzzDiagnosticResult> analysisByFixId = new HashMap<>();
        Map<String, Map<String, Set<String>>> sourcePathsByTicketAndClass = new LinkedHashMap<>();
        Map<String, Map<String, InducingCommitEvidence>> earliestInducingByTicketAndClass = new LinkedHashMap<>();
        Map<String, Set<String>> szzClassesByTicket = new LinkedHashMap<>();
        int uniqueFixes = (int) fixesByTicket.values().stream().flatMap(List::stream).map(RevCommit::getName).distinct().count();
        int analysedFixes = 0;
        System.out.println("Fixing commit distinti da analizzare con SZZ diagnostico: " + uniqueFixes);

        try (Repository repository = Utils.GitRepositoryUtils.openRepository()) {
            SzzAnalyzer analyzer = new SzzAnalyzer(repository);
            for (TicketDiagnosticContext context : eligibleContexts) {
                String ticket = context.ticket().key();
                for (RevCommit fix : fixesByTicket.getOrDefault(ticket, List.of())) {
                    SzzDiagnosticResult result = analysisByFixId.get(fix.getName());
                    if (result == null) {
                        // Questo metodo e' separato da findInducingCommits:
                        // segue i rename solo per leggere i source path nel report.
                        result = analyzer.analyseFixingCommitForDiagnostics(fix);
                        analysisByFixId.put(fix.getName(), result);
                        analysedFixes++;
                        if (analysedFixes % 50 == 0 || analysedFixes == uniqueFixes)
                            System.out.println("  SZZ diagnostico completato: " + analysedFixes + "/" + uniqueFixes + " fixing commit distinti");
                    }
                    Set<String> classes = szzClassesByTicket.computeIfAbsent(ticket, ignored -> new LinkedHashSet<>());
                    classes.addAll(result.inducingCommitsByClass().keySet());
                    Map<String, Set<String>> sourceByClass = sourcePathsByTicketAndClass.computeIfAbsent(ticket, ignored -> new LinkedHashMap<>());
                    Map<String, InducingCommitEvidence> earliestByClass = earliestInducingByTicketAndClass.computeIfAbsent(ticket, ignored -> new LinkedHashMap<>());
                    for (SzzLineDiagnostic line : result.analysedLines()) {
                        // Un source path e' utile soltanto per classi che SZZ
                        // ha realmente restituito (almeno un inducing commit).
                        if (result.inducingCommitsByClass().containsKey(line.filePath()) && line.sourcePath() != null)
                            sourceByClass.computeIfAbsent(line.filePath(), ignored -> new LinkedHashSet<>()).add(line.sourcePath());
                    }
                    result.inducingCommitsByClass().forEach((classPath, inducingCommits) -> inducingCommits.forEach(inducing -> {
                        LocalDate date = inducing.getCommitterIdent().getWhen().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                        InducingCommitEvidence candidate = new InducingCommitEvidence(inducing.getName(), date);
                        earliestByClass.merge(classPath, candidate,
                                (current, found) -> found.date().isBefore(current.date()) ? found : current);
                    }));
                }
            }
        }

        List<SzzClassResolution> resolutions = new ArrayList<>();
        for (TicketDiagnosticContext context : eligibleContexts) {
            String ticket = context.ticket().key();
            for (String szzPath : szzClassesByTicket.getOrDefault(ticket, Set.of())) {
                Set<String> sourcePaths = sourcePathsByTicketAndClass.getOrDefault(ticket, Map.of())
                        .getOrDefault(szzPath, Set.of());
                InducingCommitEvidence earliestInducing = earliestInducingByTicketAndClass.getOrDefault(ticket, Map.of())
                        .get(szzPath);
                for (Release release : training) {
                    if (!releaseIsBuggyForContext(release, context, calculator, releases)) continue;
                    resolutions.add(resolveSzzClassPath(ticket, release, szzPath, sourcePaths,
                            earliestInducing, scannerPathsByRelease.getOrDefault(release.name(), Set.of())));
                }
            }
        }
        printSzzClassResolutionReport(resolutions, training);
    }

    /** Replica solo l'intervallo di BuggyLabeler; non etichetta né altera classi. */
    private static boolean touchesTrainingInActualLabeling(TicketDiagnosticContext context, ProportionCalculator calculator,
                                                           List<Release> releases, List<Release> training) {
        return training.stream().anyMatch(release -> releaseIsBuggyForContext(release, context, calculator, releases));
    }

    private static boolean releaseIsBuggyForContext(Release release, TicketDiagnosticContext context,
                                                    ProportionCalculator calculator, List<Release> releases) {
        if (context.iv().isEmpty()) return false;
        ProportionCalculator.FixedVersionResolution fixed = calculator.resolveFixedVersion(context.ticket(), releases);
        if (fixed.status() == ProportionCalculator.FixedVersionStatus.FV_AFTER_CORPUS)
            return release.index() >= context.iv().get().index();
        return fixed.release().isPresent()
                && context.iv().get().index() < fixed.release().get().index()
                && release.index() >= context.iv().get().index()
                && release.index() < fixed.release().get().index();
    }

    /** Applica l'ordine richiesto, conservando i candidati per rendere verificabile il risultato. */
    private static SzzClassResolution resolveSzzClassPath(String ticket, Release release, String szzPath,
                                                          Set<String> sourcePaths, InducingCommitEvidence earliestInducing,
                                                          Set<String> scannerPaths) {
        if (scannerPaths.contains(szzPath))
            return new SzzClassResolution(ticket, release, szzPath, sourcePaths, earliestInducing, ResolutionStatus.EXACT_PATH, List.of(szzPath));
        List<String> matchingSources = sourcePaths.stream().filter(scannerPaths::contains).sorted().toList();
        if (!matchingSources.isEmpty())
            return new SzzClassResolution(ticket, release, szzPath, sourcePaths, earliestInducing, ResolutionStatus.SOURCE_PATH, matchingSources);
        String simpleName = simpleClassName(szzPath);
        List<String> candidates = scannerPaths.stream().filter(path -> simpleClassName(path).equals(simpleName)).sorted().toList();
        if (candidates.isEmpty())
            return new SzzClassResolution(ticket, release, szzPath, sourcePaths, earliestInducing, ResolutionStatus.NOT_FOUND, List.of());
        if (candidates.size() == 1)
            return new SzzClassResolution(ticket, release, szzPath, sourcePaths, earliestInducing, ResolutionStatus.UNIQUE_SIMPLE_NAME, candidates);
        return new SzzClassResolution(ticket, release, szzPath, sourcePaths, earliestInducing, ResolutionStatus.AMBIGUOUS, candidates);
    }

    private static void printSzzClassResolutionReport(List<SzzClassResolution> resolutions, List<Release> training) {
        section("RIEPILOGO GLOBALE RISOLUZIONE CLASSI SZZ");
        printResolutionCounts("totale class/release da risolvere", resolutions);
        section("RIEPILOGO PER RELEASE");
        for (Release release : training) {
            List<SzzClassResolution> perRelease = resolutions.stream().filter(value -> value.release().name().equals(release.name())).toList();
            printResolutionCounts("release=" + release.name(), perRelease);
        }
        section("RIEPILOGO PER TICKET");
        resolutions.stream().map(SzzClassResolution::ticket).distinct().sorted().forEach(ticket ->
                printResolutionCounts("ticket=" + ticket, resolutions.stream().filter(value -> value.ticket().equals(ticket)).toList()));
        section("CASI AMBIGUOUS");
        resolutions.stream().filter(value -> value.status() == ResolutionStatus.AMBIGUOUS).forEach(value ->
                System.out.println("ticket=" + value.ticket() + " | release=" + value.release().name()
                        + " | SZZ path=" + value.szzPath() + " | simple class name=" + simpleClassName(value.szzPath())
                        + " | candidate paths=" + value.candidates()));
        section("CASI NOT_FOUND");
        resolutions.stream().filter(value -> value.status() == ResolutionStatus.NOT_FOUND).forEach(value ->
                System.out.println("ticket=" + value.ticket() + " | release=" + value.release().name()
                        + " | SZZ path=" + value.szzPath() + " | eventuale source path=" + value.sourcePaths()
                        + " | simple class name=" + simpleClassName(value.szzPath())));
        printNotFoundTemporalDiagnostic(resolutions, training);
    }

    /**
     * Separa i NOT_FOUND senza provare altri matching: una release anteriore
     * al primo inducing commit non puo' contenere quella classe per quel bug.
     */
    private static void printNotFoundTemporalDiagnostic(List<SzzClassResolution> resolutions, List<Release> training) {
        section("DIAGNOSTICA TEMPORALE DEI CASI NOT_FOUND");
        List<SzzClassResolution> notFound = resolutions.stream().filter(value -> value.status() == ResolutionStatus.NOT_FOUND).toList();
        printNotFoundTemporalCounts("NOT_FOUND totale", notFound);
        System.out.println("Per release:");
        for (Release release : training) {
            List<SzzClassResolution> perRelease = notFound.stream().filter(value -> value.release().name().equals(release.name())).toList();
            printNotFoundTemporalCounts("  release=" + release.name(), perRelease);
        }
        for (NotFoundTemporalStatus status : NotFoundTemporalStatus.values()) {
            System.out.println("Esempi " + status + " (massimo 10):");
            notFound.stream().filter(value -> classifyNotFound(value) == status).limit(EXAMPLE_LIMIT)
                    .forEach(CheckFunctionality::printNotFoundTemporalExample);
        }
        printPostInducingReachability(notFound, training);
    }

    /**
     * Un POST_INDUCING puo' ancora essere assente perche' il tag appartiene a
     * un branch parallelo. isMergedInto(inducing, release) lo rende visibile,
     * senza trasformare l'esito in una scelta di path o in una label.
     */
    private static void printPostInducingReachability(List<SzzClassResolution> notFound, List<Release> training) {
        List<SzzClassResolution> postInducing = notFound.stream()
                .filter(value -> classifyNotFound(value) == NotFoundTemporalStatus.POST_INDUCING_NOT_FOUND).toList();
        section("RAGGIUNGIBILITA' GIT - POST_INDUCING_NOT_FOUND");
        if (postInducing.isEmpty()) {
            System.out.println("POST_INDUCING_NOT_FOUND totale = 0");
            return;
        }
        List<PostInducingReachability> results = new ArrayList<>();
        try (Repository repository = Utils.GitRepositoryUtils.openRepository();
             Git git = new Git(repository);
             RevWalk walk = new RevWalk(repository)) {
            Map<String, ObjectId> releaseCommits = resolveReleaseTagCommits(repository, git, walk, training);
            Map<String, Boolean> ancestryCache = new HashMap<>();
            for (SzzClassResolution value : postInducing) {
                ObjectId releaseCommit = releaseCommits.get(value.release().name());
                boolean reachable = false;
                String issue = null;
                try {
                    if (releaseCommit == null) throw new IllegalStateException("tag della release non risolto");
                    reachable = isDescendant(walk, ancestryCache,
                            ObjectId.fromString(value.earliestInducing().commitId()), releaseCommit);
                } catch (Exception exception) {
                    // Un commit/tag non leggibile non permette di sostenere la
                    // raggiungibilita': resta visibile nel report come nota.
                    issue = rootMessage(exception);
                }
                results.add(new PostInducingReachability(value, reachable, issue));
            }
        } catch (Exception exception) {
            error("Raggiungibilita' Git non eseguibile: " + rootMessage(exception));
            return;
        }
        printPostInducingReachabilitySummary(results, training);
    }

    private static void printPostInducingReachabilitySummary(List<PostInducingReachability> results, List<Release> training) {
        System.out.println("POST_INDUCING_NOT_FOUND totale = " + results.size());
        printReachabilityCounts("globale", results);
        System.out.println("Per release:");
        for (Release release : training) {
            printReachabilityCounts("  release=" + release.name(), results.stream()
                    .filter(value -> value.resolution().release().name().equals(release.name())).toList());
        }
        System.out.println("Esempi INDUCING_REACHABLE_FROM_RELEASE (massimo 20):");
        results.stream().filter(PostInducingReachability::reachable).limit(20)
                .forEach(CheckFunctionality::printReachabilityExample);
        System.out.println("Esempi INDUCING_NOT_REACHABLE_FROM_RELEASE (massimo 10):");
        results.stream().filter(value -> !value.reachable()).limit(EXAMPLE_LIMIT)
                .forEach(CheckFunctionality::printReachabilityExample);
    }

    private static void printReachabilityCounts(String label, List<PostInducingReachability> values) {
        long reachable = values.stream().filter(PostInducingReachability::reachable).count();
        long notReachable = values.size() - reachable;
        System.out.println(label + " = " + values.size());
        System.out.println("    INDUCING_REACHABLE_FROM_RELEASE = " + reachable + " ("
                + format(values.isEmpty() ? 0 : 100.0 * reachable / values.size()) + "%)");
        System.out.println("    INDUCING_NOT_REACHABLE_FROM_RELEASE = " + notReachable + " ("
                + format(values.isEmpty() ? 0 : 100.0 * notReachable / values.size()) + "%)");
    }

    private static void printReachabilityExample(PostInducingReachability value) {
        SzzClassResolution resolution = value.resolution();
        String category = value.reachable() ? "INDUCING_REACHABLE_FROM_RELEASE" : "INDUCING_NOT_REACHABLE_FROM_RELEASE";
        System.out.println("  categoria=" + category + " | ticket=" + resolution.ticket()
                + " | release=" + resolution.release().name() + " | SZZ path=" + resolution.szzPath()
                + " | earliest inducing=" + resolution.earliestInducing().commitId() + "/" + resolution.earliestInducing().date()
                + " | source path=" + resolution.sourcePaths()
                + " | candidati filename/simple name=" + resolution.candidates()
                + (value.issue() == null ? "" : " | nota=" + value.issue()));
    }

    private static void printNotFoundTemporalCounts(String label, List<SzzClassResolution> values) {
        System.out.println(label + " = " + values.size());
        for (NotFoundTemporalStatus status : NotFoundTemporalStatus.values()) {
            long count = values.stream().filter(value -> classifyNotFound(value) == status).count();
            System.out.println("    " + status + " = " + count + " (" + format(values.isEmpty() ? 0 : 100.0 * count / values.size()) + "%)");
        }
    }

    private static NotFoundTemporalStatus classifyNotFound(SzzClassResolution value) {
        if (value.earliestInducing() == null || value.earliestInducing().date() == null)
            return NotFoundTemporalStatus.NO_INDUCING_DATE;
        return value.release().releaseDate().isBefore(value.earliestInducing().date())
                ? NotFoundTemporalStatus.PRE_INDUCING : NotFoundTemporalStatus.POST_INDUCING_NOT_FOUND;
    }

    private static void printNotFoundTemporalExample(SzzClassResolution value) {
        InducingCommitEvidence evidence = value.earliestInducing();
        String commit = evidence == null ? "<assente>" : evidence.commitId();
        String date = evidence == null || evidence.date() == null ? "<assente>" : evidence.date().toString();
        String delta = evidence == null || evidence.date() == null ? "<non calcolabile>"
                : String.valueOf(ChronoUnit.DAYS.between(evidence.date(), value.release().releaseDate()));
        System.out.println("  ticket=" + value.ticket() + " | SZZ path=" + value.szzPath()
                + " | release/date=" + value.release().name() + "/" + value.release().releaseDate()
                + " | earliest inducing commit=" + commit + " | inducing date=" + date
                + " | delta giorni (release-inducing)=" + delta);
    }

    private static void printResolutionCounts(String label, List<SzzClassResolution> values) {
        System.out.println(label + " = " + values.size());
        for (ResolutionStatus status : ResolutionStatus.values()) {
            long count = values.stream().filter(value -> value.status() == status).count();
            System.out.println("  " + status + " = " + count + " (" + format(values.isEmpty() ? 0 : 100.0 * count / values.size()) + "%)");
        }
    }

    /**
     * Ricostruisce, a solo scopo diagnostico, il nome storico di quattro file
     * SZZ. La fonte iniziale e' il parent del fixing commit, cioe' lo stesso
     * stato del repository su cui SZZ ha effettuato il blame.
     */
    public static void checkPathHistory() throws Exception {
        section("PATH HISTORY - RENAME/MOVE DEI FILE SZZ");
        List<Release> releases = new ReleaseManager().getReleases();
        List<Release> training = new ReleaseManager().getTrainingReleases(releases);
        Map<String, List<RevCommit>> commitsByTicket = new CommitManager().findFixCommits(
                Set.of("STORM-3052", "STORM-3132", "STORM-2950"));
        List<PathHistoryRequest> requests = List.of(
                new PathHistoryRequest("STORM-3052", "storm-server/src/main/java/org/apache/storm/localizer/LocalizedResource.java"),
                new PathHistoryRequest("STORM-3052", "storm-server/src/main/java/org/apache/storm/utils/ServerUtils.java"),
                new PathHistoryRequest("STORM-3132", "storm-client/src/jvm/org/apache/storm/tuple/Values.java"),
                new PathHistoryRequest("STORM-2950", "storm-client/src/jvm/org/apache/storm/utils/TupleUtils.java"));

        // Il check usa il medesimo scanner che fornisce i path alle CsvRow,
        // ma non costruisce metriche né salva dati nel dataset.
        Map<String, Set<String>> scannerPaths = scanTrainingPaths(training);
        if (scannerPaths.isEmpty()) return;
        try (Repository repository = Utils.GitRepositoryUtils.openRepository();
             RevWalk ancestryWalk = new RevWalk(repository)) {
            Map<String, Boolean> ancestryCache = new HashMap<>();
            for (PathHistoryRequest request : requests) {
                Optional<RevCommit> fixingCommit = findFixingCommitWhoseParentContains(
                        repository, commitsByTicket.getOrDefault(request.ticket(), List.of()), request.szzPath());
                if (fixingCommit.isEmpty()) {
                    error(request.ticket() + " | path nel parent del fixing commit non trovato: " + request.szzPath());
                    continue;
                }
                RevCommit fixing = fixingCommit.get();
                RevCommit parent = parseParent(repository, fixing);
                System.out.println("\nTicket=" + request.ticket() + " | SZZ path=" + request.szzPath());
                System.out.println("Fixing commit=" + fixing.getName() + " | parent usato come punto di partenza=" + parent.getName());
                List<PathHistoryStep> history = tracePathBackward(repository, parent, request.szzPath());
                printPathHistory(history);
                printPathAtTrainingReleases(repository, ancestryWalk, ancestryCache, training, scannerPaths,
                        parent, request.szzPath(), history);
            }
        }
    }

    private static Optional<RevCommit> findFixingCommitWhoseParentContains(Repository repository, List<RevCommit> fixes,
                                                                             String path) throws IOException {
        for (RevCommit fixing : fixes) {
            if (fixing.getParentCount() == 0) continue;
            RevCommit parent = parseParent(repository, fixing);
            if (fileExistsAtCommit(repository, parent, path)) return Optional.of(fixing);
        }
        return Optional.empty();
    }

    private static RevCommit parseParent(Repository repository, RevCommit commit) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            return walk.parseCommit(commit.getParent(0).getId());
        }
    }

    /**
     * Segue il primo-parent path dal parent del fix verso il passato. E' una
     * scelta diagnostica coerente con SZZ, che usa appunto il primo parent.
     * Le righe SAME sono commit che modificano il file ma non ne cambiano path.
     */
    private static List<PathHistoryStep> tracePathBackward(Repository repository, RevCommit start, String startPath) throws Exception {
        List<PathHistoryStep> result = new ArrayList<>();
        String currentPath = startPath;
        try (RevWalk walk = new RevWalk(repository);
             DiffFormatter diff = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            diff.setRepository(repository);
            diff.setDetectRenames(true);
            RevCommit current = walk.parseCommit(start.getId());
            while (current.getParentCount() > 0) {
                RevCommit parent = walk.parseCommit(current.getParent(0).getId());
                List<DiffEntry> entries = diff.scan(parent.getTree(), current.getTree());
                DiffEntry changedPath = null;
                for (DiffEntry entry : entries) {
                    if (currentPath.equals(entry.getNewPath()) || currentPath.equals(entry.getOldPath())) {
                        changedPath = entry;
                        break;
                    }
                }
                if (changedPath != null) {
                    String type = historicalChangeType(changedPath);
                    result.add(new PathHistoryStep(current.getId(), parent.getId(), changedPath.getOldPath(),
                            changedPath.getNewPath(), type, changedPath.getScore()));
                    // Procedendo all'indietro, solo ADD comunica che il file
                    // non esiste prima del parent. RENAME/MOVE porta invece al
                    // vecchio path; SAME mantiene lo stesso nome.
                    if (changedPath.getChangeType() == DiffEntry.ChangeType.ADD
                            || changedPath.getChangeType() == DiffEntry.ChangeType.COPY) break;
                    if (changedPath.getChangeType() == DiffEntry.ChangeType.RENAME) currentPath = changedPath.getOldPath();
                }
                current = parent;
            }
        }
        return result;
    }

    private static String historicalChangeType(DiffEntry entry) {
        return switch (entry.getChangeType()) {
            case ADD -> "ADD";
            case DELETE -> "DELETE";
            case RENAME -> sameFileName(entry.getOldPath(), entry.getNewPath()) ? "MOVE" : "RENAME";
            // Una copia non dimostra che il vecchio file sia la stessa entita'
            // storica: viene esposta nel report, ma non e' seguita come rename.
            case COPY -> "COPY";
            default -> "SAME";
        };
    }

    private static boolean sameFileName(String first, String second) {
        return first.substring(first.lastIndexOf('/') + 1).equals(second.substring(second.lastIndexOf('/') + 1));
    }

    /** Ogni riga usa il dato JGit originale: nessun mapping di path e' applicato qui. */
    private static void printBlamedLine(TicketDiagnosticContext context, SzzLineDiagnostic line) {
        String inducingDate = line.inducingCommitDate() == null ? "<assente>" : line.inducingCommitDate().toString();
        String comparedToIv = line.inducingCommitDate() == null ? "<non confrontabile>"
                : String.valueOf(!line.inducingCommitDate().isAfter(context.iv().get().releaseDate()));
        System.out.println("      ticket=" + context.ticket().key()
                + " | IV release/date=" + context.iv().get().name() + "/" + context.iv().get().releaseDate()
                + " | fixing file path=" + line.filePath()
                + " | old line nel parent del fix=" + line.oldLineNumber()
                + " | tipo edit=" + line.editType()
                + " | inducing commit=" + line.inducingCommitId()
                + " | inducing commit/date=" + inducingDate
                + " | inducing <= IV date=" + comparedToIv
                + " | source path della riga=" + (line.sourcePath() == null ? "<assente>" : line.sourcePath()));
    }

    private static void printPathHistory(List<PathHistoryStep> history) {
        System.out.println("Storia del file (dal parent del fix verso il passato; first-parent):");
        if (history.isEmpty()) {
            warn("Nessun commit che modifica o rinomina il path trovato nella catena first-parent.");
            return;
        }
        for (PathHistoryStep step : history) {
            String score = step.similarityScore() > 0 ? String.valueOf(step.similarityScore()) : "<non disponibile>";
            System.out.println("  commit=" + step.childCommit().name() + " | old path=" + step.oldPath()
                    + " | new path=" + step.newPath() + " | tipo=" + step.type() + " | similarity score=" + score);
        }
    }

    private static void printPathAtTrainingReleases(Repository repository, RevWalk walk, Map<String, Boolean> ancestryCache,
                                                     List<Release> training, Map<String, Set<String>> scannerPaths,
                                                     RevCommit start, String startPath, List<PathHistoryStep> history) throws Exception {
        System.out.println("Presenza nelle 14 release training:");
        for (Release release : training) {
            RevCommit releaseCommit = CommitManager.getReleaseCommit(release);
            HistoricalPathResolution resolution = resolveHistoricalPathForRelease(walk, ancestryCache, releaseCommit,
                    start, startPath, history);
            boolean present = resolution.path().isPresent() && fileExistsAtCommit(repository, releaseCommit, resolution.path().get());
            boolean scannerContains = resolution.path().isPresent()
                    && scannerPaths.getOrDefault(release.name(), Set.of()).contains(resolution.path().get());
            System.out.println("  release=" + release.name() + " | historical path risolto="
                    + resolution.path().orElse(resolution.status()) + " | stato=" + resolution.status()
                    + " | file presente=" + present + " | scanner contiene quel path=" + scannerContains);
        }
    }

    /** Non inventa un path quando il tag non e' ancestor del parent del fix. */
    private static HistoricalPathResolution resolveHistoricalPathForRelease(RevWalk walk, Map<String, Boolean> cache,
                                                                             RevCommit release, RevCommit start, String startPath,
                                                                             List<PathHistoryStep> history) throws IOException {
        if (!isDescendant(walk, cache, release.getId(), start.getId()))
            return new HistoricalPathResolution(Optional.empty(), "NOT_ON_TRACED_ANCESTRY");
        String path = startPath;
        for (PathHistoryStep step : history) {
            if (isDescendant(walk, cache, release.getId(), step.parentCommit())) {
                if (step.type().equals("ADD") || step.type().equals("COPY"))
                    return new HistoricalPathResolution(Optional.empty(), "NOT_YET_EXISTING");
                path = step.oldPath();
            }
        }
        return new HistoricalPathResolution(Optional.of(path), "RESOLVED");
    }

    private static boolean fileExistsAtCommit(Repository repository, RevCommit commit, String path) throws IOException {
        try (TreeWalk tree = TreeWalk.forPath(repository, path, commit.getTree())) {
            return tree != null;
        }
    }

    public static void checkLabeling(int limit) throws Exception {
        int requested = Math.max(3, limit);
        section("BUGGY LABELER - CAMPIONE MIRATO SULLE RELEASE TRAINING (" + requested + " TICKET)");
        List<Release> releases = new ReleaseManager().getReleases();
        List<Release> training = new ReleaseManager().getTrainingReleases(releases);
        List<Ticket> tickets = fixedTickets();
        ProportionCalculator calculator = new ProportionCalculator();
        Map<String, Release> known = tickets.stream().map(ticket -> Map.entry(ticket.key(), firstAffected(ticket, releases))).filter(entry -> entry.getValue().isPresent()).collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().get()));
        OptionalDouble p = calculator.calculateProportionTotal(tickets, releases, known);
        CommitManager manager = new CommitManager();
        Map<String, List<RevCommit>> commits = manager.findFixCommits(ticketKeys(tickets));
        List<TicketDiagnosticContext> contexts = diagnosticContexts(tickets, releases);
        Map<String, Set<String>> selectionRoles = new LinkedHashMap<>();
        List<TicketDiagnosticContext> eligible = contexts.stream()
                .filter(context -> intersectsTraining(context, training))
                .filter(context -> !commits.getOrDefault(context.ticket().key(), List.of()).isEmpty()).toList();
        selectTicketForRole(eligible, selectionRoles, "AV_INTERSECTA_TRAINING", context -> context.source().equals("AFFECTED_VERSION"));
        selectTicketForRole(eligible, selectionRoles, "PROPORTION_INTERSECTA_TRAINING", context -> context.source().equals("PROPORTION"));
        selectTicketForRole(eligible, selectionRoles, "MULTI_FIXING_COMMIT", context -> commits.getOrDefault(context.ticket().key(), List.of()).size() > 1);
        if (!selectionContainsRole(selectionRoles, "AV_INTERSECTA_TRAINING"))
            warn("Nessun ticket con AV valida e intervallo che interseca il training e' selezionabile.");
        if (!selectionContainsRole(selectionRoles, "PROPORTION_INTERSECTA_TRAINING"))
            warn("Nessun ticket senza AV con IV stimata da Proportion interseca il training ed e' selezionabile.");
        if (selectionRoles.keySet().stream().noneMatch(key -> selectionRoles.get(key).contains("MULTI_FIXING_COMMIT")))
            warn("Nessun ticket selezionabile con piu' fixing commit nel campione che interseca il training.");
        for (TicketDiagnosticContext context : eligible) {
            if (selectionRoles.size() >= requested) break;
            selectionRoles.computeIfAbsent(context.ticket().key(), ignored -> new LinkedHashSet<>()).add("ULTERIORE_CAMPIONE");
        }
        List<TicketDiagnosticContext> selected = eligible.stream().filter(context -> selectionRoles.containsKey(context.ticket().key())).toList();
        System.out.println("Criterio campione: [IV,FV) interseca le 14 training release e il ticket possiede fixing commit.");
        Map<String, Set<String>> buggyClassesByRelease = new TreeMap<>();
        // Conserva il risultato SZZ del campione nello stesso formato passato
        // normalmente al BuggyLabeler. Ci permette di osservare il collegamento
        // SZZ -> scanner -> CsvRow senza rieseguire né modificare SZZ.
        Map<String, Map<String, Set<RevCommit>>> inducingBySelectedTicket = new LinkedHashMap<>();
        // E' lo stesso formato ricco usato dal DatasetBuilder: conserva anche
        // il source path del blame per poter riprodurre il labeling finale.
        Map<String, Map<String, SzzClassEvidence>> evidenceBySelectedTicket = new LinkedHashMap<>();
        try (Repository repository = Utils.GitRepositoryUtils.openRepository()) {
            SzzAnalyzer szz = new SzzAnalyzer(repository);
            for (TicketDiagnosticContext context : selected) {
                Ticket ticket = context.ticket();
                Map<String, Set<RevCommit>> classes = new LinkedHashMap<>();
                Map<String, SzzClassEvidence> evidence = new LinkedHashMap<>();
                for (RevCommit fix : commits.get(ticket.key())) {
                    SzzDiagnosticResult szzResult = szz.analyseFixingCommitWithSourcePaths(fix);
                    Map<String, Set<String>> sourcePathsByClass = new LinkedHashMap<>();
                    szzResult.analysedLines().forEach(line -> {
                        if (!line.inducingCommitId().equals("<nessun commit>")
                                && line.sourcePath() != null && !line.sourcePath().isBlank()) {
                            sourcePathsByClass.computeIfAbsent(line.filePath(), ignored -> new LinkedHashSet<>())
                                    .add(line.sourcePath());
                        }
                    });
                    szzResult.inducingCommitsByClass().forEach((path, found) -> {
                        classes.computeIfAbsent(path, ignored -> new LinkedHashSet<>()).addAll(found);
                        SzzClassEvidence old = evidence.get(path);
                        Set<RevCommit> allInducing = new LinkedHashSet<>(found);
                        Set<String> allSourcePaths = new LinkedHashSet<>(sourcePathsByClass.getOrDefault(path, Set.of()));
                        if (old != null) {
                            allInducing.addAll(old.inducingCommits());
                            allSourcePaths.addAll(old.sourcePaths());
                        }
                        evidence.put(path, new SzzClassEvidence(path, allInducing, allSourcePaths));
                    });
                }
                inducingBySelectedTicket.put(ticket.key(), classes);
                evidenceBySelectedTicket.put(ticket.key(), evidence);
                ProportionCalculator.FixedVersionResolution fixedResolution = calculator.resolveFixedVersion(ticket, releases);
                Optional<Release> iv = context.iv();
                String source = context.source();
                Optional<Release> fv = context.fv();
                System.out.println("Ticket " + ticket.key() + " | ruoli=" + selectionRoles.get(ticket.key())
                        + " | fixing commit=" + commits.getOrDefault(ticket.key(), List.of()).size());
                System.out.println("  classi SZZ=" + classes.keySet());
                System.out.println("  IV=" + releaseDescription(iv) + " | fonte IV=" + source + " | OV=" + releaseDescription(context.ov())
                        + " | FV=" + releaseDescription(fv) + " | AV=" + ticket.affectedVersions() + " | stato FV=" + fixedResolution.status());
                if (classes.isEmpty()) warn("  Saltato: nessuna classe SZZ");
                else if (fixedResolution.status() == ProportionCalculator.FixedVersionStatus.NO_FV) warn("  Saltato: NO_FV");
                else if (fixedResolution.status() == ProportionCalculator.FixedVersionStatus.FV_UNRESOLVED) warn("  Saltato: FV_UNRESOLVED");
                else if (iv.isEmpty()) warn("  Saltato: IV non stimabile");
                else {
                    Release chosenIv = iv.get();
                    if (fixedResolution.status() == ProportionCalculator.FixedVersionStatus.FV_AFTER_CORPUS) {
                        List<String> markedReleases = releases.stream().filter(release -> release.index() >= chosenIv.index()).map(Release::name).toList();
                        warn("  FV_AFTER_CORPUS: labeling right-censored fino a " + releases.get(releases.size() - 1).name());
                        for (String classPath : classes.keySet()) {
                            System.out.println("  ticket=" + ticket.key() + " | class=" + classPath + " | IV=" + chosenIv.name() + " | fonte IV=" + source + " | FV=RIGHT_CENSORED | releases marcate buggy=" + markedReleases);
                        }
                        for (String releaseName : markedReleases) buggyClassesByRelease.computeIfAbsent(releaseName, ignored -> new TreeSet<>()).addAll(classes.keySet());
                        continue;
                    }
                    Release fixedVersion = fv.get();
                    List<String> markedReleases = releases.stream()
                            .filter(release -> release.index() >= chosenIv.index() && release.index() < fixedVersion.index())
                            .map(Release::name).toList();
                    System.out.println("  Intervallo buggy: [" + chosenIv.index() + ", " + fixedVersion.index() + ")");
                    for (String classPath : classes.keySet()) {
                        System.out.println("  ticket=" + ticket.key() + " | class=" + classPath
                                + " | IV=" + chosenIv.name() + " | fonte IV=" + source
                                + " | FV=" + fixedVersion.name() + " | releases marcate buggy=" + markedReleases);
                    }
                    for (String releaseName : markedReleases) {
                        buggyClassesByRelease.computeIfAbsent(releaseName, ignored -> new TreeSet<>()).addAll(classes.keySet());
                    }
                }
            }
        }
        // Usiamo una nuova istanza locale del labeler con tutti i ticket Jira e
        // soltanto le classi SZZ del campione. P_total e le regole applicate
        // restano quindi quelle reali; non viene scritto alcun CSV.
        BuggyLabeler observedLabeler = new BuggyLabeler();
        observedLabeler.labelBugsWithEvidence(tickets, releases, evidenceBySelectedTicket);
        Map<String, Set<String>> scannedPathsByRelease = printSzzPathPresenceDiagnostics(
                selected, training, inducingBySelectedTicket, observedLabeler);
        Map<String, Set<String>> resolvedBuggyByRelease = resolveHistoricalCandidatesForCheck(
                training, observedLabeler, scannedPathsByRelease);
        printLabelingSummaryByTrainingRelease(releases, resolvedBuggyByRelease, selected.size(), scannedPathsByRelease);
    }

    public static void checkFirstReleaseMetrics() throws Exception {
        section("CK, JGIT, PMD E CLASS METRICS COLLECTOR");
        Release release = firstTrainingRelease();
        CheckoutManager checkout = new CheckoutManager();
        JavaClassScanner scanner = new JavaClassScanner();
        CKMetrics ck = new CKMetrics(); PMDMetrics pmd = new PMDMetrics(); JGitMetrics jgit = new JGitMetrics(); CommitManager commits = new CommitManager();
        RevCommit releaseCommit = CommitManager.getReleaseCommit(release);
        // Usiamo gli stessi fixing commit del DatasetBuilder. Passare Set.of()
        // renderebbe sempre zero le metriche "fix" e trasformerebbe il check
        // in un controllo diverso dalla pipeline reale.
        List<Ticket> fixedTickets = fixedTickets();
        Map<String, List<RevCommit>> fixCommitsByTicket = commits.findFixCommits(ticketKeys(fixedTickets));
        Set<String> fixCommitIds = fixCommitsByTicket.values().stream().flatMap(List::stream)
                .map(RevCommit::getName).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, List<ClassChanges>> history = commits.getClassChanges(releaseCommit, fixCommitIds);
        checkout.checkoutRelease(release);
        try {
            Path repository = checkout.getRepositoryPath();
            List<Path> files = scanner.findJavaFiles(repository);
            Map<String, CKClassMetrics> ckResults = ck.calculate(repository, files);
            Map<String, Integer> smells = pmd.calculate(files);
            List<CsvRow> rows = new ClassMetricsCollector(checkout, scanner, ck, jgit, pmd).collect(release, history);
            ok("Input Java=" + files.size() + " | CK=" + ckResults.size() + " | PMD=" + smells.size() + " | CsvRow=" + rows.size());
            status(files.size() == rows.size(), "Ogni file Java ha una CsvRow", "File Java e CsvRow hanno cardinalita' diversa");
            long withoutHistory = files.stream().map(path -> relative(repository, path)).filter(path -> history.getOrDefault(path, List.of()).isEmpty()).count();
            long withoutCk = files.stream().filter(path -> !ckResults.containsKey(path.toAbsolutePath().normalize().toString())).count();
            long withoutPmd = files.stream().filter(path -> !smells.containsKey(path.toAbsolutePath().normalize().toString())).count();
            System.out.println("Senza storia JGit=" + withoutHistory + " | senza CK=" + withoutCk + " | senza PMD=" + withoutPmd);
            long javaProductionCommits = history.values().stream().flatMap(List::stream).map(ClassChanges::commitId).distinct().count();
            long classChanges = history.values().stream().mapToLong(List::size).sum();
            long fixChanges = history.values().stream().flatMap(List::stream).filter(ClassChanges::fix).count();
            long distinctFixCommits = history.values().stream().flatMap(List::stream).filter(ClassChanges::fix).map(ClassChanges::commitId).distinct().count();
            System.out.println("Commit che toccano Java production: " + javaProductionCommits + " | ClassChanges prodotti: " + classChanges);
            System.out.println("ClassChanges FIX: " + fixChanges + " | Fixing commit distinti nella storia: " + distinctFixCommits + " | Fixing commit distinti identificati dai ticket: " + fixCommitIds.size());
            printMetricRange("LOC", rows, row -> row.loc()); printMetricRange("WMC", rows, row -> row.wmc()); printMetricRange("CBO", rows, row -> row.cbo()); printMetricRange("NSmells", rows, row -> row.nSmells());
            System.out.println("Top 10 smell:"); rows.stream().sorted(Comparator.comparingInt(CsvRow::nSmells).reversed()).limit(10).forEach(row -> System.out.println("  " + row.classPath() + " -> " + row.nSmells()));
            System.out.println("Prime 10 metriche complete:");
            rows.stream().limit(10).forEach(row -> System.out.println("  " + row.classPath() + " | LOC=" + row.loc() + " WMC=" + row.wmc() + " CBO=" + row.cbo() + " RFC=" + row.rfc() + " commits=" + row.commitCount() + " fixes=" + row.fixCommitCount() + " churn=" + row.churn() + " smells=" + row.nSmells()));
            long nonFinite = rows.stream().filter(row -> !Double.isFinite(row.averageChangeSetSize()) || !Double.isFinite(row.changeFrequency()) || !Double.isFinite(row.modificationIntervalsStdDev()) || !Double.isFinite(row.authorChangeEntropy())).count();
            status(nonFinite == 0, "Nessun NaN/infinito nelle metriche JGit", "Metriche JGit non finite: " + nonFinite);
            long negativeGit = rows.stream().filter(row -> row.commitCount() < 0 || row.fixCommitCount() < 0 || row.churn() < 0 || row.averageChangeSetSize() < 0 || row.distinctAuthors() < 0 || row.daysSinceLastChange() < 0 || row.changeFrequency() < 0 || row.changeCountLast90Days() < 0 || row.modificationIntervalsStdDev() < 0 || row.authorChangeEntropy() < 0).count();
            status(negativeGit == 0, "Nessuna metrica JGit negativa", "Classi con metriche JGit negative: " + negativeGit);
        } finally { checkout.cleanRepository(); }
    }

    public static void checkCsv() throws Exception {
        section("CSV FINALE");
        if (!Files.exists(Config.DATASET_CSV)) { warn("CSV assente: " + Config.DATASET_CSV.toAbsolutePath()); return; }
        List<String> lines = Files.readAllLines(Config.DATASET_CSV, StandardCharsets.UTF_8);
        if (lines.isEmpty()) { error("CSV vuoto"); return; }
        List<String> header = parseCsv(lines.get(0));
        ok("Colonne: " + header.size() + " | attese: 25");
        int invalidColumns = 0, nulls = 0, nonFinite = 0, tests = 0; long buggy = 0; Set<String> keys = new HashSet<>(); int duplicates = 0; Map<String, Long> buggyByRelease = new TreeMap<>(); Map<String, Long> totalByRelease = new TreeMap<>(); Map<String, Long> buggyClassOccurrences = new TreeMap<>(); Map<String, Set<String>> csvClassesByRelease = new TreeMap<>();
        for (String line : lines.subList(1, lines.size())) {
            List<String> row = parseCsv(line); if (row.size() != header.size()) { invalidColumns++; continue; }
            nulls += row.stream().filter(String::isBlank).count();
            if (row.stream().anyMatch(value -> value.equalsIgnoreCase("nan") || value.equalsIgnoreCase("infinity") || value.equalsIgnoreCase("-infinity"))) nonFinite++;
            String key = row.get(2) + "|" + row.get(3); if (!keys.add(key)) duplicates++;
            if (isTestPath(row.get(3))) tests++;
            totalByRelease.merge(row.get(2), 1L, Long::sum);
            csvClassesByRelease.computeIfAbsent(row.get(2), ignored -> new TreeSet<>()).add(row.get(3));
            if (Boolean.parseBoolean(row.get(row.size() - 1))) { buggy++; buggyByRelease.merge(row.get(2), 1L, Long::sum); buggyClassOccurrences.merge(row.get(3), 1L, Long::sum); }
        }
        long data = Math.max(0, lines.size() - 1);
        ok("Righe dati: " + data + " | buggy=true: " + buggy + " | buggy=false: " + (data - buggy) + " | percentuale buggy=" + format(data == 0 ? 0 : 100.0 * buggy / data) + "%");
        status(invalidColumns == 0, "Numero colonne coerente per ogni riga", "Righe con colonne errate: " + invalidColumns);
        status(duplicates == 0, "Nessun duplicato (release, class_path)", "Duplicati (release, class_path): " + duplicates);
        status(tests == 0, "Nessuna classe test nel CSV", "Classi test nel CSV: " + tests);
        status(nonFinite == 0, "Nessun NaN/infinito", "Valori non finiti: " + nonFinite);
        if (nulls > 0) warn("Campi vuoti: " + nulls); else ok("Nessun campo vuoto");
        System.out.println("Distribuzione buggy per release:"); totalByRelease.forEach((release, total) -> { long count=buggyByRelease.getOrDefault(release,0L); System.out.println("  " + release + " -> " + count + "/" + total + " (" + format(100.0*count/total) + "%)"); });
        System.out.println("Top 10 classi buggy per numero di release:"); buggyClassOccurrences.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(10).forEach(entry -> System.out.println("  " + entry.getKey() + " -> " + entry.getValue()));
        crossCheckCsvClassesAgainstCheckout(csvClassesByRelease);
    }

    private static List<Ticket> fixedTickets() throws Exception { TicketManager manager = new TicketManager(); manager.getTickets(); return manager.getFixedTickets(); }
    /**
     * Le linee major.minor sono soltanto una lente diagnostica per Storm, che
     * mantiene rami di rilascio paralleli. Gli indici cronologici usati da P
     * non vengono letti o modificati da questo metodo.
     */
    private static void printReleaseLineageDiagnostics(List<ProportionSample> samples, List<ProportionSample> ivGtOvSamples) {
        section("LINEAGE DELLE RELEASE USATE PER PROPORTION");
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ProportionSample sample : samples) {
            String ivLine = releaseLine(sample.iv().name());
            String ovLine = releaseLine(sample.ov().name());
            String fvLine = releaseLine(sample.fv().name());
            String category = lineageCategory(ivLine, ovLine, fvLine);
            counts.merge(category, 1L, Long::sum);
            System.out.println("  " + sample.ticket().key()
                    + " | IV=" + sample.iv().index() + "/" + sample.iv().name() + " | linea IV=" + ivLine
                    + " | OV=" + sample.ov().index() + "/" + sample.ov().name() + " | linea OV=" + ovLine
                    + " | FV=" + sample.fv().index() + "/" + sample.fv().name() + " | linea FV=" + fvLine
                    + " | " + category);
        }
        System.out.println("SAME_LINE_IV_OV_FV: " + counts.getOrDefault("SAME_LINE_IV_OV_FV", 0L));
        System.out.println("IV_FV_SAME_LINE_OV_DIFFERENT: " + counts.getOrDefault("IV_FV_SAME_LINE_OV_DIFFERENT", 0L));
        System.out.println("IV_OV_SAME_LINE_FV_DIFFERENT: " + counts.getOrDefault("IV_OV_SAME_LINE_FV_DIFFERENT", 0L));
        System.out.println("OV_FV_SAME_LINE_IV_DIFFERENT: " + counts.getOrDefault("OV_FV_SAME_LINE_IV_DIFFERENT", 0L));
        System.out.println("ALL_DIFFERENT_LINES: " + counts.getOrDefault("ALL_DIFFERENT_LINES", 0L));
        System.out.println("Ticket IV_GT_OV_USED_FOR_P con linee: " + ivGtOvSamples.size());
    }

    /**
     * Confronta i tre tag di ogni ticket usato da P senza cambiare le sue
     * distanze numeriche. Storm ha anche release pubblicate su branch diversi:
     * qui verifichiamo quindi se i tre commit sono davvero su una sola catena.
     */
    private static void printGitAncestryDiagnostics(List<ProportionSample> samples, List<Release> releases) {
        if (samples.isEmpty()) return;
        section("ANCESTRY GIT - TUTTI I TICKET USATI PER PROPORTION");
        try (Repository repository = Utils.GitRepositoryUtils.openRepository();
             Git git = new Git(repository);
             RevWalk walk = new RevWalk(repository)) {
            Map<String, ObjectId> commitsByRelease = resolveReleaseTagCommits(repository, git, walk, releases);
            Map<String, Boolean> ancestryCache = new HashMap<>();
            List<GitLineageDiagnostic> diagnostics = new ArrayList<>();
            for (ProportionSample sample : samples) {
                try {
                    ObjectId iv = commitsByRelease.get(sample.iv().name());
                    ObjectId ov = commitsByRelease.get(sample.ov().name());
                    ObjectId fv = commitsByRelease.get(sample.fv().name());
                    if (iv == null || ov == null || fv == null) throw new IllegalStateException("tag/commit assente");
                    boolean fvDescendsIv = isDescendant(walk, ancestryCache, iv, fv);
                    boolean fvDescendsOv = isDescendant(walk, ancestryCache, ov, fv);
                    boolean ovDescendsIv = isDescendant(walk, ancestryCache, iv, ov);
                    boolean ivDescendsOv = isDescendant(walk, ancestryCache, ov, iv);
                    String category = gitLineageCategory(fvDescendsIv, fvDescendsOv, ovDescendsIv, ivDescendsOv);
                    diagnostics.add(new GitLineageDiagnostic(sample, category, fvDescendsIv, fvDescendsOv, ovDescendsIv, ivDescendsOv));
                    System.out.println("  " + sample.ticket().key()
                            + " | IV=" + sample.iv().name() + " (linea " + releaseLine(sample.iv().name()) + ")"
                            + " | OV=" + sample.ov().name() + " (linea " + releaseLine(sample.ov().name()) + ")"
                            + " | FV=" + sample.fv().name() + " (linea " + releaseLine(sample.fv().name()) + ")"
                            + " | FV descendant of IV: " + fvDescendsIv
                            + " | FV descendant of OV: " + fvDescendsOv
                            + " | OV descendant of IV: " + ovDescendsIv
                            + " | IV descendant of OV: " + ivDescendsOv
                            + " | " + category);
                } catch (Exception exception) {
                    error("Ancestry non determinabile per " + sample.ticket().key() + ": " + rootMessage(exception));
                }
            }
            printGitLineageSummary(diagnostics, releases, commitsByRelease, walk, ancestryCache);
        } catch (Exception exception) {
            error("Ancestry Git non eseguibile: " + rootMessage(exception));
        }
    }

    /** Risolve tutti i tag una sola volta, evitando 3 aperture del clone per ticket. */
    private static Map<String, ObjectId> resolveReleaseTagCommits(Repository repository, Git git, RevWalk walk, List<Release> releases) throws Exception {
        Map<String, ObjectId> result = new HashMap<>();
        for (Release release : releases) {
            Optional<String> tag = CheckoutManager.findTag(git, release.name());
            if (tag.isEmpty()) continue;
            Ref ref = repository.findRef(tag.get());
            if (ref == null) continue;
            ObjectId id = repository.resolve(ref.getName());
            if (id != null) result.put(release.name(), walk.parseCommit(id).getId());
        }
        return result;
    }

    /** La cache rende il report completo veloce anche quando le release si ripetono fra ticket. */
    private static boolean isDescendant(RevWalk walk, Map<String, Boolean> cache, ObjectId ancestor, ObjectId descendant) throws IOException {
        String key = ancestor.name() + "->" + descendant.name();
        Boolean cached = cache.get(key);
        if (cached != null) return cached;
        walk.reset();
        boolean value = walk.isMergedInto(walk.parseCommit(ancestor), walk.parseCommit(descendant));
        cache.put(key, value);
        return value;
    }

    private static String gitLineageCategory(boolean fvDescendsIv, boolean fvDescendsOv, boolean ovDescendsIv, boolean ivDescendsOv) {
        // Prima i due percorsi lineari completi. L'ordine e' importante nei
        // rarissimi casi in cui IV e OV puntano allo stesso commit Git.
        if (ovDescendsIv && fvDescendsOv) return "LINEAR_IV_OV_FV";
        if (ivDescendsOv && fvDescendsIv) return "LINEAR_OV_IV_FV";
        if (fvDescendsIv && !fvDescendsOv && !ovDescendsIv && !ivDescendsOv) return "IV_FV_CONNECTED_OV_OUTSIDE";
        if (fvDescendsOv && !fvDescendsIv && !ovDescendsIv && !ivDescendsOv) return "OV_FV_CONNECTED_IV_OUTSIDE";
        return "NO_SINGLE_LINEAGE";
    }

    private static void printGitLineageSummary(List<GitLineageDiagnostic> diagnostics, List<Release> releases,
                                                Map<String, ObjectId> commitsByRelease, RevWalk walk,
                                                Map<String, Boolean> ancestryCache) throws IOException {
        section("RIEPILOGO LINEAGE GIT");
        List<String> categories = List.of("LINEAR_IV_OV_FV", "LINEAR_OV_IV_FV", "IV_FV_CONNECTED_OV_OUTSIDE",
                "OV_FV_CONNECTED_IV_OUTSIDE", "NO_SINGLE_LINEAGE");
        Map<String, List<GitLineageDiagnostic>> byCategory = diagnostics.stream()
                .collect(Collectors.groupingBy(GitLineageDiagnostic::category, LinkedHashMap::new, Collectors.toList()));
        for (String category : categories) {
            List<GitLineageDiagnostic> values = byCategory.getOrDefault(category, List.of());
            System.out.println(category + ": " + values.size() + "/" + diagnostics.size() + " ("
                    + format(diagnostics.isEmpty() ? 0 : 100.0 * values.size() / diagnostics.size()) + "%)");
            values.stream().limit(EXAMPLE_LIMIT).forEach(value -> System.out.println("  esempio " + value.sample().ticket().key()
                    + " | IV=" + value.sample().iv().name() + " | OV=" + value.sample().ov().name()
                    + " | FV=" + value.sample().fv().name()));
        }

        List<GitLineageDiagnostic> linear = diagnostics.stream()
                .filter(value -> value.category().equals("LINEAR_IV_OV_FV")).toList();
        section("DISTANZE DI RELEASE SULLA CATENA GIT - LINEAR_IV_OV_FV");
        int different = 0;
        for (GitLineageDiagnostic diagnostic : linear) {
            ProportionSample sample = diagnostic.sample();
            int chainIvFv = chainReleaseDistance(sample.iv(), sample.fv(), releases, commitsByRelease, walk, ancestryCache);
            int chainOvFv = chainReleaseDistance(sample.ov(), sample.fv(), releases, commitsByRelease, walk, ancestryCache);
            int globalIvFv = sample.fv().index() - sample.iv().index();
            int globalOvFv = sample.fv().index() - sample.ov().index();
            boolean differs = chainIvFv != globalIvFv || chainOvFv != globalOvFv;
            if (differs) different++;
            System.out.println("  " + sample.ticket().key() + " | IV->FV catena=" + chainIvFv + " globale=" + globalIvFv
                    + " | OV->FV catena=" + chainOvFv + " globale=" + globalOvFv + " | diverso=" + differs);
        }
        System.out.println("LINEAR_IV_OV_FV con almeno una distanza diversa: " + different + "/" + linear.size());
    }

    /**
     * Conta i tag Jira presenti sul percorso Git inclusivo fra due endpoint.
     * La distanza e' il numero di passaggi fra release della catena, non una
     * nuova numerazione: e' mostrata solamente accanto alla distanza globale.
     */
    private static int chainReleaseDistance(Release start, Release end, List<Release> releases,
                                            Map<String, ObjectId> commitsByRelease, RevWalk walk,
                                            Map<String, Boolean> ancestryCache) throws IOException {
        ObjectId startCommit = commitsByRelease.get(start.name());
        ObjectId endCommit = commitsByRelease.get(end.name());
        int onPath = 0;
        for (Release candidate : releases) {
            ObjectId candidateCommit = commitsByRelease.get(candidate.name());
            if (candidateCommit != null
                    && isDescendant(walk, ancestryCache, startCommit, candidateCommit)
                    && isDescendant(walk, ancestryCache, candidateCommit, endCommit)) onPath++;
        }
        return Math.max(0, onPath - 1);
    }

    private static String releaseLine(String releaseName) {
        if (releaseName == null) return "<unknown>";
        String value = releaseName.startsWith("v") ? releaseName.substring(1) : releaseName;
        String[] parts = value.split("\\.");
        if (parts.length < 2 || !parts[0].matches("\\d+") || !parts[1].matches("\\d+")) return "<unknown>";
        return parts[0] + "." + parts[1];
    }

    private static String lineageCategory(String iv, String ov, String fv) {
        if (iv.equals(ov) && ov.equals(fv)) return "SAME_LINE_IV_OV_FV";
        if (iv.equals(fv)) return "IV_FV_SAME_LINE_OV_DIFFERENT";
        if (iv.equals(ov)) return "IV_OV_SAME_LINE_FV_DIFFERENT";
        if (ov.equals(fv)) return "OV_FV_SAME_LINE_IV_DIFFERENT";
        return "ALL_DIFFERENT_LINES";
    }

    /**
     * Prepara il contesto che il report visualizza senza scrivere label e senza
     * alterare il ticket. La stima IV e' la stessa gia' usata dal labeling:
     * AV quando presente, altrimenti Proportion Total.
     */
    private static List<TicketDiagnosticContext> diagnosticContexts(List<Ticket> tickets, List<Release> releases) {
        ProportionCalculator calculator = new ProportionCalculator();
        Map<String, Release> known = tickets.stream()
                .map(ticket -> Map.entry(ticket.key(), firstAffected(ticket, releases)))
                .filter(entry -> entry.getValue().isPresent())
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().get()));
        OptionalDouble p = calculator.calculateProportionTotal(tickets, releases, known);
        List<TicketDiagnosticContext> result = new ArrayList<>();
        for (Ticket ticket : tickets) {
            Optional<Release> iv = firstAffected(ticket, releases);
            String source = "AFFECTED_VERSION";
            ProportionCalculator.FixedVersionResolution fixed = calculator.resolveFixedVersion(ticket, releases);
            if (iv.isEmpty() && fixed.release().isPresent()) {
                iv = calculator.estimateInjectedVersion(ticket, releases, p);
                source = "PROPORTION";
            }
            result.add(new TicketDiagnosticContext(ticket, iv, calculator.findOpeningVersion(ticket, releases), fixed.release(), source));
        }
        return result;
    }

    /** L'intervallo e' trattato come [IV,FV), esattamente come nel labeler. */
    private static boolean intersectsTraining(TicketDiagnosticContext context, List<Release> training) {
        if (context.iv().isEmpty() || context.fv().isEmpty() || training.isEmpty()) return false;
        int firstTrainingIndex = training.get(0).index();
        int lastTrainingIndex = training.get(training.size() - 1).index();
        return context.iv().get().index() <= lastTrainingIndex && context.fv().get().index() > firstTrainingIndex;
    }

    /** Assegna un ruolo alla prima scelta utile senza duplicare artificialmente un ticket. */
    private static void selectTicketForRole(List<TicketDiagnosticContext> candidates, Map<String, Set<String>> roles,
                                            String role, java.util.function.Predicate<TicketDiagnosticContext> predicate) {
        candidates.stream().filter(predicate).findFirst().ifPresent(context ->
                roles.computeIfAbsent(context.ticket().key(), ignored -> new LinkedHashSet<>()).add(role));
    }

    private static boolean selectionContainsRole(Map<String, Set<String>> roles, String role) {
        return roles.values().stream().anyMatch(values -> values.contains(role));
    }

    private static String releaseDescription(Optional<Release> release) {
        return release.map(value -> value.index() + "/" + value.name()).orElse("<assente>");
    }
    private static Set<String> ticketKeys(List<Ticket> tickets) { return tickets.stream().map(Ticket::key).collect(Collectors.toCollection(LinkedHashSet::new)); }
    private static Release firstTrainingRelease() throws Exception { ReleaseManager manager = new ReleaseManager(); return manager.getTrainingReleases(manager.getReleases()).get(0); }
    private static Optional<Release> firstAffected(Ticket ticket, List<Release> releases) { return ticket.affectedVersions().stream().flatMap(name -> releases.stream().filter(release -> release.name().equals(name))).min(Comparator.comparingInt(Release::index)); }
    private static String relative(Path root, Path path) { return root.relativize(path).toString().replace('\\', '/'); }
    private static ScannerCounts scanJavaFiles(Path repository, JavaClassScanner scanner) throws IOException {
        List<Path> allJava;
        try (var paths = Files.walk(repository)) {
            allJava = paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList();
        }
        Set<String> scannerPaths = scanner.findJavaFiles(repository).stream().map(path -> relative(repository, path)).collect(Collectors.toCollection(TreeSet::new));
        Set<String> filteredPaths = allJava.stream().map(path -> relative(repository, path))
                .filter(JavaClassScanner::isJavaProductionFile).collect(Collectors.toCollection(TreeSet::new));
        List<String> excluded = allJava.stream().map(path -> relative(repository, path))
                .filter(path -> !JavaClassScanner.isJavaProductionFile(path)).sorted().toList();
        return new ScannerCounts(allJava.size(), filteredPaths.size(), excluded.size(), scannerPaths, filteredPaths, excluded);
    }
    /**
     * Confronta le classi scritte nel CSV con quelle realmente visibili al tag
     * della stessa release. Non viene eseguito su un clone sporco: il check CSV
     * resta comunque utile anche quando questo approfondimento non e' sicuro.
     */
    private static void crossCheckCsvClassesAgainstCheckout(Map<String, Set<String>> csvClassesByRelease) throws Exception {
        try (Repository repository = Utils.GitRepositoryUtils.openRepository(); Git git = new Git(repository)) {
            if (!git.status().call().isClean()) {
                warn("Cross-check CSV/checkouts non eseguito: clone Storm non pulito.");
                return;
            }
        }
        section("CROSS-CHECK CSV - CLASSI NEL CHECKOUT DELLA RELEASE");
        List<Release> releases = new ReleaseManager().getReleases();
        Map<String, Release> byName = releases.stream().collect(Collectors.toMap(Release::name, release -> release));
        CheckoutManager checkout = new CheckoutManager();
        JavaClassScanner scanner = new JavaClassScanner();
        try {
            for (Map.Entry<String, Set<String>> entry : csvClassesByRelease.entrySet()) {
                Release release = byName.get(entry.getKey());
                if (release == null) {
                    error("Release presente nel CSV ma assente dal ReleaseManager: " + entry.getKey());
                    continue;
                }
                checkout.checkoutRelease(release);
                Set<String> checkoutClasses = scanner.findJavaFiles(checkout.getRepositoryPath()).stream()
                        .map(path -> relative(checkout.getRepositoryPath(), path)).collect(Collectors.toCollection(TreeSet::new));
                Set<String> phantom = new TreeSet<>(entry.getValue()); phantom.removeAll(checkoutClasses);
                Set<String> missing = new TreeSet<>(checkoutClasses); missing.removeAll(entry.getValue());
                System.out.println("release=" + release.name() + " | righe CSV=" + entry.getValue().size()
                        + " | classi checkout=" + checkoutClasses.size() + " | fantasma CSV=" + phantom.size()
                        + " | classi checkout assenti CSV=" + missing.size());
                if (!phantom.isEmpty()) System.out.println("  Esempi fantasma: " + phantom.stream().limit(5).toList());
                if (!missing.isEmpty()) System.out.println("  Esempi assenti dal CSV: " + missing.stream().limit(5).toList());
            }
        } finally {
            checkout.cleanRepository();
        }
    }
    /**
     * Il labeling avviene su path Git, mentre il CSV contiene solo classi che
     * esistono davvero nel checkout. L'intersezione qui sotto replica questa
     * condizione e rende leggibile la percentuale buggy per ogni release.
     */
    /**
     * Verifica il punto esatto in cui un path SZZ potrebbe non arrivare alla
     * riga CSV. Il confronto e' letterale: non corregge slash, rename o path.
     */
    private static Map<String, Set<String>> printSzzPathPresenceDiagnostics(
            List<TicketDiagnosticContext> selected, List<Release> training,
            Map<String, Map<String, Set<RevCommit>>> inducingByTicket, BuggyLabeler labeler) throws Exception {
        section("COLLEGAMENTO SZZ -> SCANNER -> CSV SULLE RELEASE TRAINING");
        Map<String, Set<String>> pathsByRelease = scanTrainingPaths(training);
        if (pathsByRelease.isEmpty()) return pathsByRelease;

        Map<String, Boolean> persistedCsvMarks = readPersistedCsvMarks();
        System.out.println("EXACT_PATH_FOUND confronta letteralmente il path SZZ con JavaClassScanner.");
        System.out.println("CSV_ROW_PERSISTED legge il CSV attuale. TEMPORAL_CANDIDATE mostra solo [IV,FV); il riepilogo successivo applica anche ancestry e resolver storico, come DatasetBuilder.");

        for (TicketDiagnosticContext context : selected) {
            for (String szzPath : inducingByTicket.getOrDefault(context.ticket().key(), Map.of()).keySet()) {
                for (Release release : training) {
                    // La richiesta riguarda precisamente le coppie che il
                    // labeler considera buggy per quel path e quella release.
                    if (!labeler.isBuggy(release, szzPath)) continue;
                    Set<String> scanned = pathsByRelease.getOrDefault(release.name(), Set.of());
                    boolean exact = scanned.contains(szzPath);
                    Boolean persisted = persistedCsvMarks.get(csvKey(release.name(), szzPath));
                    System.out.println("ticket=" + context.ticket().key() + " | release=" + release.name()
                            + " | SZZ path=" + szzPath + " | RELEASE_IN_BUGGY_INTERVAL=true"
                            + " | EXACT_PATH_FOUND=" + exact
                            + " | CLASS_EXISTS_IN_RELEASE=" + exact
                            + " | CSV_ROW_PERSISTED=" + (persisted == null ? "<assente>" : persisted)
                            + " | TEMPORAL_CANDIDATE=true");
                    if (!exact) printPathCandidates(szzPath, scanned);
                }
            }
        }
        return pathsByRelease;
    }

    /** Scansiona le 14 release una sola volta e riusa il risultato nel riepilogo. */
    private static Map<String, Set<String>> scanTrainingPaths(List<Release> training) throws Exception {
        try (Repository repository = Utils.GitRepositoryUtils.openRepository(); Git git = new Git(repository)) {
            if (!git.status().call().isClean()) {
                warn("Diagnostica path non eseguita: clone Storm non pulito.");
                return Map.of();
            }
        }
        CheckoutManager checkout = new CheckoutManager();
        JavaClassScanner scanner = new JavaClassScanner();
        Map<String, Set<String>> result = new LinkedHashMap<>();
        try {
            for (Release release : training) {
                try {
                    result.put(release.name(), scannerPathsAfterCheckout(checkout, scanner, release));
                } catch (Exception exception) {
                    error("Scanner release " + release.name() + " non eseguibile: " + rootMessage(exception));
                }
            }
        } finally {
            checkout.cleanRepository();
        }
        return result;
    }

    /**
     * Riproduce la parte finale di DatasetBuilder per il solo campione labels.
     * Le classi diventano buggy soltanto dopo controllo di data, ancestry Git
     * e mapping prudente verso i path reali dello scanner.
     */
    private static Map<String, Set<String>> resolveHistoricalCandidatesForCheck(
            List<Release> training,
            BuggyLabeler labeler,
            Map<String, Set<String>> scannerPathsByRelease) throws Exception {

        if (scannerPathsByRelease.isEmpty()) {
            return Map.of();
        }

        section("LABELING FINALE DEL CAMPIONE: ANCESTRY E PATH STORICI");
        Map<String, Set<String>> result = new LinkedHashMap<>();
        try (Repository repository = Utils.GitRepositoryUtils.openRepository();
             HistoricalSzzClassResolver resolver = new HistoricalSzzClassResolver(repository)) {
            for (Release release : training) {
                HistoricalSzzClassResolver.ResolutionResult resolved = resolver.resolve(
                        release,
                        CommitManager.getReleaseCommit(release),
                        scannerPathsByRelease.getOrDefault(release.name(), Set.of()),
                        labeler.getCandidates(release));
                result.put(release.name(), resolved.buggyPaths());
                HistoricalSzzClassResolver.ResolutionStatistics stats = resolved.statistics();
                System.out.println("release=" + release.name()
                        + " | candidati Jira=" + stats.totalCandidates()
                        + " | PRE_INDUCING=" + stats.preInducing()
                        + " | NON_RAGGIUNGIBILE=" + stats.inducingNotReachable()
                        + " | EXACT=" + stats.exactPath()
                        + " | SOURCE=" + stats.sourcePath()
                        + " | UNIQUE=" + stats.uniqueSimpleName()
                        + " | AMBIGUOUS=" + stats.ambiguous()
                        + " | NOT_FOUND=" + stats.notFound()
                        + " | CsvRow buggy=" + resolved.buggyPaths().size());
            }
        }
        return result;
    }

    /** Legge soltanto il CSV gia' presente: non crea né aggiorna alcuna riga. */
    private static Map<String, Boolean> readPersistedCsvMarks() throws IOException {
        if (!Files.exists(Config.DATASET_CSV)) {
            warn("CSV assente: non e' possibile osservare CSV_ROW_PERSISTED.");
            return Map.of();
        }
        List<String> lines = Files.readAllLines(Config.DATASET_CSV, StandardCharsets.UTF_8);
        if (lines.size() < 2) return Map.of();
        Map<String, Boolean> result = new HashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            List<String> row = parseCsv(line);
            if (row.size() < 5) continue;
            result.put(csvKey(row.get(2), row.get(3)), Boolean.parseBoolean(row.get(row.size() - 1)));
        }
        return result;
    }

    private static String csvKey(String releaseName, String classPath) { return releaseName + "\u0000" + classPath; }

    /** Stampa candidati senza usarli per cambiare alcun path o label. */
    private static void printPathCandidates(String szzPath, Set<String> scannedPaths) {
        String fileName = szzPath.substring(szzPath.lastIndexOf('/') + 1);
        String simpleClassName = fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - 5) : fileName;
        String packageClass = packageClassSuffix(szzPath);
        printCandidates("stesso filename", scannedPaths.stream().filter(path -> path.endsWith("/" + fileName) || path.equals(fileName)).toList());
        printCandidates("stessa simple class name", scannedPaths.stream().filter(path -> {
            String candidate = path.substring(path.lastIndexOf('/') + 1);
            return candidate.equals(simpleClassName + ".java");
        }).toList());
        if (packageClass != null) printCandidates("stesso package/class name (" + packageClass + ")",
                scannedPaths.stream().filter(path -> path.endsWith("/" + packageClass) || path.equals(packageClass)).toList());
        else System.out.println("  candidate package/class: <non ricavabile dal path SZZ>");
    }

    private static void printCandidates(String label, List<String> candidates) {
        System.out.println("  candidate " + label + " (" + candidates.size() + "): " + candidates.stream().limit(20).toList());
    }

    /** Estrae la porzione Java package + classe senza supporre un unico layout Maven. */
    private static String packageClassSuffix(String path) {
        String normalized = path.replace('\\', '/');
        for (String marker : List.of("/src/main/java/", "/src/jvm/")) {
            int position = normalized.indexOf(marker);
            if (position >= 0) return normalized.substring(position + marker.length());
        }
        for (String packageRoot : List.of("/org/", "/backtype/")) {
            int position = normalized.indexOf(packageRoot);
            if (position >= 0) return normalized.substring(position + 1);
        }
        return null;
    }

    private static void printLabelingSummaryByTrainingRelease(List<Release> releases, Map<String, Set<String>> buggyClassesByRelease,
                                                              int sampledTickets, Map<String, Set<String>> scannedPathsByRelease) throws Exception {
        section("RIEPILOGO LABELING PER RELEASE TRAINING - CAMPIONE " + sampledTickets + " TICKET");
        List<Release> training = new ReleaseManager().getTrainingReleases(releases);
        if (scannedPathsByRelease.isEmpty()) {
            warn("Riepilogo per release non disponibile: scanner non eseguito sul clone corrente.");
            return;
        }
        for (Release release : training) {
            Set<String> production = scannedPathsByRelease.getOrDefault(release.name(), Set.of());
            Set<String> buggy = new TreeSet<>(buggyClassesByRelease.getOrDefault(release.name(), Set.of()));
            buggy.retainAll(production);
            double percentage = production.isEmpty() ? 0 : 100.0 * buggy.size() / production.size();
            System.out.println("release=" + release.name() + " | classi buggy=" + buggy.size()
                    + " | classi non-buggy=" + (production.size() - buggy.size())
                    + " | totale classi=" + production.size() + " | percentuale buggy=" + format(percentage) + "%");
        }
    }
    private static Set<String> scannerPathsAfterCheckout(CheckoutManager checkout, JavaClassScanner scanner, Release release) {
        checkout.checkoutRelease(release);
        return scanner.findJavaFiles(checkout.getRepositoryPath()).stream()
                .map(path -> relative(checkout.getRepositoryPath(), path)).collect(Collectors.toCollection(TreeSet::new));
    }
    private static VersionMappingCounts countVersionMappings(List<Ticket> tickets, java.util.function.Function<Ticket, List<String>> versions, Set<String> knownNames) {
        List<String> declared = tickets.stream().flatMap(ticket -> versions.apply(ticket).stream()).toList();
        List<String> unresolved = declared.stream().filter(name -> !knownNames.contains(name)).toList();
        return new VersionMappingCounts(declared.size(), declared.size() - unresolved.size(), unresolved);
    }
    private static List<String> resolvedVersions(List<String> declared, Set<String> knownNames) { return declared.stream().filter(knownNames::contains).toList(); }
    private static boolean isTestPath(String path) { String normalized = path.replace('\\', '/'); return normalized.contains("/src/test/") || normalized.startsWith("src/test/") || normalized.contains("/test/") || normalized.startsWith("test/"); }
    /**
     * Gli oggetti JGit aggiungono spesso un'eccezione esterna molto generica.
     * Per un controllo diagnostico e' piu' utile mostrare il motivo originale
     * (per esempio un file modificato o un tag assente).
     */
    private static String rootMessage(Throwable exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getClass().getSimpleName() + ": " + String.valueOf(root.getMessage());
    }

    private static int readLimit(String[] args, int defaultValue) { for (String arg : args) if (arg.startsWith("--limit=")) return Integer.parseInt(arg.substring(8)); return defaultValue; }
    private static void section(String value) { System.out.println("\n============================================================\n" + value + "\n============================================================"); }
    private static void ok(String value) { System.out.println("[OK] " + value); }
    private static void warn(String value) { System.out.println("[WARN] " + value); }
    private static void error(String value) { System.out.println("[ERROR] " + value); }
    private static void status(boolean condition, String okValue, String errorValue) { if (condition) ok(okValue); else error(errorValue); }
    private static void increment(Map<String, Integer> values, String key) { values.merge(key, 1, Integer::sum); }
    private static String format(double value) { return String.format(Locale.ROOT, "%.4f", value); }
    private static <T> void countAndPrint(String label, List<T> values, java.util.function.Function<T, String> function) { System.out.println(label + ": " + values.stream().collect(Collectors.groupingBy(function, TreeMap::new, Collectors.counting()))); }
    private static void printMetricRange(String label, List<CsvRow> rows, ToDoubleFunction<CsvRow> function) { DoubleSummaryStatistics s = rows.stream().mapToDouble(function).summaryStatistics(); System.out.println(label + " min=" + format(s.getMin()) + " max=" + format(s.getMax()) + " media=" + format(s.getAverage())); }
    private static void printPrediction(Ticket ticket, List<Release> releases, ProportionCalculator calculator, double p) { Optional<Release> ov=calculator.findOpeningVersion(ticket,releases); ProportionCalculator.FixedVersionResolution fixed=calculator.resolveFixedVersion(ticket,releases); if(ov.isEmpty()){System.out.println("  "+ticket.key()+" | NO_OV");return;} if(fixed.status()!=ProportionCalculator.FixedVersionStatus.RESOLVED){System.out.println("  "+ticket.key()+" | "+fixed.status());return;} Optional<Release> fv=fixed.release(); if(fv.get().index()<=ov.get().index()){String reason=fv.get().index()==ov.get().index()?"FV_EQ_OV":"FV_LT_OV";System.out.println("  "+ticket.key()+" | "+reason+" | IV non stimabile");return;} double raw=fv.get().index()-p*(fv.get().index()-ov.get().index()); System.out.println("  "+ticket.key()+" | raw IV="+format(raw)+" | rounded index="+Math.round(raw)+" | release scelta="+calculator.estimateInjectedVersion(ticket,releases,OptionalDouble.of(p)).map(Release::name).orElse("<assente>")); }
    private static List<String> parseCsv(String row) { List<String> values=new ArrayList<>(); StringBuilder current=new StringBuilder(); boolean quoted=false; for(int i=0;i<row.length();i++){char c=row.charAt(i); if(c=='"'&&quoted&&i+1<row.length()&&row.charAt(i+1)=='"'){current.append(c);i++;}else if(c=='"'){quoted=!quoted;}else if(c==','&&!quoted){values.add(current.toString());current.setLength(0);}else current.append(c);} values.add(current.toString()); return values; }
    private static FileCounts countChangedFiles(Repository repository, RevCommit commit) throws Exception { if(commit.getParentCount()==0)return new FileCounts(0,0,0,Set.of()); try(RevWalk walk=new RevWalk(repository); DiffFormatter diff=new DiffFormatter(DisabledOutputStream.INSTANCE)){RevCommit current=walk.parseCommit(commit.getId());RevCommit parent=walk.parseCommit(current.getParent(0));diff.setRepository(repository);int total=0,prod=0,test=0;Set<String> paths=new TreeSet<>();for(DiffEntry entry:diff.scan(parent.getTree(),current.getTree())){total++;String path=entry.getChangeType()==DiffEntry.ChangeType.DELETE?entry.getOldPath():entry.getNewPath();if(path.endsWith(".java")){if(isTestPath(path))test++;else if(JavaClassScanner.isJavaProductionFile(path)){prod++;paths.add(path);}}}return new FileCounts(total,prod,test,paths);} }

    @FunctionalInterface
    private interface CheckedRunnable { void run() throws Exception; }

    /** Scrive gli stessi byte sulla console e sul file, senza chiudere la console Maven. */
    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream console;
        private final OutputStream file;

        private TeeOutputStream(OutputStream console, OutputStream file) {
            this.console = console;
            this.file = file;
        }

        @Override public void write(int value) throws IOException { console.write(value); file.write(value); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException { console.write(bytes, offset, length); file.write(bytes, offset, length); }
        @Override public void flush() throws IOException { console.flush(); file.flush(); }
        @Override public void close() throws IOException { flush(); }
    }

    private record ScannerCounts(int totalJava, int production, int excluded, Set<String> scannerPaths, Set<String> filteredPaths, List<String> excludedPaths) { }
    private record VersionMappingCounts(long declared, long resolved, List<String> unresolved) { }
    private record OpeningVersionDiagnostic(Ticket ticket, Optional<Release> openingVersion, String status) { }
    private record TemporalOrderDiagnostic(Ticket ticket, Release iv, Release fv, Release ov, String reason) { }
    private record FileCounts(int total,int production,int test,Set<String> productionPaths) { }
    private record ProportionSample(Ticket ticket,Release iv,Release ov,Release fv,double value) { }
    /** Risultato immutabile dell'osservazione Git, separato dal dato usato da P. */
    private record GitLineageDiagnostic(ProportionSample sample, String category, boolean fvDescendsIv,
                                        boolean fvDescendsOv, boolean ovDescendsIv, boolean ivDescendsOv) { }
    /** Snapshot read-only IV/OV/FV usato per scegliere campioni che toccano il training. */
    private record TicketDiagnosticContext(Ticket ticket, Optional<Release> iv, Optional<Release> ov,
                                           Optional<Release> fv, String source) { }
    /** Richiesta fissa: non deriva candidati dal filename e non applica mapping. */
    private record PathHistoryRequest(String ticket, String szzPath) { }
    /** Un evento Git del file, letto dal commit piu' recente verso il passato. */
    private record PathHistoryStep(ObjectId childCommit, ObjectId parentCommit, String oldPath, String newPath,
                                   String type, int similarityScore) { }
    /** Esito esplicito: un path assente non viene trasformato in un candidato. */
    private record HistoricalPathResolution(Optional<String> path, String status) { }
    /** Ordine di risoluzione richiesto dal check, mai usato per modificare una label. */
    private enum ResolutionStatus { EXACT_PATH, SOURCE_PATH, UNIQUE_SIMPLE_NAME, AMBIGUOUS, NOT_FOUND }
    /** Spiega temporalmente un NOT_FOUND senza proporre un path sostitutivo. */
    private enum NotFoundTemporalStatus { PRE_INDUCING, POST_INDUCING_NOT_FOUND, NO_INDUCING_DATE }
    /** Commit inducing piu' antico osservato per la singola classe SZZ del ticket. */
    private record InducingCommitEvidence(String commitId, LocalDate date) { }
    /** Una singola coppia ticket/classe/release osservata nel solo report globale. */
    private record SzzClassResolution(String ticket, Release release, String szzPath, Set<String> sourcePaths,
                                      InducingCommitEvidence earliestInducing,
                                      ResolutionStatus status, List<String> candidates) { }
    /** Esito informativo dell'ancestry Git per un NOT_FOUND post-inducing. */
    private record PostInducingReachability(SzzClassResolution resolution, boolean reachable, String issue) { }
}
