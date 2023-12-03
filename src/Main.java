import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.io.*;

public class Main{

    public static void main(String[] args)
            throws IOException
    {
        LocalDateTime now = LocalDateTime.now();
        String timestamp = getTimestamp(now);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter("C:\\Users\\Yuval\\Desktop\\internet logs\\internet log.txt", true))) {
            writer.write("\nStarted logging at "+timestamp+"\n");
        }
        System.out.println("Started logging at "+timestamp+"\n");
        // create the ping command as a list of strings
        Main ping = new Main();
        List<String> commands = new ArrayList<String>();
        commands.add("ping");
        commands.add("-n");
        commands.add("1");
        commands.add("-w");
        commands.add("1000");
        commands.add("www.google.com");
        while (true){
            if(ping.doCommand(commands)){
                try{
                    Thread.sleep(1000);
                } catch(InterruptedException ignored){}
            }
        }
    }

    private static String getTimestamp(LocalDateTime time) {
        return fixNumber(time.getDayOfMonth()) + "/" + fixNumber(time.getMonthValue()) + "/" + fixNumber(time.getYear()) + " "
                + fixNumber(time.getHour()) + ":" + fixNumber(time.getMinute()) + ":" + fixNumber(time.getSecond());
    }

    public boolean doCommand(List<String> command)
            throws IOException
    {
        String s = null;

        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();

        BufferedReader stdInput = new BufferedReader(new InputStreamReader(process.getInputStream()));

        while ((s = stdInput.readLine()) != null) {
            if (notConnected(s)) {
                LocalDateTime now = LocalDateTime.now();
                String timestamp = getTimestamp(now);
                System.out.println("No internet connection at time: " + timestamp);
                try (BufferedWriter writer = new BufferedWriter(new FileWriter("C:\\Users\\Yuval\\Desktop\\internet logs\\internet log.txt", true))) {
                    writer.write("No internet connection at time: " + timestamp + "\n");
                }
                if (s.contains("could not find host")) return true;
                return false;
            }
        }
        return true;
    }

    public static boolean notConnected(String s) {
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

    public static String fixNumber(int num){
        return num < 10 ? "0"+num : ""+num;
    }

}



