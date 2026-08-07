package manager.metricsManager;

import config.Config;
import manager.checkoutManager.CheckoutManager;
import model.CsvRow;
import model.Release;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/** Estrae le classi Java e le LOC della fotografia Git corrispondente a una release. */
public final class ClassMetricsCollector {
    private final CheckoutManager checkoutManager;
    public ClassMetricsCollector(CheckoutManager checkoutManager) { this.checkoutManager = checkoutManager; }

    /** Il checkout resta confinato in un try/finally, cosi' il clone viene sempre ripristinato. */
    public List<CsvRow> collect(Release release) throws IOException {
        checkoutManager.checkoutRelease(release);
        try (var files = Files.walk(checkoutManager.getWorkingDirectory().toPath())) {
            List<CsvRow> rows = new ArrayList<>();
            files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> Config.INCLUDE_TESTS || !isTestFile(p)).forEach(p -> rows.add(toRow(release, p)));
            return rows;
        } finally { checkoutManager.cleanRepository(); }
    }
    private static boolean isTestFile(Path path) { String p=path.toString().replace('\\','/'); return p.contains("/src/test/"); }
    private static CsvRow toRow(Release release, Path file) {
        try {
            String relative = Config.REPOSITORY.relativize(file).toString().replace('\\','/');
            long loc = Files.readAllLines(file, StandardCharsets.UTF_8).size();
            return new CsvRow(Config.PROJECT_KEY, release.index(), release.name(), relative, loc, false);
        } catch (IOException exception) { throw new IllegalStateException("Impossibile leggere " + file, exception); }
    }
}
