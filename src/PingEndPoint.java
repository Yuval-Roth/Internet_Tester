import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class PingEndPoint {
    private final String ip;
    private Process proc;
    private BufferedReader stdInput;
    private final List<String> outputHistory;

    @SafeVarargs
    public PingEndPoint(String ip, Pair<String,String> ... params){
        this.ip = ip;
        outputHistory = new ArrayList<>(10);
        setParams(params);
    }

    public String getIp() {
        return ip;
    }

    public String readOutputLine() {
        try {
            String s = stdInput.readLine();
            outputHistory.add(s);
            if(outputHistory.size() > 2) outputHistory.removeFirst();
            return s == null ? "" : s;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String getPreviousOutputLine() {
        if(outputHistory.size() < 2) return "";
        return outputHistory.getLast();
    }

    @SafeVarargs
    public final void setParams(Pair<String, String>... params) {
        if(proc != null) proc.destroy();
        try {
            String[] command = paramsToCommand(params);
            proc = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        stdInput = new BufferedReader(new InputStreamReader(proc.getInputStream()));
        readOutputLine(); readOutputLine(); // skip the first 2 lines
    }

    @SafeVarargs
    private String[] paramsToCommand(Pair<String,String> ... params) {
        List<String> command = new ArrayList<>();
        command.add("ping");
        command.add(ip);
        for (Pair<String, String> param : params) {
            command.add(param.first);
            if(param.second != null) command.add(param.second);
        }
        return command.toArray(new String[0]);
    }
}
