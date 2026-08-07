package model;

import java.time.LocalDate;
import java.util.List;

/** Dati minimi di un ticket Jira usati per stabilire le release affette. */
public record Ticket(String key, String id, String type, String status, String resolution,
                     LocalDate creationDate, LocalDate resolutionDate, List<String> affectedVersions,
                     List<String> fixVersions) { }
