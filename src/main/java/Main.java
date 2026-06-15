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

                String input = inputBuilder.toString();
                if (input.trim().isEmpty()) {
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
                    executeCd(parsed);
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
        ensureBuiltinStderrTargetExists(parsed);
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
        ensureBuiltinStderrTargetExists(parsed);
        PrintStream out = getStdoutStream(parsed);
        out.println(currentDirectory);
        out.flush();

        if (out != System.out) {
            out.close();
        }
    }

    private static void executeType(ParsedCommand parsed) throws Exception {
        ensureBuiltinStderrTargetExists(parsed);
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

    private static void executeCd(ParsedCommand parsed) throws Exception {
        PrintStream err = getStderrStream(parsed);
        try {
            String target;
            String homeDir = getHomeDirectory();

            if (parsed.args.size() < 2 || "~".equals(parsed.args.get(1))) {
                target = homeDir;
            } else if (parsed.args.get(1).startsWith("~/")) {
                target = homeDir + parsed.args.get(1).substring(1);
            } else {
                target = parsed.args.get(1);
            }

            Path newPath = Paths.get(target);
            if (!newPath.isAbsolute()) {
                newPath = currentDirectory.resolve(newPath);
            }
            newPath = newPath.normalize();

            if (Files.exists(newPath) && Files.isDirectory(newPath)) {
                currentDirectory = newPath;
            } else {
                err.println("cd: " + parsed.args.get(1) + ": No such file or directory");
                err.flush();
            }
        } finally {
            if (err != System.err) {
                err.close();
            }
        }
    }

    private static String getHomeDirectory() {
        String home = System.getenv("HOME");
        if (home != null && !home.isEmpty()) {
            return home;
        }
        return System.getProperty("user.home");
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
                File file = prepareFile(parsed.stdoutFile).toFile();
                pb.redirectOutput(parsed.stdoutAppend
                        ? ProcessBuilder.Redirect.appendTo(file)
                        : ProcessBuilder.Redirect.to(file));
            } else {
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }

            if (parsed.stderrFile != null) {
                File file = prepareFile(parsed.stderrFile).toFile();
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
        Path filePath = prepareFile(parsed.stdoutFile);
        return new PrintStream(new FileOutputStream(filePath.toFile(), parsed.stdoutAppend));
    }

    private static PrintStream getStderrStream(ParsedCommand parsed) throws Exception {
        if (parsed.stderrFile == null) {
            return System.err;
        }
        Path filePath = prepareFile(parsed.stderrFile);
        return new PrintStream(new FileOutputStream(filePath.toFile(), parsed.stderrAppend));
    }

    private static void ensureBuiltinStderrTargetExists(ParsedCommand parsed) throws Exception {
        if (parsed.stderrFile == null) {
            return;
        }

        Path filePath = prepareFile(parsed.stderrFile);
        FileOutputStream fos = new FileOutputStream(filePath.toFile(), parsed.stderrAppend);
        fos.close();
    }

    private static Path prepareFile(String file) throws Exception {
        Path filePath = resolvePath(file);
        File parent = filePath.toFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        if (!Files.exists(filePath)) {
            Files.createFile(filePath);
        }
        return filePath;
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
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaping = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            if (escaping) {
                if (inDoubleQuote) {
                    if (ch == '\\' || ch == '"' || ch == '$' || ch == '\n') {
                        current.append(ch);
                    } else {
                        current.append('\\');
                        current.append(ch);
                    }
                } else {
                    current.append(ch);
                }
                escaping = false;
                continue;
            }

            if (ch == '\\' && !inSingleQuote) {
                escaping = true;
                continue;
            }

            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }

            if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }

            if (Character.isWhitespace(ch) && !inSingleQuote && !inDoubleQuote) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(ch);
            }
        }

        if (escaping) {
            current.append('\\');
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    private static class ParsedCommand {
        List<String> args = new ArrayList<>();
        String stdoutFile;
        boolean stdoutAppend;
        String stderrFile;
        boolean stderrAppend;
    }
}