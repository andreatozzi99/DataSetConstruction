package manager.csvManager;

import model.CsvRow;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Gestisce esclusivamente la scrittura del dataset CSV.
 */
public final class CsvManager implements AutoCloseable {

    private final Path path;
    private BufferedWriter writer;

    public CsvManager(Path path) {
        this.path = path;
    }

    /**
     * Crea il file CSV e apre il writer.
     */
    public void createCsv() throws IOException {

        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        writer = Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8);
    }

    /**
     * Scrive l'intestazione del dataset.
     */
    public void writeHeader() throws IOException {

        writer.write(
                "project,"
                        + "release_index,"
                        + "release_name,"
                        + "class_path,"

                        // CK
                        + "loc,"
                        + "wmc,"
                        + "cbo,"
                        + "rfc,"
                        + "lcom,"
                        + "dit,"
                        + "noc,"
                        + "fanin,"
                        + "fanout,"

                        // JGit
                        + "commit_count,"
                        + "fix_commit_count,"
                        + "churn,"
                        + "average_change_set_size,"
                        + "distinct_authors,"
                        + "days_since_last_change,"
                        + "change_frequency,"
                        + "change_count_last_90_days,"
                        + "modification_intervals_std_dev,"
                        + "author_change_entropy,"
                        + "n_smells,"
                        + "buggy\n"
        );
    }

    /**
     * Aggiunge una riga al dataset.
     */
    public void appendRow(CsvRow row) throws IOException {

        writer.write(
                q(row.project()) + ","
                        + row.releaseIndex() + ","
                        + q(row.releaseName()) + ","
                        + q(row.classPath()) + ","

                        // CK
                        + row.loc() + ","
                        + row.wmc() + ","
                        + row.cbo() + ","
                        + row.rfc() + ","
                        + row.lcom() + ","
                        + row.dit() + ","
                        + row.noc() + ","
                        + row.fanin() + ","
                        + row.fanout() + ","

                        // JGit
                        + row.commitCount() + ","
                        + row.fixCommitCount() + ","
                        + row.churn() + ","
                        + row.averageChangeSetSize() + ","
                        + row.distinctAuthors() + ","
                        + row.daysSinceLastChange() + ","
                        + row.changeFrequency() + ","
                        + row.changeCountLast90Days() + ","
                        + row.modificationIntervalsStdDev() + ","
                        + row.authorChangeEntropy() + ","
                        + row.nSmells() + ","
                        + row.buggy()
                        + "\n"
        );
    }

    /**
     * Chiude il writer.
     */
    @Override
    public void close() throws IOException {

        if (writer != null) {
            writer.close();
            writer = null;
        }
    }

    /**
     * Escape di un campo CSV.
     */
    private static String q(String value) {

        if (value == null) {
            return "\"\"";
        }

        return "\""
                + value.replace("\"", "\"\"")
                + "\"";
    }
}