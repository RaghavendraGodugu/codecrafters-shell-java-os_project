import java.io.File;
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

            String input = scanner.nextLine();
            
            // 1. Handle exit command
            if (input.startsWith("exit")) {
                break;
            } 
            // 2. Handle echo command
            else if (input.startsWith("echo ")) {
                System.out.println(input.substring(5));
            } 
            // 3. Handle type command
            else if (input.startsWith("type ")) {
                String commandToCheck = input.substring(5).trim();
                
                // Check if it's a shell builtin
                if (commandToCheck.equals("echo") || commandToCheck.equals("exit") || commandToCheck.equals("type")) {
                    System.out.println(commandToCheck + " is a shell builtin");
                } else {
                    // Check PATH environment variable for external executables
                    String pathEnv = System.getenv("PATH");
                    boolean found = false;

                    if (pathEnv != null) {
                        // Split path based on OS-specific separator (: for Unix, ; for Windows)
                        String[] directories = pathEnv.split(File.pathSeparator);
                        
                        for (String dir : directories) {
                            File file = new File(dir, commandToCheck);
                            if (file.exists() && file.canExecute()) {
                                System.out.println(commandToCheck + " is " + file.getAbsolutePath());
                                found = true;
                                break;
                            }
                        }
                    }

                    if (!found) {
                        System.out.println(commandToCheck + ": not found");
                    }
                }
            }
            // 4. Handle unknown commands
            else {
                System.out.println(input + ": command not found");
            }
        }
        
        scanner.close();
    }
}