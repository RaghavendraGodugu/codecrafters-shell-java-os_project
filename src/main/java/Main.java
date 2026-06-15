import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Main {
    public static void main(String[] args) throws Exception {
        new Shell().run();
    }
}

class Shell {
    private static final Set<String> BUILT_INS =
            Set.of("exit", "echo", "type", "pwd", "cd", "jobs");

    private Path currentDirectory = Paths.get(System.getProperty("user.dir"));
    private final List<BackgroundJob> backgroundJobs = new ArrayList<>();
    private int nextJobNumber = 1;

    public void run() throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            reapFinishedJobs();

            System.out.print("$ ");
            System.out.flush();

            if (!scanner.hasNextLine()) {
                break;
            }

            String input = scanner.nextLine();
            if (input == null || input.trim().isEmpty()) {
                continue;
            }

            input = input.trim();

            if (isBackgroundCommand(input)) {
                executeBackgroundInput(input);
            } else {
                List<String> pipelineParts = splitByPipe(input);
                if (pipelineParts.size() == 2) {
                    executePipeline(pipelineParts.get(0), pipelineParts.get(1));
                } else {
                    executeSingleCommand(parseCommand(input));
                }
            }
        }
    }

    private boolean isBackgroundCommand(String input) {
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean escaping = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            if (escaping) {
                escaping = false;
                continue;
            }

            if (ch == '\\' && !inSingleQuotes) {
                escaping = true;
                continue;
            }

            if (ch == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                continue;
            }

            if (ch == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
            }
        }

        return !inSingleQuotes && !inDoubleQuotes && input.endsWith("&");
    }

    private void executeBackgroundInput(String input) throws Exception {
        String commandText = input.substring(0, input.lastIndexOf('&')).trim();
        if (commandText.isEmpty()) {
            return;
        }

        List<String> command = parseCommand(commandText);
        if (command.isEmpty()) {
            return;
        }

        String cmd = command.get(0);

        if (BUILT_INS.contains(cmd) && !cmd.equals("jobs")) {
            executeSingleCommand(command);
            return;
        }

        Process process = startExternalProcess(command, null);

        BackgroundJob job = new BackgroundJob(
                nextJobNumber++,
                process,
                commandText,
                commandText + " &"
        );
        backgroundJobs.add(job);

        System.out.printf("[%d] %d%n", job.jobNumber, process.pid());

        pipeAsync(process.getInputStream(), System.out, false);
        pipeAsync(process.getErrorStream(), System.err, false);
    }

    private void reapFinishedJobs() {
        Iterator<BackgroundJob> iterator = backgroundJobs.iterator();
        while (iterator.hasNext()) {
            BackgroundJob job = iterator.next();
            if (!job.process.isAlive() && !job.notifiedDone) {
                String marker = getJobMarker(job);
                System.out.printf("[%d]%s  %-23s %s%n",
                        job.jobNumber,
                        marker,
                        "Done",
                        job.commandWithoutAmpersand);
                job.notifiedDone = true;
            }
        }
    }

    private String getJobMarker(BackgroundJob targetJob) {
        int size = backgroundJobs.size();
        int index = backgroundJobs.indexOf(targetJob);

        // If only one job, it's the most recent => "+"
        if (size == 1) {
            return "+";
        }

        // Most recent job => "+"
        if (index == size - 1) {
            return "+";
        }

        // Job just before most recent => "-"
        if (index == size - 2) {
            return "-";
        }

        // Older jobs => " "
        return " ";
    }

    private List<String> splitByPipe(String input) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean escaping = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            if (escaping) {
                current.append(ch);
                escaping = false;
                continue;
            }

            if (ch == '\\' && !inSingleQuotes) {
                escaping = true;
                current.append(ch);
                continue;
            }

            if (ch == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                current.append(ch);
                continue;
            }

            if (ch == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
                current.append(ch);
                continue;
            }

            if (ch == '|' && !inSingleQuotes && !inDoubleQuotes) {
                parts.add(current.toString().trim());
                current.setLength(0);
                continue;
            }

            current.append(ch);
        }

        parts.add(current.toString().trim());
        return parts;
    }

    private List<String> parseCommand(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean escaping = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            if (escaping) {
                if (inDoubleQuotes && (ch == '\\' || ch == '"' || ch == '$' || ch == '\n')) {
                    current.append(ch);
                } else if (!inSingleQuotes) {
                    current.append(ch);
                } else {
                    current.append('\\').append(ch);
                }
                escaping = false;
                continue;
            }

            if (ch == '\\' && !inSingleQuotes) {
                escaping = true;
                continue;
            }

            if (ch == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                continue;
            }

            if (ch == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
                continue;
            }

            if (Character.isWhitespace(ch) && !inSingleQuotes && !inDoubleQuotes) {
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

    private void executeSingleCommand(List<String> command) throws Exception {
        if (command.isEmpty()) {
            return;
        }

        String cmd = command.get(0);

        switch (cmd) {
            case "exit":
                if (command.size() > 1 && command.get(1).equals("0")) {
                    System.exit(0);
                }
                return;

            case "echo":
                System.out.println(String.join(" ", command.subList(1, command.size())));
                return;

            case "pwd":
                System.out.println(currentDirectory);
                return;

            case "cd":
                handleCd(command);
                return;

            case "type":
                handleType(command);
                return;

            case "jobs":
                handleJobs();
                return;

            default:
                executeExternal(command, null, System.out);
        }
    }

    private void handleCd(List<String> command) {
        String target;
        if (command.size() < 2 || command.get(1).equals("~")) {
            target = System.getProperty("user.home");
        } else {
            target = command.get(1);
        }

        Path newPath = Paths.get(target);
        if (!newPath.isAbsolute()) {
            newPath = currentDirectory.resolve(newPath);
        }

        newPath = newPath.normalize();

        if (Files.exists(newPath) && Files.isDirectory(newPath)) {
            currentDirectory = newPath;
        } else {
            System.out.printf("cd: %s: No such file or directory%n", target);
        }
    }

    private void handleType(List<String> command) {
        if (command.size() < 2) {
            return;
        }

        String target = command.get(1);

        if (BUILT_INS.contains(target)) {
            System.out.printf("%s is a shell builtin%n", target);
            return;
        }

        String path = findExecutable(target);
        if (path != null) {
            System.out.printf("%s is %s%n", target, path);
        } else {
            System.out.printf("%s: not found%n", target);
        }
    }

    private void handleJobs() {
        Iterator<BackgroundJob> iterator = backgroundJobs.iterator();
        while (iterator.hasNext()) {
            BackgroundJob job = iterator.next();

            String marker = getJobMarker(job);
            String status = job.process.isAlive() ? "Running" : "Done";
            String commandText = job.process.isAlive()
                    ? job.commandWithAmpersand
                    : job.commandWithoutAmpersand;

            System.out.printf("[%d]%s  %-23s %s%n",
                    job.jobNumber,
                    marker,
                    status,
                    commandText);

            // Remove finished jobs from the list after printing them once here
            if (!job.process.isAlive()) {
                iterator.remove();
            }
        }
    }

    private void executePipeline(String leftInput, String rightInput) throws Exception {
        List<String> leftCommand = parseCommand(leftInput);
        List<String> rightCommand = parseCommand(rightInput);

        if (leftCommand.isEmpty() || rightCommand.isEmpty()) {
            return;
        }

        Process leftProcess = startExternalProcess(leftCommand, null);
        Process rightProcess = startExternalProcess(rightCommand, leftProcess.getInputStream());

        Thread leftErr = pipeAsync(leftProcess.getErrorStream(), System.err, false);
        Thread rightErr = pipeAsync(rightProcess.getErrorStream(), System.err, false);
        Thread rightOut = pipeAsync(rightProcess.getInputStream(), System.out, false);

        rightProcess.waitFor();

        try {
            leftProcess.destroy();
        } catch (Exception ignored) {
        }

        try {
            leftProcess.waitFor();
        } catch (Exception ignored) {
        }

        if (leftErr != null) leftErr.join();
        if (rightErr != null) rightErr.join();
        if (rightOut != null) rightOut.join();
    }

    private void executeExternal(List<String> command, InputStream input, OutputStream output) throws Exception {
        Process process = startExternalProcess(command, input);

        Thread errThread = pipeAsync(process.getErrorStream(), System.err, false);
        Thread outThread = pipeAsync(process.getInputStream(), output, false);

        process.waitFor();

        if (errThread != null) errThread.join();
        if (outThread != null) outThread.join();
    }

    private Process startExternalProcess(List<String> command, InputStream input) throws Exception {
        String executable = resolveExecutable(command.get(0));
        if (executable == null) {
            System.out.printf("%s: command not found%n", command.get(0));
            return new ProcessBuilder("sh", "-c", "exit 127").start();
        }

        List<String> fullCommand = new ArrayList<>();
        fullCommand.add(executable);
        fullCommand.addAll(command.subList(1, command.size()));

        ProcessBuilder pb = new ProcessBuilder(fullCommand);
        pb.directory(currentDirectory.toFile());

        Process process = pb.start();

        if (input != null) {
            pipeAsync(input, process.getOutputStream(), true);
        } else {
            try {
                process.getOutputStream().close();
            } catch (Exception ignored) {
            }
        }

        return process;
    }

    private Thread pipeAsync(InputStream in, OutputStream out, boolean closeOut) {
        Thread t = new Thread(() -> {
            byte[] buffer = new byte[8192];
            int n;
            try {
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
                if (closeOut) {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private String resolveExecutable(String command) {
        if (command.contains("/")) {
            Path path = Paths.get(command);
            if (!path.isAbsolute()) {
                path = currentDirectory.resolve(path).normalize();
            }
            if (Files.exists(path) && Files.isRegularFile(path) && Files.isExecutable(path)) {
                return path.toString();
            }
            return null;
        }

        return findExecutable(command);
    }

    private String findExecutable(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) {
            return null;
        }

        String[] dirs = pathEnv.split(File.pathSeparator);
        for (String dir : dirs) {
            Path candidate = Paths.get(dir, command);
            if (Files.exists(candidate) && Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }

        return null;
    }

    private static class BackgroundJob {
        final int jobNumber;
        final Process process;
        final String commandWithoutAmpersand;
        final String commandWithAmpersand;
        boolean notifiedDone = false;

        BackgroundJob(int jobNumber, Process process, String commandWithoutAmpersand, String commandWithAmpersand) {
            this.jobNumber = jobNumber;
            this.process = process;
            this.commandWithoutAmpersand = commandWithoutAmpersand;
            this.commandWithAmpersand = commandWithAmpersand;
        }
    }
}