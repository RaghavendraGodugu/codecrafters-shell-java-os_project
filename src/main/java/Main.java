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
                if (inDoubleQuotes) {
                    if (c == '"' || c == '\\' || c == '$' || c == '`' || c == '\n') {
                        currentArg.append(c);
                    } else {
                        currentArg.append('\\').append(c);
                    }
                } else {
                    currentArg.append(c);
                }
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
        List<String> builtins = Arrays.asList("echo", "exit", "type", "pwd", "cd", "jobs");

        PrintWriter out = new PrintWriter(new OutputStreamWriter(outFp), true);
        PrintWriter err = new PrintWriter(new OutputStreamWriter(errFp), true);

        if (cmd.equals("exit")) {
            System.exit(0);
        } else if (cmd.equals("echo")) {
            out.println(String.join(" ", args.subList(1, args.size())));
        } else if (cmd.equals("type")) {
            if (args.size() > 1) {
                String targetCommand = args.get(1);
                if (builtins.contains(targetCommand)) {
                    out.println(targetCommand + " is a shell builtin");
                } else {
                    String foundPath = findPath(targetCommand);
                    if (foundPath != null) {
                        out.println(targetCommand + " is " + foundPath);
                    } else {
                        out.println(targetCommand + ": not found");
                    }
                }
            }
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
            List<Job> jobsToKeep = new ArrayList<>();
            for (int i = 0; i < totalJobs; i++) {
                Job job = jobsList.get(i);
                if (!job.proc.isAlive()) {
                    job.status = "Done";
                    if (job.cmd.endsWith("&")) {
                        job.cmd = job.cmd.substring(0, job.cmd.length() - 1).stripTrailing();
                    }
                }
                String marker = " ";
                if (i == totalJobs - 1) marker = "+";
                else if (i == totalJobs - 2) marker = "-";

                String statusPadded = String.format("%-24s", job.status);
                out.println("[" + job.id + "]" + marker + "  " + statusPadded + job.cmd);

                if (job.status.equals("Running")) {
                    jobsToKeep.add(job);
                }
            }
            jobsList.clear();
            jobsList.addAll(jobsToKeep);
        } else {
            String foundPath = findPath(cmd);
            if (foundPath != null) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(args);
                    pb.directory(currentDir);
                    Process proc = pb.start();
                    if (inFd != null) {
                        new Thread(() -> {
                            try {
                                inFd.transferTo(proc.getOutputStream());
                                proc.getOutputStream().close();
                            } catch (IOException ignored) {}
                        }).start();
                    }
                    Thread outThread = new Thread(() -> {
                        try {
                            proc.getInputStream().transferTo(outFp);
                            outFp.flush();
                        } catch (IOException ignored) {}
                    });
                    outThread.start();
                    Thread errThread = new Thread(() -> {
                        try {
                            proc.getErrorStream().transferTo(errFp);
                            errFp.flush();
                        } catch (IOException ignored) {}
                    });
                    errThread.start();

                    return new ShellProcess(proc, outThread, errThread);

                } catch (IOException e) {
                    err.println(cmd + ": execution error - " + e.getMessage());
                }
            } else {
                err.println(cmd + ": command not found");
            }
        }
        return null;
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
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
                if (parsedArgs.isEmpty()) continue;
            }

            String redirectStdout = null;
            String redirectStderr = null;
            boolean appendStdout = false;
            boolean appendStderr = false;
            int idx = parsedArgs.indexOf("2>>");
            if (idx != -1) {
                redirectStderr = parsedArgs.get(idx + 1);
                appendStderr = true;
                parsedArgs.remove(idx); parsedArgs.remove(idx);
            } else if ((idx = parsedArgs.indexOf("2>")) != -1) {
                redirectStderr = parsedArgs.get(idx + 1);
                appendStderr = false;
                parsedArgs.remove(idx); parsedArgs.remove(idx);
            }
            idx = parsedArgs.indexOf(">>");
            if (idx != -1) {
                redirectStdout = parsedArgs.get(idx + 1);
                appendStdout = true;
                parsedArgs.remove(idx); parsedArgs.remove(idx);
            } else if ((idx = parsedArgs.indexOf("1>>")) != -1) {
                redirectStdout = parsedArgs.get(idx + 1);
                appendStdout = true;
                parsedArgs.remove(idx); parsedArgs.remove(idx);
            } else if ((idx = parsedArgs.indexOf(">")) != -1) {
                redirectStdout = parsedArgs.get(idx + 1);
                appendStdout = false;
                parsedArgs.remove(idx); parsedArgs.remove(idx);
            } else if ((idx = parsedArgs.indexOf("1>")) != -1) {
                redirectStdout = parsedArgs.get(idx + 1);
                appendStdout = false;
                parsedArgs.remove(idx); parsedArgs.remove(idx);
            }

            try {
                OutputStream outFp = redirectStdout != null ? new FileOutputStream(redirectStdout, appendStdout) : System.out;
                OutputStream errFp = redirectStderr != null ? new FileOutputStream(redirectStderr, appendStderr) : System.err;

                if (parsedArgs.contains("|")) {
                    List<List<String>> commands = new ArrayList<>();
                    List<String> currentCmd = new ArrayList<>();
                    for (String arg : parsedArgs) {
                        if (arg.equals("|")) {
                            if (!currentCmd.isEmpty()) commands.add(currentCmd);
                            currentCmd = new ArrayList<>();
                        } else {
                            currentCmd.add(arg);
                        }
                    }
                    if (!currentCmd.isEmpty()) commands.add(currentCmd);

                    List<ShellProcess> processes = new ArrayList<>();
                    InputStream prevR = null;

                    for (int i = 0; i < commands.size(); i++) {
                        List<String> cmdArgs = commands.get(i);
                        boolean isLast = (i == commands.size() - 1);

                        OutputStream currentOut;
                        InputStream nextPrevR = null;

                        if (!isLast) {
                            PipedOutputStream pos = new PipedOutputStream();
                            PipedInputStream pis = new PipedInputStream(pos);
                            currentOut = pos;
                            nextPrevR = pis;
                        } else {
                            currentOut = outFp;
                        }

                        ShellProcess p = executeCommand(cmdArgs, currentOut, errFp, prevR);
                        if (p != null) processes.add(p);

                        if (!isLast) {
                            currentOut.close(); 
                        }
                        if (prevR != null) {
                            prevR.close();
                        }
                        if (!isLast) {
                            prevR = nextPrevR;
                        }
                    }

                    for (ShellProcess p : processes) {
                        if (p != null) {
                            p.waitForCompletion();
                        }
                    }

                    if (redirectStdout != null) outFp.close();
                    if (redirectStderr != null) errFp.close();
                    continue;
                }

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

                if (redirectStdout != null) outFp.close();
                if (redirectStderr != null) errFp.close();

            } catch (IOException | InterruptedException e) {
                System.err.println("I/O Error: " + e.getMessage());
            }
        }
    }
}