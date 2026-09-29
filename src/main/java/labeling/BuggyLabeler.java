package labeling;

import model.Release;
import model.Ticket;
import org.eclipse.jgit.revwalk.RevCommit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Determina l'intervallo temporale in cui una classe SZZ puo' essere buggy.
 *
 * <p>Questa classe applica esclusivamente le regole Jira IV/OV/FV e
 * Proportion. La verifica che un path esista davvero nella storia Git della
 * singola release e' compito di {@link HistoricalSzzClassResolver}: separare
 * i due aspetti evita di propagare una classe da un branch Storm a un altro.</p>
 */
public final class BuggyLabeler {

    /*
     * Conserva i path SZZ temporalmente candidati. Il campo e' mantenuto per
     * compatibilita' con i check storici che mostrano il solo intervallo Jira;
     * DatasetBuilder usa invece getCandidates() e il resolver storico prima
     * di decidere una CsvRow.
     */
    private final Map<String, Set<String>> classesByRelease = new HashMap<>();

    /** Candidati completi, inclusi commit inducing e source path del blame. */
    private final Map<String, List<LabelCandidate>> candidatesByRelease = new HashMap<>();

    private final ProportionCalculator proportionCalculator = new ProportionCalculator();

    /**
     * API mantenuta per i check preesistenti: non possiede source path e quindi
     * produce candidati con la sola evidenza SZZ tradizionale.
     */
    public void labelBugs(
            List<Ticket> tickets,
            List<Release> releases,
            Map<String, Map<String, Set<RevCommit>>> inducingCommits) {

        Map<String, Map<String, SzzClassEvidence>> evidenceByTicket = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Set<RevCommit>>> ticketEntry : inducingCommits.entrySet()) {
            Map<String, SzzClassEvidence> evidenceByPath = new LinkedHashMap<>();
            for (Map.Entry<String, Set<RevCommit>> classEntry : ticketEntry.getValue().entrySet()) {
                evidenceByPath.put(classEntry.getKey(), new SzzClassEvidence(
                        classEntry.getKey(), classEntry.getValue(), Set.of()));
            }
            evidenceByTicket.put(ticketEntry.getKey(), evidenceByPath);
        }

        labelBugsWithEvidence(tickets, releases, evidenceByTicket);
    }

    /**
     * Prepara i candidati temporali con l'evidenza completa di SZZ.
     *
     * <p>La label definitiva non viene assegnata qui: per ogni release il
     * DatasetBuilder controllera' prima data, ancestry e presenza della classe
     * tramite {@link HistoricalSzzClassResolver}.</p>
     */
    public void labelBugsWithEvidence(
            List<Ticket> tickets,
            List<Release> releases,
            Map<String, Map<String, SzzClassEvidence>> evidenceByTicket) {

        classesByRelease.clear();
        candidatesByRelease.clear();

        /*
         * La consegna usa la prima Affected Version valida come IV nota.
         * Solo questi ticket contribuiscono alla media Proportion Total.
         */
        Map<String, Release> knownInjectedVersions = new HashMap<>();
        for (Ticket ticket : tickets) {
            findAffectedVersion(ticket, releases)
                    .ifPresent(iv -> knownInjectedVersions.put(ticket.key(), iv));
        }

        OptionalDouble proportion = proportionCalculator.calculateProportionTotal(
                tickets, releases, knownInjectedVersions);

        System.out.println("Proportion Total: "
                + (proportion.isPresent() ? proportion.getAsDouble() : "non calcolabile"));

        for (Ticket ticket : tickets) {
            Map<String, SzzClassEvidence> szzEvidenceByClass =
                    evidenceByTicket.getOrDefault(ticket.key(), Map.of());

            // Senza fixing commit/SZZ non esistono classi da attribuire al bug.
            if (szzEvidenceByClass.isEmpty()) {
                continue;
            }

            ProportionCalculator.FixedVersionResolution fixedResolution =
                    proportionCalculator.resolveFixedVersion(ticket, releases);
            Optional<Release> fixedVersion = fixedResolution.release();

            // AV e' la fonte primaria della IV definita dalla consegna.
            Optional<Release> injectedVersion = findAffectedVersion(ticket, releases);
            if (injectedVersion.isEmpty() && fixedVersion.isPresent()) {
                injectedVersion = proportionCalculator.estimateInjectedVersion(
                        ticket, releases, proportion);
            }

            if (injectedVersion.isEmpty()) {
                continue;
            }

            /*
             * Una FV dimostrabilmente oltre il corpus e' right-censored: non
             * inventiamo una FV interna e rendiamo candidati tutti i tag da IV
             * alla fine del corpus. Il resolver Git filtrera' poi ramo e path.
             */
            if (fixedResolution.status()
                    == ProportionCalculator.FixedVersionStatus.FV_AFTER_CORPUS) {
                for (SzzClassEvidence evidence : szzEvidenceByClass.values()) {
                    markBuggyUntilCorpusEnd(ticket.key(), evidence,
                            injectedVersion.get(), releases);
                }
                continue;
            }

            if (fixedVersion.isEmpty()
                    || injectedVersion.get().index() >= fixedVersion.get().index()) {
                continue;
            }

            for (SzzClassEvidence evidence : szzEvidenceByClass.values()) {
                markBuggyInterval(ticket.key(), evidence, injectedVersion.get(),
                        fixedVersion.get(), releases);
            }
        }
    }

    /** Restituisce i candidati Jira per una release, ancora da validare su Git. */
    public List<LabelCandidate> getCandidates(Release release) {
        return List.copyOf(candidatesByRelease.getOrDefault(release.name(), List.of()));
    }

    /** Trova la prima Affected Version valida presente nel corpus Jira. */
    private Optional<Release> findAffectedVersion(Ticket ticket, List<Release> releases) {
        return ticket.affectedVersions().stream()
                .map(version -> findRelease(version, releases))
                .flatMap(Optional::stream)
                .min(Comparator.comparingInt(Release::index));
    }

    /** Cerca una release tramite il nome Jira, senza normalizzazioni inventate. */
    private Optional<Release> findRelease(String version, List<Release> releases) {
        if (version == null || version.isBlank()) {
            return Optional.empty();
        }

        return releases.stream()
                .filter(release -> release.name().equals(version))
                .findFirst();
    }

    /** Marca il candidato temporalmente attivo nell'intervallo {@code IV <= r < FV}. */
    private void markBuggyInterval(
            String ticketKey,
            SzzClassEvidence evidence,
            Release injectedVersion,
            Release fixedVersion,
            List<Release> releases) {

        for (Release release : releases) {
            if (release.index() >= injectedVersion.index()
                    && release.index() < fixedVersion.index()) {
                addCandidate(release, ticketKey, evidence);
            }
        }
    }

    /** Marca il candidato fino all'ultima release osservata per un ticket censurato. */
    private void markBuggyUntilCorpusEnd(
            String ticketKey,
            SzzClassEvidence evidence,
            Release injectedVersion,
            List<Release> releases) {

        for (Release release : releases) {
            if (release.index() >= injectedVersion.index()) {
                addCandidate(release, ticketKey, evidence);
            }
        }
    }

    private void addCandidate(Release release, String ticketKey, SzzClassEvidence evidence) {
        classesByRelease.computeIfAbsent(release.name(), ignored -> new LinkedHashSet<>())
                .add(evidence.szzPath());
        candidatesByRelease.computeIfAbsent(release.name(), ignored -> new ArrayList<>())
                .add(new LabelCandidate(ticketKey, evidence));
    }

    /**
     * Espone il risultato temporale letterale per i check storici. Non deve
     * essere usato dal DatasetBuilder come label finale, perche' ignora
     * ancestry Git e rename/move tra release.
     */
    public boolean isBuggy(Release release, String classPath) {
        return classesByRelease.getOrDefault(release.name(), Set.of()).contains(classPath);
    }

    /** Vedi {@link #isBuggy(Release, String)}: insieme temporale, non CSV finale. */
    public Set<String> getBuggyClasses(Release release) {
        return Set.copyOf(classesByRelease.getOrDefault(release.name(), Set.of()));
    }
}
