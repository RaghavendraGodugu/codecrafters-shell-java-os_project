import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ShellPipelineHandler {

    public static void handleCommandLine(String input) {
        // 1. Check if the input contains a pipeline operator
        if (input.contains("|")) {
            executePipeline(input);
        } else {
            // Your existing logic for single commands / builtins
            executeSingleCommand(input);
        }
    }

    private static void executePipeline(String input) {
        // 2. Split the command line by the pipe symbol
        // Note: This is a basic split; a robust shell would handle '|' inside quotes.
        String[] parts = input.split("\\|");
        
        if (parts.length != 2) {
            System.out.println("Error: Only dual-command pipelines are supported in this stage.");
            return;
        }

        // 3. Parse arguments for both commands
        List<String> cmd1Args = parseArguments(parts[0].trim());
        List<String> cmd2Args = parseArguments(parts[1].trim());

        try {
            // 4. Create ProcessBuilders for both commands
            ProcessBuilder pb1 = new ProcessBuilder(cmd1Args);
            ProcessBuilder pb2 = new ProcessBuilder(cmd2Args);

            // Inherit the environment and error streams if necessary
            pb1.redirectError(ProcessBuilder.Redirect.INHERIT);
            pb2.redirectError(ProcessBuilder.Redirect.INHERIT);
            
            // Explicitly route the final command's output back to the shell's stdout
            pb2.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            // 5. Start the pipeline
            // startPipeline handles connecting pb1's stdout into pb2's stdin automatically
            List<Process> processes = ProcessBuilder.startPipeline(Arrays.asList(pb1, pb2));

            // 6. Wait for both processes to complete
            for (Process p : processes) {
                p.waitFor();
            }

        } catch (IOException | InterruptedException e) {
            System.err.println("Pipeline execution failed: " + e.getMessage());
        }
    }

    private static List<String> parseArguments(String commandSection) {
        // Simple whitespace splitter (expand this if you already implemented quoting support)
        return new ArrayList<>(Arrays.asList(commandSection.split("\\s+")));
    }

    private static void executeSingleCommand(String input) {
        // Your existing single command / binary execution logic goes here
    }
}