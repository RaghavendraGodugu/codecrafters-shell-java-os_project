import java.io.File;
import java.io.IOException;
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
            
            // 1. Handle exit command
            if (input.startsWith("exit")) {
                break;
            } 
            // 2. Handle echo command
            else if (input.startsWith("echo ")) {
                System.out.println(input.substring(5));
            } 
            // 3. Handle pwd command
            else if (input.equals("pwd")) {
                // Fetch and print the absolute path of the current working directory
                System.out.println(System.getProperty("user.dir"));
            }
            // 4. Handle type command
            else if (input.startsWith("type ")) {
                String commandToCheck = input.substring(5).trim();
                
                // Add "pwd" to your list of recognized builtins
                if (commandToCheck.equals("echo") || commandToCheck.equals("exit") || 
                    commandToCheck.equals("type") || commandToCheck.equals("pwd")) {
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
            // 5. Try running it as an external program
            else {
                String[] parsedInput = input.split(" ");
                String command = parsedInput[0];
                
                String executablePath = findInPath(command);
                
                if (executablePath != null) {
                    try {
                        ProcessBuilder pb = new ProcessBuilder(parsedInput);
                        pb.inheritIO();
                        Process process = pb.start();
                        process.waitFor();
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