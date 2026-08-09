package model;

/**
 * Metriche calcolate da CK per una classe Java.
 */
public record CKClassMetrics(
        String className,
        String filePath,
        int loc,
        int wmc,
        int cbo,
        int rfc,
        int lcom,
        int dit,
        int noc,
        int fanin,
        int fanout
) {
}