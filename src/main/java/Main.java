import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {

    private static class Job {
        final int id;
        final long pid;
        final String command;
        final Process process;
        boolean isDoneHandled = false; // Tracks if a Done message was already printed

        Job(int id, long pid, String command, Process process) {
            this.id = id;
            this.pid = pid;
            this.command = command;
            this.process = process;
        }
    }

    private static final List<Job> jobs = new ArrayList<>();
    private static int nextJobId = 1;

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);
        String currentDirectory = System.getProperty("user.dir");

        while (true) {
            // 1. Automatic reaping before showing the prompt
            reapAndPrintJobsBeforePrompt();
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
                if (parts.size() < 2) continue;
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
                // Determine active/dead state before snapshotting for stable markers
                List<Job> snapshot = new ArrayList<>(jobs);
                List<Job> reapedThisTurn = silentReap();

                if (snapshot.isEmpty()) continue;

                Job mostRecent = snapshot.get(snapshot.size() - 1);
                Job secondMost = (snapshot.size() >= 2) ? snapshot.get(snapshot.size() - 2) : null;

                for (Job j : snapshot) {
                    String marker = " ";
                    if (j == mostRecent) marker = "+";
                    else if (j == secondMost) marker = "-";

                    String status;
                    String suffix;

                    if (reapedThisTurn.contains(j) || j.isDoneHandled) {
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

                    System.out.println("[" + j.id + "]" + marker + "  " + status + sb.toString() + j.command + suffix);
                }
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

                String baseCmd = cmdParts.get(0);
                if (findExecutable(baseCmd, currentDirectory) == null) {
                    System.out.println(baseCmd + ": command not found");
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
                long pid = process.pid();

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

    private static boolean isBuiltin(String cmd) {
        return cmd.equals("echo")
                || cmd.equals("exit")
                || cmd.equals("type")
                || cmd.equals("pwd")
                || cmd.equals("cd")
                || cmd.equals("jobs");
    }

    private static String findExecutable(String command, String currentDirectory) {
        if (isBuiltin(command)) return command;

        File cmdFile = new File(command);
        if (!cmdFile.isAbsolute()) {
            cmdFile = new File(currentDirectory, command);
        }
        if (cmdFile.exists() && cmdFile.isFile() && cmdFile.canExecute()) {
            return cmdFile.getAbsolutePath();
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) pathEnv = "";
        String[] paths = pathEnv.split(File.pathSeparator);

        for (String p : paths) {
            File file = new File(p, command);
            if (file.exists() && file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }

        return null;
    }

    /**
     * Automatic reaping logic performed right before presenting a new shell prompt.
     * Elements are printed and then permanently purged from the tracking table.
     */
    private static void reapAndPrintJobsBeforePrompt() {
        if (jobs.isEmpty()) return;

        List<Job> remaining = new ArrayList<>();
        Job mostRecent = jobs.get(jobs.size() - 1);
        Job secondMost = (jobs.size() >= 2) ? jobs.get(jobs.size() - 2) : null;

        for (Job j : jobs) {
            if (j.process.isAlive()) {
                remaining.add(j);
            } else {
                String marker = " ";
                if (j == mostRecent) marker = "+";
                else if (j == secondMost) marker = "-";
                
                String status = "Done";
                int pad = 24 - status.length();
                if (pad < 0) pad = 0;
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < pad; k++) sb.append(' ');
                
                System.out.println("[" + j.id + "]" + marker + "  " + status + sb.toString() + j.command);
            }
        }
        jobs.clear();
        jobs.addAll(remaining);
    }

    /**
     * Checks job collection states silently without printing anything instantly.
     * Clears completely dead references out of the active running map array.
     */
    private static List<Job> silentReap() {
        List<Job> reapedThisTurn = new ArrayList<>();
        List<Job> remaining = new ArrayList<>();

        for (Job j : jobs) {
            if (j.process.isAlive()) {
                remaining.add(j);
            } else {
                reapedThisTurn.add(j);
            }
        }
        jobs.clear();
        jobs.addAll(remaining);
        return reapedThisTurn;
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