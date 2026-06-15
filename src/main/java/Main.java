import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

public class Main {
    // Keep track of the shell's current working directory internally
    private static Path currentDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath();

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
                // Print our internally tracked current directory
                System.out.println(currentDirectory.toString());
            }
            // 4. Handle cd command (Absolute Paths)
            else if (input.startsWith("cd ")) {
                String targetPathStr = input.substring(3).trim();
                File targetDir = new File(targetPathStr);

                // Verify if the path exists and is a directory
                if (targetDir.exists() && targetDir.isDirectory()) {
                    currentDirectory = targetDir.toPath().toAbsolutePath();
                } else {
                    System.out.println("cd: " + targetPathStr + ": No such file or directory");
                }
            }
            // 5. Handle type command
            else if (input.startsWith("type ")) {
                String commandToCheck = input.substring(5).trim();
                
                // Added "cd" to the recognized builtins
                if (commandToCheck.equals("echo") || commandToCheck.equals("exit") || 
                    commandToCheck.equals("type") || commandToCheck.equals("pwd") || 
                    commandToCheck.equals("cd")) {
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
            // 6. Try running it as an external program
            else {
                String[] parsedInput = input.split(" ");
                String command = parsedInput[0];
                
                String executablePath = findInPath(command);
                
                if (executablePath != null) {
                    try {
                        ProcessBuilder pb = new ProcessBuilder(parsedInput);
                        // Make sure external processes launch inside our tracked directory path
                        pb.directory(currentDirectory.toFile());
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