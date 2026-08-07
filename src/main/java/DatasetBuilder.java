import config.Config;
import manager.checkoutManager.CheckoutManager;
import manager.csvManager.CsvManager;
import manager.metricsManager.ClassMetricsCollector;
import manager.releaseManager.ReleaseManager;
import model.CsvRow;
import model.Release;
import java.util.List;

/** Orchestratore della prima estrazione: release selezionate, classi e CSV iniziale. */
public final class DatasetBuilder {
    public static void main(String[] args) throws Exception {
        ReleaseManager releaseManager = new ReleaseManager();
        List<Release> trainingReleases = releaseManager.getTrainingReleases(releaseManager.getReleases());
        ClassMetricsCollector metrics = new ClassMetricsCollector(new CheckoutManager());
        try (CsvManager csv = new CsvManager(Config.DATASET_CSV)) {
            csv.createCsv(); csv.writeHeader();
            for (Release release : trainingReleases) {
                for (CsvRow row : metrics.collect(release)) csv.appendRow(row);
                System.out.println("Elaborata release " + release.name());
            }
        }
    }
}