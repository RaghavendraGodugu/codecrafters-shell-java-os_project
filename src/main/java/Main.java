import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

public class Main {
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

            // Check if the command contains a pipeline
            if (input.contains("|")) {
                executePipeline(input);
            } else {
                executeSingleCommand(input);
            }
        }
        scanner.close();
    }

    private static void executePipeline(String input) {
        // Split the command line by the pipe symbol
        String[] parts = input.split("\\|");
        
        if (parts.length != 2) {
            System.out.println("Error: Only dual-command pipelines are supported.");
            return;
        }

        // Parse arguments for both commands safely
        List<String> cmd1Args = parseArguments(parts[0].trim());
        List<String> cmd2Args = parseArguments(parts[1].trim());

        try {
            // Create ProcessBuilders for both processes
            ProcessBuilder pb1 = new ProcessBuilder(cmd1Args);
            ProcessBuilder pb2 = new ProcessBuilder(cmd2Args);

            // Redirect error streams to inherit from your shell
            pb1.redirectError(ProcessBuilder.Redirect.INHERIT);
            pb2.redirectError(ProcessBuilder.Redirect.INHERIT);
            
            // Redirect the final command's output back to your terminal stdout
            pb2.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            // Start the pipeline natively (Connects pb1 stdout -> pb2 stdin automatically)
            List<Process> processes = ProcessBuilder.startPipeline(Arrays.asList(pb1, pb2));

            // Wait for both background processes to fully finish execution
            for (Process p : processes) {
                p.waitFor();
            }

        } catch (IOException | InterruptedException e) {
            System.err.println("Pipeline execution failed: " + e.getMessage());
        }
    }

    private static void executeSingleCommand(String input) {
        List<String> args = parseArguments(input);
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
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            
            Process process = pb.start();
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            System.out.println(command +": command not found");
        }
    }

    private static List<String> parseArguments(String commandSection) {
        // Splitting by whitespace to separate executable and flags
        return new ArrayList<>(Arrays.asList(commandSection.split("\\s+")));
    }
}