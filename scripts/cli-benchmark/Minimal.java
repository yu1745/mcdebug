import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** 最小 Native Image 对照：与 Kotlin/Native 探针具有相同功能范围。 */
public class Minimal {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--version")) {
            System.out.println("mcdebug-native-probe 1"); return;
        }
        int port = Integer.parseInt(args[0]), count = Integer.parseInt(args[1]);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var output = socket.getOutputStream();
            if (!input.readLine().contains("server.ready")) throw new IOException("handshake");
            for (int index = 0; index < count; index++) {
                output.write(("{\"jsonrpc\":\"2.0\",\"id\":"+(index+1)+",\"method\":\"server.status\",\"params\":{}}\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
                String response = input.readLine();
                if (response == null || !response.contains("\"result\"") || response.contains("\"error\"")) throw new IOException("response");
                System.out.println(response);
            }
        }
    }
}
