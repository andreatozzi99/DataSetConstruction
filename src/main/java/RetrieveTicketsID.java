import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;

// Classe per recuperare gli ID dei ticket di tipo BUG
class RetrieveTicketsID {
    // Metodo privato per leggere tutti i dati da un Reader e restituirli come stringa
    private static String readAll(Reader rd) throws IOException {
        StringBuilder sb = new StringBuilder();
        int cp;
        while ((cp = rd.read()) != -1) {
            sb.append((char) cp);
        }
        return sb.toString();
    }
    // Metodo per leggere un JSONArray da un URL
    public static JSONArray readJsonArrayFromUrl(String url) throws IOException, JSONException {
        try (InputStream is = new URL(url).openStream()) {
            BufferedReader rd = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String jsonText = readAll(rd);
            return new JSONArray(jsonText);
        }
    }
    // Metodo per leggere un JSONObject da un URL
    public static JSONObject readJsonFromUrl(String url) throws IOException, JSONException {
        try (InputStream is = new URL(url).openStream()) {
            BufferedReader rd = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String jsonText = readAll(rd);
            return new JSONObject(jsonText);
        }
    }

    public static void main(String[] args) throws IOException, JSONException {
        // Nome del progetto
        String projName ="BOOKKEEPER";
        Integer j, i = 0;
        // Ottieni dati JSON per i bug chiusi con AV nel progetto
        int total;
        do {
            // Ottieni solo un massimo di 1000 alla volta, quindi devi farlo più volte se ci sono più di 1000 bug
            j = i + 1000;
            String url = "https://issues.apache.org/jira/rest/api/2/search?jql=project=%22"
                    + projName + "%22AND%22issueType%22=%22Bug%22AND(%22status%22=%22closed%22OR"
                    + "%22status%22=%22resolved%22)AND%22resolution%22=%22fixed%22&fields=key,resolutiondate,versions,created&startAt="
                    + i + "&maxResults=" + j;
            JSONObject json = readJsonFromUrl(url);
            JSONArray issues = json.getJSONArray("issues");
            total = json.getInt("total");
            // Itera su ogni bug
            for (; i < total && i < j; i++) {
                String key = issues.getJSONObject(i%1000).get("key").toString();
                System.out.println(key);
                // ------ Salvataggio su file csv --------

            }
        } while (i < total);
    }
}
