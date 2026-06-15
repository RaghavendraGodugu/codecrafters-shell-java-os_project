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
            
            // Check if the command starts with "exit"
            if (input.startsWith("exit")) {
                break; // Breaks the loop and terminates the program cleanly
            }
            
            // If it's not a known command, print the error
            System.out.println(input + ": command not found");
        }
        
        scanner.close();
    }
}