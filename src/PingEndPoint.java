import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

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
            String command = paramsToCommand(params);
            proc = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        stdInput = new BufferedReader(new InputStreamReader(proc.getInputStream()));
    }

    @SafeVarargs
    private String paramsToCommand(Pair<String,String> ... params) {
        StringBuilder command = new StringBuilder("ping "+ip);
        for(Pair<String,String> param : params) {
            command.append(" ").append(param.first).append(" ").append(param.second);
        }
        return command.toString();
    }
}
