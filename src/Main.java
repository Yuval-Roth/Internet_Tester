
import javax.sound.sampled.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {

    public static final String DEFAULT_TIMEOUT = "4000";
    public static final String DEFAULT_DISCONNECT_PING_COUNT = "1";
    public static final String DEFAULT_CONNECT_PING_COUNT = "1";
    public static final String DEFAULT_MASTER_GAIN = "-24.0";
    public static final String DEFAULT_CONFIG_FILE = """
            # READ ME:
            # Editing this config will take up to 60 seconds to take effect
            # There is no need to restart the program after changing these values
            
            # Deleting lines in this file will make the program use the default values for these settings
            # Deleting the config file will regenerate a new one
            
            # lines that start with '#' are ignored
            
            # timeout in milliseconds
            timeout: %s
            
            # The number of times it will make a ping sound when connecting / disconnecting
            # setting these values to 0 will disable ping sounds
            disconnect_ping_count: %s
            connect_ping_count: %s
            
            # volume of the pings (dB scale)
            master_gain: %s
            """.formatted(
            DEFAULT_TIMEOUT,
            DEFAULT_DISCONNECT_PING_COUNT,
            DEFAULT_CONNECT_PING_COUNT,
            DEFAULT_MASTER_GAIN);
    public static final long SLEEP_TIME_BETWEEN_CONNECTION_CHECKS = 100;
    public static final long ONE_MINUTE = 1000 * 60;
    public static final long SLEEP_TIME_BETWEEN_ANIMATION_UPDATES = 250;
    public static final long SLEEP_TIME_BETWEEN_PINGS = 1000;
    private static String[] addresses;
    private static boolean[] addressStatus;
    private static char symbol = '|';
    private static Clip clip;
    private static Map<String, String> config;
    private static Integer timeout;
    private static Integer disconnect_ping_count;
    private static float master_gain;
    private static int connect_ping_count;
    private static Thread[] workerThreads;
    private static Object timeOfDisconnectionLock;
    private static AtomicInteger disconnectedCounter;
    private static boolean connected;
    private static String lastMsg;
    private static LocalDateTime timeOfDisconnection;
    private static boolean running;

    public static void main(String[] args)  {

        while(true){
            try{
                // ==== initialize basic fields =====
                addresses = args;
                workerThreads = new Thread[addresses.length];
                addressStatus = new boolean[addresses.length];
                Arrays.fill(addressStatus,true);
                clip = AudioSystem.getClip();
                disconnectedCounter = new AtomicInteger(0);
                connected = true;
                lastMsg = "";
                timeOfDisconnectionLock = new Object();
                // ==================================

                readConfig();
                initWorkerThreads();

                // print start message
                String timestamp = getTimestamp(LocalDateTime.now());
                String message = "\n[%s] Started logging connection to %s with timeout of %s\n".formatted(timestamp, getAddressesStamp() ,timeout);
                log(message);

                // start
                for(Thread t : workerThreads) t.start();
                mainLoop();

            } catch (Exception e){
                String timestamp = getTimestamp(LocalDateTime.now());
                running = false;
                for(Thread t:workerThreads) {
                    try {
                        t.join();
                    } catch (InterruptedException ignored) {}
                }
                String message = timestamp+": "+ e +"\n";
                try(BufferedWriter writer = getWriter("error-log")){
                    writer.write(message);
                } catch (IOException ignored) {}
                System.out.println(message);
                e.printStackTrace();
                System.out.println("Restarting...\n\n\n\n");
            }
        }
    }

    private static void log(String message) throws IOException {
        System.out.print(message);
        try (BufferedWriter writer = getLogWriter()) {
            writer.write(message);
        }
    }

    private static void mainLoop() throws IOException {
        long nextConnectionCheckTime = System.currentTimeMillis();
        long nextAnimationTime = System.currentTimeMillis();
        long nextReadConfigTime = System.currentTimeMillis() + ONE_MINUTE;
        long nextWakeUp;
        while(true){

            // connection check
            boolean statusChanged = false;
            if(System.currentTimeMillis() >= nextConnectionCheckTime){
                statusChanged = checkConnectionStatus();
                nextConnectionCheckTime = System.currentTimeMillis() + SLEEP_TIME_BETWEEN_CONNECTION_CHECKS;
            }

            // monitoring animation
            if(System.currentTimeMillis() >= nextAnimationTime || statusChanged){
                animateMonitoring();
                nextAnimationTime = System.currentTimeMillis() + SLEEP_TIME_BETWEEN_ANIMATION_UPDATES;
            }

            // check for changes in the config file
            if(System.currentTimeMillis() >= nextReadConfigTime){
                readConfig();
                nextReadConfigTime = System.currentTimeMillis() + ONE_MINUTE;
            }

            nextWakeUp = Math.min(nextAnimationTime,nextConnectionCheckTime);
            try{
                Thread.sleep(Math.max(nextWakeUp - System.currentTimeMillis(),0));
            } catch(InterruptedException ignored){}
        }
    }

    private static void initWorkerThreads() {
        for(int i = 0; i < addresses.length; i++){
            // create the ping command as a list of strings
            List<String> commands = new ArrayList<String>();
            commands.add("ping");
            commands.add(addresses[i]);
            commands.add("-w");
            commands.add((timeout)+"");
            commands.add("-n");
            commands.add("1");
            int threadIndex = i;
            workerThreads[i] = new Thread(() -> workerThreadMainLoop(commands, threadIndex));
        }
    }

    private static void workerThreadMainLoop(List<String> commands, int threadIndex) {
        while(running){
            try{
                LocalDateTime now = LocalDateTime.now();
                if (!doCommand(commands)){

                    synchronized (timeOfDisconnectionLock){
                        if(timeOfDisconnection == null || timeOfDisconnection.isAfter(now)){
                            timeOfDisconnection = now;
                        }
                    }

                    int expectedValue;
                    int newValue;

                    addressStatus[threadIndex] = false;
                    do {
                        expectedValue = disconnectedCounter.get();
                        newValue = expectedValue+1;
                    } while(! disconnectedCounter.compareAndSet(expectedValue,newValue));

                    // wait for connection to return
                    List<String> waitingCommands = new ArrayList<>(commands);
                    waitingCommands.set(3,"500");
                    while(running && !doCommand(waitingCommands)){
                        try{
                            Thread.sleep(SLEEP_TIME_BETWEEN_CONNECTION_CHECKS);
                        } catch (InterruptedException ignored) {}
                    }

                    addressStatus[threadIndex] = true;
                    do {
                        expectedValue = disconnectedCounter.get();
                        newValue = expectedValue-1;
                    } while(! disconnectedCounter.compareAndSet(expectedValue,newValue));
                }
            } catch (IOException e){
                throw new RuntimeException(e);
            }
            try {
                Thread.sleep(SLEEP_TIME_BETWEEN_PINGS);
            } catch (InterruptedException ignored) {}
        }
    }


    private static String getAddressesStamp() {
        StringBuilder builder = new StringBuilder();
        for (String address : addresses) {
            builder.append("\"").append(address).append("\"").append(",");
        }
        builder.deleteCharAt(builder.length()-1);
        return builder.toString();
    }

    private static void readConfig() throws IOException {

        config = new HashMap<>();

        try (BufferedReader configFile = new BufferedReader(new FileReader(getFolderPath()+"config.txt"))) {
            String line;
            while ((line = configFile.readLine()) != null){
                if(line.contains(":") && line.charAt(0) != '#'){
                    String key = line.substring(0,line.indexOf(":")).strip().toLowerCase();
                    String value = line.substring((line.indexOf(":")+1)).strip().toLowerCase();
                    config.put(key,value);
                }
            }
        } catch (FileNotFoundException e){
            try(BufferedWriter writer = getWriter("config.txt")){
                writer.write(DEFAULT_CONFIG_FILE);
            };
            readConfig();
        }
        initGlobalVariables();
    }

    private static void initGlobalVariables() {
        timeout = Integer.parseInt(config.getOrDefault("timeout", DEFAULT_TIMEOUT));
        disconnect_ping_count = Integer.parseInt(config.getOrDefault("disconnect_ping_count", DEFAULT_DISCONNECT_PING_COUNT));
        connect_ping_count = Integer.parseInt(config.getOrDefault("connect_ping_count", DEFAULT_CONNECT_PING_COUNT));
        master_gain = Float.parseFloat(config.getOrDefault("master_gain", DEFAULT_MASTER_GAIN));
    }

    private static boolean checkConnectionStatus() throws IOException {

        // catch illegal value of counter and multi-threading issues
        if(disconnectedCounter.get() < 0 || disconnectedCounter.get() > addresses.length){
            throw new IllegalStateException("disconnected counter illegal count: "+disconnectedCounter.get());
        }

        if (disconnectedCounter.get() == addresses.length) {
            if (connected) {

                // log the disconnection
                String timeStamp = getTimestamp(timeOfDisconnection);
                clearLine();
                String message = "[%s] Lost connection\n".formatted(timeStamp);
                log(message);

                if (disconnect_ping_count > 0) playAudio("disconnect_ping.wav", disconnect_ping_count);
                connected = false;
                return true; // signal that connection status changed
            }
        } else {
            if(! connected){

                // get time of reconnection
                LocalDateTime now = LocalDateTime.now();
                String timestamp = getTimestamp(now);
                String timeDiff = getTimeDiff(timeOfDisconnection,now);

                // log the reconnection
                clearLine();
                String message = "[%s] Found connection after %s\n".formatted(timestamp,timeDiff);
                log(message);

                if(connect_ping_count > 0) playAudio("connect_ping.wav",connect_ping_count);
                connected = true;
                synchronized (timeOfDisconnectionLock){
                    timeOfDisconnection = null;
                }
                return true; // signal that connection status changed
            }
        }

        // this code fixes disconnection time skews from addresses that haven't responded in a while.
        // if an address doesn't respond, it will mark the time of disconnection as the time
        // that the address stopped responding, which could be a long time ago.
        // if that time passes the timeout period plus one second but the other connections are fine
        // it's safe to assume that it is a problem with the address and not with the connection.
        // So we reset the time of disconnection.
        if(timeOfDisconnection != null){
            if(connected) {
                synchronized (timeOfDisconnectionLock){
                    if(timeOfDisconnection.isBefore(LocalDateTime.now().minusSeconds(timeout*1000 + 1000))){
                        timeOfDisconnection = null;
                    }
                }
            }
        }

        return false; // signal that connection status has not changed
    }

    private static String getTimeDiff(LocalDateTime from,LocalDateTime to) {
        Duration diff = Duration.between(from, to);
        long hours = diff.toHours();
        int minutes = diff.toMinutesPart();
        int seconds = diff.toSecondsPart();

        StringBuilder output = new StringBuilder();
        if(hours > 0){
            output.append(hours).append(" hours");
            if(minutes > 0 || seconds > 0) {
                output.append(", ");
            }
        }
        if(minutes > 0){
            output.append(minutes).append(" minutes");
            if(seconds > 0) {
                output.append(", ");
            }
        }
        if(seconds > 0){
            output.append(seconds).append(" seconds");
        }

        return output.toString();
    }

    private static void clearLine() {
        System.out.print(lastMsg.replaceAll("."," "));
        System.out.print('\r');
    }

    private static BufferedWriter getLogWriter() throws IOException {
        String stamp = getAddressesStamp().replace("\"","").replace(","," - ");
        return getWriter("internet log - " + stamp + ".txt");
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
        String msg = "\rMonitoring [ "+ symbol+" ]";
        for (int i = 0; i< addresses.length;i++){
            msg += " - "+addresses[i]+" [ %s ]".formatted(addressStatus[i] ? "OK" : "XX");
        }
        System.out.print(msg);
        lastMsg = msg;
        symbol = getNextSymbol(symbol);
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
