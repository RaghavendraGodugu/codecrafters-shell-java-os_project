import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public class Main {
    private static final List<Job> jobs = new ArrayList<>();
    private static int nextJobId = 1;
    private static Path currentDirectory = Paths.get(System.getProperty("user.dir"));

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

            input = input.trim();
            if (input.isEmpty()) {
                continue;
            }

            List<String> tokens = tokenize(input);
            if (tokens.isEmpty()) {
                continue;
            }

            String command = tokens.get(0);

            if (command.equals("exit")) {
                int code = 0;
                if (tokens.size() > 1) {
                    try {
                        code = Integer.parseInt(tokens.get(1));
                    } catch (NumberFormatException ignored) {
                    }
                }
                System.exit(code);
            } else if (command.equals("echo")) {
                runEcho(tokens);
            } else if (command.equals("pwd")) {
                System.out.println(currentDirectory.toAbsolutePath().normalize());
            } else if (command.equals("cd")) {
                runCd(tokens);
            } else if (command.equals("type")) {
                runType(tokens);
            } else if (command.equals("jobs")) {
                runJobs();
            } else {
                runExternal(input, tokens);
            }
        }
    }

    private static void runEcho(List<String> tokens) {
        if (tokens.size() == 1) {
            System.out.println();
            return;
        }
        System.out.println(String.join(" ", tokens.subList(1, tokens.size())));
    }

    private static void runCd(List<String> tokens) {
        String target;
        if (tokens.size() < 2 || tokens.get(1).equals("~")) {
            target = System.getProperty("user.home");
        } else {
            target = tokens.get(1);
        }

        Path newPath = Paths.get(target);
        if (!newPath.isAbsolute()) {
            newPath = currentDirectory.resolve(newPath);
        }
        newPath = newPath.normalize();

        if (Files.exists(newPath) && Files.isDirectory(newPath)) {
            currentDirectory = newPath;
        } else {
            System.out.println("cd: " + tokens.get(1) + ": No such file or directory");
        }
    }

    private static void runType(List<String> tokens) {
        if (tokens.size() < 2) {
            return;
        }

        String target = tokens.get(1);
        List<String> builtins = Arrays.asList("echo", "exit", "type", "pwd", "cd", "jobs");

        if (builtins.contains(target)) {
            System.out.println(target + " is a shell builtin");
            return;
        }

        String pathValue = System.getenv("PATH");
        if (pathValue != null) {
            for (String dir : pathValue.split(":")) {
                Path candidate = Paths.get(dir, target);
                if (Files.exists(candidate) && Files.isExecutable(candidate)) {
                    System.out.println(target + " is " + candidate);
                    return;
                }
            }
        }

        System.out.println(target + ": not found");
    }

    private static void runJobs() {
        reapCompletedJobs(true);

        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get(i);
            String marker = markerForIndex(i, jobs.size());
            String status = job.process.isAlive() ? "Running" : "Done";
            System.out.printf("[%d]%s  %-22s%s%n", job.jobId, marker, status, job.command);
        }
    }

    private static void runExternal(String originalInput, List<String> tokens) {
        boolean background = false;

        if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).equals("&")) {
            background = true;
            tokens = new ArrayList<>(tokens.subList(0, tokens.size() - 1));
        }

        if (tokens.isEmpty()) {
            return;
        }

        ProcessBuilder pb = new ProcessBuilder(tokens);
        pb.directory(currentDirectory.toFile());
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        try {
            Process process = pb.start();

            if (background) {
                Job job = new Job(nextJobId++, process, buildStoredCommand(tokens));
                jobs.add(job);
                System.out.printf("[%d] %d%n", job.jobId, process.pid());
            } else {
                process.waitFor();
            }
        } catch (IOException e) {
            System.out.println(tokens.get(0) + ": command not found");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void reapCompletedJobs(boolean printDoneLines) {
        Iterator<Job> iterator = jobs.iterator();

        while (iterator.hasNext()) {
            Job job = iterator.next();
            if (!job.process.isAlive()) {
                if (printDoneLines) {
                    String marker = markerForJob(job);
                    System.out.printf("[%d]%s  %-22s%s%n", job.jobId, marker, "Done", job.command);
                }
                iterator.remove();
            }
        }
    }

    private static String markerForJob(Job target) {
        for (int i = 0; i < jobs.size(); i++) {
            if (jobs.get(i) == target) {
                return markerForIndex(i, jobs.size());
            }
        }
        return " ";
    }

    private static String markerForIndex(int index, int size) {
        if (size == 1) {
            return "+";
        }
        if (index == size - 1) {
            return "+";
        }
        if (index == size - 2) {
            return "-";
        }
        return " ";
    }

    private static String buildStoredCommand(List<String> tokens) {
        return String.join(" ", tokens);
    }

    private static List<String> tokenize(String input) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

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
                    result.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }

            current.append(ch);
        }

        if (current.length() > 0) {
            result.add(current.toString());
        }

        return result;
    }

    private static class Job {
        final int jobId;
        final Process process;
        final String command;

        Job(int jobId, Process process, String command) {
            this.jobId = jobId;
            this.process = process;
            this.command = command;
        }
    }
}