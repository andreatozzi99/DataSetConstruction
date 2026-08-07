package model;

import java.time.LocalDate;

/** Una release Jira ordinabile cronologicamente e collegabile al relativo tag Git. */
public record Release(int index, String jiraId, String name, LocalDate releaseDate, boolean archived) { }
