import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class Main {

    private static class Job {
        final int id;
        final long pid;
        final String command;
        final Process process;

        Job(int id, long pid, String command, Process process) {
            this.id = id;
            this.pid = pid;
            this.command = command;
            this.process = process;
        }
    }

    private static final List<Job> jobs = new ArrayList<>();
    private static int nextJobId = 1;

    // command -> completer path
    private static final Map<String, String> completions = new HashMap<>();

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);
        String currentDirectory = System.getProperty("user.dir");

        while (true) {
            reapJobs(true);
            System.out.print("$ ");

            if (!sc.hasNextLine()) break;
            String input = sc.nextLine();
            if (input == null || input.trim().isEmpty()) continue;

            input = input.trim();

            if (input.equals("exit")) {
                System.exit(0);
            }

            else if (input.equals("pwd")) {
                System.out.println(currentDirectory);
            }

            else if (input.startsWith("cd ")) {
                String path = input.substring(3).trim();

                if (path.equals("~")) {
                    String home = System.getenv("HOME");
                    if (home != null) currentDirectory = home;
                    continue;
                }

                File dir = new File(path);
                if (!dir.isAbsolute()) {
                    dir = new File(currentDirectory, path);
                }

                if (dir.exists() && dir.isDirectory()) {
                    currentDirectory = dir.getCanonicalPath();
                } else {
                    System.out.println("cd: " + path + ": No such file or directory");
                }
            }

            else if (input.startsWith("type ")) {
                List<String> parts = parse(input);
                String cmd = parts.get(1);

                if (isBuiltin(cmd)) {
                    System.out.println(cmd + " is a shell builtin");
                } else {
                    String path = findExecutable(cmd, currentDirectory);
                    if (path != null) {
                        System.out.println(cmd + " is " + path);
                    } else {
                        System.out.println(cmd + ": not found");
                    }
                }
            }

            else if (input.equals("jobs")) {
                List<Job> original = new ArrayList<>(jobs);
                List<Job> doneJobs = reapJobs(false);
                if (original.isEmpty() && doneJobs.isEmpty()) continue;

                // Calculate markers from REMAINING jobs (after reaping)
                Job mostRecent = null;
                Job secondMost = null;
                if (!jobs.isEmpty()) {
                    mostRecent = jobs.get(jobs.size() - 1);
                    if (jobs.size() >= 2) {
                        secondMost = jobs.get(jobs.size() - 2);
                    }
                }

                for (Job j : original) {
                    String marker;
                    if (j == mostRecent) marker = "+";
                    else if (j == secondMost) marker = "-";
                    else marker = " ";

                    String status;
                    String suffix;
                    if (doneJobs.contains(j)) {
                        status = "Done";
                        suffix = "";
                    } else {
                        status = "Running";
                        suffix = " &";
                    }

                    int pad = 24 - status.length();
                    if (pad < 0) pad = 0;
                    StringBuilder sb = new StringBuilder();
                    for (int k = 0; k < pad; k++) sb.append(' ');

                    System.out.println("[" + j.id + "]" + marker + "  " + status + sb + j.command + suffix);
                }
            }

            else if (input.startsWith("complete")) {
                handleComplete(input);
            }

            else {
                List<String> parts = parse(input);
                if (parts.isEmpty()) continue;

                boolean background = false;

                if (parts.get(parts.size() - 1).equals("&")) {
                    background = true;
                    parts.remove(parts.size() - 1);
                }

                List<String> cmdParts = new ArrayList<>();

                String outFile = null;
                String errFile = null;
                boolean appendOut = false;
                boolean appendErr = false;

                for (int i = 0; i < parts.size(); i++) {
                    String p = parts.get(i);

                    if (p.equals(">") || p.equals("1>")) {
                        outFile = parts.get(++i);
                        appendOut = false;
                    } else if (p.equals(">>") || p.equals("1>>")) {
                        outFile = parts.get(++i);
                        appendOut = true;
                    } else if (p.equals("2>")) {
                        errFile = parts.get(++i);
                        appendErr = false;
                    } else if (p.equals("2>>")) {
                        errFile = parts.get(++i);
                        appendErr = true;
                    } else {
                        cmdParts.add(p);
                    }
                }

                if (cmdParts.isEmpty()) continue;

                if (findExecutable(cmdParts.get(0), currentDirectory) == null) {
                    System.out.println(input + ": command not found");
                    continue;
                }

                ProcessBuilder pb = new ProcessBuilder(cmdParts);
                pb.directory(new File(currentDirectory));

                if (outFile != null) {
                    File out = new File(outFile);
                    if (!out.isAbsolute()) out = new File(currentDirectory, outFile);
                    if (out.getParentFile() != null) out.getParentFile().mkdirs();
                    pb.redirectOutput(appendOut ? ProcessBuilder.Redirect.appendTo(out) : ProcessBuilder.Redirect.to(out));
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }

                if (errFile != null) {
                    File err = new File(errFile);
                    if (!err.isAbsolute()) err = new File(currentDirectory, errFile);
                    if (err.getParentFile() != null) err.getParentFile().mkdirs();
                    pb.redirectError(appendErr ? ProcessBuilder.Redirect.appendTo(err) : ProcessBuilder.Redirect.to(err));
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                Process process = pb.start();
                int pid = (int) process.pid();

                if (background) {
                    String cmdString = String.join(" ", cmdParts);
                    Job job = new Job(nextJobId++, pid, cmdString, process);
                    jobs.add(job);
                    System.out.println("[" + job.id + "] " + job.pid);
                    continue;
                }

                process.waitFor();
            }
        }
    }

    private static void handleComplete(String input) {
        List<String> parts = parse(input);

        // complete -C /path/to/completer git
        if (parts.size() == 4 && parts.get(1).equals("-C")) {
            String completerPath = parts.get(2);
            String command = parts.get(3);
            completions.put(command, completerPath);
            return;
        }

        // complete -r git
        if (parts.size() == 3 && parts.get(1).equals("-r")) {
            String command = parts.get(2);
            completions.remove(command);
            return;
        }

        // For now, ignore unsupported complete variants silently for this stage
    }

    private static boolean isBuiltin(String cmd) {
        return cmd.equals("echo")
                || cmd.equals("exit")
                || cmd.equals("type")
                || cmd.equals("pwd")
                || cmd.equals("cd")
                || cmd.equals("jobs")
                || cmd.equals("complete");
    }

    private static String findExecutable(String command, String currentDirectory) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) pathEnv = "";

        File cmdFile = new File(command);
        if (!cmdFile.isAbsolute()) {
            cmdFile = new File(currentDirectory, command);
        }
        if (cmdFile.exists() && cmdFile.isFile() && cmdFile.canExecute()) {
            return cmdFile.getAbsolutePath();
        }

        String[] paths = pathEnv.split(File.pathSeparator);

        for (String p : paths) {
            File file = new File(p, command);
            if (file.exists() && file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }

        return null;
    }

    private static List<Job> reapJobs(boolean showDone) {
        List<Job> done = new ArrayList<>();
        if (jobs.isEmpty()) return done;

        Job mostRecent = jobs.get(jobs.size() - 1);
        Job secondMost = (jobs.size() >= 2) ? jobs.get(jobs.size() - 2) : null;

        List<Job> remaining = new ArrayList<>();
        for (Job j : jobs) {
            boolean alive = j.process.isAlive();
            if (alive) {
                try {
                    alive = !j.process.waitFor(0, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            if (alive) {
                remaining.add(j);
            } else {
                done.add(j);
                if (showDone) {
                    String marker;
                    if (j == mostRecent) marker = "+";
                    else if (j == secondMost) marker = "-";
                    else marker = " ";
                    String status = "Done";
                    int pad = 24 - status.length();
                    if (pad < 0) pad = 0;
                    StringBuilder sb = new StringBuilder();
                    for (int k = 0; k < pad; k++) sb.append(' ');
                    System.out.println("[" + j.id + "]" + marker + "  " + status + sb + j.command);
                }
            }
        }

        jobs.clear();
        jobs.addAll(remaining);
        return done;
    }

    private static List<String> parse(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();

        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (!inSingle && !inDouble && c == '\\') {
                if (i + 1 < input.length()) {
                    i++;
                    cur.append(input.charAt(i));
                }
                continue;
            }

            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }

            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }

            if (inDouble && c == '\\') {
                if (i + 1 < input.length()) {
                    char n = input.charAt(i + 1);
                    if (n == '"' || n == '\\') {
                        cur.append(n);
                        i++;
                        continue;
                    }
                }
                cur.append(c);
                continue;
            }

            if (!inSingle && !inDouble && Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }

            cur.append(c);
        }

        if (cur.length() > 0) tokens.add(cur.toString());

        return tokens;
    }
}