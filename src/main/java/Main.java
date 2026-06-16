import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class Main {

    static class Job {
        int jobId;
        Process process;
        long pid;
        String command;

        Job(int jobId, Process process, long pid, String command) {
            this.jobId = jobId;
            this.process = process;
            this.pid = pid;
            this.command = command;
        }
    }

    private static final List<Job> activeJobs = new ArrayList<>();

    private static void sortJobs() {
        activeJobs.sort(Comparator.comparingInt(j -> j.jobId));
    }

    private static String getMarker(Job job) {
        int index = activeJobs.indexOf(job);
        if (index == -1) {
            return " ";
        }
        if (index == activeJobs.size() - 1) {
            return "+";
        }
        if (index == activeJobs.size() - 2) {
            return "-";
        }
        return " ";
    }

    private static void printJob(Job job, String marker, String status, PrintStream out) {
        String paddedStatus = String.format("%-24s", status);
        String cmdPart = job.command;
        if (status.equals("Running")) {
            cmdPart += " &";
        }
        out.printf("[%d]%s  %s%s%n", job.jobId, marker, paddedStatus, cmdPart);
    }

    private static void reapFinishedJobs() {
        sortJobs();
        List<Job> finished = new ArrayList<>();
        for (Job job : activeJobs) {
            if (!job.process.isAlive()) {
                finished.add(job);
            }
        }
        for (Job job : finished) {
            String marker = getMarker(job);
            printJob(job, marker, "Done", System.out);
            activeJobs.remove(job);
        }
    }

    private static void listJobs(PrintStream out) {
        sortJobs();
        List<Job> finished = new ArrayList<>();
        for (Job job : activeJobs) {
            String marker = getMarker(job);
            if (job.process.isAlive()) {
                printJob(job, marker, "Running", out);
            } else {
                printJob(job, marker, "Done", out);
                finished.add(job);
            }
        }
        activeJobs.removeAll(finished);
    }

    private static void executeBuiltin(List<String> cmdTokens, PrintStream out, PrintStream err, Path currpath, Set<String> builtinCommands) {
        String cmd = cmdTokens.getFirst();
        String firstArg = cmdTokens.size() > 1 ? cmdTokens.get(1) : null;
        switch (cmd) {
            case "exit" -> System.exit(0);
            case "echo" -> echo(cmdTokens, out);
            case "type" -> type(builtinCommands, firstArg, out);
            case "pwd" -> out.println(currpath.toAbsolutePath());
            case "cd" -> changeDirectory(firstArg, currpath, err);
            case "jobs" -> listJobs(out);
        }
    }

    private static void executePipeline(List<String> tokens, Path currpath, Set<String> builtinCommands) {
        List<List<String>> pipelineCmds = new ArrayList<>();
        List<String> currentCmd = new ArrayList<>();
        for (String token : tokens) {
            if (token.equals("|")) {
                if (!currentCmd.isEmpty()) {
                    pipelineCmds.add(currentCmd);
                    currentCmd = new ArrayList<>();
                }
            } else {
                currentCmd.add(token);
            }
        }
        if (!currentCmd.isEmpty()) {
            pipelineCmds.add(currentCmd);
        }

        int n = pipelineCmds.size();
        InputStream[] stageIns = new InputStream[n];
        OutputStream[] stageOuts = new OutputStream[n];

        String[] stdoutFiles = new String[n];
        boolean[] stdoutAppends = new boolean[n];
        String[] stderrFiles = new String[n];
        boolean[] stderrAppends = new boolean[n];
        List<List<String>> cleanCmdTokens = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            List<String> cmdTokens = pipelineCmds.get(i);
            String stdoutFile = null;
            boolean stdoutAppend = false;
            String stderrFile = null;
            boolean stderrAppend = false;
            List<String> filteredTokens = new ArrayList<>();

            for (int j = 0; j < cmdTokens.size(); j++) {
                String token = cmdTokens.get(j);
                if (token.equals(">") || token.equals("1>")) {
                    if (j + 1 < cmdTokens.size()) {
                        stdoutFile = cmdTokens.get(j + 1);
                        stdoutAppend = false;
                        j++;
                    }
                } else if (token.equals(">>") || token.equals("1>>")) {
                    if (j + 1 < cmdTokens.size()) {
                        stdoutFile = cmdTokens.get(j + 1);
                        stdoutAppend = true;
                        j++;
                    }
                } else if (token.equals("2>")) {
                    if (j + 1 < cmdTokens.size()) {
                        stderrFile = cmdTokens.get(j + 1);
                        stderrAppend = false;
                        j++;
                    }
                } else if (token.equals("2>>")) {
                    if (j + 1 < cmdTokens.size()) {
                        stderrFile = cmdTokens.get(j + 1);
                        stderrAppend = true;
                        j++;
                    }
                } else {
                    filteredTokens.add(token);
                }
            }
            stdoutFiles[i] = stdoutFile;
            stdoutAppends[i] = stdoutAppend;
            stderrFiles[i] = stderrFile;
            stderrAppends[i] = stderrAppend;
            cleanCmdTokens.add(filteredTokens);
        }

        boolean anyBuiltin = false;
        for (int i = 0; i < n; i++) {
            List<String> cmdTokens = cleanCmdTokens.get(i);
            if (!cmdTokens.isEmpty() && builtinCommands.contains(cmdTokens.getFirst())) {
                anyBuiltin = true;
                break;
            }
        }

        if (!anyBuiltin) {
            for (int i = 0; i < n; i++) {
                List<String> cmdTokens = cleanCmdTokens.get(i);
                if (cmdTokens.isEmpty()) continue;
                String cmd = cmdTokens.getFirst();
                if (getValidPath(cmd).isEmpty()) {
                    System.err.printf("%s: command not found%n", cmd);
                    return;
                }
            }

            List<ProcessBuilder> builders = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                List<String> cmdTokens = cleanCmdTokens.get(i);
                if (cmdTokens.isEmpty()) continue;

                ProcessBuilder pb = new ProcessBuilder(cmdTokens);
                pb.directory(currpath.toAbsolutePath().toFile());

                if (stderrFiles[i] != null) {
                    File file = new File(stderrFiles[i]);
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    if (stderrAppends[i]) {
                        pb.redirectError(ProcessBuilder.Redirect.appendTo(file));
                    } else {
                        pb.redirectError(ProcessBuilder.Redirect.to(file));
                    }
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                if (i == 0) {
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                }

                if (i == n - 1) {
                    if (stdoutFiles[i] != null) {
                        File file = new File(stdoutFiles[i]);
                        File parent = file.getParentFile();
                        if (parent != null && !parent.exists()) {
                            parent.mkdirs();
                        }
                        if (stdoutAppends[i]) {
                            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(file));
                        } else {
                            pb.redirectOutput(ProcessBuilder.Redirect.to(file));
                        }
                    } else {
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }
                }
                builders.add(pb);
            }

            try {
                List<Process> processes = ProcessBuilder.startPipeline(builders);
                if (!processes.isEmpty()) {
                    processes.getLast().waitFor();
                }
            } catch (IOException | InterruptedException e) {
                System.err.println(e.getMessage());
            }
            return;
        }

        for (int i = 0; i < n - 1; i++) {
            try {
                java.io.PipedOutputStream pos = new java.io.PipedOutputStream();
                java.io.PipedInputStream pis = new java.io.PipedInputStream(pos);
                stageOuts[i] = pos;
                stageIns[i+1] = pis;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        stageIns[0] = null;

        if (stdoutFiles[n-1] != null) {
            try {
                File file = new File(stdoutFiles[n-1]);
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                stageOuts[n-1] = new java.io.FileOutputStream(file, stdoutAppends[n-1]);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            stageOuts[n-1] = System.out;
        }

        List<Thread> threads = new ArrayList<>();
        List<Process> processes = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            final int index = i;
            List<String> cmdTokens = cleanCmdTokens.get(i);
            if (cmdTokens.isEmpty()) continue;

            String cmd = cmdTokens.getFirst();
            boolean isBuiltin = builtinCommands.contains(cmd);

            if (isBuiltin) {
                final OutputStream outStream = stageOuts[index];
                final PrintStream out = (outStream == System.out) ? System.out : new PrintStream(outStream);
                final PrintStream err = System.err;
                Thread t = new Thread(() -> {
                    try {
                        executeBuiltin(cmdTokens, out, err, currpath, builtinCommands);
                    } finally {
                        if (outStream != System.out) {
                            out.close();
                        }
                    }
                });
                t.setDaemon(true);
                threads.add(t);
                t.start();
            } else {
                Optional<Path> cmdPathOpt = getValidPath(cmd);
                if (cmdPathOpt.isEmpty()) {
                    System.err.printf("%s: command not found%n", cmd);
                    if (stageOuts[index] != null && stageOuts[index] != System.out) {
                        try {
                            stageOuts[index].close();
                        } catch (IOException e) {}
                    }
                    continue;
                }
                Path cmdPath = cmdPathOpt.get();

                ProcessBuilder pb = new ProcessBuilder(cmdTokens);
                pb.directory(new File(cmdPath.getParent().toString()));

                if (stderrFiles[index] != null) {
                    File file = new File(stderrFiles[index]);
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    if (stderrAppends[index]) {
                        pb.redirectError(ProcessBuilder.Redirect.appendTo(file));
                    } else {
                        pb.redirectError(ProcessBuilder.Redirect.to(file));
                    }
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                if (index == 0) {
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                } else {
                    pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                }

                if (index == n - 1) {
                    if (stdoutFiles[index] != null) {
                        File file = new File(stdoutFiles[index]);
                        if (stdoutAppends[index]) {
                            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(file));
                        } else {
                            pb.redirectOutput(ProcessBuilder.Redirect.to(file));
                        }
                    } else {
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                }

                try {
                    Process process = pb.start();
                    processes.add(process);

                    if (index > 0) {
                        final InputStream inPipe = stageIns[index];
                        final OutputStream procIn = process.getOutputStream();
                        Thread tIn = new Thread(() -> {
                            try (inPipe; procIn) {
                                inPipe.transferTo(procIn);
                            } catch (IOException e) {
                                // Pipe closed
                            }
                        });
                        tIn.setDaemon(true);
                        threads.add(tIn);
                        tIn.start();
                    }

                    if (index < n - 1) {
                        final InputStream procOut = process.getInputStream();
                        final OutputStream outPipe = stageOuts[index];
                        Thread tOut = new Thread(() -> {
                            try (procOut; outPipe) {
                                procOut.transferTo(outPipe);
                            } catch (IOException e) {
                                // Pipe closed
                            }
                        });
                        tOut.setDaemon(true);
                        threads.add(tOut);
                        tOut.start();
                    }

                } catch (IOException e) {
                    System.err.println(e.getMessage());
                }
            }
        }

        if (!processes.isEmpty()) {
            try {
                processes.getLast().waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Optional<Path> getValidPath(String input) {

        String[] path = Optional.ofNullable(System.getenv("PATH")).orElse("").split(":");
        Optional<Path> filePath;

        for (String dir : path) {

            filePath = getFile(input, dir);

            if (filePath.isPresent()) return filePath;
        }

        return Optional.empty();
    }

    private static Optional<Path> getFile(final String fileName, final String dir) {

        Path path = Paths.get(dir, fileName);

        return path.toFile().exists() && path.toFile().canExecute()
                ? Optional.of(path)
                : Optional.empty();
    }

    public static void executeScript(Path pathToScript, List<String> tokens,
                                     String stdoutFile, boolean stdoutAppend,
                                     String stderrFile, boolean stderrAppend,
                                     boolean isBackground, String commandStr,
                                     Path currpath) {

        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command(tokens);
        processBuilder.directory(currpath.toAbsolutePath().toFile());

        if (stdoutFile != null) {
            File file = new File(stdoutFile);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (stdoutAppend) {
                processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(file));
            } else {
                processBuilder.redirectOutput(ProcessBuilder.Redirect.to(file));
            }
        } else {
            processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        }

        if (stderrFile != null) {
            File file = new File(stderrFile);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (stderrAppend) {
                processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(file));
            } else {
                processBuilder.redirectError(ProcessBuilder.Redirect.to(file));
            }
        } else {
            processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
        }

        processBuilder.redirectInput(ProcessBuilder.Redirect.INHERIT);

        try {
            Process process = processBuilder.start();
            if (isBackground) {
                int jobId = 1;
                while (true) {
                    final int tempId = jobId;
                    if (activeJobs.stream().noneMatch(j -> j.jobId == tempId)) {
                        break;
                    }
                    jobId++;
                }
                long pid = process.pid();
                Job job = new Job(jobId, process, pid, commandStr);
                activeJobs.add(job);
                System.out.printf("[%d] %d%n", jobId, pid);
            } else {
                process.waitFor();
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {

        Set<String> builtinCommands = new HashSet<>();
        fillBuiltinCommands(builtinCommands);

        Path currpath = Paths.get("");
        Scanner scanner = new Scanner(System.in);

        while (true) {
            reapFinishedJobs();
            System.out.print("$ ");
            String input = scanner.nextLine();

            List<String> tokens = tokenize(input);
            if (tokens.isEmpty()) {
                continue;
            }

            if (tokens.contains("|")) {
                executePipeline(tokens, currpath, builtinCommands);
                continue;
            }

            String stdoutFile = null;
            boolean stdoutAppend = false;
            String stderrFile = null;
            boolean stderrAppend = false;

            List<String> cmdTokens = new ArrayList<>();
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);
                if (token.equals(">") || token.equals("1>")) {
                    if (i + 1 < tokens.size()) {
                        stdoutFile = tokens.get(i + 1);
                        stdoutAppend = false;
                        i++;
                    }
                } else if (token.equals(">>") || token.equals("1>>")) {
                    if (i + 1 < tokens.size()) {
                        stdoutFile = tokens.get(i + 1);
                        stdoutAppend = true;
                        i++;
                    }
                } else if (token.equals("2>")) {
                    if (i + 1 < tokens.size()) {
                        stderrFile = tokens.get(i + 1);
                        stderrAppend = false;
                        i++;
                    }
                } else if (token.equals("2>>")) {
                    if (i + 1 < tokens.size()) {
                        stderrFile = tokens.get(i + 1);
                        stderrAppend = true;
                        i++;
                    }
                } else {
                    cmdTokens.add(token);
                }
            }

            if (cmdTokens.isEmpty()) {
                continue;
            }

            boolean isBackground = false;
            String lastToken = cmdTokens.getLast();
            if (lastToken.equals("&")) {
                isBackground = true;
                cmdTokens.removeLast();
            } else if (lastToken.endsWith("&")) {
                isBackground = true;
                cmdTokens.set(cmdTokens.size() - 1, lastToken.substring(0, lastToken.length() - 1));
            }

            if (cmdTokens.isEmpty()) {
                continue;
            }

            String commandStr = String.join(" ", cmdTokens);

            String cmd = cmdTokens.getFirst();
            String firstArg = cmdTokens.size() > 1 ? cmdTokens.get(1) : null;

            java.io.PrintStream outStream = System.out;
            java.io.PrintStream errStream = System.err;
            java.io.FileOutputStream stdoutFos = null;
            java.io.FileOutputStream stderrFos = null;

            try {
                if (stdoutFile != null) {
                    File file = new File(stdoutFile);
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    stdoutFos = new java.io.FileOutputStream(file, stdoutAppend);
                    outStream = new java.io.PrintStream(stdoutFos);
                }
                if (stderrFile != null) {
                    File file = new File(stderrFile);
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    stderrFos = new java.io.FileOutputStream(file, stderrAppend);
                    errStream = new java.io.PrintStream(stderrFos);
                }

                switch (cmd) {
                    case "exit" -> System.exit(0);
                    case "echo" -> echo(cmdTokens, outStream);
                    case "type" -> type(builtinCommands, firstArg, outStream);
                    case "pwd" -> outStream.println(currpath.toAbsolutePath());
                    case "cd" -> currpath = changeDirectory(firstArg, currpath, errStream);
                    case "jobs" -> listJobs(outStream);
                    default -> execute(cmdTokens, stdoutFile, stdoutAppend, stderrFile, stderrAppend, isBackground, commandStr, currpath);
                }
            } catch (IOException e) {
                System.err.println(e.getMessage());
            } finally {
                if (stdoutFos != null) {
                    try {
                        stdoutFos.close();
                    } catch (IOException e) {}
                }
                if (stderrFos != null) {
                    try {
                        stderrFos.close();
                    } catch (IOException e) {}
                }
            }
        }
    }

    private static void echo(List<String> tokens, PrintStream out) {

        for (int i = 1; i < tokens.size(); i++) {
            out.print(tokens.get(i));
            if (i < tokens.size() - 1) out.print(" ");
        }

        out.println();
    }

    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder currentToken = new StringBuilder();
        boolean insideSingleQuote = false;
        boolean insideDoubleQuote = false;
        boolean hasTokenContent = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (insideSingleQuote) {
                if (c == '\'') {
                    insideSingleQuote = false;
                    hasTokenContent = true;
                } else {
                    currentToken.append(c);
                    hasTokenContent = true;
                }
            } else if (insideDoubleQuote) {
                if (c == '\"') {
                    insideDoubleQuote = false;
                    hasTokenContent = true;
                } else if (c == '\\') {
                    if (i + 1 < input.length()) {
                        char nextChar = input.charAt(i + 1);
                        if (nextChar == '\\' || nextChar == '\"' || nextChar == '$' || nextChar == '`') {
                            currentToken.append(nextChar);
                            i++;
                        } else {
                            currentToken.append(c);
                        }
                    } else {
                        currentToken.append(c);
                    }
                    hasTokenContent = true;
                } else {
                    currentToken.append(c);
                    hasTokenContent = true;
                }
            } else {
                if (c == '\'') {
                    insideSingleQuote = true;
                    hasTokenContent = true;
                } else if (c == '\"') {
                    insideDoubleQuote = true;
                    hasTokenContent = true;
                } else if (c == '\\') {
                    if (i + 1 < input.length()) {
                        currentToken.append(input.charAt(i + 1));
                        i++;
                    } else {
                        currentToken.append(c);
                    }
                    hasTokenContent = true;
                } else if (c == ' ') {
                    if (hasTokenContent || !currentToken.isEmpty()) {
                        tokens.add(currentToken.toString());
                        currentToken.setLength(0);
                        hasTokenContent = false;
                    }
                } else {
                    currentToken.append(c);
                    hasTokenContent = true;
                }
            }
        }

        if (hasTokenContent || !currentToken.isEmpty()) {
            tokens.add(currentToken.toString());
        }

        return tokens;
    }

    private static void fillBuiltinCommands(Set<String> builtinCommands) {
        builtinCommands.add("exit");
        builtinCommands.add("echo");
        builtinCommands.add("type");
        builtinCommands.add("pwd");
        builtinCommands.add("cd");
        builtinCommands.add("jobs");
    }

    private static void execute(List<String> tokens,
                                String stdoutFile, boolean stdoutAppend,
                                String stderrFile, boolean stderrAppend,
                                boolean isBackground, String commandStr,
                                Path currpath) {

        getValidPath(tokens.getFirst())
                .ifPresentOrElse(
                        path -> executeScript(path, tokens, stdoutFile, stdoutAppend, stderrFile, stderrAppend, isBackground, commandStr, currpath),
                        () -> System.err.printf("%s: command not found%n", tokens.getFirst()));
    }

    private static void type(Set<String> st, String argument, PrintStream out) {

        if (st.contains(argument)) {
            out.printf("%s is a shell builtin%n", argument);
            return;
        }

        Optional<Path> path = getValidPath(argument);

        if (path.isPresent()) {
            out.printf("%s is %s%n", argument, path.get());
            return;
        }

        out.printf("%s: not found%n", argument);
    }

    private static Path changeDirectory(String arguments, Path currpath, PrintStream out) {

        if (arguments == null) return currpath;
        if ("~".equals(arguments.strip())) arguments = System.getenv("HOME");

        Path newPath = currpath.resolve(arguments).toAbsolutePath().normalize();
        if (newPath.toFile().exists()) {
            currpath = newPath;
        } else {
            out.printf("cd: %s: No such file or directory%n", newPath.toAbsolutePath());
        }
        return currpath;
    }
}