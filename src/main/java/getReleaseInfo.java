import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;

public class getReleaseInfo {
	public static HashMap<LocalDateTime, String> releaseNames;
	public static HashMap<LocalDateTime, String> releaseID;
	public static ArrayList<LocalDateTime> releases;
	public static Integer numVersions;

	public static void main(String[] args) throws IOException, JSONException {
		String projName ="BOOKKEEPER";
		// Riempie l'ArrayList con le date delle release e le ordina
		// Ignora le release con date mancanti
		releases = new ArrayList<>();
		String url = "https://issues.apache.org/jira/rest/api/2/project/" + projName;
		JSONObject json = readJsonFromUrl(url);
		JSONArray versions = json.getJSONArray("versions");
		releaseNames = new HashMap<>();
		releaseID = new HashMap<>();
		String name = "";
		String id = "";
		for (int i = 0; i < versions.length(); i++ ) {
			if(versions.getJSONObject(i).has("releaseDate")) {
				if (versions.getJSONObject(i).has("name"))
					name = versions.getJSONObject(i).get("name").toString();
				if (versions.getJSONObject(i).has("id"))
					id = versions.getJSONObject(i).get("id").toString();
				addRelease(versions.getJSONObject(i).get("releaseDate").toString(), name, id);
			}
		}
		// Ordina le release per data
		releases.sort(new Comparator<LocalDateTime>() {
            public int compare(LocalDateTime o1, LocalDateTime o2) {
                return o1.compareTo(o2);
            }
        });
		if (releases.size() < 6)
			return;
		FileWriter fileWriter = null;
		try {
			String outName = projName + "VersionInfo.csv"; // Nome del file CSV per l'output
			fileWriter = new FileWriter(outName);
			fileWriter.append("Index,Version ID,Version Name,Date");
			fileWriter.append("\n");
			numVersions = releases.size();
			for (int i = 0; i < releases.size(); i++) {
				int index = i + 1;
				fileWriter.append(Integer.toString(index));
				fileWriter.append(",");
				fileWriter.append(releaseID.get(releases.get(i)));
				fileWriter.append(",");
				fileWriter.append(releaseNames.get(releases.get(i)));
				fileWriter.append(",");
				fileWriter.append(releases.get(i).toString());
				fileWriter.append("\n");
			}
		} catch (Exception e) {
			System.out.println("Errore nella scrittura del CSV");
			e.fillInStackTrace();
		} finally {
			try {
                assert fileWriter != null;
                fileWriter.flush();
				fileWriter.close();
			} catch (IOException e) {
				System.out.println("Errore durante il flushing/chiusura del fileWriter !!!");
				e.fillInStackTrace();
			}
		}
		return;
	}
	// Aggiunge una release all ArrayList
	public static void addRelease(String strDate, String name, String id) {
		LocalDate date = LocalDate.parse(strDate);
		LocalDateTime dateTime = date.atStartOfDay();
		if (!releases.contains(dateTime))
			releases.add(dateTime);
		releaseNames.put(dateTime, name);
		releaseID.put(dateTime, id);
		return;
	}
	// Legge un JSONObject da un URL
	public static JSONObject readJsonFromUrl(String url) throws IOException, JSONException {
        try (InputStream is = new URL(url).openStream()) {
            BufferedReader rd = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String jsonText = readAll(rd);
            JSONObject json = new JSONObject(jsonText);
            return json;
        }
	}
	// Legge tutti i dati da un Reader e li restituisce come stringa
	private static String readAll(Reader rd) throws IOException {
		StringBuilder sb = new StringBuilder();
		int cp;
		while ((cp = rd.read()) != -1) {
			sb.append((char) cp);
		}
		return sb.toString();
	}
}
