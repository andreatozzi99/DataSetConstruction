import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class RetrieveFixVersion {
    private static final String CSV_FILE_PATH = "BOOKKEEPER_Version_Info.csv";
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static String retrieveFixVersionFromDate(LocalDate date) throws IOException {
        List<VersionInfo> versions = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(CSV_FILE_PATH))) {
            String line;
            reader.readLine(); // skip header
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                String versionName = parts[2];
                LocalDate releaseDate = LocalDate.parse(parts[3].split("T")[0], formatter);
                versions.add(new VersionInfo(versionName, releaseDate));
            }
        }

        for (VersionInfo version : versions) {
            if (version.releaseDate.isAfter(date)) {
                return version.versionName;
            }
        }

        return null; // or some default value
    }
    // Metodo per recuperare la Fix Version specificata (se più di una è specificata, prende la più datata) nel Ticket nella rest API
    public static String retrieveFixVersionFromUrl(String url) throws IOException, JSONException {
        JSONObject json = RetrieveTicketsInfo.readJsonFromUrl(url);
        JSONArray fixVersions = json.getJSONObject("fields").optJSONArray("fixVersions");

        int fixVersionsLength = (fixVersions != null) ? fixVersions.length() : 0;

        String earliestFixVersion = null;
        for (int v = 0; v < fixVersionsLength; v++) {
            JSONObject fixVersion = fixVersions.getJSONObject(v);
            String fixVersionName = fixVersion.optString("name", "");
            if (earliestFixVersion == null || fixVersionName.compareTo(earliestFixVersion) < 0) {
                earliestFixVersion = fixVersionName;
            }
        }

        return earliestFixVersion;
    }
    static class VersionInfo {
        String versionName;
        LocalDate releaseDate;

        public VersionInfo(String versionName, LocalDate releaseDate) {
            this.versionName = versionName;
            this.releaseDate = releaseDate;
        }
    }
}
