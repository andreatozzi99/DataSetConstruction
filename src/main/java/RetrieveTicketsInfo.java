import org.json.JSONArray;
import java.time.LocalDate;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Classe per recuperare gli ID dei ticket di tipo BUG e risolti con un FIX.
 * **/
class RetrieveTicketsInfo {
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
        File file = new File(Config.TICKET_CSV_PATH);
        if (file.exists()) {
            retrieveTicketInfoAndWriteToCSV();
            return;
        }
        // Nome del progetto
        String projName = Config.PROJECT_NAME;
        int j, i = 0;
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
                String key = issues.getJSONObject(i % 1000).get("key").toString();
                // Crea file csv e inserisci su ogni riga l'ID del ticket
                try {
                    FileWriter fileWriter = new FileWriter(Config.TICKET_CSV_PATH, true);
                    fileWriter.append(key);
                    fileWriter.append("\n");
                    fileWriter.flush();
                    fileWriter.close();
                } catch (Exception e) {
                    System.out.println("Errore nella scrittura del CSV");
                    e.fillInStackTrace();
                }
            }
        } while (i < total);
        retrieveTicketInfoAndWriteToCSV();
    }

    // Metodo per recuperare le informazioni dei ticket e scriverle su un file CSV
    public static void retrieveTicketInfoAndWriteToCSV() throws IOException, JSONException {
    String projName = Config.PROJECT_NAME;
    File file = new File(Config.TICKET_INFO_CSV_PATH);
    if (file.exists()) {
        file.delete();
    }
    int j, i = 0;
    int total;
    FileWriter fileWriter = null;
    try {
        fileWriter = new FileWriter(Config.TICKET_INFO_CSV_PATH);
        // Write header
        fileWriter.append("Key,ID,Creation_Date,Resolution_Date,AV(Jira),FV(Jira),FV(Calculated)");
        fileWriter.append("\n");
        do {
            j = i + 1000;
            // ######################## Sono inclusi ticket SENZA fix version ########################
            String url = "https://issues.apache.org/jira/rest/api/2/search?jql=project=%22"
                    + projName + "%22AND%22issueType%22=%22Bug%22AND(%22status%22=%22closed%22OR"
                    + "%22status%22=%22resolved%22)AND%22resolution%22=%22fixed%22&fields=key,id,resolutiondate,versions,created&startAt="
                    + i + "&maxResults=" + j;
            JSONObject json = readJsonFromUrl(url);
            JSONArray issues = json.getJSONArray("issues");
            total = json.getInt("total");
            for (; i < total && i < j; i++) {
                JSONObject issue = issues.getJSONObject(i % 1000);
                String key = issue.get("key").toString();
                String id = issue.get("id").toString();
                JSONObject fields = issue.getJSONObject("fields"); // Get the "fields" object
                String resolutionDate = fields.optString("resolutiondate", ""); // Access "resolutiondate" from "fields"
                String createdDate = fields.optString("created"); // Access "created" from "fields"
                JSONArray versions = fields.optJSONArray("versions"); // Access "versions" from "fields"

                // Write ticket information to a CSV file
                fileWriter.append(key).append(",");
                fileWriter.append(id).append(",").append(createdDate);
                fileWriter.append(",").append(resolutionDate);
                fileWriter.append(",");

                // Check if versions is null before accessing its length
                int versionsLength = (versions != null) ? versions.length() : 0;
                // Append all versions to a single string
                StringBuilder versionsString = new StringBuilder();
                for (int v = 0; v < versionsLength; v++) {
                    JSONObject version = versions.getJSONObject(v);
                    boolean released = version.optBoolean("released", false);
                    if (released) {
                        String versionName = version.optString("name", "");
                        versionsString.append(versionName);
                        if (v < versionsLength - 1) {
                            versionsString.append("/");
                        }
                    }
                }
                // Scrivo Affected Version
                fileWriter.append(versionsString.toString());
                fileWriter.append(",");

                // Recupera la Fix version più datata dalla rest API
                String selfUrl = issue.get("self").toString();
                String jiraFixVersion = RetrieveFixVersion.retrieveFixVersionFromUrl(selfUrl);
                fileWriter.append(jiraFixVersion).append(",");

                // Calcola la Fix version più vicina alla data di risoluzione
                LocalDate resolutionLocalDate = LocalDate.parse(resolutionDate.split("T")[0]);
                String fixVersion = RetrieveFixVersion.retrieveFixVersionFromDate(resolutionLocalDate);
                fileWriter.append(fixVersion);
                fileWriter.append("\n");
            }
        } while (i < total);
    } catch (Exception e) {
        System.out.println("Errore nella scrittura del CSV");
        e.printStackTrace();
    } finally {
        try {
            if (fileWriter != null) {
                fileWriter.flush();
                fileWriter.close();
            }
        } catch (IOException e) {
            System.out.println("Errore durante il flushing/chiusura del fileWriter !!!");
            e.printStackTrace();
        }
    }
    }
}