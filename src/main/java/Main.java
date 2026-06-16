import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;

public class Main {
    private static final String HOME = "~";
    private static final String PATH = "PATH";
    private static Path pwd = Paths.get(System.getProperty("user.dir"));

    private static int nextJobNumber = 1;
    private static final Map<Integer, Process> activeProcesses = new LinkedHashMap<>();
    private static final Map<Integer, String> activeCommands = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            reapFinishedJobs();
            
            System.out.print("$ ");
            if (!scanner.hasNextLine()) {
                break;
            }

            String line = scanner.nextLine().trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.contains("|")) {
                runPipeline(line);
            } else {
                Command command = parse(line);
                run(command);
            }
        }
        scanner.close();
    }

    private static void runPipeline(String line) throws IOException, InterruptedException {
        String[] segments = line.split("\\|");
        if (segments.length != 2) {
            System.out.println("Error: Only dual-command pipelines are supported.");
            return;
        }

        List<String> cmd1Args = splitCommand(segments[0].trim());
        List<String> cmd2Args = splitCommand(segments[1].trim());

        if (cmd1Args.isEmpty() || cmd2Args.isEmpty()) return;

        String firstCmdName = cmd1Args.get(0);
        CommandName builtIn1 = CommandName.of(firstCmdName);

        if (builtIn1 != null) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream originalOut = System.out;
            System.setOut(new PrintStream(baos));

            try {
                String[] remainingArgs = cmd1Args.subList(1, cmd1Args.size()).toArray(new String[0]);
                Command mockBuiltIn = new Command(firstCmdName, remainingArgs, cmd1Args.toArray(new String[0]), null, "");
                run(mockBuiltIn);
            } finaly {
                System.setOut(originalOut);
            }

            ProcessBuilder pb2 = new ProcessBuilder(cmd2Args);
            pb2.redirectError(ProcessBuilder.Redirect.INHERIT);
            pb2.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            Process p2 = pb2.start();
            try (OutputStream os = p2.getOutputStream()) {
                os.write(baos.toByteArray());
                os.flush();
            }
            p2.waitFor();
        } else {
            ProcessBuilder pb1 = new ProcessBuilder(cmd1Args);
            ProcessBuilder pb2 = new ProcessBuilder(cmd2Args);

            pb1.redirectError(ProcessBuilder.Redirect.INHERIT);
            pb2.redirectError(ProcessBuilder.Redirect.INHERIT);
            pb2.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            List<Process> processes = ProcessBuilder.startPipeline(Arrays.asList(pb1, pb2));
            for (Process p : processes) {
                p.waitFor();
            }
        }
    }

    private static void reapFinishedJobs() {
        List<Integer> finishedJobIds = new ArrayList<>();

        for (Map.Entry<Integer, Process> entry : activeProcesses.entrySet()) {
            if (!entry.getValue().isAlive()) {
                finishedJobIds.add(entry.getKey());
            }
        }

        // Determine what the current (+) job would be before deleting finished ones
        int maxJobId = -1;
        for (Integer id : activeProcesses.keySet()) {
            if (id > maxJobId) {
                maxJobId = id;
            }
        }

        for (Integer id : finishedJobIds) {
            String originalCommand = activeCommands.get(id);
            // Matches the standard termination prefix format expected by the system
            char sign = (id == maxJobId) ? '+' : '-';
            System.out.printf("[%d]%c  Done                    %s\n", id, sign, originalCommand);
            activeProcesses.remove(id);
            activeCommands.remove(id);
        }
    }

    enum CommandName {
        exit, echo, type, pwd, cd, jobs;

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
        boolean isBackground = false;

        Command(String command, String[] args, String[] commandWithArgs, RedirectType redirectType, String redirectTo) {
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
        stdout, stderr, stdout_append, stderr_append
    }

    private enum QuteMode {
        singleQuote, doubleQuote
    }

    private static Command parse(String command) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command cannot be null or empty");
        }

        List<String> split = splitCommand(command);
        if (split.isEmpty()) {
            throw new IllegalArgumentException("command cannot be empty");
        }

        boolean background = false;
        if (split.get(split.size() - 1).equals("&")) {
            background = true;
            split.remove(split.size() - 1);
        }

        String[] splitArray = split.toArray(new String[0]);

        Command cmdObj;
        if (splitArray.length == 1) {
            cmdObj = new Command(split.get(0), new String[0], splitArray, null, "");
        } else {
            Redirect redirect = getRedirect(splitArray);
            int redirectAt = redirect.redirectAt;
            String[] args = Arrays.copyOfRange(splitArray, 1, redirectAt);
            String[] commandWithArgs = Arrays.copyOf(splitArray, redirectAt);
            String redirectTo = redirect.redirectType != null ? splitArray[redirectAt + 1] : "";

            cmdObj = new Command(split.get(0), args, commandWithArgs, redirect.redirectType, redirectTo);
        }
        
        cmdObj.isBackground = background;
        return cmdObj;
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
            case jobs:
                // Find the highest active job ID to flag with '+'
                int maxJobId = -1;
                for (Integer id : activeCommands.keySet()) {
                    if (id > maxJobId) {
                        maxJobId = id;
                    }
                }

                for (Map.Entry<Integer, String> entry : activeCommands.entrySet()) {
                    int id = entry.getKey();
                    char sign = (id == maxJobId) ? '+' : '-';
                    System.out.printf("[%d]%c  Running                 %s\n", id, sign, entry.getValue());
                }
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
                    Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    break;
                case stderr:
                    Files.write(path, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    System.out.println(message);
                    break;
                case stdout_append:
                    byte[] bytes2 = String.format("%s%n", message).getBytes();
                    Files.write(path, bytes2, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    break;
                case stderr_append:
                    Files.write(path, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
                System.out.printf("%s is %s\n", arg0, executable);
            } else {
                System.out.printf("%s: not found\n", arg0);
            }
        } else {
            System.out.printf("%s is a shell builtin\n", toType);
        }
    }

    private static void runNotBuiltin(Command command) throws IOException, InterruptedException {
        String executable = findExecutable(command.command);
        if (executable != null) {
            ProcessBuilder processBuilder = new ProcessBuilder(command.commandWithArgs);
            
            if (!command.isBackground) {
                processBuilder.redirectInput(ProcessBuilder.Redirect.INHERIT);
            }
            
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
                processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
            }

            Process process = processBuilder.start();
            
            if (command.isBackground) {
                long pid = process.pid();
                int currentJobId = nextJobNumber;
                
                System.out.println("[" + currentJobId + "] " + pid);
                
                String reconstructedCmd = String.join(" ", command.commandWithArgs) + " &";
                activeProcesses.put(currentJobId, process);
                activeCommands.put(currentJobId, reconstructedCmd);
                
                nextJobNumber++;
            } else {
                process.waitFor();
            }
        } else {
            String error = String.format("%s: command not found", command.command);
            System.out.println(error);
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