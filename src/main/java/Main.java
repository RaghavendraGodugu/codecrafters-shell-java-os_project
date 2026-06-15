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
            // Ignore if tty is unavailable
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
                        System.out.flush();
                        break;
                    } else if (c == '\t') {
                        handleTabCompletion(inputBuilder);
                    } else if (code == 127) {
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

                if ("exit".equals(command)) {
                    if (parts.size() > 1 && "0".equals(parts.get(1))) {
                        return;
                    }
                    return;
                } else if ("echo".equals(command)) {
                    for (int i = 1; i < parts.size(); i++) {
                        if (i > 1) {
                            System.out.print(" ");
                        }
                        System.out.print(parts.get(i));
                    }
                    System.out.println();
                } else if ("pwd".equals(command)) {
                    System.out.println(currentDirectory);
                } else if ("cd".equals(command)) {
                    handleCd(parts);
                } else if ("type".equals(command)) {
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
            ringBell();
            return;
        }

        List<String> matches = findMatches(prefix);

        if (matches.size() == 1) {
            String match = matches.get(0);
            String beforeToken = lastSpace == -1 ? "" : currentInput.substring(0, lastSpace + 1);
            String completed = beforeToken + match + " ";

            for (int i = 0; i < prefix.length(); i++) {
                System.out.print("\b \b");
            }

            inputBuilder.setLength(0);
            inputBuilder.append(completed);

            System.out.print(match + " ");
            System.out.flush();
        } else if (matches.isEmpty()) {
            ringBell();
        }
    }

    private static void ringBell() {
        System.out.print("\u0007");
        System.out.flush();
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
            String[] directories = pathEnv.split(File.pathSeparator);

            for (String dir : directories) {
                File folder = new File(dir);
                if (!folder.exists() || !folder.isDirectory()) {
                    continue;
                }

                File[] files = folder.listFiles();
                if (files == null) {
                    continue;
                }

                for (File file : files) {
                    if (file.isFile() && file.canExecute() && file.getName().startsWith(prefix)) {
                        matches.add(file.getName());
                    }
                }
            }
        }

        return new ArrayList<>(matches);
    }

    private static void handleCd(List<String> parts) {
        String target;
        if (parts.size() < 2 || "~".equals(parts.get(1))) {
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

        String executablePath = findExecutable(target);
        if (executablePath != null) {
            System.out.println(target + " is " + executablePath);
        } else {
            System.out.println(target + ": not found");
        }
    }

    private static String findExecutable(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) {
            return null;
        }

        String[] directories = pathEnv.split(File.pathSeparator);
        for (String dir : directories) {
            File file = new File(dir, command);
            if (file.exists() && file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }

        return null;
    }

    private static void runExternal(List<String> parts) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(parts);
            processBuilder.directory(currentDirectory.toFile());
            processBuilder.inheritIO();

            Process process = processBuilder.start();
            process.waitFor();
        } catch (Exception e) {
            System.out.println(parts.get(0) + ": command not found");
        }
    }

    private static List<String> tokenize(String input) {
        return new ArrayList<>(Arrays.asList(input.split("\\s+")));
    }
}