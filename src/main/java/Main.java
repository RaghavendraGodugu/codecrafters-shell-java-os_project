import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Main {
    private static Path currentDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    private static final List<String> BUILTINS = Arrays.asList("echo", "exit", "pwd", "cd", "type", "complete", "jobs");
    private static final Map<String, String> completionSpecs = new LinkedHashMap<>();

    private static String lastTabInput = null;
    private static List<String> lastTabDisplayOptions = new ArrayList<>();

    private static final List<BackgroundJob> backgroundJobs = new ArrayList<>();
    private static int nextJobNumber = 1;

    public static void main(String[] args) {
        try {
            Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", "stty -icanon -echo < /dev/tty"}).waitFor();
        } catch (Exception ignored) {
        }

        InputStream inputReader = System.in;

        while (true) {
            try {
                reapCompletedJobs(System.out, true);

                System.out.print("$ ");
                System.out.flush();

                StringBuilder inputBuilder = new StringBuilder();
                resetTabState();

                while (true) {
                    int code = inputReader.read();

                    if (code == -1) {
                        return;
                    }

                    char c = (char) code;

                    if (c == '\n' || c == '\r') {
                        System.out.print("\n");
                        System.out.flush();
                        resetTabState();
                        break;
                    } else if (c == '\t') {
                        handleTabCompletion(inputBuilder);
                    } else if (code == 127) {
                        if (inputBuilder.length() > 0) {
                            inputBuilder.deleteCharAt(inputBuilder.length() - 1);
                            System.out.print("\b \b");
                            System.out.flush();
                        }
                        resetTabState();
                    } else {
                        inputBuilder.append(c);
                        System.out.print(c);
                        System.out.flush();
                        resetTabState();
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
                } else if ("complete".equals(command)) {
                    executeComplete(parsed);
                } else if ("jobs".equals(command)) {
                    executeJobs(parsed);
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
        boolean isCommandPosition = lastSpace == -1;
        String prefix = isCommandPosition ? currentInput : currentInput.substring(lastSpace + 1);

        if (!isCommandPosition) {
            List<String> args = tokenize(currentInput.trim());
            if (!args.isEmpty()) {
                String commandName = args.get(0);
                String scriptPath = completionSpecs.get(commandName);

                if (scriptPath != null) {
                    String currentWord = currentInput.endsWith(" ") ? "" : prefix;

                    String previousWord;
                    if (currentInput.endsWith(" ")) {
                        previousWord = args.get(args.size() - 1);
                    } else {
                        previousWord = args.size() >= 2 ? args.get(args.size() - 2) : commandName;
                    }

                    List<String> scriptCandidates = runCompleterScript(
                            scriptPath,
                            commandName,
                            currentWord,
                            previousWord,
                            currentInput
                    );

                    if (scriptCandidates.isEmpty()) {
                        ringBell();
                        resetTabState();
                        return;
                    }

                    if (scriptCandidates.size() == 1) {
                        String replacement = scriptCandidates.get(0) + " ";
                        applyCompletion(inputBuilder, currentInput, lastSpace, prefix, replacement);
                        resetTabState();
                        return;
                    }

                    String lcp = longestCommonPrefixStrings(scriptCandidates);
                    if (lcp.length() > currentWord.length()) {
                        applyCompletion(inputBuilder, currentInput, lastSpace, prefix, lcp);
                        resetTabState();
                        return;
                    }

                    if (currentInput.equals(lastTabInput) && scriptCandidates.equals(lastTabDisplayOptions)) {
                        System.out.print("\n");
                        for (int i = 0; i < scriptCandidates.size(); i++) {
                            if (i > 0) {
                                System.out.print("  ");
                            }
                            System.out.print(scriptCandidates.get(i));
                        }
                        System.out.print("\n$ " + currentInput);
                        System.out.flush();
                        resetTabState();
                    } else {
                        ringBell();
                        lastTabInput = currentInput;
                        lastTabDisplayOptions = new ArrayList<>(scriptCandidates);
                    }
                    return;
                }
            }
        }

        List<CompletionMatch> matches = isCommandPosition
                ? findCommandMatches(prefix)
                : findArgumentMatches(prefix);

        if (matches.isEmpty()) {
            ringBell();
            resetTabState();
            return;
        }

        if (matches.size() == 1) {
            CompletionMatch match = matches.get(0);
            String replacement = match.value + (match.isDirectory ? "/" : " ");
            applyCompletion(inputBuilder, currentInput, lastSpace, prefix, replacement);
            resetTabState();
            return;
        }

        String commonPrefix = longestCommonPrefix(matches);
        if (commonPrefix.length() > prefix.length()) {
            applyCompletion(inputBuilder, currentInput, lastSpace, prefix, commonPrefix);
            resetTabState();
            return;
        }

        List<String> displayOptions = new ArrayList<>();
        for (CompletionMatch match : matches) {
            displayOptions.add(match.value + (match.isDirectory ? "/" : ""));
        }

        if (currentInput.equals(lastTabInput) && displayOptions.equals(lastTabDisplayOptions)) {
            System.out.print("\n");
            for (int i = 0; i < displayOptions.size(); i++) {
                if (i > 0) {
                    System.out.print("  ");
                }
                System.out.print(displayOptions.get(i));
            }
            System.out.print("\n$ " + currentInput);
            System.out.flush();
            resetTabState();
        } else {
            ringBell();
            lastTabInput = currentInput;
            lastTabDisplayOptions = new ArrayList<>(displayOptions);
        }
    }

    private static List<String> runCompleterScript(
            String scriptPath,
            String arg1,
            String arg2,
            String arg3,
            String fullCommandLine
    ) {
        List<String> candidates = new ArrayList<>();

        try {
            ProcessBuilder pb = new ProcessBuilder(scriptPath, arg1, arg2, arg3);
            pb.directory(currentDirectory.toFile());
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);

            Map<String, String> env = pb.environment();
            env.put("COMP_LINE", fullCommandLine);
            env.put("COMP_POINT", String.valueOf(fullCommandLine.getBytes(StandardCharsets.UTF_8).length));

            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isEmpty()) {
                        candidates.add(line);
                    }
                }
            }

            process.waitFor();
        } catch (Exception ignored) {
        }

        Collections.sort(candidates);
        return candidates;
    }

    private static void resetTabState() {
        lastTabInput = null;
        lastTabDisplayOptions = new ArrayList<>();
    }

    private static void ringBell() {
        System.out.print("\u0007");
        System.out.flush();
    }

    private static void applyCompletion(StringBuilder inputBuilder, String currentInput, int lastSpace, String prefix, String replacement) {
        String beforeToken = lastSpace == -1 ? "" : currentInput.substring(0, lastSpace + 1);

        for (int i = 0; i < prefix.length(); i++) {
            System.out.print("\b \b");
        }

        inputBuilder.setLength(0);
        inputBuilder.append(beforeToken).append(replacement);

        System.out.print(replacement);
        System.out.flush();
    }

    private static String longestCommonPrefix(List<CompletionMatch> matches) {
        if (matches.isEmpty()) return "";

        String prefix = matches.get(0).value;
        for (int i = 1; i < matches.size(); i++) {
            String current = matches.get(i).value;
            int j = 0;
            while (j < prefix.length() && j < current.length() && prefix.charAt(j) == current.charAt(j)) {
                j++;
            }
            prefix = prefix.substring(0, j);
            if (prefix.isEmpty()) break;
        }
        return prefix;
    }

    private static String longestCommonPrefixStrings(List<String> values) {
        if (values.isEmpty()) return "";

        String prefix = values.get(0);
        for (int i = 1; i < values.size(); i++) {
            String current = values.get(i);
            int j = 0;
            while (j < prefix.length() && j < current.length() && prefix.charAt(j) == current.charAt(j)) {
                j++;
            }
            prefix = prefix.substring(0, j);
            if (prefix.isEmpty()) break;
        }
        return prefix;
    }

    private static List<CompletionMatch> findCommandMatches(String prefix) {
        Set<String> seen = new LinkedHashSet<>();
        List<CompletionMatch> matches = new ArrayList<>();

        for (String builtin : BUILTINS) {
            if (builtin.startsWith(prefix) && seen.add(builtin)) {
                matches.add(new CompletionMatch(builtin, false));
            }
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv != null && !pathEnv.isEmpty()) {
            String[] directories = pathEnv.split(File.pathSeparator);
            for (String dir : directories) {
                File folder = new File(dir);
                if (!folder.exists() || !folder.isDirectory()) continue;

                File[] files = folder.listFiles();
                if (files == null) continue;

                for (File file : files) {
                    String name = file.getName();
                    if (file.isFile() && file.canExecute() && name.startsWith(prefix) && seen.add(name)) {
                        matches.add(new CompletionMatch(name, false));
                    }
                }
            }
        }

        Collections.sort(matches, (a, b) -> a.value.compareTo(b.value));
        return matches;
    }

    private static List<CompletionMatch> findArgumentMatches(String prefix) {
        List<CompletionMatch> matches = new ArrayList<>();

        String directoryPart = "";
        String namePrefix = prefix;

        int lastSlash = prefix.lastIndexOf('/');
        if (lastSlash >= 0) {
            directoryPart = prefix.substring(0, lastSlash + 1);
            namePrefix = prefix.substring(lastSlash + 1);
        }

        Path searchDir = directoryPart.isEmpty()
                ? currentDirectory
                : currentDirectory.resolve(directoryPart).normalize();

        File folder = searchDir.toFile();
        File[] files = folder.listFiles();
        if (files == null) return matches;

        for (File file : files) {
            String name = file.getName();
            if (name.startsWith(namePrefix)) {
                matches.add(new CompletionMatch(directoryPart + name, file.isDirectory()));
            }
        }

        Collections.sort(matches, (a, b) -> a.value.compareTo(b.value));
        return matches;
    }

    private static void executeEcho(ParsedCommand parsed) throws Exception {
        ensureBuiltinStderrTargetExists(parsed);
        PrintStream out = getStdoutStream(parsed);

        for (int i = 1; i < parsed.args.size(); i++) {
            if (i > 1) out.print(" ");
            out.print(parsed.args.get(i));
        }
        out.println();
        out.flush();

        if (out != System.out) out.close();
    }

    private static void executePwd(ParsedCommand parsed) throws Exception {
        ensureBuiltinStderrTargetExists(parsed);
        PrintStream out = getStdoutStream(parsed);
        out.println(currentDirectory);
        out.flush();

        if (out != System.out) out.close();
    }

    private static void executeType(ParsedCommand parsed) throws Exception {
        ensureBuiltinStderrTargetExists(parsed);
        PrintStream out = getStdoutStream(parsed);

        if (parsed.args.size() < 2) {
            if (out != System.out) out.close();
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
        if (out != System.out) out.close();
    }

    private static void executeComplete(ParsedCommand parsed) throws Exception {
        ensureBuiltinStderrTargetExists(parsed);
        PrintStream out = getStdoutStream(parsed);
        PrintStream err = getStderrStream(parsed);

        try {
            if (parsed.args.size() >= 4 && "-C".equals(parsed.args.get(1))) {
                completionSpecs.put(parsed.args.get(3), parsed.args.get(2));
                out.flush();
                return;
            }

            if (parsed.args.size() >= 3 && "-r".equals(parsed.args.get(1))) {
                completionSpecs.remove(parsed.args.get(2));
                out.flush();
                return;
            }

            if (parsed.args.size() >= 3 && "-p".equals(parsed.args.get(1))) {
                String commandName = parsed.args.get(2);
                String scriptPath = completionSpecs.get(commandName);

                if (scriptPath == null) {
                    err.println("complete: " + commandName + ": no completion specification");
                    err.flush();
                } else {
                    out.println("complete -C '" + scriptPath + "' " + commandName);
                    out.flush();
                }
                return;
            }

            out.flush();
        } finally {
            if (out != System.out) out.close();
            if (err != System.err) err.close();
        }
    }

    private static void executeJobs(ParsedCommand parsed) throws Exception {
        ensureBuiltinStderrTargetExists(parsed);
        PrintStream out = getStdoutStream(parsed);

        reapCompletedJobs(out, false);

        for (int i = 0; i < backgroundJobs.size(); i++) {
            BackgroundJob job = backgroundJobs.get(i);
            String marker = getJobMarker(i, backgroundJobs.size());
            out.println(formatJobLine(job.jobNumber, marker, "Running", job.commandLine));
        }

        out.flush();
        if (out != System.out) out.close();
    }

    private static void reapCompletedJobs(PrintStream out, boolean printDoneLines) {
        List<Integer> completedIndices = new ArrayList<>();

        for (int i = 0; i < backgroundJobs.size(); i++) {
            if (!backgroundJobs.get(i).process.isAlive()) {
                completedIndices.add(i);
            }
        }

        if (printDoneLines) {
            for (int idx : completedIndices) {
                BackgroundJob job = backgroundJobs.get(idx);
                String marker = getJobMarker(idx, backgroundJobs.size());
                out.println(formatJobLine(job.jobNumber, marker, "Done", stripTrailingAmpersand(job.commandLine)));
            }
            out.flush();
        }

        for (int i = completedIndices.size() - 1; i >= 0; i--) {
            backgroundJobs.remove((int) completedIndices.get(i));
        }
    }

    private static String getJobMarker(int index, int size) {
        if (size <= 0) return " ";
        if (size == 1) return "+";
        if (index == size - 1) return "+";
        if (index == size - 2) return "-";
        return " ";
    }

    private static String formatJobLine(int jobNumber, String marker, String status, String commandLine) {
        return "[" + jobNumber + "]" + marker + " " + status + " " + commandLine;
    }

    private static String stripTrailingAmpersand(String commandLine) {
        String s = commandLine.trim();
        if (s.endsWith("&")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
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
            if (!newPath.isAbsolute()) newPath = currentDirectory.resolve(newPath);
            newPath = newPath.normalize();

            if (Files.exists(newPath) && Files.isDirectory(newPath)) {
                currentDirectory = newPath;
            } else {
                err.println("cd: " + parsed.args.get(1) + ": No such file or directory");
                err.flush();
            }
        } finally {
            if (err != System.err) err.close();
        }
    }

    private static String getHomeDirectory() {
        String home = System.getenv("HOME");
        if (home != null && !home.isEmpty()) return home;
        return System.getProperty("user.home");
    }

    private static String findExecutable(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) return null;

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

            if (parsed.background) {
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

                pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

                Process process = pb.start();
                long pid = process.pid();
                int jobNumber = nextJobNumber++;
                String commandLine = parsed.originalCommandForJobs != null
                        ? parsed.originalCommandForJobs
                        : String.join(" ", parsed.args);

                backgroundJobs.add(new BackgroundJob(jobNumber, pid, process, commandLine));
                System.out.println("[" + jobNumber + "] " + pid);
                System.out.flush();
                return;
            }

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

            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

            Process process = pb.start();
            process.waitFor();
        } catch (Exception e) {
            System.out.println(parsed.args.get(0) + ": command not found");
        }
    }

    private static PrintStream getStdoutStream(ParsedCommand parsed) throws Exception {
        if (parsed.stdoutFile == null) return System.out;
        Path filePath = prepareFile(parsed.stdoutFile);
        return new PrintStream(new FileOutputStream(filePath.toFile(), parsed.stdoutAppend));
    }

    private static PrintStream getStderrStream(ParsedCommand parsed) throws Exception {
        if (parsed.stderrFile == null) return System.err;
        Path filePath = prepareFile(parsed.stderrFile);
        return new PrintStream(new FileOutputStream(filePath.toFile(), parsed.stderrAppend));
    }

    private static void ensureBuiltinStderrTargetExists(ParsedCommand parsed) throws Exception {
        if (parsed.stderrFile == null) return;
        Path filePath = prepareFile(parsed.stderrFile);
        FileOutputStream fos = new FileOutputStream(filePath.toFile(), parsed.stderrAppend);
        fos.close();
    }

    private static Path prepareFile(String file) throws Exception {
        Path filePath = resolvePath(file);
        File parent = filePath.toFile().getParentFile();
        if (parent != null) parent.mkdirs();
        if (!Files.exists(filePath)) Files.createFile(filePath);
        return filePath;
    }

    private static Path resolvePath(String file) {
        Path path = Paths.get(file);
        if (!path.isAbsolute()) path = currentDirectory.resolve(path);
        return path.normalize();
    }

    private static ParsedCommand parseCommand(List<String> tokens) {
        ParsedCommand parsed = new ParsedCommand();

        List<String> workingTokens = new ArrayList<>(tokens);
        if (!workingTokens.isEmpty() && "&".equals(workingTokens.get(workingTokens.size() - 1))) {
            parsed.background = true;
            workingTokens.remove(workingTokens.size() - 1);
            parsed.originalCommandForJobs = String.join(" ", workingTokens) + " &";
        }

        for (int i = 0; i < workingTokens.size(); i++) {
            String token = workingTokens.get(i);

            if (token.equals(">") || token.equals("1>")) {
                if (i + 1 < workingTokens.size()) {
                    parsed.stdoutFile = workingTokens.get(++i);
                    parsed.stdoutAppend = false;
                }
            } else if (token.equals(">>") || token.equals("1>>")) {
                if (i + 1 < workingTokens.size()) {
                    parsed.stdoutFile = workingTokens.get(++i);
                    parsed.stdoutAppend = true;
                }
            } else if (token.equals("2>")) {
                if (i + 1 < workingTokens.size()) {
                    parsed.stderrFile = workingTokens.get(++i);
                    parsed.stderrAppend = false;
                }
            } else if (token.equals("2>>")) {
                if (i + 1 < workingTokens.size()) {
                    parsed.stderrFile = workingTokens.get(++i);
                    parsed.stderrAppend = true;
                }
            } else {
                parsed.args.add(token);
            }
        }

        if (parsed.originalCommandForJobs == null) {
            parsed.originalCommandForJobs = String.join(" ", parsed.args);
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
                    if (ch == '\\' || ch == '"' || ch == '$' || ch == '\n') current.append(ch);
                    else current.append('\\').append(ch);
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

        if (escaping) current.append('\\');
        if (current.length() > 0) tokens.add(current.toString());

        return tokens;
    }

    private static class ParsedCommand {
        List<String> args = new ArrayList<>();
        String stdoutFile;
        boolean stdoutAppend;
        String stderrFile;
        boolean stderrAppend;
        boolean background;
        String originalCommandForJobs;
    }

    private static class CompletionMatch {
        String value;
        boolean isDirectory;

        CompletionMatch(String value, boolean isDirectory) {
            this.value = value;
            this.isDirectory = isDirectory;
        }
    }

    private static class BackgroundJob {
        int jobNumber;
        long pid;
        Process process;
        String commandLine;

        BackgroundJob(int jobNumber, long pid, Process process, String commandLine) {
            this.jobNumber = jobNumber;
            this.pid = pid;
            this.process = process;
            this.commandLine = commandLine;
        }
    }
}