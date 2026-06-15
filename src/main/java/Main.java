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
                // Cut off the "echo " part (5 characters) and print the rest
                System.out.println(input.substring(5));
            } 
            // 3. Handle unknown commands
            else {
                System.out.println(input + ": command not found");
            }
        }
        
        scanner.close();
    }
}