import java.io.*;
import java.util.*;

public class Shell {

    public static void executePipeline(String leftCmd, String rightCmd)
            throws IOException, InterruptedException {

        List<String> leftArgs = parseCommand(leftCmd);
        List<String> rightArgs = parseCommand(rightCmd);

        Process leftProcess = new ProcessBuilder(leftArgs).start();
        Process rightProcess = new ProcessBuilder(rightArgs).start();

        // Pipe left stdout -> right stdin
        Thread pipeThread = new Thread(() -> {
            try (
                InputStream in = leftProcess.getInputStream();
                OutputStream out = rightProcess.getOutputStream()
            ) {
                in.transferTo(out);
            } catch (IOException ignored) {
            }
        });

        // Forward stderr from both commands to shell stderr
        Thread leftErr = new Thread(() -> {
            try {
                leftProcess.getErrorStream().transferTo(System.err);
            } catch (IOException ignored) {
            }
        });

        Thread rightErr = new Thread(() -> {
            try {
                rightProcess.getErrorStream().transferTo(System.err);
            } catch (IOException ignored) {
            }
        });

        // Forward final command stdout to shell stdout
        Thread outputThread = new Thread(() -> {
            try {
                rightProcess.getInputStream().transferTo(System.out);
            } catch (IOException ignored) {
            }
        });

        pipeThread.start();
        leftErr.start();
        rightErr.start();
        outputThread.start();

        leftProcess.waitFor();
        pipeThread.join(); // close right stdin after left exits

        rightProcess.waitFor();

        outputThread.join();
        leftErr.join();
        rightErr.join();
    }

    private static List<String> parseCommand(String command) {
        return Arrays.asList(command.trim().split("\\s+"));
    }

    public static void main(String[] args) throws Exception {
        String input = "cat /tmp/foo/file | wc";

        String[] parts = input.split("\\|", 2);

        if (parts.length == 2) {
            executePipeline(parts[0].trim(), parts[1].trim());
        }
    }
}