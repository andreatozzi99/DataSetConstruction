package model;

import java.time.LocalDateTime;

public record ClassChanges(
        String classPath,
        String commitId,
        String author,
        LocalDateTime date,
        int linesAdded,
        int linesDeleted,
        int changeSetSize,
        boolean fix
) {
}
