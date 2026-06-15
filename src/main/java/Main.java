import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {
    private static Path currentDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath();

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            if (!scanner.hasNextLine()) {
                break;
            }

            String input = scanner.nextLine().trim();
            if (input.isEmpty()) {
                continue;
            }

            // Parse the input handling single quotes properly
            List<String> argsList = parseArguments(input);
            if (argsList.isEmpty()) {
                continue;
            }

            String command = argsList.get(0);

            // 1. Handle exit command
            if (command.equals("exit")) {
                break;
            } 
            // 2. Handle echo command
            else if (command.equals("echo")) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < argsList.size(); i++) {
                    sb.append(argsList.get(i));
                    if (i < argsList.size() - 1) {
                        sb.append(" ");
                    }
                }
                System.out.println(sb.toString());
            } 
            // 3. Handle pwd command
            else if (command.equals("pwd")) {
                System.out.println(currentDirectory.toString());
            }
            // 4. Handle cd command
            else if (command.equals("cd")) {
                String targetPathStr = argsList.size() > 1 ? argsList.get(1) : "~";
                Path targetPath;

                if (targetPathStr.equals("~")) {
                    String homeDir = System.getenv("HOME");
                    if (homeDir == null) {
                        homeDir = System.getenv("USERPROFILE");
                    }
                    targetPath = Paths.get(homeDir);
                } else if (targetPathStr.startsWith("/")) {
                    targetPath = Paths.get(targetPathStr);
                } else {
                    targetPath = currentDirectory.resolve(targetPathStr);
                }

                targetPath = targetPath.normalize().toAbsolutePath();
                File targetDir = targetPath.toFile();

                if (targetDir.exists() && targetDir.isDirectory()) {
                    currentDirectory = targetPath;
                } else {
                    System.out.println("cd: " + targetPathStr + ": No such file or directory");
                }
            }
            // 5. Handle type command
            else if (command.equals("type")) {
                if (argsList.size() > 1) {
                    String commandToCheck = argsList.get(1);
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
            }
            // 6. Try running it as an external program (like cat)
            else {
                String executablePath = findInPath(command);
                if (executablePath != null) {
                    try {
                        ProcessBuilder pb = new ProcessBuilder(argsList);
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

    // New Helper method to cleanly parse arguments with single quote tracking
    private static List<String> parseArguments(String input) {
        List<String> list = new ArrayList<>();
        StringBuilder currentArg = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean hasContent = false; // Tracks if we've accumulated anything for the current arg

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '\'') {
                inSingleQuotes = !inSingleQuotes;
                hasContent = true; // Handles empty quotes '' safely as valid empty content
            } else if (c == ' ' && !inSingleQuotes) {
                // If we encounter a space outside quotes, complete the current token
                if (hasContent || currentArg.length() > 0) {
                    list.add(currentArg.toString());
                    currentArg.setLength(0);
                    hasContent = false;
                }
            } else {
                currentArg.append(c);
                hasContent = true;
            }
        }

        // Add the final token if anything remains
        if (hasContent || currentArg.length() > 0) {
            list.add(currentArg.toString());
        }

        return list;
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