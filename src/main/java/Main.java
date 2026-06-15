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
                
                // Check if the given command is one of our builtins
                if (commandToCheck.equals("echo") || commandToCheck.equals("exit") || commandToCheck.equals("type")) {
                    System.out.println(commandToCheck + " is a shell builtin");
                } else {
                    System.out.println(commandToCheck + ": not found");
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