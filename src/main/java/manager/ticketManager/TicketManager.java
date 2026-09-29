package manager.ticketManager;

import config.Config;
import model.Ticket;
import org.json.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** Recupera i ticket del progetto e applica i filtri in metodi separati e verificabili. */
public final class TicketManager {
    private List<Ticket> tickets = List.of();

    /** Restituisce solo il totale di una query JQL: utile ai report senza scaricare tutte le issue. */
    public int countIssues(String jql) throws IOException, InterruptedException {
        URI uri = URI.create(Config.JIRA.baseUrl() + "/search?jql="
                + URLEncoder.encode(jql, StandardCharsets.UTF_8) + "&maxResults=0");
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("Jira ha risposto HTTP " + response.statusCode());
        return new JSONObject(response.body()).getInt("total");
    }
    public List<Ticket> getTickets() throws IOException, InterruptedException {
        List<Ticket> result = new ArrayList<>();
        int start = 0, total;
        do {
            // Per il dataset interessano solo bug chiusi con una correzione:
            // scaricare ogni issue Storm rende la fase preliminare inutilmente lenta.
            String jql = "project = " + Config.PROJECT_KEY + " AND issuetype = Bug"
                    + " AND status in (Closed, Resolved) AND resolution = Fixed";
            URI uri = URI.create(Config.JIRA.baseUrl() + "/search?jql=" + URLEncoder.encode(jql, StandardCharsets.UTF_8) + "&fields=key,id,issuetype,status,resolution,created,resolutiondate,versions,fixVersions&startAt=" + start + "&maxResults=100");
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IOException("Jira ha risposto HTTP " + response.statusCode());
            JSONObject page = new JSONObject(response.body()); total = page.getInt("total"); JSONArray issues = page.getJSONArray("issues");
            for (int i=0;i<issues.length();i++) result.add(toTicket(issues.getJSONObject(i)));
            start += issues.length();
        } while (start < total);
        tickets = List.copyOf(result); return tickets;
    }
    /** Restituisce Closed e Resolved anche se Jira usa entrambe le denominazioni nella stessa storia. */
    public List<Ticket> getClosedTickets() { return tickets.stream().filter(t -> "Closed".equalsIgnoreCase(t.status()) || "Resolved".equalsIgnoreCase(t.status())).toList(); }
    /** Mantiene soltanto i bug realmente corretti, escludendo task e ticket non risolti. */
    public List<Ticket> getFixedTickets() { return getClosedTickets().stream().filter(t -> "Bug".equalsIgnoreCase(t.type()) && "Fixed".equalsIgnoreCase(t.resolution())).toList(); }
    private static Ticket toTicket(JSONObject issue) {
        JSONObject f=issue.getJSONObject("fields");
        return new Ticket(issue.getString("key"),issue.optString("id",""),name(f.optJSONObject("issuetype")),name(f.optJSONObject("status")),name(f.optJSONObject("resolution")),date(f.optString("created","")),date(f.optString("resolutiondate","")),names(f.optJSONArray("versions")),names(f.optJSONArray("fixVersions")));
    }
    private static LocalDate date(String value) { return value.isBlank()?null:LocalDate.parse(value.substring(0,10)); }
    private static String name(JSONObject value) { return value == null ? "" : value.optString("name", ""); }
    private static List<String> names(JSONArray values) { if(values==null)return List.of(); List<String> r=new ArrayList<>(); for(int i=0;i<values.length();i++)r.add(values.getJSONObject(i).optString("name","")); return List.copyOf(r); }
}
