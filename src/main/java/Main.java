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

    static String findPath(String cmdName) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        String[] paths = pathEnv.split(File.pathSeparator);
        for (String p : paths) {
            File f = new File(p, cmdName);
            if (f.isFile() && f.canExecute()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    static List<String> parseArguments(String cmdArg) {
        List<String> args = new ArrayList<>();
        StringBuilder currentArg = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean isEscaping = false;

        for (int i = 0; i < cmdArg.length(); i++) {
            char c = cmdArg.charAt(i);

            if (isEscaping) {
                currentArg.append(c);
                isEscaping = false;
            } else if (c == '\\' && !inSingleQuotes) {
                isEscaping = true;
            } else if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
            } else if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
            } else if (Character.isWhitespace(c) && !inSingleQuotes && !inDoubleQuotes) {
                if (currentArg.length() > 0) {
                    args.add(currentArg.toString());
                    currentArg.setLength(0);
                }
            } else {
                currentArg.append(c);
            }
        }

        if (currentArg.length() > 0) {
            args.add(currentArg.toString());
        }

        return args;
    }

    static ShellProcess executeCommand(List<String> args, OutputStream outFp, OutputStream errFp, InputStream inFd) {
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
                String directory = args.get(1);
                if (directory.equals("~")) {
                    directory = System.getProperty("user.home");
                }
                File newDir = new File(directory);
                if (!newDir.isAbsolute()) {
                    newDir = new File(currentDir, directory);
                }
                if (newDir.exists() && newDir.isDirectory()) {
                    try {
                        currentDir = newDir.getCanonicalFile();
                    } catch (IOException e) {
                        err.println("cd: error resolving path");
                    }
                } else {
                    err.println("cd: " + args.get(1) + ": No such file or directory");
                }
            }
        } else if (cmd.equals("jobs")) {
            int totalJobs = jobsList.size();
            List<Job> remaining = new ArrayList<>();

            for (int i = 0; i < totalJobs; i++) {
                Job job = jobsList.get(i);

                if (!job.proc.isAlive()) {
                    job.status = "Done";
                }

                String marker = " ";
                if (i == totalJobs - 1) marker = "+";
                else if (i == totalJobs - 2) marker = "-";

                String status = String.format("%-24s", job.status);
                out.println("[" + job.id + "]" + marker + "  " + status + job.cmd);

                if (job.status.equals("Running")) {
                    remaining.add(job);
                }
            }

            jobsList.clear();
            jobsList.addAll(remaining);
        } else {
            try {
                ProcessBuilder pb = new ProcessBuilder(args);
                pb.directory(currentDir);
                Process proc = pb.start();

                Thread outThread = new Thread(() -> {
                    try {
                        proc.getInputStream().transferTo(outFp);
                    } catch (IOException ignored) {}
                });

                Thread errThread = new Thread(() -> {
                    try {
                        proc.getErrorStream().transferTo(errFp);
                    } catch (IOException ignored) {}
                });

                outThread.start();
                errThread.start();

                return new ShellProcess(proc, outThread, errThread);

            } catch (IOException e) {
                err.println(cmd + ": command not found");
            }
        }

        return null;
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            for (Job job : jobsList) {
                if (!job.proc.isAlive()) {
                    job.status = "Done";
                }
            }

            System.out.print("$ ");
            System.out.flush();

            String command;
            try {
                command = scanner.nextLine().trim();
            } catch (NoSuchElementException e) {
                break;
            }

            if (command.isEmpty()) continue;

            List<String> parsedArgs = parseArguments(command);

            if (parsedArgs.isEmpty()) continue;

            boolean runInBackground = false;
            if (parsedArgs.get(parsedArgs.size() - 1).equals("&")) {
                runInBackground = true;
                parsedArgs.remove(parsedArgs.size() - 1);
            }

            try {
                OutputStream outFp = System.out;
                OutputStream errFp = System.err;

                ShellProcess proc = executeCommand(parsedArgs, outFp, errFp, null);

                if (proc != null) {
                    if (runInBackground) {
                        System.out.println("[" + jobCounter + "] " + proc.pid);
                        jobsList.add(new Job(jobCounter, proc, command));
                        jobCounter++;
                    } else {
                        proc.waitForCompletion();
                    }
                }

            } catch (Exception e) {
                System.err.println("I/O Error: " + e.getMessage());
            }
        }
    }
}