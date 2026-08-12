package labeling;

import model.Release;
import model.Ticket;
import java.util.*;

/**
 * Per ogni ticket fixed
 trova fixing commit
 trova classi toccate

 se esiste IV da SZZ:
 usa IV

 altrimenti se esiste Affected Version:
 usa la prima AV valida

 altrimenti:
 stima IV con Proportion Total

 trova FV

 per ogni release r tale che:
 IV <= r < FV

 marca buggy=true
 per le classi coinvolte */
public final class BuggyLabeler {
    private final Map<String, Set<String>> classesByRelease = new HashMap<>();

    /**
     * Ogni ticket fornisce le versioni affette e le classi toccate dalla sua
     * correzione. La mappa e' separata dal recupero Jira perche' i file cambiati
     * arrivano dalla storia Git, non dall'API dei ticket.
     */
    public void labelBugs(List<Ticket> tickets, Map<String, Set<String>> changedClasses) {
        classesByRelease.clear();
        for (Ticket ticket : tickets) {
            Set<String> classes = changedClasses.getOrDefault(ticket.key(), Set.of());
            for (String version : ticket.affectedVersions()) {
                classesByRelease.computeIfAbsent(version, ignored -> new HashSet<>()).addAll(classes);
            }
        }
    }

    public boolean isBuggy(Release release, String classPath) {
        return classesByRelease.getOrDefault(release.name(), Set.of()).contains(classPath);
    }

    public Set<String> getBuggyClasses(Release release) {
        return Set.copyOf(classesByRelease.getOrDefault(release.name(), Set.of()));
    }
}
