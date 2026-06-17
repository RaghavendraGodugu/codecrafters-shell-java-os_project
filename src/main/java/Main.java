import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Main {
    private static final List<Job> jobs = new ArrayList<>();
    private static int nextJobId = 1;

    public static void main(String[] args) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            reapCompletedJobs(true);

            System.out.print("$ ");
            System.out.flush();

            String input = reader.readLine();
            if (input == null) {
                break;
            }

            if (input.trim().isEmpty()) {
                continue;
            }

            ParsedCommand cmd = parseCommand(input);
            if (cmd.args.isEmpty()) {
                continue;
            }

            String command = cmd.args.get(0);

            switch (command) {
                case "exit":
                    int code = 0;
                    if (cmd.args.size() > 1) {
                        try {
                            code = Integer.parseInt(cmd.args.get(1));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    System.exit(code);
                    return;

                case "echo":
                    handleEcho(cmd.args);
                    break;

                case "pwd":
                    System.out.println(System.getProperty("user.dir"));
                    break;

                case "cd":
                    handleCd(cmd.args);
                    break;

                case "type":
                    handleType(cmd.args);
                    break;

                case "jobs":
                    reapCompletedJobs(false);
                    printJobs();
                    break;

                default:
                    runExternal(cmd);
                    break;
            }
        }
    }

    private static void handleEcho(List<String> args) {
        if (args.size() <= 1) {
            System.out.println();
            return;
        }
        System.out.println(String.join(" ", args.subList(1, args.size())));
    }

    private static void handleCd(List<String> args) {
        String target;
        if (args.size() < 2 || args.get(1).equals("~")) {
            target = System.getProperty("user.home");
        } else {
            target = args.get(1);
            if (target.startsWith("~")) {
                target = System.getProperty("user.home") + target.substring(1);
            }
        }

        Path path = Paths.get(target);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir")).resolve(path).normalize();
        }

        if (Files.exists(path) && Files.isDirectory(path)) {
            System.setProperty("user.dir", path.toAbsolutePath().toString());
        } else {
            System.out.println("cd: " + args.get(1) + ": No such file or directory");
        }
    }

    private static void handleType(List<String> args) {
        if (args.size() < 2) return;

        String cmd = args.get(1);
        if (isBuiltin(cmd)) {
            System.out.println(cmd + " is a shell builtin");
            return;
        }

        String executable = findExecutable(cmd);
        if (executable != null) {
            System.out.println(cmd + " is " + executable);
        } else {
            System.out.println(cmd + ": not found");
        }
    }

    private static boolean isBuiltin(String cmd) {
        return Set.of("exit", "echo", "pwd", "cd", "type", "jobs").contains(cmd);
    }

    private static void runExternal(ParsedCommand cmd) {
        String executable = findExecutable(cmd.args.get(0));
        if (executable == null) {
            System.out.println(cmd.args.get(0) + ": command not found");
            return;
        }

        ProcessBuilder pb = new ProcessBuilder(cmd.args);
        pb.directory(new File(System.getProperty("user.dir")));
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        try {
            Process process = pb.start();

            if (cmd.background) {
                Job job = new Job(nextJobId++, process, cmd.commandWithoutAmpersand);
                jobs.add(job);
                System.out.println("[" + job.id + "] " + process.pid());
            } else {
                try (InputStream in = process.getInputStream()) {
                    in.transferTo(System.out);
                }
                process.waitFor();
            }
        } catch (IOException e) {
            System.out.println(cmd.args.get(0) + ": command not found");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void reapCompletedJobs(boolean printDone) {
        List<Job> completed = new ArrayList<>();

        for (Job job : jobs) {
            if (!job.process.isAlive()) {
                completed.add(job);
            }
        }

        if (printDone) {
            for (Job job : completed) {
                int idx = jobs.indexOf(job);
                String marker = markerForIndex(idx, jobs.size());
                System.out.println("[" + job.id + "] " + marker + " Done " + job.command);
            }
        }

        jobs.removeAll(completed);
    }

    private static void printJobs() {
        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get(i);
            String marker = markerForIndex(i, jobs.size());
            System.out.println("[" + job.id + "]" + marker + " Running " + job.command + " &");
        }
    }

    private static String markerForIndex(int index, int size) {
        if (index == size - 1) return "+";
        if (index == size - 2) return "-";
        return " ";
    }

    private static ParsedCommand parseCommand(String input) {
        String trimmed = stripTrailingWhitespace(input);
        boolean background = false;

        if (trimmed.endsWith("&")) {
            background = true;
            trimmed = stripTrailingWhitespace(trimmed.substring(0, trimmed.length() - 1));
        }

        List<String> args = tokenize(trimmed);
        return new ParsedCommand(args, background, trimmed);
    }

    private static String stripTrailingWhitespace(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaping = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            if (escaping) {
                current.append(ch);
                escaping = false;
                continue;
            }

            if (ch == '\\') {
                if (inSingle) {
                    current.append(ch);
                } else {
                    escaping = true;
                }
                continue;
            }

            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }

            if (ch == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }

            if (Character.isWhitespace(ch) && !inSingle && !inDouble) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }

            current.append(ch);
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    private static String findExecutable(String command) {
        if (command.contains("/")) {
            Path path = Paths.get(command);
            if (!path.isAbsolute()) {
                path = Paths.get(System.getProperty("user.dir")).resolve(path).normalize();
            }
            if (Files.exists(path) && Files.isRegularFile(path) && Files.isExecutable(path)) {
                return path.toAbsolutePath().toString();
            }
            return null;
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) return null;

        String[] dirs = pathEnv.split(File.pathSeparator);
        for (String dir : dirs) {
            Path candidate = Paths.get(dir, command);
            if (Files.exists(candidate) && Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }

        return null;
    }

    private static class Job {
        final int id;
        final Process process;
        final String command;

        Job(int id, Process process, String command) {
            this.id = id;
            this.process = process;
            this.command = command;
        }
    }

    private static class ParsedCommand {
        final List<String> args;
        final boolean background;
        final String commandWithoutAmpersand;

        ParsedCommand(List<String> args, boolean background, String commandWithoutAmpersand) {
            this.args = args;
            this.background = background;
            this.commandWithoutAmpersand = commandWithoutAmpersand;
        }
    }
}