import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Main {
    private static final Set<String> BUILT_INS = Set.of("exit", "echo", "type", "pwd", "cd");

    public static void main(String[] args) throws Exception {
        Shell shell = new Shell();
        shell.run();
    }
}

class Shell {
    private Path currentDirectory = Paths.get(System.getProperty("user.dir"));

    public void run() throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            if (!scanner.hasNextLine()) {
                break;
            }

            String input = scanner.nextLine();
            if (input == null || input.trim().isEmpty()) {
                continue;
            }

            List<String> pipelineParts = splitByPipe(input);

            if (pipelineParts.size() == 2) {
                executePipeline(pipelineParts.get(0), pipelineParts.get(1));
            } else {
                executeSingleCommand(parseCommand(input));
            }
        }
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

        if (Main.BUILT_INS.contains(target)) {
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

    private void executePipeline(String leftInput, String rightInput) throws Exception {
        List<String> leftCommand = parseCommand(leftInput);
        List<String> rightCommand = parseCommand(rightInput);

        if (leftCommand.isEmpty() || rightCommand.isEmpty()) {
            return;
        }

        Process leftProcess = startExternalProcess(leftCommand, null, true);
        Process rightProcess = startExternalProcess(rightCommand, leftProcess.getInputStream(), false);

        Thread leftErr = pipeAsync(leftProcess.getErrorStream(), System.err, false);
        Thread rightErr = pipeAsync(rightProcess.getErrorStream(), System.err, false);
        Thread rightOut = pipeAsync(rightProcess.getInputStream(), System.out, false);

        int rightExit = rightProcess.waitFor();

        try {
            leftProcess.getInputStream().close();
        } catch (Exception ignored) {
        }

        try {
            leftProcess.getOutputStream().close();
        } catch (Exception ignored) {
        }

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
        Process process = startExternalProcess(command, input, false);

        Thread errThread = pipeAsync(process.getErrorStream(), System.err, false);
        Thread outThread = pipeAsync(process.getInputStream(), output, false);

        process.waitFor();

        if (errThread != null) errThread.join();
        if (outThread != null) outThread.join();
    }

    private Process startExternalProcess(List<String> command, InputStream input, boolean pipeStdout) throws Exception {
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
}