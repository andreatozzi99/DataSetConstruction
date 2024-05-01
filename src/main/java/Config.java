public class Config {
    // --------------- Variano tra progetti  -------------------
    public static final String PROJECT_NAME = "BOOKKEEPER";
    public static final String REPOSITORY_PATH = "C:/Users/andre/Desktop/bookkeeper";
    public static final String TAG_FORMAT = "release-"; // Potrei prenderlo nel codice senza doverlo specificare

    // --------------- CSV (stesso formato per diversi progetti) -------------------
    public static final String DATA_SET_CSV_PATH = "./"+PROJECT_NAME+"_Data_Set.csv";
    public static final String VERSION_CSV_PATH = "./"+PROJECT_NAME+"_Version_Info.csv";
    public static final String TICKET_CSV_PATH = "./"+PROJECT_NAME+"_TicketsID.csv";
    public static final String TICKET_INFO_CSV_PATH = "./"+PROJECT_NAME+"_Tickets_Info.csv";
    public static final boolean INCLUDE_TEST_CLASSES = true;
    public static final String DATA_SET_CSV_HEADER = "Version,Class_Name,LOC";

}