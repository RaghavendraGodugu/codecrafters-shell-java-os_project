import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class Main {
    private static Path currentDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    private static final List<String> BUILTINS = Arrays.asList("echo", "exit", "pwd", "cd", "type");

    public static void main(String[] args) {
        String[] cmd = {"/bin/sh", "-c", "stty -icanon -echo < /dev/tty"};
        try {
            Runtime.getRuntime().exec(cmd).waitFor();
        } catch (Exception e) {
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
                    } else if (c == '\t') {
                        handleTabCompletion(inputBuilder);
                    } else if (code == 127) { // backspace
                        if (inputBuilder.length() > 0) {
                            inputBuilder.deleteCharAt(inputBuilder.length() - 1);
                            System.out.print("\b \b");
                            System.out.flush();
                        }
                    } else {
                        inputBuilder.append(c);
                        System.out.print(c);
                        System.out.flush();
                    }
                }

                String input = inputBuilder.toString().trim();
                if (input.isEmpty()) {
                    continue;
                }

                List<String> parts = tokenize(input);
                String command = parts.get(0);

                if (command.equals("exit")) {
                    int codeToExit = 0;
                    if (parts.size() > 1) {
                        try {
                            codeToExit = Integer.parseInt(parts.get(1));
                        } catch (NumberFormatException e) {
                            codeToExit = 0;
                        }
                    }
                    return;
                } else if (command.equals("echo")) {
                    for (int i = 1; i < parts.size(); i++) {
                        if (i > 1) System.out.print(" ");
                        System.out.print(parts.get(i));
                    }
                    System.out.println();
                } else if (command.equals("pwd")) {
                    System.out.println(currentDirectory);
                } else if (command.equals("cd")) {
                    handleCd(parts);
                } else if (command.equals("type")) {
                    handleType(parts);
                } else {
                    runExternal(parts);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void handleTabCompletion(StringBuilder inputBuilder) {
        String currentInput = inputBuilder.toString();
        int lastSpace = currentInput.lastIndexOf(' ');
        String prefix = lastSpace == -1 ? currentInput : currentInput.substring(lastSpace + 1);

        if (prefix.isEmpty()) {
            return;
        }

        List<String> matches = findMatches(prefix);

        if (matches.size() == 1) {
            String match = matches.get(0);
            String completion = match + " ";
            String beforeToken = lastSpace == -1 ? "" : currentInput.substring(0, lastSpace + 1);

            for (int i = 0; i < prefix.length(); i++) {
                System.out.print("\b \b");
            }

            inputBuilder.setLength(0);
            inputBuilder.append(beforeToken).append(completion);

            System.out.print(completion);
            System.out.flush();
        }
    }

    private static List<String> findMatches(String prefix) {
        Set<String> matches = new LinkedHashSet<>();

        for (String builtin : BUILTINS) {
            if (builtin.startsWith(prefix)) {
                matches.add(builtin);
            }
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv != null && !pathEnv.isEmpty()) {
            String[] dirs = pathEnv.split(File.pathSeparator);
            for (String dir : dirs) {
                File folder = new File(dir);
                if (!folder.exists() || !folder.isDirectory()) {
                    continue;
                }

                File[] files = folder.listFiles();
                if (files == null) {
                    continue;
                }

                for (File file : files) {
                    if (file.getName().startsWith(prefix) && file.isFile() && file.canExecute()) {
                        matches.add(file.getName());
                    }
                }
            }
        }

        return new ArrayList<>(matches);
    }

    private static void handleCd(List<String> parts) {
        String target;
        if (parts.size() < 2 || parts.get(1).equals("~")) {
            target = System.getProperty("user.home");
        } else if (parts.get(1).startsWith("~/")) {
            target = System.getProperty("user.home") + parts.get(1).substring(1);
        } else {
            target = parts.get(1);
        }

        Path newPath = Paths.get(target);
        if (!newPath.isAbsolute()) {
            newPath = currentDirectory.resolve(newPath);
        }
        newPath = newPath.normalize();

        if (Files.exists(newPath) && Files.isDirectory(newPath)) {
            currentDirectory = newPath;
        } else {
            System.out.println("cd: " + parts.get(1) + ": No such file or directory");
        }
    }

    private static void handleType(List<String> parts) {
        if (parts.size() < 2) {
            return;
        }

        String target = parts.get(1);

        if (BUILTINS.contains(target)) {
            System.out.println(target + " is a shell builtin");
            return;
        }

        String path = findExecutable(target);
        if (path != null) {
            System.out.println(target + " is " + path);
        } else {
            System.out.println(target + ": not found");
        }
    }

    private static String findExecutable(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) {
            return null;
        }

        String[] dirs = pathEnv.split(File.pathSeparator);
        for (String dir : dirs) {
            File file = new File(dir, command);
            if (file.exists() && file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }

    private static void runExternal(List<String> parts) {
        try {
            ProcessBuilder pb = new ProcessBuilder(parts);
            pb.directory(currentDirectory.toFile());
            pb.inheritIO();
            Process process = pb.start();
            process.waitFor();
        } catch (Exception e) {
            System.out.println(parts.get(0) + ": command not found");
        }
    }

    private static List<String> tokenize(String input) {
        return new ArrayList<>(Arrays.asList(input.split("\\s+")));
    }
}