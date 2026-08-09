import config.Config;
import manager.checkoutManager.CheckoutManager;
import manager.commitManager.CommitManager;
import manager.csvManager.CsvManager;
import manager.javaClassScannerManager.JavaClassScanner;
import manager.metricsManager.CKMetrics;
import manager.metricsManager.ClassMetricsCollector;
import manager.releaseManager.ReleaseManager;
import model.CsvRow;
import model.Release;

import java.util.List;

/**
 * Costruisce il dataset iniziale a partire dalle release selezionate.
 */
public final class DatasetBuilder {

    public static void main(String[] args) throws Exception {

        ReleaseManager releaseManager =
                new ReleaseManager();

        List<Release> trainingReleases =
                releaseManager.getTrainingReleases(
                        releaseManager.getReleases());

        JavaClassScanner scanner =
                new JavaClassScanner();

        CheckoutManager checkoutManager =
                new CheckoutManager();

        CKMetrics ckMetrics =
                new CKMetrics();
        CommitManager commitManager =
                new CommitManager();

        ClassMetricsCollector metrics =
                new ClassMetricsCollector(
                        checkoutManager,
                        scanner,
                        ckMetrics,
                        commitManager);

        try (CsvManager csv =
                     new CsvManager(Config.DATASET_CSV)) {

            csv.createCsv();
            csv.writeHeader();

            for (Release release : trainingReleases) {

                List<CsvRow> rows =
                        metrics.collect(release);

                for (CsvRow row : rows) {
                    csv.appendRow(row);
                }

                System.out.println(
                        "Elaborata release "
                                + release.name()
                                + " - classi: "
                                + rows.size());
            }
        }
    }
}