package manager.metricsManager;

import com.github.mauricioaniche.ck.CK;
import com.github.mauricioaniche.ck.CKClassResult;
import com.github.mauricioaniche.ck.CKNotifier;
import model.CKClassMetrics;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Calcola le metriche CK delle classi Java presenti nella release.
 */
public final class CKMetrics {

    /**
     * Esegue CK sui file Java indicati.
     *
     * I risultati vengono organizzati usando il percorso del file
     * come chiave, così possono essere associati alle righe del dataset.
     */
    public Map<String, CKClassMetrics> calculate(
            Path repositoryPath,
            List<Path> javaFiles) {

        Map<String, CKClassMetrics> results =
                new HashMap<>();

        List<CKClassResult> ckResults =
                new ArrayList<>();

        CKNotifier notifier = new CKNotifier() {

            @Override
            public void notify(CKClassResult result) {
                ckResults.add(result);
            }

            @Override
            public void notifyError(
                    String sourceFilePath,
                    Exception exception) {

                System.err.println(
                        "Errore CK nel file "
                                + sourceFilePath);

                exception.printStackTrace();
            }
        };

        CK ck = new CK();

        ck.calculate(
                repositoryPath,
                notifier,
                javaFiles.toArray(new Path[0]));

        for (CKClassResult result : ckResults) {

            String filePath =
                    Path.of(result.getFile())
                            .toAbsolutePath()
                            .normalize()
                            .toString();

            /*
             * Se un file contiene più classi, per ora manteniamo
             * il primo risultato prodotto da CK.
             */
            results.putIfAbsent(
                    filePath,
                    toMetrics(result));
        }

        return results;
    }

    private CKClassMetrics toMetrics(
            CKClassResult result) {

        return new CKClassMetrics(
                result.getClassName(),
                result.getFile(),
                result.getLoc(),
                result.getWmc(),
                result.getCbo(),
                result.getRfc(),
                result.getLcom(),
                result.getDit(),
                result.getNoc(),
                result.getFanin(),
                result.getFanout()
        );
    }
}