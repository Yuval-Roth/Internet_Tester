public class test {
    public static String s = """
            Pinging 10.4.7.254 with 32 bytes of data:
            Request timed out.
            """;

    public static void main(String[] args){
        System.out.println(notConnected(s));
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
}
