
import javax.sound.sampled.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings({"SynchronizeOnNonFinalField", "BooleanMethodIsAlwaysInverted", "BusyWait"})
public class Main {

    // < CONSTANTS >
    private static final long SLEEP_TIME_BETWEEN_CONNECTION_CHECKS = 250;
    private static final long ONE_MINUTE = 1000 * 60;
    private static final long SLEEP_TIME_BETWEEN_ANIMATION_UPDATES = 250;
    // < CONSTANTS />

    // < DEFAULTS >
    public static final String DEFAULT_TIMEOUT = "4000";
    public static final String DEFAULT_DISCONNECT_PING_COUNT = "1";
    public static final String DEFAULT_CONNECT_PING_COUNT = "1";
    public static final String DEFAULT_MASTER_GAIN = "-24.0";
    public static final String DEFAULT_ENABLE_DEBUG_LOG = "false";
    public static final String DEFAULT_LONG_RESPONSE_THRESHOLD = "1000";
    public static final String DEFAULT_CONFIG_FILE = """
            # READ ME:
            # Editing this config will take up to 60 seconds to take effect
            # There is no need to restart the program after changing these values
            
            # Deleting lines in this file will make the program use the default values for these settings
            # Deleting the config file will regenerate a new one
            
            # Lines that start with '#' are ignored
            
            # Timeout in milliseconds
            timeout: %s
            
            # The number of times it will make a ping sound when connecting / disconnecting
            # Setting these values to 0 will disable ping sounds
            disconnect_ping_count: %s
            connect_ping_count: %s
            
            # Volume of the pings (dB scale)
            master_gain: %s
            
            # Enable debug logging or not
            # Warning: The debug log can get big after a long time
            enable_debug_log: %s
            
            # The threshold in milliseconds to alert of an unusually long response time
            # Setting this value to 0 will disable the alert
            long_response_threshold: %s
            """.formatted(
            DEFAULT_TIMEOUT,
            DEFAULT_DISCONNECT_PING_COUNT,
            DEFAULT_CONNECT_PING_COUNT,
            DEFAULT_MASTER_GAIN,
            DEFAULT_ENABLE_DEBUG_LOG,
            DEFAULT_LONG_RESPONSE_THRESHOLD);
    // < DEFAULTS />

    // < GLOBAL VARIABLES >
    private static Map<String, String> config;
    private static Integer timeout;
    private static Integer disconnect_ping_count;
    private static int connect_ping_count;
    private static float master_gain;
    private static boolean enable_debug_log;
    private static int long_response_threshold;
    // < GLOBAL VARIABLES />

    // < THREADS RELATED >
    private static Thread[] workerThreads;
    private static PingEndPoint[] pingEndPoints;
    private static Exception[] workerThreadExceptions;
    private static boolean[] addressStatus;
    private volatile static boolean running;
    // < THREADS RELATED />

    // < LOCKS >
    private static Object timeOfDisconnectionLock;
    private static Object debugLogLock;
    private static Object errorLogLock;
    private static Object internetLogLock;
    private static Semaphore printQueueLock;
    // < LOCKS />

    // < GENERAL APPLICATION DATA >
    private static String[] addresses;
    private static char symbol = '|';
    private static Clip clip;
    private static AtomicInteger disconnectedCounter;
    private static AtomicBoolean connected;
    private static String lastMsg;
    private static LocalDateTime timeOfDisconnection;
    private static LocalDateTime mainThreadTimeOfDisconnection;
    private static List<String> printQueue;
    private static boolean firstConfigRead;
    // < GENERAL APPLICATION DATA />

    @SuppressWarnings("InfiniteLoopStatement")
    public static void main(String[] args)  {

        // argument check
        if(args.length == 0) {
            System.out.println("Error: no arguments received");
            System.out.println("  see \"instructions.txt\"");
            System.out.println("\n\npress enter to exit");
            Scanner s = new Scanner(System.in);
            s.nextLine();
            return;
        }

        while(true){
            try{
                // ==== initialize basic fields =====
                addresses = args;
                workerThreads = new Thread[addresses.length];
                addressStatus = new boolean[addresses.length];
                pingEndPoints = new PingEndPoint[addresses.length];
                workerThreadExceptions = new Exception[addresses.length];
                Arrays.fill(addressStatus,true);
                clip = AudioSystem.getClip();
                disconnectedCounter = new AtomicInteger(0);
                connected = new AtomicBoolean(true);
                lastMsg = "";
                timeOfDisconnectionLock = new Object();
                debugLogLock = new Object();
                errorLogLock = new Object();
                internetLogLock = new Object();
                printQueue = new LinkedList<>();
                printQueueLock = new Semaphore(1,true);
                firstConfigRead = true;
                // ==================================

                readConfig();
                initWorkerThreads();

                // print start message
                String timestamp = getTimestamp(LocalDateTime.now());
                String message = "\n[%s] Started logging connection to %s with timeout of %s".formatted(timestamp, getAddressesStamp() ,timeout);
                logInternet(message);

                // start
                running = true;
                for(Thread t : workerThreads) t.start();
                mainLoop();

            } catch (Exception e){
                String timestamp = getTimestamp(LocalDateTime.now());
                stopWorkerThreads();
                String errorMessage = "[%s]\n%s".formatted(timestamp,stackTraceToString(e));
                try {
                    logError(errorMessage);
                } catch (IOException ignored) {}
                System.out.println();
                System.out.println("\n"+errorMessage);
                System.out.println("Restarting...\n\n\n\n");
            }
        }
    }


    //========================================================================== |
    //============================ MAIN LOOPS ================================== |
    //========================================================================== |
    @SuppressWarnings("InfiniteLoopStatement")
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

            checkForExceptionInWorkerThreads();

            nextWakeUp = Math.min(nextAnimationTime,nextConnectionCheckTime);
            try{
                Thread.sleep(Math.max(nextWakeUp - System.currentTimeMillis(),0));
            } catch(InterruptedException ignored){}
        }
    }

    private static void workerThreadMainLoop(int threadIndex) {
        while(running){
            try{
                LocalDateTime now = LocalDateTime.now();
                if (!checkPing(threadIndex)){

                    synchronized (timeOfDisconnectionLock){
                        if(timeOfDisconnection == null || timeOfDisconnection.isAfter(now)){
                            timeOfDisconnection = now;
                        }
                    }

                    if(! checkPing(threadIndex)){
                        addressStatus[threadIndex] = false;
                        addToDisconnectedCounter(1);

                        // wait for connection to return
                        while(running && !checkPing(threadIndex)){
                            try{
                                Thread.sleep(SLEEP_TIME_BETWEEN_CONNECTION_CHECKS);
                            } catch (InterruptedException ignored) {}
                        }

                        addressStatus[threadIndex] = true;
                        addToDisconnectedCounter(-1);
                    } else {
                        logInternet("[%s] %s %s".formatted(getTimestamp(now),
                                addresses[threadIndex],
                                pingEndPoints[threadIndex].getPreviousOutput()));
                    }

                    // reset the time of disconnection if only this thread got disconnected
                    synchronized (timeOfDisconnectionLock){
                        if(connected.get()){
                            if(timeOfDisconnection == now) { // prevent unexpected interactions with other threads
                                timeOfDisconnection = null;
                            }
                        }
                    }
                }
                if(running){
                    try {
                        Thread.sleep(SLEEP_TIME_BETWEEN_CONNECTION_CHECKS);
                    } catch (InterruptedException ignored) {}
                }
            } catch (Exception e){
                LocalDateTime now = LocalDateTime.now();
                try {
                    logError("[%s]\n%s".formatted(getTimestamp(now),e.toString()));
                } catch (IOException ignored) {}
                // Tell the main thread of the exception
                workerThreadExceptions[threadIndex] = e;
                throw new RuntimeException(e);
            }
        }
    }



    //========================================================================== |
    //====================== PROGRAM FLOW FUNCTIONS ============================ |
    //========================================================================== |
    private static boolean checkPing(int threadIndex) throws IOException {

        if(! pingEndPoints[threadIndex].isRunning()){
            pingEndPoints[threadIndex].start();
        }

        LocalDateTime now = LocalDateTime.now();

        // get output
        String output = "";
        do{
            output = pingEndPoints[threadIndex].readOutputLine();
        } while(output.isEmpty());

        long delay = 0;
        if(! notConnected(output)){
            delay = extractDelay(output);
        }
        if(delay == -1) {
            String message = "Error: could not extract delay from output";
            logError("[%s] %s".formatted(getTimestamp(now),message));
        }
        // alert if response time passed the threshold
        if(long_response_threshold > 0
                && connected.get()
                && ! notConnected(output)
                && delay >= long_response_threshold){
            String timeStamp = getTimestamp(now);
            String message = "[%s] %s took %s ms to respond".formatted(timeStamp, addresses[threadIndex], delay);
            logInternet(message);
        }

        if (enable_debug_log){
            String debugMsg = "[%s]\n%s\n".formatted(getTimestamp(now), output);
            logDebug(debugMsg);
        }

        return !notConnected(output);
    }

    private static boolean checkConnectionStatus() throws IOException {

        // catch illegal value of counter and multi-threading issues
        if(disconnectedCounter.get() < 0 || disconnectedCounter.get() > addresses.length){
            throw new IllegalStateException("disconnected counter illegal count: "+disconnectedCounter.get());
        }

        boolean stateChanged = false;

        if (disconnectedCounter.get() == addresses.length) {
            if (connected.get()) {

                // edge case but prevents race conditions and null pointer exceptions
                synchronized (timeOfDisconnectionLock){
                    if(timeOfDisconnection == null){
                        return stateChanged;
                    } else {
                        mainThreadTimeOfDisconnection = timeOfDisconnection;
                    }
                }

                connected.set(false);

                // log the disconnection
                String timeStamp = getTimestamp(mainThreadTimeOfDisconnection);
                String message = "[%s] Lost connection".formatted(timeStamp);
                logInternet(message);

                if (disconnect_ping_count > 0) playAudio("disconnect_ping.wav", disconnect_ping_count);
                stateChanged = true; // signal that connection status changed
            }
        } else {
            if(! connected.get()){

                // get time of reconnection
                LocalDateTime now = LocalDateTime.now();
                String timestamp = getTimestamp(now);
                String timeDiff = getTimeDiff(mainThreadTimeOfDisconnection,now);

                // log the reconnection
                String message = "[%s] Found connection after %s".formatted(timestamp,timeDiff);
                logInternet(message);

                if(connect_ping_count > 0) playAudio("connect_ping.wav",connect_ping_count);
                connected.set(true);
                synchronized (timeOfDisconnectionLock){
                    timeOfDisconnection = null;
                }
                stateChanged = true; // signal that connection status changed
            }
        }

        // this code fixes disconnection time skews from addresses that haven't responded in a while.
        // if an address doesn't respond, it will mark the time of disconnection as the time
        // that the address stopped responding, which could be a long time ago.
        // if a long time passes but the other connections are fine
        // it's safe to assume that it is a problem with the address and not with the connection.
        // So we reset the time of disconnection.
        if(addresses.length > 1){
            synchronized (timeOfDisconnectionLock){
                if(timeOfDisconnection != null){
                    if(connected.get()) {
                        LocalDateTime threshold = LocalDateTime.now().minusSeconds((long) Math.ceil(timeout / 1000.0) * 4 + 2);
                        if(timeOfDisconnection.isBefore(threshold)){
                            timeOfDisconnection = null;
                        }
                    }
                }
            }
        }


        return stateChanged;
    }
    private static void readConfig() throws IOException {

        config = new HashMap<>();

        try (BufferedReader configFile = new BufferedReader(new FileReader(getFolderPath()+"config.txt"))) {
            String line;
            while ((line = configFile.readLine()) != null){
                if(line.contains(":") && line.charAt(0) != '#'){
                    //break the lines into key & value
                    String key = line.substring(0,line.indexOf(":")).strip().toLowerCase();
                    String value = line.substring((line.indexOf(":")+1)).strip().toLowerCase();
                    config.put(key,value);
                }
            }
        } catch (FileNotFoundException e){
            //if a config doesn't exist, generate one and then read it
            try(BufferedWriter writer = getWriter("config.txt")){
                writer.write(DEFAULT_CONFIG_FILE);
            };
            readConfig();
        }
        initGlobalVariables();
    }

    private static void animateMonitoring() {
        checkPrintQueue();
        StringBuilder msg = new StringBuilder("\rMonitoring [ " + symbol + " ]");
        for (int i = 0; i< addresses.length;i++){
            msg.append(" - ").append(addresses[i]).append(" [ %s ]".formatted(addressStatus[i] ? "OK" : "XX"));
        }
        System.out.print(msg);
        lastMsg = msg.toString();
        symbol = getNextSymbol(symbol);
    }

    private static void checkPrintQueue() {
        if(! printQueue.isEmpty()){
            try {
                printQueueLock.acquire();
            } catch (InterruptedException ignored) {}
            StringBuilder toPrint = new StringBuilder();
            while(! printQueue.isEmpty()){
                toPrint.append(printQueue.getFirst()).append("\n");
                printQueue.removeFirst();
            }
            printQueueLock.release();
            clearLine();
            System.out.print(toPrint);
        }
    }

    private static void initGlobalVariables() throws IOException {
        int newTimeout = Integer.parseInt(config.getOrDefault("timeout", DEFAULT_TIMEOUT));
        if(!firstConfigRead && newTimeout != timeout){
            updatePingEndPoints();
            String message = "Timeout changed to "+newTimeout+" milliseconds";
            logInternet("[%s] %s".formatted(getTimestamp(LocalDateTime.now()),message));
        }
        timeout = newTimeout;
        disconnect_ping_count = Integer.parseInt(config.getOrDefault("disconnect_ping_count", DEFAULT_DISCONNECT_PING_COUNT));
        connect_ping_count = Integer.parseInt(config.getOrDefault("connect_ping_count", DEFAULT_CONNECT_PING_COUNT));
        master_gain = Float.parseFloat(config.getOrDefault("master_gain", DEFAULT_MASTER_GAIN));
        enable_debug_log = Boolean.parseBoolean(config.getOrDefault("enable_debug_log",DEFAULT_ENABLE_DEBUG_LOG));
        long_response_threshold = Integer.parseInt(config.getOrDefault("long_response_threshold",DEFAULT_LONG_RESPONSE_THRESHOLD));
        firstConfigRead = false;
    }

    @SuppressWarnings("unchecked")
    private static Pair<String,String>[] getPingParams(){
        return (Pair<String,String>[]) new Pair[]{
                Pair.of("-n","60"),
                Pair.of("-w",String.valueOf(timeout))
        };
    }

    private static void updatePingEndPoints() {
        for(var endpoint : pingEndPoints){
            endpoint.setParams(getPingParams());
        }
    }

    private static void initWorkerThreads() {
        for(int i = 0; i < addresses.length; i++){
            PingEndPoint endPoint = new PingEndPoint(addresses[i], getPingParams());
            int threadIndex = i;
            pingEndPoints[i] = endPoint;
            workerThreads[i] = new Thread(() -> workerThreadMainLoop(threadIndex));
        }
    }

    private static void checkForExceptionInWorkerThreads() {
        for(int i = 0; i < addresses.length ; i++){
            if(workerThreadExceptions[i] != null) {
                throw new RuntimeException("Exception in worker threads");
            }
        }
    }

    private static void stopWorkerThreads() {
        running = false;
        for(Thread t:workerThreads) {
            try {
                t.join();
            } catch (InterruptedException ignored) {}
        }
    }


    //========================================================================== |
    //====================== LOGS RELATED FUNCTIONS ============================ |
    //========================================================================== |

    private static void logInternet(String message) throws IOException {
        synchronized (internetLogLock){
            print(message);
            try (BufferedWriter writer = getLogWriter("internet_log")) {
                writer.write(message+"\n");
            }
        }
    }
    private static void logDebug(String message) throws IOException {
        // this is synchronized because multiple threads can write to the debug log
        synchronized (debugLogLock){
            try (BufferedWriter writer = getLogWriter("debug_log")) {
                writer.write(message+"\n");
            }
        }
    }

    private static void logError(String message) throws IOException {
        // this is synchronized because multiple threads can write to the error log
        synchronized (errorLogLock){
            try (BufferedWriter writer = getLogWriter("error_log")) {
                writer.write(message+"\n");
            }
        }
    }
    private static BufferedWriter getLogWriter(String fileName) throws IOException {
        String stamp = getAddressesStamp().replace("\"", "").replace(",", " - ");
        return getWriter(fileName + " - " + stamp + ".txt");
    }

    private static BufferedWriter getWriter(String fileName) throws IOException {
        String folderPath = getFolderPath();
        return new BufferedWriter(new FileWriter(folderPath+fileName, true));
    }



    //========================================================================== |
    //======================== UTILITY FUNCTIONS =============================== |
    //========================================================================== |
    private static void addToDisconnectedCounter(int num) {
        int expectedValue;
        int newValue;
        do {
            expectedValue = disconnectedCounter.get();
            newValue = expectedValue+num;
        } while(! disconnectedCounter.compareAndSet(expectedValue,newValue));
    }

    private static String getAddressesStamp() {
        StringBuilder builder = new StringBuilder();
        for (String address : addresses) {
            builder.append("\"").append(address).append("\"").append(",");
        }
        builder.deleteCharAt(builder.length()-1);
        return builder.toString();
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

    private static boolean notConnected(String s) {
        s = s.toLowerCase();
        String[] keyWords = {
                "no resources",
                "general failure",
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

    @SuppressWarnings("SuspiciousRegexArgument")
    private static void clearLine() {
        System.out.print(lastMsg.replaceAll("."," "));
        System.out.print('\r');
    }

    @SuppressWarnings("DataFlowIssue")
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
        return fixDualDigitNumber(time.getDayOfMonth()) + "/" + fixDualDigitNumber(time.getMonthValue()) + "/" + fixDualDigitNumber(time.getYear()) + " "
                + fixDualDigitNumber(time.getHour()) + ":" + fixDualDigitNumber(time.getMinute()) + ":" + fixDualDigitNumber(time.getSecond());
    }

    private static void playAudio(String fileName,int count){
        if(clip.isOpen())
            clip.close();

        try {
            InputStream in = Main.class.getResourceAsStream(fileName);
            assert in != null;
            BufferedInputStream buffIn = new BufferedInputStream(in);
            clip.open(AudioSystem.getAudioInputStream(buffIn));
            FloatControl control = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            control.setValue(master_gain);
        } catch (LineUnavailableException | IOException | UnsupportedAudioFileException e) {
            throw new RuntimeException(e);
        }
        clip.loop(count-1);
    }

    private static String fixDualDigitNumber(int num){
        return num < 10 ? "0"+num : ""+num;
    }

    private static String stackTraceToString(Exception e) {
        StringBuilder output  = new StringBuilder();
        output.append(e).append("\n");
        for (var element: e.getStackTrace()) {
            output.append("\t").append(element).append("\n");
        }
        return output.toString();
    }

    private static void print(String message) {
        try {
            printQueueLock.acquire();
        } catch (InterruptedException ignored) {}
        printQueue.add(message);
        printQueueLock.release();
    }

    private static int extractDelay(String output) {
        for(String word : output.split(" ")){
            if(word.contains("time=")){
                return Integer.parseInt(word.substring(5,word.indexOf("ms")));
            }
        }
        return -1;
    }
}
