import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.completer.ArgumentCompleter;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class Main {
    private static String lastWord = "";
    private static boolean showedList = false;
    private static Path workingDir = Path.of("").toAbsolutePath().normalize();

    public static List<String> parseArgs(String command) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean isEscaped = false;

        for (char c : command.toCharArray()) {
            if (isEscaped) {
                if (inDoubleQuotes) {
                    if (c == '"' || c == '\\' || c == '$' || c == '`' || c == '\n') {
                        current.append(c);
                    }
                    else {
                        current.append("\\").append(c);
                    }
                }
                else {
                    current.append(c);
                }

                isEscaped = false;
                continue;
            }
            if (c == '\\') {
                if (inSingleQuotes) {
                    current.append(c);
                }
                else {
                    isEscaped = true;
                }
            }
            else if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
            }
            else if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
            }
            else if (c == ' ') {
                if (inSingleQuotes || inDoubleQuotes) {
                    current.append(c);
                }
                else if (!current.isEmpty()) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            }
            else  {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            args.add(current.toString());
        }
        return args;
    }

    public static Set<String> getAllExecs(String[] paths) {
        Set<String> execs = new HashSet<>();

        for (String dirPath : paths) {
            File directory = new File(dirPath);

            if (directory.exists() && directory.isDirectory()) {
                File[] files = directory.listFiles();

                if (files != null) {
                    for (File f : files) {
                        if (f.isFile() && f.canExecute()) {
                            execs.add(f.getName());
                        }
                    }
                }
            }
        }
        return execs;
    }

    private static Completer getFileCompleter() {
        return (reader, line, candidates) -> {
            String lastWord = line.word();

            Path searchDir;
            String prefix;
            String pathPart;

            if (lastWord.contains("/")) {
                int lastSlash = lastWord.lastIndexOf("/");
                pathPart = lastWord.substring(0, lastSlash + 1);
                prefix = lastWord.substring(lastSlash + 1);
                searchDir = workingDir.resolve(pathPart).normalize();
            }
            else {
                pathPart = "";
                prefix = lastWord;
                searchDir = workingDir;
            }

            if (Files.exists(searchDir) && Files.isDirectory(searchDir)) {
                try (var stream = Files.newDirectoryStream(searchDir)) {
                    for (Path path : stream) {
                        String fileName = path.getFileName().toString();

                        if (fileName.startsWith(prefix)) {
                            boolean isDir = Files.isDirectory(path);
                            String fullPath = pathPart + fileName + (isDir ? "/" : "");

                            candidates.add(new Candidate(fullPath, fileName + (isDir ? "/" : ""),
                                    null, null, null, null, !isDir));
                        }
                    }
                } catch (IOException ignored) {}
            }
        };
    }

    private static Completer getBellCompleter(Set<String> allCommands, Terminal terminal) {
        Completer commandCompleter = new StringsCompleter(allCommands);
        Completer fileCompleter = getFileCompleter();
        Completer mergedCompleter = new ArgumentCompleter(commandCompleter, fileCompleter);
        return (reader, line, candidates) -> {
            int sizeBefore = candidates.size();
            mergedCompleter.complete(reader, line, candidates);
            int matchesFound = candidates.size() - sizeBefore;
            String currentWord = line.word();

            if (matchesFound > 1) {
                if (currentWord.equals(lastWord) && !showedList) {
                    List<String> matches = new ArrayList<>();

                    for (int i = sizeBefore; i < candidates.size(); i++) {
                        String candidate = candidates.get(i).value();

                        if (candidate.startsWith(currentWord)) {
                            matches.add(candidate);
                        }
                    }

                    Collections.sort(matches);
                    terminal.writer().println();
                    terminal.writer().println(String.join("  ", matches));
                    terminal.flush();
                    candidates.clear();
                    reader.callWidget(LineReader.REDRAW_LINE);
                    reader.callWidget(LineReader.REDISPLAY);
                    showedList = true;
                }
                else {
                    terminal.puts(InfoCmp.Capability.bell);
                    terminal.flush();
                    showedList = false;
                }
            }
            else if (matchesFound == 0 && !currentWord.isEmpty()) {
                terminal.puts(InfoCmp.Capability.bell);
                terminal.flush();
                showedList = false;
            }
            else {
                showedList = false;
            }
            lastWord = currentWord;
        };
    }

    private static void doChain(List<String> parts) throws IOException, InterruptedException {
        List<ProcessBuilder> pipes = new ArrayList<>();

        for (int i = 0; i < parts.size(); i++) {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", parts.get(i)).directory(workingDir.toFile());

            if (i == 0) {
                pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            }
            if (i == parts.size() - 1) {
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }

            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            pipes.add(pb);
        }

        List<Process> processes = ProcessBuilder.startPipeline(pipes);
        processes.getLast().waitFor();
    }

    public static void main(String[] args) throws Exception {
        Terminal terminal = TerminalBuilder.builder().system(true).build();
        String[] paths = System.getenv("PATH").split(File.pathSeparator);
        List<String> builtins = List.of("exit", "echo", "type", "pwd", "cd");
        Set<String> allCommands = new HashSet<>(builtins);
        allCommands.addAll(getAllExecs(paths));
        Completer bellCompleter = getBellCompleter(allCommands, terminal);
        DefaultParser parser = new DefaultParser();
        parser.setQuoteChars(null);
        parser.setEscapeChars(null);
        parser.setEofOnUnclosedQuote(false);
        parser.setEofOnEscapedNewLine(false);

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .parser(parser)
                .completer(bellCompleter)
                .option(LineReader.Option.AUTO_LIST, false)
                .option(LineReader.Option.AUTO_MENU, false)
                .build();

        while (true) {
            String command = reader.readLine("$ ");
            List<String> parts = new ArrayList<>(List.of(command.split("\\|")));

            if (parts.size() > 1) {
                doChain(parts);
                continue;
            }
            else {
                parts = parseArgs(command);
            }

            String commandName = parts.getFirst();
            String commandArg = parts.size() > 1 ? parts.get(1) : null;
            PrintStream out = System.out;
            PrintStream err = System.err;
            Set<String> operators = new HashSet<>(Arrays.asList(">", "1>", ">>", "1>>", "2>", "2>>"));
            String redirOperator = null;
            String fileName = null;
            int operatorIndex = -1;

            for (int i = parts.size() - 1; i >= 0; i--) {
                String currentPart = parts.get(i);

                if (operators.contains(currentPart)) {
                    redirOperator = currentPart;
                    operatorIndex = i;
                    fileName = parts.get(i + 1);
                    break;
                }
            }

            if (redirOperator != null) {
                parts.remove(operatorIndex + 1);
                parts.remove(operatorIndex);

                File file = workingDir.resolve(fileName).normalize().toFile();
                boolean append = redirOperator.equals(">>") || redirOperator.equals("1>>") || redirOperator.equals("2>>");
                PrintStream fileOutput = new PrintStream(new FileOutputStream(file, append));

                if (redirOperator.startsWith("2")) {
                    err = fileOutput;
                }
                else {
                    out = fileOutput;
                }
            }

            if (commandName.equals("exit")) {
                break;
            }
            else if (command.startsWith("echo ")) {
                StringBuilder echo = new StringBuilder();

                for (int i = 1; i < parts.size(); i++) {
                    echo.append(parts.get(i));

                    if (i != parts.size() - 1) {
                        echo.append(' ');
                    }
                }
                out.println(echo);
            }
            else if (commandName.equals("type") && commandArg != null) {
                if (builtins.contains(commandArg)) {
                    out.println(commandArg + " is a shell builtin");
                }
                else {
                    boolean found = false;

                    for (String s : paths) {
                        Path path = Path.of(s, commandArg);

                        if (Files.exists(path) && Files.isExecutable(path)) {
                            out.println(commandArg + " is " + path);
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        err.println(commandArg + ": not found");
                    }
                }
            }
            else if (commandName.equals("pwd")) {
                out.println(workingDir);
            }
            else if (commandName.equals("cd") && commandArg != null) {
                Path path = workingDir.resolve(commandArg.equals("~") ? System.getenv("HOME") : commandArg).normalize();

                if (Files.exists(path)) {
                    workingDir = path;
                }
                else {
                    err.println("cd: " + commandArg + ": No such file or directory");
                }
            }
            else {
                boolean found = false;

                for (String s : paths) {
                    Path path = Path.of(s, commandName);

                    if (Files.exists(path) && Files.isExecutable(path)) {
                        ProcessBuilder pb = new ProcessBuilder(parts).directory(workingDir.toFile())
                                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                                .redirectError(ProcessBuilder.Redirect.INHERIT);
                        found = true;

                        if (redirOperator != null) {
                            File targetFile = workingDir.resolve(fileName).normalize().toFile();

                            switch (redirOperator) {
                                case ">", "1>" -> pb.redirectOutput(targetFile);
                                case ">>", "1>>" -> pb.redirectOutput(ProcessBuilder.Redirect.appendTo(targetFile));
                                case "2>" -> pb.redirectError(targetFile);
                                case "2>>" -> pb.redirectError(ProcessBuilder.Redirect.appendTo(targetFile));
                            }
                        }

                        Process process = pb.start();
                        process.waitFor();
                        break;
                    }
                }
                if (!found) {
                    err.println(commandName + ": not found");
                }
            }

            if (out != System.out) out.close();
            if (err != System.err) err.close();
        }
    }
}