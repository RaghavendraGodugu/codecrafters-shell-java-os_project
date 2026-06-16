import java.io.*;
import java.util.*;

public class Main {
    static int jobCounter = 1;
    static List<Job> jobsList = new ArrayList<>();
    static File currentDir = new File(System.getProperty("user.dir"));

    static class Job {
        int id;
        ShellProcess proc;
        String cmd;
        String status;

        Job(int id, ShellProcess proc, String cmd) {
            this.id = id;
            this.proc = proc;
            this.cmd = cmd;
            this.status = "Running";
        }
    }

    static class ShellProcess {
        Process proc;
        Thread outThread;
        Thread errThread;
        long pid;

        ShellProcess(Process proc, Thread outThread, Thread errThread) {
            this.proc = proc;
            this.outThread = outThread;
            this.errThread = errThread;
            this.pid = proc.pid();
        }

        void waitForCompletion() {
            try {
                proc.waitFor();
                if (outThread != null) outThread.join();
                if (errThread != null) errThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        boolean isAlive() {
            return proc.isAlive();
        }
    }

    static void reapJobs() {
        for (Job job : jobsList) {
            if (!job.proc.isAlive()) {
                job.status = "Done";
            }
        }
    }

    static String findPath(String cmdName) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        String[] paths = pathEnv.split(File.pathSeparator);
        for (String p : paths) {
            File f = new File(p, cmdName);
            if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    static List<String> parseArguments(String cmdArg) {
        List<String> args = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean sq = false, dq = false, esc = false;

        for (int i = 0; i < cmdArg.length(); i++) {
            char c = cmdArg.charAt(i);

            if (esc) {
                cur.append(c);
                esc = false;
            } else if (c == '\\' && !sq) {
                esc = true;
            } else if (c == '\'' && !dq) {
                sq = !sq;
            } else if (c == '"' && !sq) {
                dq = !dq;
            } else if (Character.isWhitespace(c) && !sq && !dq) {
                if (cur.length() > 0) {
                    args.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }

        if (cur.length() > 0) args.add(cur.toString());
        return args;
    }

    static ShellProcess executeCommand(List<String> args, OutputStream outFp, OutputStream errFp) {
        String cmd = args.get(0);

        PrintWriter out = new PrintWriter(new OutputStreamWriter(outFp), true);
        PrintWriter err = new PrintWriter(new OutputStreamWriter(errFp), true);

        if (cmd.equals("exit")) {
            System.exit(0);
        } else if (cmd.equals("echo")) {
            out.println(String.join(" ", args.subList(1, args.size())));
        } else if (cmd.equals("pwd")) {
            out.println(currentDir.getAbsolutePath());
        } else if (cmd.equals("cd")) {
            if (args.size() > 1) {
                String d = args.get(1);
                if (d.equals("~")) d = System.getProperty("user.home");
                File f = new File(d);
                if (!f.isAbsolute()) f = new File(currentDir, d);
                if (f.exists() && f.isDirectory()) {
                    try {
                        currentDir = f.getCanonicalFile();
                    } catch (IOException e) {
                        err.println("cd: error resolving path");
                    }
                } else {
                    err.println("cd: " + args.get(1) + ": No such file or directory");
                }
            }
        } else if (cmd.equals("jobs")) {
            int n = jobsList.size();
            List<Job> keep = new ArrayList<>();

            for (int i = 0; i < n; i++) {
                Job j = jobsList.get(i);

                if (!j.proc.isAlive()) j.status = "Done";

                String mark = " ";
                if (i == n - 1) mark = "+";
                else if (i == n - 2) mark = "-";

                out.println("[" + j.id + "]" + mark + "  " +
                        String.format("%-24s", j.status) + j.cmd);

                if (j.status.equals("Running")) keep.add(j);
            }

            jobsList = keep;
            return null;
        } else {
            try {
                ProcessBuilder pb = new ProcessBuilder(args);
                pb.directory(currentDir);
                Process p = pb.start();

                Thread t1 = new Thread(() -> {
                    try { p.getInputStream().transferTo(outFp); } catch (Exception ignored) {}
                });

                Thread t2 = new Thread(() -> {
                    try { p.getErrorStream().transferTo(errFp); } catch (Exception ignored) {}
                });

                t1.start();
                t2.start();

                return new ShellProcess(p, t1, t2);

            } catch (IOException e) {
                err.println(cmd + ": command not found");
            }
        }

        return null;
    }

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);

        while (true) {
            reapJobs();

            System.out.print("$ ");
            System.out.flush();

            String line;
            try {
                line = sc.nextLine().trim();
            } catch (Exception e) {
                break;
            }

            if (line.isEmpty()) continue;

            List<String> parsed = parseArguments(line);
            if (parsed.isEmpty()) continue;

            boolean bg = false;
            if (parsed.get(parsed.size() - 1).equals("&")) {
                bg = true;
                parsed.remove(parsed.size() - 1);
            }

            reapJobs();

            ShellProcess p = executeCommand(parsed, System.out, System.err);

            if (p != null) {
                if (bg) {
                    System.out.println("[" + jobCounter + "] " + p.pid);
                    jobsList.add(new Job(jobCounter++, p, line));
                } else {
                    p.waitForCompletion();
                }
            }
        }
    }
}