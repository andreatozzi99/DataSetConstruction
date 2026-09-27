package labeling;

import model.Release;
import model.Ticket;
import org.eclipse.jgit.revwalk.RevCommit;

import java.util.*;

/**
 * Esegue il labeling delle classi buggy.
 *
 * SZZ identifica quali classi sono coinvolte nel bug.
 *
 * Per stabilire da quale release la classe è buggy:
 * - usa la prima Affected Version Jira valida;
 * - se AV manca, stima IV tramite Proportion Total.
 *
 * La classe viene marcata buggy nell'intervallo:
 *
 * IV <= release < FV
 */
public final class BuggyLabeler {

    private final Map<String, Set<String>> classesByRelease =
            new HashMap<>();

    private final ProportionCalculator proportionCalculator =
            new ProportionCalculator();

    public void labelBugs(
            List<Ticket> tickets,
            List<Release> releases,
            Map<String, Map<String, Set<RevCommit>>> inducingCommits) {

        classesByRelease.clear();

        /*
         * La consegna usa la prima Affected Version valida come IV nota.
         * Solo questi ticket contribuiscono alla media Proportion Total.
         */
        Map<String, Release> knownInjectedVersions =
                new HashMap<>();

        for (Ticket ticket : tickets) {
            findAffectedVersion(ticket, releases)
                    .ifPresent(iv -> knownInjectedVersions.put(ticket.key(), iv));
        }

        OptionalDouble proportion =
                proportionCalculator.calculateProportionTotal(
                        tickets,
                        releases,
                        knownInjectedVersions);

        System.out.println(
                "Proportion Total: "
                        + (proportion.isPresent() ? proportion.getAsDouble() : "non calcolabile"));

        /*
         * Labeling.
         */
        for (Ticket ticket : tickets) {

            /*
             * SZZ stabilisce quali classi sono buggy
             * per questo ticket.
             */
            Map<String, Set<RevCommit>> szzInducingByClass =
                    inducingCommits.getOrDefault(
                            ticket.key(),
                            Map.of());

            // SZZ decide le classi coinvolte; un ticket senza tali classi non produce label.
            if (szzInducingByClass.isEmpty()) {
                continue;
            }

            Optional<Release> fixedVersion =
                    proportionCalculator.findFixedVersion(
                            ticket,
                            releases);

            if (fixedVersion.isEmpty()) {
                continue;
            }

            // AV e' la fonte primaria della IV stabilita dalla consegna.
            Optional<Release> injectedVersion = findAffectedVersion(ticket, releases);

            /*
             * Se AV manca, usiamo Proportion Total.
             */
            if (injectedVersion.isEmpty()) {

                injectedVersion =
                        proportionCalculator
                                .estimateInjectedVersion(
                                        ticket,
                                        releases,
                                        proportion);
            }

            if (injectedVersion.isEmpty()) {
                continue;
            }

            if (injectedVersion.get().index()
                    >= fixedVersion.get().index()) {

                continue;
            }

            /*
             * SOLO le classi identificate da SZZ
             * vengono marcate buggy.
             */
            for (String classPath : szzInducingByClass.keySet()) {

                markBuggyInterval(
                        classPath,
                        injectedVersion.get(),
                        fixedVersion.get(),
                        releases);
            }
        }
    }

    /**
     * Trova la prima Affected Version valida
     * presente tra le release conosciute.
     */
    private Optional<Release> findAffectedVersion(
            Ticket ticket,
            List<Release> releases) {

        return ticket.affectedVersions()
                .stream()
                .map(version ->
                        findRelease(
                                version,
                                releases))
                .flatMap(Optional::stream)
                .min(
                        Comparator.comparingInt(
                                Release::index));
    }

    /**
     * Cerca una release tramite il nome Jira.
     */
    private Optional<Release> findRelease(
            String version,
            List<Release> releases) {

        if (version == null
                || version.isBlank()) {

            return Optional.empty();
        }

        return releases.stream()
                .filter(release ->
                        release.name()
                                .equals(version))
                .findFirst();
    }

    /**
     * Marca la classe buggy nell'intervallo:
     *
     * IV <= release < FV
     */
    private void markBuggyInterval(
            String classPath,
            Release injectedVersion,
            Release fixedVersion,
            List<Release> releases) {

        for (Release release : releases) {

            if (release.index()
                    >= injectedVersion.index()
                    && release.index()
                    < fixedVersion.index()) {

                classesByRelease
                        .computeIfAbsent(
                                release.name(),
                                ignored ->
                                        new HashSet<>())
                        .add(classPath);
            }
        }
    }

    public boolean isBuggy(
            Release release,
            String classPath) {

        return classesByRelease
                .getOrDefault(
                        release.name(),
                        Set.of())
                .contains(classPath);
    }

    public Set<String> getBuggyClasses(
            Release release) {

        return Set.copyOf(
                classesByRelease
                        .getOrDefault(
                                release.name(),
                                Set.of()));
    }
}
