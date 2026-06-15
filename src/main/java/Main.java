import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Main {
    private static Path currentDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    private static final List<String> BUILTINS = Arrays.asList("echo", "exit", "pwd", "cd", "type");

    public static void main(String[] args) {
        String[] cmd = {"/bin/sh", "-c", "stty -icanon -echo < /dev/tty"};
        try {
            Runtime.getRuntime().exec(cmd).waitFor();
        } catch (Exception e) {
            // Fallback for environments without standard tty allocation
        }

        InputStream inputReader = System.in;

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            StringBuilder inputBuilder = new StringBuilder();
            
            try {
                while (true) {
                    int code = inputReader.read();
                    
                    if (code == -1) {
                        return;
                    }

                    char c = (char) code;

                    if (c == '\n' || c == '\r') {
                        System.out.print("\n");
                        break;
                    }
                    else if (c == '\t') {