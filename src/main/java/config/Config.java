package config;

import java.nio.file.Path;

/** Punto unico in cui cambiare progetto, cartelle e URL della pipeline. */
public final class Config {
    public static final String PROJECT_KEY = "STORM";
    public static final URIHolder JIRA = new URIHolder("https://issues.apache.org/jira/rest/api/2");
    public static final Path REPOSITORY = Path.of("C:", "Users", "andre", "Desktop", "ISW2", "storm");
    public static final Path OUTPUT = Path.of("output", "storm");
    public static final Path RELEASES_CSV = OUTPUT.resolve("storm_releases.csv");
    public static final Path TICKETS_CSV = OUTPUT.resolve("storm_tickets.csv");
    public static final Path DATASET_CSV = OUTPUT.resolve("storm_dataset.csv");
    public static final Path TICKET_ANALYSIS_CSV = OUTPUT.resolve("ticket_preliminary_analysis.csv");
    public static final Path TICKET_ANALYSIS_REPORT = OUTPUT.resolve("ticket_preliminary_report.txt");
    public static final boolean INCLUDE_TESTS = false;
    public static final float RELEASES_PERCENTAGE_TO_USE = 0.34f;

    /**
     * Definisce come salvare il nome della classe nel dataset.
     */
    public enum ClassNameFormat {
        REPOSITORY_RELATIVE, // es. storm/src/main/java/org/apache/storm/StormSubmitter.java
        PACKAGE_RELATIVE // es. org/apache/storm/StormSubmitter.java
    }
    public static final ClassNameFormat CLASS_NAME_FORMAT =
            ClassNameFormat.REPOSITORY_RELATIVE; // Cambiare qui per cambiare il formato del nome della classe nel dataset.
    private Config() { }

    /** Evita stringhe URL sparse nel codice, ma non nasconde il valore usato. */
    public record URIHolder(String baseUrl) { }
}
