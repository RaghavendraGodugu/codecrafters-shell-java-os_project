import org.jline.keymap.KeyMap;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class Main {
    private static final String HOME = "~";
    private static final String PATH = "PATH";
    private static final String PROMPT = "$ ";
    private static Path pwd = Paths.get(System.getProperty("user.dir"));
    private static String lastAmbiguousPrefix = null;
    private static List<String> lastAmbiguousMatches = new ArrayList<String>();

    public static void main(String[] args) throws Exception {
        Terminal terminal = TerminalBuilder.builder().system(true).build();
        DefaultParser parser = new DefaultParser();
        parser.setEscapeChars(new char[0]);
        LineReader lineReader =
                LineReaderBuilder.builder()
                        .terminal(terminal)
                        .parser(parser)
                        .build();
        configureTabCompletion(lineReader);

        while (true) {
            resetTabState();
            String line = lineReader.readLine(PROMPT);
            if (line != null && !line.isEmpty()) {
                if (line.contains("|")) {
                    runPipeline(line);
                } else {
                    Command command = parse(line);
                    run(command);
                }
            }
        }
    }

    private static void configureTabCompletion(LineReader lineReader) {
        KeyMap mainKeyMap = lineReader.getKeyMaps().get(LineReader.MAIN);
        mainKeyMap.bind(new Reference("my-complete"), "\t");
        lineReader.getWidgets().put(
                "my-complete",
                () -> {
                    handleTab(lineReader);
                    return true;
                });
    }

    private static void resetTabState() {
        lastAmbiguousPrefix = null;
        lastAmbiguousMatches = new ArrayList<String>();
    }

    private static void handleTab(LineReader lineReader) {
        String buffer = lineReader.getBuffer().toString();
        int cursor = lineReader.getBuffer().cursor();
        if (cursor != buffer.length()) {
            return;
        }
        String prefix = buffer;
        if (prefix.length() == 0) {
            beep(lineReader);
            resetTabState();
            return;
        }

        List<String> matches = getCommandCompletions(prefix);
        if (matches.isEmpty()) {
            beep(lineReader);
            resetTabState();
            return;
        }

        if (matches.size() == 1) {
            String candidate = matches.get(0);
            String newBuffer = candidate + " ";
            lineReader.getBuffer().clear();
            lineReader.getBuffer().write(newBuffer);
            resetTabState();
            return;
        }

        String lcp = longestCommonPrefix(matches);
        if (lcp.length() > prefix.length()) {
            lineReader.getBuffer().clear();
            lineReader.getBuffer().write(lcp);
            resetTabState();
            return;
        }

        if (prefix.equals(lastAmbiguousPrefix) && matches.equals(lastAmbiguousMatches)) {
            String list = joinWithDoubleSpace(matches);
            var writer = lineReader.getTerminal().writer();
            writer.write(System.lineSeparator());
            writer.write(list);
            writer.write(System.lineSeparator());
            writer.write(PROMPT);
            writer.write(prefix);
            writer.flush();
            resetTabState();
            return;
        }

        beep(lineReader);
        lastAmbiguousPrefix = prefix;
        lastAmbiguousMatches = matches;
    }

    private static void beep(LineReader lineReader) {
        lineReader.getTerminal().writer().write("\007");
        lineReader.getTerminal().writer().flush();
    }

    private static List<String> getCommandCompletions(String prefix) {
        List<String> result = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();

        for (CommandName name : CommandName.values()) {
            String cmd = name.name();
            if (cmd.startsWith(prefix)) {
                result.add(cmd);
                seen.add(cmd);
            }
        }

        String pathEnv = System.getenv(PATH);
        if (pathEnv != null && !pathEnv.isEmpty()) {
            String[] directories = pathEnv.split(System.getProperty("path.separator"));
            for (String dir : directories) {
                if (dir == null || dir.isEmpty()) {
                    continue;
                }
                Path dirPath = Paths.get(dir);
                if (!Files.isDirectory(dirPath)) {
                    continue;
                }
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dirPath)) {
                    for (Path p : stream) {
                        if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                            String name = p.getFileName().toString();
                            if (name.startsWith(prefix) && !seen.contains(name)) {
                                result.add(name);
                                seen.add(name);
                            }
                        }
                    }
                } catch (IOException e) {
                }
            }
        }

        Collections.sort(result);
        return result;
    }

    private static String longestCommonPrefix(List<String> strings) {
        if (strings.isEmpty()) {
            return "";
        }
        String prefix = strings.get(0);
        for (int i = 1; i < strings.size(); i++) {
            String s = strings.get(i);
            int j = 0;
            int max = Math.min(prefix.length(), s.length());
            while (j < max && prefix.charAt(j) == s.charAt(j)) {
                j++;
            }
            prefix = prefix.substring(0, j);
            if (prefix.isEmpty()) {
                break;
            }
        }
        return prefix;
    }

    private static String joinWithDoubleSpace(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append("  ");
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static void runPipeline(String line) throws IOException, InterruptedException {
        // Split the pipeline string into two distinct segments
        String[] segments = line.split("\\|");
        if (segments.length != 2) {
            System.out.println("Error: Only dual-command pipelines are supported.");
            return;
        }

        // Leverage your existing string parsing mechanism to handle nested quotes correctly
        List<String> cmd1Args = splitCommand(segments[0].trim());
        List<String> cmd2Args = splitCommand(segments[1].trim());

        ProcessBuilder pb1 = new ProcessBuilder(cmd1Args);
        ProcessBuilder pb2 = new ProcessBuilder(cmd2Args);

        // Standard error configuration
        pb1.redirectError(ProcessBuilder.Redirect.INHERIT);
        pb2.redirectError(ProcessBuilder.Redirect.INHERIT);
        
        // Pass stdout of the final command directly to your interactive console output
        pb2.redirectOutput(ProcessBuilder.Redirect.INHERIT);

        // Native OS process pipeline connection setup
        List<Process> processes = ProcessBuilder.startPipeline(Arrays.asList(pb1, pb2));

        // Sync execution flow until both pipe operations finish up entirely
        for (Process p : processes) {
            p.waitFor();
        }
    }

    enum CommandName {
        exit,
        echo,
        type,
        pwd,
        cd;

        static CommandName of(String name) {
            try {
                return valueOf(name);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    static class Command {
        final String command;
        final String[] args;
        final String[] commandWithArgs;
        final RedirectType redirectType;
        final String redirectTo;

        Command(
                String command,
                String[] args,
                String[] commandWithArgs,
                RedirectType redirectType,
                String redirectTo) {
            this.command = command;
            this.args = args;
            this.commandWithArgs = commandWithArgs;
            this.redirectType = redirectType;
            this.redirectTo = redirectTo;
        }
    }

    static class Redirect {
        final RedirectType redirectType;
        final int redirectAt;

        Redirect(RedirectType redirectType, int redirectAt) {
            this.redirectType = redirectType;
            this.redirectAt = redirectAt;
        }
    }

    private enum RedirectType {
        stdout,
        stderr,
        stdout_append,
        stderr_append
    }

    private enum QuteMode {
        singleQuote,
        doubleQuote
    }

    private static Command parse(String command) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command cannot be null or empty");
        }

        List<String> split = splitCommand(command);
        if (split.isEmpty()) {
            throw new IllegalArgumentException("command cannot be empty");
        }

        String[] splitArray = split.toArray(new String[0]);

        if (splitArray.length == 1) {
            return new Command(split.get(0), new String[0], splitArray, null, "");
        }

        Redirect redirect = getRedirect(splitArray);
        int redirectAt = redirect.redirectAt;
        String[] args = Arrays.copyOfRange(splitArray, 1, redirectAt);
        String[] commandWithArgs = Arrays.copyOf(splitArray, redirectAt);
        String redirectTo = redirect.redirectType != null ? splitArray[redirectAt + 1] : "";

        return new Command(split.get(0), args, commandWithArgs, redirect.redirectType, redirectTo);
    }

    private static Redirect getRedirect(String[] split) {
        int redirectAt = split.length;
        RedirectType type = null;
        for (int i = 0; i < split.length; i++) {
            String s = split[i];
            if (s.equals(">") || s.equals("1>")) {
                redirectAt = i;
                type = RedirectType.stdout;
                break;
            }
            if (s.equals("2>")) {
                redirectAt = i;
                type = RedirectType.stderr;
                break;
            }
            if (s.equals(">>") || s.equals("1>>")) {
                redirectAt = i;
                type = RedirectType.stdout_append;
                break;
            }
            if (s.equals("2>>")) {
                redirectAt = i;
                type = RedirectType.stderr_append;
                break;
            }
        }
        return new Redirect(type, redirectAt);
    }

    private static List<String> splitCommand(String command) {
        List<String> result = new ArrayList<String>();
        StringBuilder temp = new StringBuilder();
        QuteMode quteMode = null;
        boolean escape = false;

        for (char ch : command.toCharArray()) {
            if (quteMode == QuteMode.singleQuote) {
                if (ch == '\'') {
                    quteMode = null;
                } else {
                    temp.append(ch);
                }
            } else if (quteMode == QuteMode.doubleQuote) {
                if (escape) {
                    if (ch != '"' && ch != '\\' && ch != '$' && ch != '`') {
                        temp.append('\\');
                    }
                    temp.append(ch);
                    escape = false;
                } else {
                    if (ch == '"') {
                        quteMode = null;
                    } else if (ch == '\\') {
                        escape = true;
                    } else {
                        temp.append(ch);
                    }
                }
            } else {
                if (escape) {
                    temp.append(ch);
                    escape = false;
                } else {
                    if (ch == '\'') {
                        quteMode = QuteMode.singleQuote;
                    } else if (ch == '"') {
                        quteMode = QuteMode.doubleQuote;
                    } else if (ch == ' ') {
                        addTemp(result, temp);
                    } else if (ch == '\\') {
                        escape = true;
                    } else {
                        temp.append(ch);
                    }
                }
            }
        }

        if (quteMode != null) {
            throw new IllegalArgumentException("Unclosed quote.");
        }

        addTemp(result, temp);
        return result;
    }

    private static void addTemp(List<String> result, StringBuilder temp) {
        if (temp.length() > 0) {
            result.add(temp.toString());
            temp.setLength(0);
        }
    }

    private static void run(Command command) throws IOException, InterruptedException {
        CommandName commandName = CommandName.of(command.command);

        if (Objects.isNull(commandName)) {
            runNotBuiltin(command);
            return;
        }

        switch (commandName) {
            case exit:
                int status = 0;
                if (command.args.length != 0) {
                    status = Integer.parseInt(command.args[0]);
                }
                System.exit(status);
                break;
            case echo:
                runEcho(command);
                break;
            case type:
                runType(command);
                break;
            case pwd:
                System.out.println(pwd);
                break;
            case cd:
                runCd(command);
                break;
        }
    }

    private static void runEcho(Command command) throws IOException {
        String message = String.join(" ", command.args);
        if (command.redirectType != null) {
            Path path = Paths.get(command.redirectTo);
            switch (command.redirectType) {
                case stdout:
                    byte[] bytes = String.format("%s%n", message).getBytes();
                    Files.write(
                            path,
                            bytes,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                    break;
                case stderr:
                    Files.write(
                            path,
                            new byte[0],
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                    System.out.println(message);
                    break;
                case stdout_append:
                    byte[] bytes2 = String.format("%s%n", message).getBytes();
                    Files.write(path, bytes2, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    break;
                case stderr_append:
                    Files.write(
                            path,
                            new byte[0],
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                    System.out.println(message);
                    break;
            }
        } else {
            System.out.println(message);
        }
    }

    private static void runCd(Command command) {
        if (command.args.length == 0) {
            return;
        }
        String targetPath = command.args[0];
        String separator = System.getProperty("file.separator");
        if (targetPath.equals(HOME) || targetPath.startsWith(HOME + separator)) {
            String homeDir = System.getenv("HOME");
            if (homeDir != null) {
                targetPath = targetPath.replaceFirst(HOME, homeDir);
            }
        }

        Path newPath = pwd.resolve(targetPath).normalize();
        if (!Files.isDirectory(newPath)) {
            String error = String.format("cd: %s: No such file or directory", newPath);
            System.out.println(error);
        } else {
            pwd = newPath;
        }
    }

    private static void runNotBuiltin(Command command) throws IOException, InterruptedException {
        String executable = findExecutable(command.command);
        if (executable != null) {
            ProcessBuilder processBuilder = new ProcessBuilder(command.commandWithArgs);
            RedirectType redirectType = command.redirectType;
            if (redirectType != null) {
                File file = Paths.get(command.redirectTo).toFile();
                switch (redirectType) {
                    case stdout:
                        processBuilder.redirectOutput(file);
                        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
                        break;
                    case stderr:
                        processBuilder.redirectError(file);
                        processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        break;
                    case stdout_append:
                        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(file));
                        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
                        break;
                    case stderr_append:
                        processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(file));
                        processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        break;
                }
            } else {
                processBuilder.inheritIO();
            }
            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
            }
        } else {
            String error = String.format("%s: command not found", command.command);
            System.out.println(error);
        }
    }

    private static void runType(Command command) {
        if (command.args.length == 0) {
            System.out.println("type command requires an argument");
            return;
        }
        String arg0 = command.args[0];
        CommandName toType = CommandName.of(arg0);
        if (toType == null) {
            String executable = findExecutable(arg0);
            if (executable != null) {
                String message = String.format("%s is %s", arg0, executable);
                System.out.println(message);
            } else {
                String error = String.format("%s: not found", arg0);
                System.out.println(error);
            }
        } else {
            String message = String.format("%s is a shell builtin", toType);
            System.out.println(message);
        }
    }

    private static String findExecutable(String commandName) {
        String pathEnv = System.getenv(PATH);
        if (pathEnv == null || pathEnv.isEmpty()) {
            return null;
        }
        String[] directories = pathEnv.split(System.getProperty("path.separator"));

        for (String dir : directories) {
            if (dir == null || dir.isEmpty()) {
                continue;
            }
            Path filePath = Paths.get(dir, commandName);
            if (Files.isExecutable(filePath) && Files.isRegularFile(filePath)) {
                return filePath.toAbsolutePath().toString();
            }
        }

        return null;
    }
}