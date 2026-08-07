package model;

/** Riga provvisoria del dataset: le metriche storiche e gli smell verranno aggiunti nelle fasi successive. */
public record CsvRow(String project, int releaseIndex, String releaseName, String classPath, long loc,
                     boolean buggy) { }
