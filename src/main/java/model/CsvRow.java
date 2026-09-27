package model;

public record CsvRow(

        String project,
        int releaseIndex,
        String releaseName,
        String classPath,

        // CK
        int loc,
        int wmc,
        int cbo,
        int rfc,
        int lcom,
        int dit,
        int noc,
        int fanin,
        int fanout,

        // JGit
        long commitCount,
        long fixCommitCount,
        long churn,
        double averageChangeSetSize,
        long distinctAuthors,
        long daysSinceLastChange,
        double changeFrequency,
        long changeCountLast90Days,
        double modificationIntervalsStdDev,
        double authorChangeEntropy,

        // PMD
        int nSmells,

        // Label del classificatore
        boolean buggy

) {
    //
    public CsvRow withBuggy(boolean buggy) {

        return new CsvRow(
                project,
                releaseIndex,
                releaseName,
                classPath,

                loc,
                wmc,
                cbo,
                rfc,
                lcom,
                dit,
                noc,
                fanin,
                fanout,

                commitCount,
                fixCommitCount,
                churn,
                averageChangeSetSize,
                distinctAuthors,
                daysSinceLastChange,
                changeFrequency,
                changeCountLast90Days,
                modificationIntervalsStdDev,
                authorChangeEntropy,

                nSmells,

                buggy
        );
    }
}