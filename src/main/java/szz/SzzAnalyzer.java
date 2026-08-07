package szz;

import model.Release;
import org.eclipse.jgit.api.BlameCommand;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Implementazione SZZ di base: esamina le righe rimosse o sostituite da un
 * fixing commit e risale, con blame sul suo genitore, al commit che le ha
 * introdotte. Le sole aggiunte non vengono considerate: non correggono una
 * riga preesistente da attribuire a un bug introducente.
 */
public final class SzzAnalyzer {
    private final Repository repository;
    public SzzAnalyzer(Repository repository) { this.repository = repository; }

    /** Restituisce, per ogni classe Java, i possibili commit introducenti trovati da SZZ. */
    public Map<String, Set<RevCommit>> findInducingCommits(RevCommit fixingCommit) throws Exception {
        Map<String, Set<RevCommit>> result = new LinkedHashMap<>();
        if (fixingCommit.getParentCount() == 0) return result;
        try (RevWalk walk = new RevWalk(repository);
             DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            // Rileggiamo il commit dal repository usato da questo analyzer: gli oggetti
            // ricevuti dal cercatore possono provenire da un RevWalk gia' chiuso.
            RevCommit fix = walk.parseCommit(fixingCommit.getId());
            RevCommit parent = walk.parseCommit(fix.getParent(0));
            formatter.setRepository(repository);
            for (DiffEntry entry : formatter.scan(parent.getTree(), fix.getTree())) {
                String path = entry.getOldPath();
                // Per SZZ leggiamo il file nel commit padre: li' esistono le righe rimosse dalla fix.
                if (!path.endsWith(".java") || path.contains("/src/test/")) continue;
                FileHeader header = formatter.toFileHeader(entry);
                Set<RevCommit> inducing = blameEditedParentLines(parent, path, header.toEditList());
                if (!inducing.isEmpty()) result.put(path, inducing);
            }
        }
        return result;
    }

    /**
     * Ogni edit ha coordinate A (file padre) e B (file dopo la fix). Le linee
     * A dell'edit sono quelle eliminate/sostituite e quindi le candidate da
     * interrogare con blame.
     */
    private Set<RevCommit> blameEditedParentLines(RevCommit parent, String path, List<Edit> edits) throws Exception {
        BlameResult blame = new BlameCommand(repository).setStartCommit(parent).setFilePath(path).call();
        Set<RevCommit> commits = new LinkedHashSet<>();
        if (blame == null) return commits;
        for (Edit edit : edits) {
            for (int line = edit.getBeginA(); line < edit.getEndA(); line++) {
                RevCommit source = blame.getSourceCommit(line);
                if (source != null) commits.add(source);
            }
        }
        return commits;
    }

    /**
     * Converte un commit introducente nella prima release pubblicata dopo il
     * commit. Questa e' l'injected version da usare per l'intervallo [IV, FV).
     */
    public Optional<Release> findInjectedVersion(RevCommit inducingCommit, List<Release> releases) {
        LocalDate commitDate = inducingCommit.getAuthorIdent().getWhen().toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDate();
        return releases.stream().filter(release -> !release.releaseDate().isBefore(commitDate))
                .min(Comparator.comparing(Release::releaseDate));
    }
}
