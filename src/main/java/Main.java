import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            if (!scanner.hasNextLine()) {
                break; // Exit if input stream ends (EOF)
            }

            String input = scanner.nextLine().trim();
            if (input.isEmpty()) {
                continue;
            }
            
            // Handle exit command
            if (input.startsWith("exit")) {
                break;
            } 
            // Handle echo command
            else if (input.startsWith("echo ")) {
                System.out.println(input.substring(5));
            } 
            // Handle type command
            else if (input.startsWith("type ")) {
                String commandToCheck = input.substring(5).trim();
                
                if (commandToCheck.equals("echo") || commandToCheck.equals("exit") || commandToCheck.equals("type")) {
                    System.out.println(commandToCheck + " is a shell builtin");
                } else {
                    String fullPath = findInPath(commandToCheck);
                    if (fullPath != null) {
                        System.out.println(commandToCheck + " is " + fullPath);
                    } else {
                        System.out.println(commandToCheck + ": not found");
                    }
                }
            }
            // 4. Try running it as an external program
            else {
                // Parse command and arguments by splitting on spaces
                String[] parsedInput = input.split(" ");
                String command = parsedInput[0];
                
                String executablePath = findInPath(command);
                
                if (executablePath != null) {
                    try {
                        // Create a ProcessBuilder with the parsed arguments array
                        ProcessBuilder pb = new ProcessBuilder(parsedInput);
                        
                        // Redirect inputs and outputs straight to our shell's terminal
                        pb.inheritIO();
                        
                        Process process = pb.start();
                        process.waitFor(); // Wait for the external program to finish executing
                    } catch (IOException | InterruptedException e) {
                        System.out.println(command + ": command not found");
                    }
                } else {
                    System.out.println(command + ": command not found");
                }
            }
        }
        
        scanner.close();
    }

    // Helper method to look up a command name in the system PATH environment variable
    private static String findInPath(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String[] directories = pathEnv.split(File.pathSeparator);
            for (String dir : directories) {
                File file = new File(dir, command);
                if (file.exists() && file.canExecute()) {
                    return file.getAbsolutePath();
                }
            }
        }
        return null;
    }
}