package labeling;

import model.Release;
import model.Ticket;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Calcola la Proportion utilizzando i ticket per i quali
 * sono note sia Injected Version sia Fixed Version.
 * -
 * La proportion media viene poi utilizzata per stimare
 * la Injected Version dei ticket che non possiedono
 * informazioni sufficienti.
 */
public final class ProportionCalculator {

    /**
     * Calcola la proportion media sui ticket per cui
     * disponiamo già di una Injected Version.
     * -
     * Per ogni ticket:
     * -
     * P = (FV - IV) / (FV - OV)
     * -
     * dove:
     * - IV = indice della Injected Version;
     * - FV = indice della Fixed Version;
     * - OV = indice della Opening Version.
     */
    public double calculateProportion(
            List<Ticket> tickets,
            List<Release> releases,
            Map<String, Release> injectedVersions) {

        double total =
                0.0;

        int validTickets =
                0;

        for (Ticket ticket : tickets) {

            Release injectedVersion =
                    injectedVersions.get(
                            ticket.key());

            if (injectedVersion == null) {
                continue;
            }

            Optional<Release> fixedVersion =
                    findFixedVersion(
                            ticket,
                            releases);

            Optional<Release> openingVersion =
                    findOpeningVersion(
                            ticket,
                            releases);

            if (fixedVersion.isEmpty()
                    || openingVersion.isEmpty()) {

                continue;
            }

            int iv =
                    injectedVersion.index();

            int fv =
                    fixedVersion.get().index();

            int ov =
                    openingVersion.get().index();

            int denominator =
                    fv - ov;

            /*
             * Il ticket non è utilizzabile per il calcolo
             * se le versioni non formano un intervallo valido.
             */
            if (denominator <= 0
                    || iv > fv) {

                continue;
            }

            double proportion =
                    (double) (fv - iv)
                            / denominator;

            /*
             * Una proportion valida deve appartenere
             * all'intervallo [0, 1].
             */
            if (proportion < 0.0
                    || proportion > 1.0) {

                continue;
            }

            total += proportion;
            validTickets++;
        }

        if (validTickets == 0) {
            return 0.0;
        }

        return total / validTickets;
    }

    /**
     * Stima la Injected Version utilizzando la proportion
     * calcolata sui ticket completi.
     * -
     * IV = FV - P * (FV - OV)
     */
    public Optional<Release> estimateInjectedVersion(
            Ticket ticket,
            List<Release> releases,
            double proportion) {

        Optional<Release> fixedVersion =
                findFixedVersion(
                        ticket,
                        releases);

        Optional<Release> openingVersion =
                findOpeningVersion(
                        ticket,
                        releases);

        if (fixedVersion.isEmpty()
                || openingVersion.isEmpty()) {

            return Optional.empty();
        }

        int fv =
                fixedVersion.get().index();

        int ov =
                openingVersion.get().index();

        if (fv <= ov) {
            return Optional.empty();
        }

        double estimated =
                fv
                        - proportion
                        * (fv - ov);

        int estimatedIndex =
                (int) Math.round(
                        estimated);

        /*
         * La IV stimata non può essere precedente
         * alla prima release né successiva alla FV.
         */
        estimatedIndex =
                Math.max(
                        releases.get(0).index(),
                        estimatedIndex);

        estimatedIndex =
                Math.min(
                        fv,
                        estimatedIndex);

        int finalEstimatedIndex =
                estimatedIndex;

        return releases
                .stream()
                .filter(release ->
                        release.index()
                                == finalEstimatedIndex)
                .findFirst();
    }

    /**
     * Trova la prima Fixed Version del ticket
     * presente tra le release Jira conosciute.
     */
    public Optional<Release> findFixedVersion(
            Ticket ticket,
            List<Release> releases) {

        return ticket
                .fixVersions()
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
     * Determina la Opening Version usando la data
     * di creazione del ticket.
     * -
     * Viene scelta la prima release pubblicata
     * successivamente alla creazione del ticket.
     */
    private Optional<Release> findOpeningVersion(
            Ticket ticket,
            List<Release> releases) {

        if (ticket.creationDate() == null) {
            return Optional.empty();
        }

        return releases
                .stream()
                .filter(release ->
                        !release.releaseDate()
                                .isBefore(
                                        ticket.creationDate()))
                .min(
                        Comparator.comparing(
                                Release::releaseDate));
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

        return releases
                .stream()
                .filter(release ->
                        release.name()
                                .equals(version))
                .findFirst();
    }
}