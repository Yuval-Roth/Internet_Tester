import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class PingEndPoint {
    private final String ip;
    private Process proc;
    private BufferedReader stdInput;

    @SafeVarargs
    public PingEndPoint(String ip, Pair<String,String> ... params){
        this.ip = ip;
        setParams(params);
    }

    public String getIp() {
        return ip;
    }

    public String getOutputLine() {
        try {
            String s = stdInput.readLine();
            return s == null ? "" : s;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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
