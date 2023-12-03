import java.io.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {

    static String address;
    private static char symbol = '|';
    private static Thread animator = new Thread(Main::animateMonitoring);
    private static AtomicBoolean shouldAnimate;

    public static void main(String[] args) throws IOException {
        address = args[0];

        // print start message
        LocalDateTime now = LocalDateTime.now();
        String timestamp = getTimestamp(now);
        String message = "\n[%s] Started logging connection to \"%s\"%s\n".formatted(timestamp, args[0], args.length > 1 ? " with timeout of " + args[1] : "");
        System.out.print(message);
        try (BufferedWriter writer = getWriter()) {
            writer.write(message);
        }

        // create the ping command as a list of strings
        List<String> commands = new ArrayList<String>();
        commands.add("ping");
        commands.add(address);
        if(args.length > 1){
            commands.add("-w");
            commands.add(args[1]);
        }
        commands.add("-n");
        commands.add("1");
        commands.add("-4");

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

    private static void testConnection(List<String> commands) throws IOException {

        LocalDateTime now = LocalDateTime.now();
        String timestamp = getTimestamp(now);
        if (!doCommand(commands)){
            int failCounter = 1;
            while(!doCommand(commands)){
                failCounter++;
                if(failCounter == 2){
                    try {
                        shouldAnimate.set(false);
                        animator.join();
                    } catch (InterruptedException ignored) {}
                    String message = "[%s] Lost connection\n".formatted(timestamp);
                    System.out.print(message);
                    try (BufferedWriter writer = getWriter()) {
                        writer.write(message);
                    }
                    shouldAnimate.set(true);
                    animator.start();
                }
            }
            if(failCounter < 2) return;

            try {
                shouldAnimate.set(false);
                animator.join();
            } catch (InterruptedException ignored) {}

            now = LocalDateTime.now();
            timestamp = getTimestamp(now);
            String message = "[%s] Found connection\n".formatted(timestamp);
            System.out.print(message);
            try (BufferedWriter writer = getWriter()) {
                writer.write(message);
            }
            shouldAnimate.set(true);
            animator.start();
        }
    }

    private static BufferedWriter getWriter() throws IOException {
        String folderPath = Main.class.getResource("Main.class").getPath();
        folderPath = folderPath.replace("%20"," "); //fix space character
        folderPath = folderPath.substring(folderPath.indexOf("/")+1); // remove initial '/'
        folderPath = folderPath.substring(0,folderPath.lastIndexOf("/")); // remove .class file from path
        folderPath = folderPath.substring(0,folderPath.lastIndexOf("/")+1); // exit jar
        folderPath = folderPath.replace("/","\\");
        String logName = "internet log - "+address+".txt";

        return new BufferedWriter(new FileWriter(folderPath+logName, true));
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


    private static String fixNumber(int num){
        return num < 10 ? "0"+num : ""+num;
    }
}
