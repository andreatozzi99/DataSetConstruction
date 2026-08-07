package manager.csvManager;

import model.CsvRow;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Scrive il dataset in modo sequenziale: una sola apertura del file evita righe interrotte. */
public final class CsvManager implements AutoCloseable {
    private final Path path; private BufferedWriter writer;
    public CsvManager(Path path) { this.path=path; }
    public void createCsv() throws IOException { Files.createDirectories(path.getParent()); writer=Files.newBufferedWriter(path,StandardCharsets.UTF_8); }
    public void writeHeader() throws IOException { writer.write("project,release_index,release_name,class_path,loc,buggy\n"); }
    public void appendRow(CsvRow row) throws IOException { writer.write(q(row.project())+","+row.releaseIndex()+","+q(row.releaseName())+","+q(row.classPath())+","+row.loc()+","+row.buggy()+"\n"); }
    public void close() throws IOException { if(writer!=null) writer.close(); }
    private static String q(String v){return '"'+v.replace("\"","\"\"")+'"';}
}
