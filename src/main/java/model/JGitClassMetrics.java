package model;

/**
 * Metriche storiche Git calcolate per una singola classe.
 */
public record JGitClassMetrics(
        long commitCount,
        long fixCommitCount,
        long churn,
        double averageChangeSetSize,
        long distinctAuthors,
        long daysSinceLastChange,
        double changeFrequency,
        long changeCountLast90Days,
        double modificationIntervalsStdDev,
        double authorChangeEntropy
) {
}