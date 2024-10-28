import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;

public class PingEndPoint {
    private final String ip;
    private Process proc;
    private BufferedReader stdInput;
    private final List<String> outputHistory;
    private final Map<String,String> params;

    @SafeVarargs
    public PingEndPoint(String ip, Pair<String,String> ... params){
        this.ip = ip;
        this.params = new HashMap<>();
        outputHistory = new ArrayList<>(10);
        setParams(params);
    }

    public void start(){
        if(isRunning()){
            throw new IllegalStateException("Process is already running");
        }

        try {
            String[] command = paramsToCommand();
            proc = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        stdInput = new BufferedReader(new InputStreamReader(proc.getInputStream()));
        readOutputLine(); readOutputLine(); // skip the first 2 lines
    }

    public void stop(){
        if(proc != null){
            if(! proc.isAlive()){
                throw new IllegalStateException("Process is not running");
            }
            proc.destroy();
        }
    }

    public boolean isRunning(){
        return proc != null && proc.isAlive();
    }

    public String getIp() {
        return ip;
    }

    public String readOutputLine() {
        try {
            String s = stdInput.readLine();
            outputHistory.addFirst(s);
            if(outputHistory.size() > 2) outputHistory.removeLast();
            return s == null ? "" : s;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String getPreviousOutput() {
        if(outputHistory.size() < 2) return "";
        return outputHistory.getLast();
    }

    @SafeVarargs
    public final void setParams(Pair<String, String>... params) {
        Arrays.stream(params).forEach(p -> this.params.put(p.first, p.second));
    }

    private String[] paramsToCommand() {
        List<String> command = new ArrayList<>();
        command.add("ping");
        command.add(ip);
        for (var param : params.entrySet()) {
            command.add(param.getKey());
            if(param.getValue() != null) command.add(param.getValue());
        }
        return command.toArray(new String[0]);
    }
}
