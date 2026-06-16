import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

public class Main {
    // Keep track of the next background job number dynamically
    private static int nextJobNumber = 1;

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            if (!scanner.hasNextLine()) {
                break;
            }
            
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) {
                continue;
            }

            // Handle Pipelines
            if (input.contains("|")) {
                executePipeline(input);
            } else {
                executeSingleCommand(input);
            }
        }
        scanner.close();
    }

    private static void executePipeline(String input) {
        String[] parts = input.split("\\|");
        
        if (parts.length != 2) {
            System.out.println("Error: Only dual-command pipelines are supported.");
            return;
        }

        List<String> cmd1Args = parseArguments(parts[0].trim());
        List<String> cmd2Args = parseArguments(parts[1].trim());

        try {
            ProcessBuilder pb1 = new ProcessBuilder(cmd1Args);
            ProcessBuilder pb2 = new ProcessBuilder(cmd2Args);

            pb1.redirectError(ProcessBuilder.Redirect.INHERIT);
            pb2.redirectError(ProcessBuilder.Redirect.INHERIT);
            pb2.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            List<Process> processes = ProcessBuilder.startPipeline(Arrays.asList(pb1, pb2));

            for (Process p : processes) {
                p.waitFor();
            }

        } catch (IOException | InterruptedException e) {
            System.err.println("Pipeline execution failed: " + e.getMessage());
        }
    }

    private static void executeSingleCommand(String input) {
        List<String> args = parseArguments(input);
        if (args.isEmpty()) return;

        // Check if this is a background job command
        boolean isBackgroundJob = false;
        if (args.get(args.size() - 1).equals("&")) {
            isBackgroundJob = true;
            args.remove(args.size() - 1); // Remove '&' so it isn't passed to the binary
        }

        String command = args.get(0);

        // 1. Handle Builtins
        if (command.equals("exit")) {
            System.exit(0);
        } else if (command.equals("echo")) {
            System.out.println(String.join(" ", args.subList(1, args.size())));
            return;
        }
        
        // 2. Handle External Commands / Binaries
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            
            // Only inherit standard input if it's running in the foreground
            if (!isBackgroundJob) {
                pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            }
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            
            Process process = pb.start();
            
            if (isBackgroundJob) {
                // Dynamically print the current job number and then increment it
                long pid = process.pid();
                System.out.println("[" + nextJobNumber + "] " + pid);
                nextJobNumber++;
                
                // Do NOT call process.waitFor() so the shell prompt returns instantly!
            } else {
                // Foreground job: wait normally
                process.waitFor();
            }
        } catch (IOException | InterruptedException e) {
            System.out.println(command + ": command not found");
        }
    }

    private static List<String> parseArguments(String commandSection) {
        return new ArrayList<>(Arrays.asList(commandSection.split("\\s+")));
    }
}