package manager.releaseManager;

import config.Config;
import model.Release;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.*;

/** Recupera le release da Jira e applica la selezione cronologica richiesta dalla milestone. */
public final class ReleaseManager {
    private List<Release> releases = List.of();

    public List<Release> getReleases() throws IOException, InterruptedException {
        // Crea l'URI per la chiamata HTTP a Jira: /rest/api/2/project/Project_Key
        URI uri = URI.create(Config.JIRA.baseUrl() + "/project/" + Config.PROJECT_KEY);
        // La chiamata HTTP e' sincrona, ma non serve un client persistente: il numero di release e' limitato e la chiamata e' fatta una sola volta.
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri)
                .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("Jira ha risposto HTTP " + response.statusCode());
        JSONArray versions = new JSONObject(response.body()).getJSONArray("versions");
        List<Release> found = new ArrayList<>();
        for (int i = 0; i < versions.length(); i++) {
            JSONObject version = versions.getJSONObject(i);
            String name = version.optString("name", "").trim();
            String date = version.optString("releaseDate", "").trim();
            // Una release senza data non si puo' associare in modo riproducibile alla storia Git.
            if (version.optBoolean("released", false) && !name.isEmpty() && !date.isEmpty())
                found.add(new Release(0, version.optString("id", ""), name, LocalDate.parse(date), version.optBoolean("archived", false)));
        }
        // Le release vengono ordinate per data di rilascio, poi per nome. L'indice parte da 1.
        found.sort(Comparator.comparing(Release::releaseDate).thenComparing(Release::name));
        releases = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) { Release r = found.get(i); releases.add(new Release(i + 1, r.jiraId(), r.name(), r.releaseDate(), r.archived())); }
        return List.copyOf(releases);
    }

    /** La traccia esclude l'ultimo 66%; si conserva quindi il primo 34%, con almeno una release. */
    public List<Release> getTrainingReleases(List<Release> all) {
        int limit = Math.max(1, (int) Math.ceil(all.size() * Config.RELEASES_PERCENTAGE_TO_USE));
        return List.copyOf(all.subList(0, limit));
    }
    public Optional<Release> getPreviousRelease(Release release) { return neighbour(release, -1); }
    public Optional<Release> getNextRelease(Release release) { return neighbour(release, 1); }
    private Optional<Release> neighbour(Release release, int offset) {
        int position = releases.indexOf(release) + offset;
        return position >= 0 && position < releases.size() ? Optional.of(releases.get(position)) : Optional.empty();
    }

}

