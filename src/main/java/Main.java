import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
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

                List<String> tokens = tokenize(input);
                ParsedCommand parsed = parseCommand(tokens);

                if (parsed.args.isEmpty()) {
                    continue;
                }

                String command = parsed.args.get(0);

                if ("exit".equals(command)) {
                    return;
                } else if ("echo".equals(command)) {
                    executeEcho(parsed);
                } else if ("pwd".equals(command)) {
                    executePwd(parsed);
                } else if ("cd".equals(command)) {
                    handleCd(parsed.args);
                } else if ("type".equals(command)) {
                    executeType(parsed);
                } else {
                    runExternal(parsed);
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

    private static void executeEcho(ParsedCommand parsed) throws Exception {
        PrintStream out = getStdoutStream(parsed);
        for (int i = 1; i < parsed.args.size(); i++) {
            if (i > 1) {
                out.print(" ");
            }
            out.print(parsed.args.get(i));
        }
        out.println();
        out.flush();
        if (out != System.out) {
            out.close();
        }
    }

    private static void executePwd(ParsedCommand parsed) throws Exception {
        PrintStream out = getStdoutStream(parsed);
        out.println(currentDirectory);
        out.flush();
        if (out != System.out) {
            out.close();
        }
    }

    private static void executeType(ParsedCommand parsed) throws Exception {
        PrintStream out = getStdoutStream(parsed);

        if (parsed.args.size() < 2) {
            if (out != System.out) {
                out.close();
            }
            return;
        }

        String target = parsed.args.get(1);

        if (BUILTINS.contains(target)) {
            out.println(target + " is a shell builtin");
        } else {
            String executablePath = findExecutable(target);
            if (executablePath != null) {
                out.println(target + " is " + executablePath);
            } else {
                out.println(target + ": not found");
            }
        }

        out.flush();
        if (out != System.out) {
            out.close();
        }
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

    private static void runExternal(ParsedCommand parsed) {
        try {
            ProcessBuilder pb = new ProcessBuilder(parsed.args);
            pb.directory(currentDirectory.toFile());

            if (parsed.stdoutFile != null) {
                File file = resolvePath(parsed.stdoutFile).toFile();
                pb.redirectOutput(parsed.stdoutAppend
                        ? ProcessBuilder.Redirect.appendTo(file)
                        : ProcessBuilder.Redirect.to(file));
            } else {
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }

            if (parsed.stderrFile != null) {
                File file = resolvePath(parsed.stderrFile).toFile();
                pb.redirectError(parsed.stderrAppend
                        ? ProcessBuilder.Redirect.appendTo(file)
                        : ProcessBuilder.Redirect.to(file));
            } else {
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            }

            Process process = pb.start();
            process.waitFor();
        } catch (Exception e) {
            System.out.println(parsed.args.get(0) + ": command not found");
        }
    }

    private static PrintStream getStdoutStream(ParsedCommand parsed) throws Exception {
        if (parsed.stdoutFile == null) {
            return System.out;
        }

        Path filePath = resolvePath(parsed.stdoutFile);
        File parent = filePath.toFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        return new PrintStream(new FileOutputStream(filePath.toFile(), parsed.stdoutAppend));
    }

    private static Path resolvePath(String file) {
        Path path = Paths.get(file);
        if (!path.isAbsolute()) {
            path = currentDirectory.resolve(path);
        }
        return path.normalize();
    }

    private static ParsedCommand parseCommand(List<String> tokens) {
        ParsedCommand parsed = new ParsedCommand();

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);

            if (token.equals(">") || token.equals("1>")) {
                if (i + 1 < tokens.size()) {
                    parsed.stdoutFile = tokens.get(++i);
                    parsed.stdoutAppend = false;
                }
            } else if (token.equals(">>") || token.equals("1>>")) {
                if (i + 1 < tokens.size()) {
                    parsed.stdoutFile = tokens.get(++i);
                    parsed.stdoutAppend = true;
                }
            } else if (token.equals("2>")) {
                if (i + 1 < tokens.size()) {
                    parsed.stderrFile = tokens.get(++i);
                    parsed.stderrAppend = false;
                }
            } else if (token.equals("2>>")) {
                if (i + 1 < tokens.size()) {
                    parsed.stderrFile = tokens.get(++i);
                    parsed.stderrAppend = true;
                }
            } else {
                parsed.args.add(token);
            }
        }

        return parsed;
    }

    private static List<String> tokenize(String input) {
        return new ArrayList<>(Arrays.asList(input.split("\\s+")));
    }

    private static class ParsedCommand {
        List<String> args = new ArrayList<>();
        String stdoutFile;
        boolean stdoutAppend;
        String stderrFile;
        boolean stderrAppend;
    }
}