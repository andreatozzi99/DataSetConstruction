package labeling;

import model.Release;
import model.Ticket;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Calcola la Proportion Total usata quando un ticket non dichiara una versione
 * di introduzione del difetto. La consegna assume che la prima Affected
 * Version Jira valida costituisca la IV nota da usare come base empirica.
 */
public final class ProportionCalculator {

    /**
     * Media delle proportion dei ticket per i quali Jira espone una IV nota.
     * Per ogni ticket valido: P = (FV - IV) / (FV - OV), dove OV e' la prima
     * release pubblicata alla/aperta dopo la creazione del ticket.
     */
    public OptionalDouble calculateProportionTotal(
            List<Ticket> tickets,
            List<Release> releases,
            Map<String, Release> knownInjectedVersions) {
        double sum = 0.0;
        int count = 0;
        for (Ticket ticket : tickets) {
            Optional<Release> iv = Optional.ofNullable(knownInjectedVersions.get(ticket.key()));
            OptionalDouble value = calculateSingleProportion(iv, findFixedVersion(ticket, releases),
                    findOpeningVersion(ticket, releases));
            if (value.isPresent()) {
                sum += value.getAsDouble();
                count++;
            }
        }
        return count == 0 ? OptionalDouble.empty() : OptionalDouble.of(sum / count);
    }

    /** Formula di un singolo ticket, esposta anche per test e report diagnostici. */
    public OptionalDouble calculateSingleProportion(
            Optional<Release> injectedVersion,
            Optional<Release> fixedVersion,
            Optional<Release> openingVersion) {
        if (injectedVersion.isEmpty() || fixedVersion.isEmpty() || openingVersion.isEmpty()) {
            return OptionalDouble.empty();
        }
        int iv = injectedVersion.get().index();
        int fv = fixedVersion.get().index();
        int ov = openingVersion.get().index();
        int denominator = fv - ov;
        /*
         * P non e' limitata a 1: quando il bug e' stato introdotto prima
         * dell'apertura del ticket, FV-IV e' maggiore di FV-OV e P e' > 1.
         * L'unico vincolo necessario per il labeling e' IV < FV.
         */
        if (denominator <= 0 || iv >= fv) return OptionalDouble.empty();
        double proportion = (double) (fv - iv) / denominator;
        return proportion > 0.0 ? OptionalDouble.of(proportion) : OptionalDouble.empty();
    }

    /**
     * Stima IV = FV - P_total * (FV - OV). La stima viene arrotondata alla
     * release Jira piu' vicina e mantenuta strettamente precedente a FV.
     */
    public Optional<Release> estimateInjectedVersion(
            Ticket ticket, List<Release> releases, OptionalDouble proportionTotal) {
        if (proportionTotal.isEmpty()) return Optional.empty();
        Optional<Release> fixedVersion = findFixedVersion(ticket, releases);
        Optional<Release> openingVersion = findOpeningVersion(ticket, releases);
        if (fixedVersion.isEmpty() || openingVersion.isEmpty()) return Optional.empty();
        int fv = fixedVersion.get().index();
        int ov = openingVersion.get().index();
        if (fv <= ov) return Optional.empty();
        int estimatedIndex = (int) Math.round(fv - proportionTotal.getAsDouble() * (fv - ov));
        int firstIndex = releases.stream().mapToInt(Release::index).min().orElse(1);
        estimatedIndex = Math.max(firstIndex, Math.min(fv - 1, estimatedIndex));
        final int targetIndex = estimatedIndex;
        return releases.stream().filter(release -> release.index() == targetIndex).findFirst();
    }

    public Optional<Release> findFixedVersion(Ticket ticket, List<Release> releases) {
        return ticket.fixVersions().stream().map(name -> findRelease(name, releases)).flatMap(Optional::stream)
                .min(Comparator.comparingInt(Release::index));
    }

    public Optional<Release> findOpeningVersion(Ticket ticket, List<Release> releases) {
        if (ticket.creationDate() == null) return Optional.empty();
        return releases.stream().filter(release -> !release.releaseDate().isBefore(ticket.creationDate()))
                .min(Comparator.comparing(Release::releaseDate));
    }

    private Optional<Release> findRelease(String name, List<Release> releases) {
        return releases.stream().filter(release -> release.name().equals(name)).findFirst();
    }
}
