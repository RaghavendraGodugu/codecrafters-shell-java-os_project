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
            
            // Check if the user wants to exit the shell
            if (input.equals("exit 0")) {
                break; // Breaks the loop and terminates the program gracefully
            }
            
            // If it's not a known command, print the error
            System.out.println(input + ": command not found");
        }
        
        scanner.close();
    }
}