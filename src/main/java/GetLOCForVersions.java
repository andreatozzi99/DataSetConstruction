import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class GetLOCForVersions {
    public static void main(String[] args) throws IOException, GitAPIException {
        String repositoryPath = Config.REPOSITORY_PATH;
        String csvFilePath = Config.VERSION_CSV_PATH;
        File file = new File(Config.DATA_SET_CSV_PATH);
        if (file.exists()){
            file.delete();
        }
        List<VersionInfo> versions = readVersionsFromCSV(csvFilePath);

        if (!versions.isEmpty()) {
            int versionIndex = 1;
            for (VersionInfo version : versions) {
                System.out.println("Check-Out Version: " + version.getVersionName());
                if (!checkoutVersion(repositoryPath, version.getVersionName())) {
                    System.out.println("Errore durante il checkout della versione: " + version.getVersionName());
                    versionIndex++;
                    continue;
                }
                getLOCForVersionAndWriteToCSV(repositoryPath, String.valueOf(versionIndex));
                versionIndex++;
            }
        } else {
            System.out.println("Nessuna versione trovata nel file CSV.");
        }
    }

    private static List<VersionInfo> readVersionsFromCSV(String csvFilePath) throws IOException {
        System.out.println("Version Found:");
        List<VersionInfo> versions = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(csvFilePath))) {
            String line;
            boolean headerSkipped = false;
            while ((line = br.readLine()) != null) {
                if (!headerSkipped) {
                    headerSkipped = true;
                    continue;
                }
                String[] parts = line.split(",");
                if (parts.length >= 3) {
                    String versionName = parts[2];
                    System.out.println((versionName));
                    versions.add(new VersionInfo(versionName));
                }
            }
        }
        return versions;
    }

    private static boolean checkoutVersion(String repositoryPath, String versionName) {
        try (Repository repository = new FileRepositoryBuilder().setGitDir(new File(repositoryPath + "/.git")).build();
             Git git = new Git(repository)) {
            git.checkout().setName(Config.TAG_FORMAT+versionName).call();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static void getLOCForVersionAndWriteToCSV(String repositoryPath, String versionIndex) throws IOException {
        List<LOCData> locDataList = new ArrayList<>();
        AtomicInteger classCount = new AtomicInteger();

        File repoDir = new File(repositoryPath);
        if (!repoDir.exists() || !repoDir.isDirectory()) {
            System.out.println("Il percorso del repository non esiste o non è una directory.");
            return;
        }

        Files.walk(Paths.get(repositoryPath))
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        String content = new String(Files.readAllBytes(path));
                        int loc = content.split("\n").length;
                        String className = path.getFileName().toString();
                        locDataList.add(new LOCData(className, loc));
                        classCount.getAndIncrement();
                        if (classCount.get() % 100 == 0) {
                            writeLOCDataToCSV(versionIndex, locDataList);
                            locDataList.clear();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });

        if (!locDataList.isEmpty()) {
            writeLOCDataToCSV(versionIndex, locDataList);
        }
    }

    private static void writeLOCDataToCSV(String versionIndex, List<LOCData> locDataList) throws IOException {
        String csvFilePath = Config.DATA_SET_CSV_PATH;
        try (FileWriter writer = new FileWriter(csvFilePath, true)) {
            if (new File(csvFilePath).length() == 0) {
                writer.append(Config.DATA_SET_CSV_HEADER +"\n");
            }
            for (LOCData locData : locDataList) {
                writer.append(versionIndex).append(",").append(locData.getFileName()).append(",").append(String.valueOf(locData.getLoc())).append("\n");
            }
        }
    }

    private static class VersionInfo {
        private final String versionName;

        public VersionInfo(String versionName) {
            this.versionName = versionName;
        }

        public String getVersionName() {
            return versionName;
        }
    }

    private static class LOCData {
        private final String fileName;
        private final int loc;

        public LOCData(String fileName, int loc) {
            this.fileName = fileName;
            this.loc = loc;
        }

        public String getFileName() {
            return fileName;
        }

        public int getLoc() {
            return loc;
        }
    }
}
