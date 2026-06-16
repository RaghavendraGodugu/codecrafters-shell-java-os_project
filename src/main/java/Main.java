import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class Main {
    private static int nextJobNumber = 1;
    
    // Tracks active background jobs: Job ID -> Command String
    private static final Map<Integer, String> activeJobs = new LinkedHashMap<>();

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

        String command = args.get(0);

        // 1. Handle Builtins
        if (command.equals("exit")) {
            System.exit(0);
        } else if (command.equals("echo")) {
            System.out.println(String.join(" ", args.subList(1, args.size())));
            return;
        } else if (command.equals("jobs")) {
            // Handle the 'jobs' builtin command explicitly
            for (Map.Entry<Integer, String> entry : activeJobs.entrySet()) {
                // Formatting matches: "[1]-  Running                 sleep 500 &"
                System.out.printf("[%d]-  Running                 %s\n", entry.getKey(), entry.getValue());
            }
            return;
        }
        
        // Check if this is a background job command
        boolean isBackgroundJob = false;
        if (args.get(args.size() - 1).equals("&")) {
            isBackgroundJob = true;
            args.remove(args.size() - 1); // Remove '&' so it isn't passed to the binary
        }

        // 2. Handle External Commands / Binaries
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            
            if (!isBackgroundJob) {
                pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            }
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            
            Process process = pb.start();
            
            if (isBackgroundJob) {
                long pid = process.pid();
                int currentJobId = nextJobNumber;
                
                System.out.println("[" + currentJobId + "] " + pid);
                
                // Store the command context in our active jobs map
                String fullJobCommand = String.join(" ", args) + " &";
                activeJobs.put(currentJobId, fullJobCommand);
                
                nextJobNumber++;
            } else {
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