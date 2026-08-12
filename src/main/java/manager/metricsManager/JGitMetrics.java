package manager.metricsManager;

import model.ClassChanges;
import model.JGitClassMetrics;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Calcola le metriche storiche Git a partire dai ClassChanges
 * già estratti dal CommitManager.
 *
 * Questa classe non interagisce direttamente con Git.
 */
public final class JGitMetrics {

    /**
     * Finestra temporale utilizzata per la metrica
     * "numero di modifiche recenti".
     */
    private static final long RECENT_DAYS = 90;

    /**
     * Calcola tutte le metriche Git per una classe.
     *
     * @param changes modifiche storiche della classe
     * @param releaseDate data della release
     * @return metriche Git della classe
     */
    public JGitClassMetrics calculate(
            List<ClassChanges> changes,
            LocalDate releaseDate) {

        if (changes == null || changes.isEmpty()) {
            return emptyMetrics();
        }

        long commitCount =
                calculateCommitCount(changes);

        long fixCommitCount =
                calculateFixCommitCount(changes);

        long churn =
                calculateChurn(changes);

        double averageChangeSetSize =
                calculateAverageChangeSetSize(changes);

        long distinctAuthors =
                calculateDistinctAuthors(changes);

        long daysSinceLastChange =
                calculateDaysSinceLastChange(
                        changes,
                        releaseDate);

        double changeFrequency =
                calculateChangeFrequency(
                        changes,
                        releaseDate);

        long changeCountLast90Days =
                calculateChangeCountLast90Days(
                        changes,
                        releaseDate);

        double modificationIntervalsStdDev =
                calculateModificationIntervalsStdDev(changes);

        double authorChangeEntropy =
                calculateAuthorChangeEntropy(changes);

        return new JGitClassMetrics(
                commitCount,
                fixCommitCount,
                churn,
                averageChangeSetSize,
                distinctAuthors,
                daysSinceLastChange,
                changeFrequency,
                changeCountLast90Days,
                modificationIntervalsStdDev,
                authorChangeEntropy
        );
    }

    /**
     * 1. Numero di commit distinti che hanno modificato la classe.
     */
    private long calculateCommitCount(
            List<ClassChanges> changes) {

        return changes.stream()
                .map(ClassChanges::commitId)
                .distinct()
                .count();
    }

    /**
     * 2. Numero di commit distinti classificati come fix.
     */
    private long calculateFixCommitCount(
            List<ClassChanges> changes) {

        return changes.stream()
                .filter(ClassChanges::fix)
                .map(ClassChanges::commitId)
                .distinct()
                .count();
    }

    /**
     * 5. Churn = linee aggiunte + linee cancellate.
     */
    private long calculateChurn(
            List<ClassChanges> changes) {

        return changes.stream()
                .mapToLong(change ->
                        change.linesAdded()
                                + change.linesDeleted())
                .sum();
    }

    /**
     * 6. Dimensione media del changeset.
     *
     * Il changeset rappresenta il numero complessivo di file
     * modificati dal commit.
     */
    private double calculateAverageChangeSetSize(
            List<ClassChanges> changes) {

        return changes.stream()
                .mapToInt(ClassChanges::changeSetSize)
                .average()
                .orElse(0.0);
    }

    /**
     * 7. Numero di autori distinti che hanno modificato la classe.
     */
    private long calculateDistinctAuthors(
            List<ClassChanges> changes) {

        return changes.stream()
                .map(ClassChanges::author)
                .filter(author ->
                        author != null
                                && !author.isBlank())
                .distinct()
                .count();
    }

    /**
     * 10. Tempo trascorso dall'ultima modifica
     * della classe fino alla release.
     *
     * Il risultato è espresso in giorni.
     */
    private long calculateDaysSinceLastChange(
            List<ClassChanges> changes,
            LocalDate releaseDate) {

        LocalDateTime lastChange =
                changes.stream()
                        .map(ClassChanges::date)
                        .max(LocalDateTime::compareTo)
                        .orElse(releaseDate.atStartOfDay());

        LocalDateTime releaseDateTime =
                releaseDate.atStartOfDay();

        long days =
                Duration.between(
                                lastChange,
                                releaseDateTime)
                        .toDays();

        return Math.max(0, days);
    }

    /**
     * 11. Frequenza di modifica.
     *
     * Definizione:
     *
     * numero di commit / giorni di vita osservati della classe.
     */
    private double calculateChangeFrequency(
            List<ClassChanges> changes,
            LocalDate releaseDate) {

        LocalDateTime firstChange =
                changes.stream()
                        .map(ClassChanges::date)
                        .min(LocalDateTime::compareTo)
                        .orElse(releaseDate.atStartOfDay());

        LocalDateTime releaseDateTime =
                releaseDate.atStartOfDay();

        long ageInDays =
                Duration.between(
                                firstChange,
                                releaseDateTime)
                        .toDays();

        /*
         * Evitiamo divisioni per zero per classi
         * introdotte nella stessa giornata della release.
         */
        ageInDays = Math.max(1, ageInDays);

        long commitCount =
                calculateCommitCount(changes);

        return (double) commitCount / ageInDays;
    }

    /**
     * 13. Numero di commit distinti che hanno modificato
     * la classe negli ultimi 90 giorni prima della release.
     */
    private long calculateChangeCountLast90Days(
            List<ClassChanges> changes,
            LocalDate releaseDate) {

        LocalDateTime releaseDateTime =
                releaseDate.atStartOfDay();

        LocalDateTime threshold =
                releaseDateTime.minusDays(RECENT_DAYS);

        return changes.stream()
                .filter(change ->
                        !change.date().isBefore(threshold)
                                && !change.date().isAfter(releaseDateTime))
                .map(ClassChanges::commitId)
                .distinct()
                .count();
    }

    /**
     * 18. Deviazione standard degli intervalli temporali
     * tra modifiche successive.
     *
     * Le date vengono ordinate cronologicamente.
     */
    private double calculateModificationIntervalsStdDev(
            List<ClassChanges> changes) {

        List<LocalDateTime> dates =
                changes.stream()
                        .map(ClassChanges::date)
                        .distinct()
                        .sorted()
                        .toList();

        /*
         * Con meno di due date non esiste alcun intervallo.
         */
        if (dates.size() < 2) {
            return 0.0;
        }

        List<Long> intervals =
                new ArrayList<>();

        for (int i = 1; i < dates.size(); i++) {

            long days =
                    Duration.between(
                                    dates.get(i - 1),
                                    dates.get(i))
                            .toDays();

            intervals.add(days);
        }

        double mean =
                intervals.stream()
                        .mapToLong(Long::longValue)
                        .average()
                        .orElse(0.0);

        double variance =
                intervals.stream()
                        .mapToDouble(interval -> {

                            double difference =
                                    interval - mean;

                            return difference * difference;
                        })
                        .average()
                        .orElse(0.0);

        return Math.sqrt(variance);
    }

    /**
     * 20. Entropia di Shannon della distribuzione
     * delle modifiche tra gli autori.
     *
     * H = -sum(p_i * log2(p_i))
     *
     * Un valore basso indica che le modifiche sono concentrate
     * su pochi autori.
     *
     * Un valore alto indica una distribuzione più uniforme
     * delle modifiche tra gli autori.
     */
    private double calculateAuthorChangeEntropy(
            List<ClassChanges> changes) {

        Map<String, Long> authorCounts =
                changes.stream()
                        .map(ClassChanges::author)
                        .filter(author ->
                                author != null
                                        && !author.isBlank())
                        .collect(Collectors.groupingBy(
                                author -> author,
                                Collectors.counting()
                        ));

        long totalChanges =
                authorCounts.values()
                        .stream()
                        .mapToLong(Long::longValue)
                        .sum();

        if (totalChanges == 0) {
            return 0.0;
        }

        return authorCounts.values()
                .stream()
                .mapToDouble(count -> {

                    double probability =
                            (double) count / totalChanges;

                    return -probability
                            * (Math.log(probability)
                            / Math.log(2));
                })
                .sum();
    }

    /**
     * Restituisce un oggetto con tutte le metriche
     * impostate ai valori neutri.
     */
    private JGitClassMetrics emptyMetrics() {

        return new JGitClassMetrics(
                0,       // commitCount
                0,       // fixCommitCount
                0,       // churn
                0.0,     // averageChangeSetSize
                0,       // distinctAuthors
                0,       // daysSinceLastChange
                0.0,     // changeFrequency
                0,       // changeCountLast90Days
                0.0,     // modificationIntervalsStdDev
                0.0      // authorChangeEntropy
        );
    }
}