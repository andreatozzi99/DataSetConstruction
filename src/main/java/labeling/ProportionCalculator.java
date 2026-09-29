package labeling;

import model.Release;
import model.Ticket;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calcola la Proportion Total usata quando un ticket non dichiara una versione
 * di introduzione del difetto. La consegna assume che la prima Affected
 * Version Jira valida costituisca la IV nota da usare come base empirica.
 */
public final class ProportionCalculator {

    /** Motivo esplicito con cui una FV Jira non puo' essere usata nel corpus. */
    public enum FixedVersionStatus {
        RESOLVED,
        NO_FV,
        FV_UNRESOLVED,
        FV_AFTER_CORPUS
    }

    /** Risoluzione della FV: separa una versione ignota da una chiaramente futura. */
    public record FixedVersionResolution(FixedVersionStatus status, Optional<Release> release) { }

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
        return resolveFixedVersion(ticket, releases).release();
    }

    /**
     * Non sostituisce mai una FV Jira sconosciuta con l'ultima release.
     * Una FV e' "after corpus" soltanto se ha una forma numerica valida ed e'
     * semanticamente maggiore della massima release Jira disponibile; versioni
     * come 1.x, 1.0.7 o 1.1.4 restano semplicemente non risolte.
     */
    public FixedVersionResolution resolveFixedVersion(Ticket ticket, List<Release> releases) {
        if (ticket.fixVersions().isEmpty()) {
            return new FixedVersionResolution(FixedVersionStatus.NO_FV, Optional.empty());
        }
        Optional<Release> resolved = ticket.fixVersions().stream()
                .map(name -> findRelease(name, releases)).flatMap(Optional::stream)
                .min(Comparator.comparingInt(Release::index));
        if (resolved.isPresent()) {
            return new FixedVersionResolution(FixedVersionStatus.RESOLVED, resolved);
        }
        boolean afterCorpus = releases.stream().max(Comparator.comparingInt(Release::index))
                .map(last -> ticket.fixVersions().stream().anyMatch(version -> isClearlyAfter(version, last.name())))
                .orElse(false);
        return new FixedVersionResolution(afterCorpus ? FixedVersionStatus.FV_AFTER_CORPUS : FixedVersionStatus.FV_UNRESOLVED,
                Optional.empty());
    }

    public Optional<Release> findOpeningVersion(Ticket ticket, List<Release> releases) {
        if (ticket.creationDate() == null) return Optional.empty();
        return releases.stream().filter(release -> !release.releaseDate().isBefore(ticket.creationDate()))
                .min(Comparator.comparing(Release::releaseDate));
    }

    private Optional<Release> findRelease(String name, List<Release> releases) {
        return releases.stream().filter(release -> release.name().equals(name)).findFirst();
    }

    private static boolean isClearlyAfter(String candidate, String corpusLastRelease) {
        Optional<int[]> candidateVersion = numericVersion(candidate);
        Optional<int[]> lastVersion = numericVersion(corpusLastRelease);
        if (candidateVersion.isEmpty() || lastVersion.isEmpty()) return false;
        for (int index = 0; index < 3; index++) {
            int comparison = Integer.compare(candidateVersion.get()[index], lastVersion.get()[index]);
            if (comparison != 0) return comparison > 0;
        }
        return false;
    }

    private static Optional<int[]> numericVersion(String value) {
        if (value == null) return Optional.empty();
        Matcher matcher = Pattern.compile("^v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?$").matcher(value.trim());
        if (!matcher.matches()) return Optional.empty();
        return Optional.of(new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3))});
    }
}
