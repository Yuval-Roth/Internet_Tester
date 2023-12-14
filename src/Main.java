
import javax.sound.sampled.*;
import java.io.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {

    public static final String DEFAULT_CONFIG_FILE = """
            timeout: 4000
            master_gain: -24.0
            disconnect_ping_count: 1
            connect_ping_count: 1
            """;
    static String[] addresses;
    private static char symbol = '|';
    private static Clip clip;
    private static Thread animator = new Thread(Main::animateMonitoring);
    private static AtomicBoolean shouldAnimate;
    private static AtomicBoolean animating;
    private static Map<String, String> config;
    private static Integer timeout;
    private static Integer disconnect_ping_count;
    private static float master_gain;
    private static int connect_ping_count;
    private static Thread[] workerThreads;
    private static LocalDateTime[] disconnection_time;
    private static AtomicInteger counter;
    private static AtomicBoolean running;

    private static final char CHECK_MARK = '\u2714';
    private static final char X_MARK = '\u274c';

    public static void main(String[] args) throws IOException {

        while(true){
            try{
                // ==== initialize basic fields =====
                workerThreads = new Thread[args.length];
                disconnection_time = new LocalDateTime[args.length];
                addresses = args;
                try {
                    clip = AudioSystem.getClip();
                } catch (LineUnavailableException e) {
                    throw new RuntimeException(e);
                }
                shouldAnimate = new AtomicBoolean(true);
                counter = new AtomicInteger(0);
                running = new AtomicBoolean(true);
                animating = new AtomicBoolean(false);
                // ==================================

                readConfig();
                initGlobalVariables();
                initWorkerThreads();

                // print start message
                String timestamp = getTimestamp(LocalDateTime.now());
                String message = "\n[%s] Started logging connection to %s with timeout of %s\n".formatted(timestamp, getAddressesStamp() ,timeout);
                System.out.print(message);
                try (BufferedWriter writer = getLogWriter()) {
                    writer.write(message);
                }

                // start
                animator.start();
                for(Thread t : workerThreads) t.start();
                mainLoop();

            } catch (Exception e){
                String timestamp = getTimestamp(LocalDateTime.now());
                String message = timestamp+": "+ e +"\n";
                try(BufferedWriter writer = getWriter("error-log")){
                    writer.write(message);
                }
                System.out.println(message);
                e.printStackTrace();
                System.out.println("Restarting...\n\n\n\n");
            }
        }
    }

    private static void mainLoop() {
        while(true){
            try {
                checkIfDisconnected();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            try{
                Thread.sleep(100);
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
            workerThreads[i] = new Thread(()->{
                workerThreadMainLoop(commands, threadIndex);
            });
        }
    }

    private static void workerThreadMainLoop(List<String> commands, int threadIndex) {
        while(running.get()){
            try{
                LocalDateTime now = LocalDateTime.now();
                if (!doCommand(commands)){

                    disconnection_time[threadIndex] = now;
                    int expected;
                    int _new;
                    do {
                        expected = counter.get();
                        _new = expected+1;
                    } while(! counter.compareAndSet(expected,_new));

                    // wait for connection to return
                    List<String> waitingCommands = new ArrayList<>(commands);
                    waitingCommands.set(3,"500");
                    while(!doCommand(commands)){
                        try{
                            Thread.sleep(100);
                        } catch (InterruptedException ignored) {}
                    }
                    do {
                        expected = counter.get();
                        _new = expected-1;
                    } while(! counter.compareAndSet(expected,_new));
                    synchronized (disconnection_time[threadIndex]){
                        disconnection_time[threadIndex] = null;
                    }
                }
            } catch (IOException e){
                throw new RuntimeException(e);
            }
            try {
                Thread.sleep(1000);
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
            while ((line = configFile.readLine()) != null && line.contains(":")){
                String key = line.substring(0,line.indexOf(":")).strip().toLowerCase();
                String value = line.substring((line.indexOf(":")+1)).strip().toLowerCase();
                config.put(key,value);
            }
        } catch (FileNotFoundException e){
            try(BufferedWriter writer = getWriter("config.txt")){
                writer.write(DEFAULT_CONFIG_FILE);
            };
            readConfig();
        }
    }

    private static void initGlobalVariables() {
        timeout = config.get("timeout") == null ? 4000 : Integer.parseInt(config.get("timeout"));
        disconnect_ping_count = config.get("disconnect_ping_count") == null ? 1 : Integer.parseInt(config.get("disconnect_ping_count"));
        connect_ping_count = config.get("connect_ping_count") == null ? 1 : Integer.parseInt(config.get("connect_ping_count"));
        master_gain = config.get("master_gain") == null ? -24.0f : Float.parseFloat(config.get("master_gain"));
    }

    private static void checkIfDisconnected() throws IOException {

        if (counter.get() == addresses.length){

            LocalDateTime first;
            synchronized (disconnection_time){

                // for the small chance that one of the threads resets before
                // the main thread can log the disconnection
                for(LocalDateTime t : disconnection_time) if(t == null) return;

                first = disconnection_time[0];
                for(int i = 1; i < addresses.length;i++){
                    if(disconnection_time[i].isBefore(first)){
                        first = disconnection_time[i];
                    }
                }
            }

            String timestamp = getTimestamp(first);
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
            while(counter.get() == addresses.length){
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {}
            }

            //log the reconnection
            stopAnimation();
            LocalDateTime now = LocalDateTime.now();
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
        animator.interrupt();
    }

    private static void stopAnimation() {
        shouldAnimate.set(false);
        while(animating.get()){
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {}
        }
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
        String msg = "";
        Object o = new Object();
        while(running.get()){
            animating.set(true);
            while(shouldAnimate.get()){
                msg = "\rMonitoring [ "+ symbol+" ]";
                for (int i = 0; i< addresses.length;i++){
                    msg += " - "+addresses[i]+" [ %s ]".formatted(disconnection_time[i] == null ? "OK" : "XX");
                }
                System.out.print(msg);
                symbol = getNextSymbol(symbol);
                try{
                    Thread.sleep(250);
                } catch(InterruptedException ignored){}
            }
            System.out.print(msg.replaceAll("."," "));
            System.out.print('\r');
            try {
                animating.set(false);
                synchronized (o){
                    o.wait();
                }
            } catch (InterruptedException ignored) {}
        }
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
