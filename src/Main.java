import javax.sound.sampled.*;
import java.io.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {

    public static final String DEFAULT_CONFIG_FILE = """
            timeout: 4000
            master_gain: -24.0
            disconnect_ping_count: 1
            connect_ping_count: 1
            """;
    static String address;
    private static char symbol = '|';
    private static Clip clip;
    private static Thread animator = new Thread(Main::animateMonitoring);
    private static AtomicBoolean shouldAnimate;
    private static Map<String, String> config;
    private static Integer timeout;
    private static Integer disconnect_ping_count;
    private static float master_gain;
    private static int connect_ping_count;

    public static void main(String[] args) throws IOException {

        address = args[0];

        //read from config or default
        config = readConfig();
        timeout = config.get("timeout") == null ? 4000 : Integer.parseInt(config.get("timeout"));
        disconnect_ping_count = config.get("disconnect_ping_count") == null ? 1 : Integer.parseInt(config.get("disconnect_ping_count"));
        connect_ping_count = config.get("connect_ping_count") == null ? 1 : Integer.parseInt(config.get("connect_ping_count"));
        master_gain = config.get("master_gain") == null ? -24.0f : Float.parseFloat(config.get("master_gain"));

        // print start message
        LocalDateTime now = LocalDateTime.now();
        String timestamp = getTimestamp(now);
        String message = "\n[%s] Started logging connection to \"%s\" with timeout of %s\n".formatted(timestamp, args[0],timeout);
        System.out.print(message);
        try (BufferedWriter writer = getLogWriter()) {
            writer.write(message);
        }

        // create the ping command as a list of strings
        List<String> commands = new ArrayList<String>();
        commands.add("ping");
        commands.add(address);
        commands.add("-w");
        commands.add(timeout.toString());
        commands.add("-n");
        commands.add("1");

        try {
            clip = AudioSystem.getClip();
        } catch (LineUnavailableException e) {
            throw new RuntimeException(e);
        }

        // start
        shouldAnimate = new AtomicBoolean(true);
        animator.start();
        while(true){
            testConnection(commands);
            try{
              Thread.sleep(1000);
            } catch(InterruptedException ignored){}
        }
    }

    private static Map<String,String> readConfig() throws IOException {

        Map<String,String> config = new HashMap<>();

        try (BufferedReader configFile = new BufferedReader(new FileReader(getFolderPath()+"config.txt"))) {
            String line;
            while ((line = configFile.readLine()) != null && line.contains(":")){
                String key = line.substring(0,line.indexOf(":")).strip().toLowerCase();
                String value = line.substring((line.indexOf(":")+1)).strip().toLowerCase();
                config.put(key,value);
            }
        } catch (FileNotFoundException e){
            try(BufferedWriter writer = getWriter("config.txt")){
                writer.write(DEFAULT_CONFIG_FILE);
            };
            return readConfig();
        }
        return config;
    }

    private static void testConnection(List<String> commands) throws IOException {

        LocalDateTime now = LocalDateTime.now();
        String timestamp = getTimestamp(now);
        if (!doCommand(commands)){

            //log the disconnection
            stopAnimation();
            String message = "[%s] Lost connection\n".formatted(timestamp);
            System.out.print(message);
            try (BufferedWriter writer = getLogWriter()) {
                writer.write(message);
            }
            startAnimation();

            if(disconnect_ping_count > 0) playAudio("disconnect_ping.wav",disconnect_ping_count);

            // wait for connection to return

            List<String> waitingCommands = new ArrayList<>(commands);
            waitingCommands.set(3,"500");
            while(!doCommand(waitingCommands)){}

            //log the reconnection
            stopAnimation();
            now = LocalDateTime.now();
            timestamp = getTimestamp(now);
            message = "[%s] Found connection\n".formatted(timestamp);
            System.out.print(message);
            try (BufferedWriter writer = getLogWriter()) {
                writer.write(message);
            }
            startAnimation();

            if(connect_ping_count > 0) playAudio("connect_ping.wav",connect_ping_count);
        }
    }

    private static void startAnimation() {
        shouldAnimate.set(true);
        animator.start();
    }

    private static void stopAnimation() {
        try {
            shouldAnimate.set(false);
            animator.join();
        } catch (InterruptedException ignored) {}
    }

    private static BufferedWriter getLogWriter() throws IOException {
        return getWriter("internet log - " + address + ".txt");
    }

    private static BufferedWriter getWriter(String fileName) throws IOException {
        String folderPath = getFolderPath();
        return new BufferedWriter(new FileWriter(folderPath+fileName, true));
    }

    private static String getFolderPath() {
        String folderPath = Main.class.getResource("Main.class").getPath();
        folderPath = folderPath.replace("%20"," "); //fix space character
        folderPath = folderPath.substring(folderPath.indexOf("/")+1); // remove initial '/'
        folderPath = folderPath.substring(0,folderPath.lastIndexOf("/")); // remove .class file from path
        folderPath = folderPath.substring(0,folderPath.lastIndexOf("/")+1); // exit jar
        folderPath = folderPath.replace("/","\\");
        return folderPath;
    }

    private static String getTimestamp(LocalDateTime time) {
        return fixNumber(time.getDayOfMonth()) + "/" + fixNumber(time.getMonthValue()) + "/" + fixNumber(time.getYear()) + " "
                + fixNumber(time.getHour()) + ":" + fixNumber(time.getMinute()) + ":" + fixNumber(time.getSecond());
    }

    private static boolean doCommand(List<String> command)
            throws IOException
    {
        String s;
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();
        BufferedReader stdInput = new BufferedReader(new InputStreamReader(process.getInputStream()));
        while ((s = stdInput.readLine()) != null) {
            if (notConnected(s)) return false;
        }
        return true;
    }

    private static boolean notConnected(String s) {
        s = s.toLowerCase();
        String[] keyWords = {
                "100% loss",
                "could not find host",
                "request timed out",
                "destination host unreachable"
        };

        for(String keyword : keyWords){
            if(s.contains(keyword)) return true;
        }
        return false;
    }

    private static char getNextSymbol(char c){
        return switch(c){
            case '|' -> '/';
            case '/' -> '-';
            case '-' -> '\\';
            case '\\' -> '|';
            default -> throw new RuntimeException("Illegal Symbol");
        };
    }

    private static void animateMonitoring() {
        while(shouldAnimate.get()){
            System.out.print("\rMonitoring [ "+ symbol+" ]");
            symbol = getNextSymbol(symbol);
            try{
                Thread.sleep(250);
            } catch(InterruptedException ignored){}
        }
        System.out.print("\r");
        animator = new Thread(Main::animateMonitoring);
    }

    private static void playAudio(String fileName,int count){
        if(clip.isOpen())
            clip.close();

        try {
            InputStream in = Main.class.getResourceAsStream(fileName);
            BufferedInputStream buffIn = new BufferedInputStream(in);
            clip.open(AudioSystem.getAudioInputStream(buffIn));
            FloatControl control = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            control.setValue(master_gain);
        } catch (LineUnavailableException | IOException | UnsupportedAudioFileException e) {
            throw new RuntimeException(e);
        }
        clip.loop(count-1);
    }

    private static String fixNumber(int num){
        return num < 10 ? "0"+num : ""+num;
    }
}
