import java.io.*;
import java.util.*;

public class CustomShell {
    static int jobCounter = 1;
    static List<Job> jobsList = new ArrayList<>();

    static class Job {
        int id;
        long pid;
        String cmd;
        String status;
        Process proc;

        public Job(int id, long pid, String cmd, String status, Process proc) {
            this.id = id;
            this.pid = pid;
            this.cmd = cmd;
            this.status = status;
            this.proc = proc;
        }
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            if (!scanner.hasNextLine()) break;
            String command = scanner.nextLine().trim();
            if (command.isEmpty()) continue;

            List<String> parsedArgs = parseArguments(command);
            if (parsedArgs.isEmpty()) continue;

            boolean runInBackground = false;
            if (parsedArgs.get(parsedArgs.size() - 1).equals("&")) {
                runInBackground = true;
                parsedArgs.remove(parsedArgs.size() - 1);
                if (parsedArgs.isEmpty()) continue;
            }

            List<List<String>> pipelines = new ArrayList<>();
            List<String> currentCmd = new ArrayList<>();
            for (String arg : parsedArgs) {
                if (arg.equals("|")) {
                    if (!currentCmd.isEmpty()) pipelines.add(currentCmd);
                    currentCmd = new ArrayList<>();
                } else {
                    currentCmd.add(arg);
                }
            }
            if (!currentCmd.isEmpty()) pipelines.add(currentCmd);

            if (pipelines.size() > 1) {
                executePipeline(pipelines, runInBackground, command);
            } else {
                executeSingleCommand(pipelines.get(0), runInBackground, command);
            }
        }
        scanner.close();
    }

    static void executeSingleCommand(List<String> args, boolean background, String fullCmd) {
        File outFile = null, errFile = null;
        boolean appendOut = false, appendErr = false;
        List<String> cleanArgs = new ArrayList<>();

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ((arg.equals(">") || arg.equals("1>")) && i + 1 < args.size()) {
                outFile = new File(args.get(++i)); appendOut = false;
            } else if ((arg.equals(">>") || arg.equals("1>>")) && i + 1 < args.size()) {
                outFile = new File(args.get(++i)); appendOut = true;
            } else if (arg.equals("2>") && i + 1 < args.size()) {
                errFile = new File(args.get(++i)); appendErr = false;
            } else if (arg.equals("2>>") && i + 1 < args.size()) {
                errFile = new File(args.get(++i)); appendErr = true;
            } else {
                cleanArgs.add(arg);
            }
        }

        if (cleanArgs.isEmpty()) return;
        String cmd = cleanArgs.get(0);
        List<String> builtins = Arrays.asList("echo", "exit", "type", "pwd", "cd", "jobs");

        if (builtins.contains(cmd)) {
            try (PrintStream out = getStream(outFile, appendOut, System.out);
                 PrintStream err = getStream(errFile, appendErr, System.err)) {
                executeBuiltin(cleanArgs, out, err);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
        } else {
            String path = findPath(cmd);
            if (path == null) {
                System.err.println(cmd + ": command not found");
                return;
            }
            try {
                ProcessBuilder pb = new ProcessBuilder(cleanArgs);
                pb.directory(new File(System.getProperty("user.dir")));

                if (outFile != null) pb.redirectOutput(appendOut ? ProcessBuilder.Redirect.appendTo(outFile) : ProcessBuilder.Redirect.to(outFile));
                else pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                
                if (errFile != null) pb.redirectError(appendErr ? ProcessBuilder.Redirect.appendTo(errFile) : ProcessBuilder.Redirect.to(errFile));
                else pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                
                pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

                Process p = pb.start();
                if (background) {
                    System.out.println("[" + jobCounter + "] " + p.pid());
                    jobsList.add(new Job(jobCounter++, p.pid(), fullCmd, "Running", p));
                } else {
                    p.waitFor();
                }
            } catch (Exception e) {
                System.err.println("Error executing " + cmd + ": " + e.getMessage());
            }
        }
    }

    static void executePipeline(List<List<String>> pipelines, boolean background, String fullCmd) {
        List<ProcessBuilder> builders = new ArrayList<>();
        
        for (List<String> pipelineArgs : pipelines) {
            List<String> cleanArgs = new ArrayList<>();
            File outFile = null, errFile = null;
            boolean appendOut = false, appendErr = false;
            
            for (int i = 0; i < pipelineArgs.size(); i++) {
                String arg = pipelineArgs.get(i);
                if (arg.equals(">") || arg.equals("1>")) { outFile = new File(pipelineArgs.get(++i)); appendOut = false; }
                else if (arg.equals(">>") || arg.equals("1>>")) { outFile = new File(pipelineArgs.get(++i)); appendOut = true; }
                else if (arg.equals("2>")) { errFile = new File(pipelineArgs.get(++i)); appendErr = false; }
                else if (arg.equals("2>>")) { errFile = new File(pipelineArgs.get(++i)); appendErr = true; }
                else cleanArgs.add(arg);
            }

            ProcessBuilder pb = new ProcessBuilder(cleanArgs);
            pb.directory(new File(System.getProperty("user.dir")));
            if (outFile != null) pb.redirectOutput(appendOut ? ProcessBuilder.Redirect.appendTo(outFile) : ProcessBuilder.Redirect.to(outFile));
            if (errFile != null) pb.redirectError(appendErr ? ProcessBuilder.Redirect.appendTo(errFile) : ProcessBuilder.Redirect.to(errFile));
            
            builders.add(pb);
        }

        try {
            List<Process> processes = ProcessBuilder.startPipeline(builders);
            Process lastProc = processes.get(processes.size() - 1);
            
            if (background) {
                System.out.println("[" + jobCounter + "] " + lastProc.pid());
                jobsList.add(new Job(jobCounter++, lastProc.pid(), fullCmd, "Running", lastProc));
            } else {
                for (Process p : processes) {
                    p.waitFor();
                }
            }
        } catch (Exception e) {
            System.err.println("Pipeline error: " + e.getMessage());
        }
    }

    static void executeBuiltin(List<String> args, PrintStream out, PrintStream err) {
        String cmd = args.get(0);
        
        if (cmd.equals("exit")) {
            System.exit(0);
        } else if (cmd.equals("echo")) {
            out.println(String.join(" ", args.subList(1, args.size())));
        } else if (cmd.equals("pwd")) {
            out.println(System.getProperty("user.dir"));
        } else if (cmd.equals("type") && args.size() > 1) {
            String target = args.get(1);
            List<String> builtins = Arrays.asList("echo", "exit", "type", "pwd", "cd", "jobs");
            if (builtins.contains(target)) {
                out.println(target + " is a shell builtin");
            } else {
                String found = findPath(target);
                if (found != null) out.println(target + " is " + found);
                else out.println(target + ": not found");
            }
        } else if (cmd.equals("cd") && args.size() > 1) {
            String dir = args.get(1);
            if (dir.equals("~")) dir = System.getProperty("user.home");
            
            File f = new File(dir);
            if (!f.isAbsolute()) f = new File(System.getProperty("user.dir"), dir);

            if (f.exists() && f.isDirectory()) {
                System.setProperty("user.dir", f.getAbsolutePath());
            } else {
                err.println("cd: " + dir + ": No such file or directory");
            }
        } else if (cmd.equals("jobs")) {
            List<Job> toKeep = new ArrayList<>();
            int total = jobsList.size();
            
            for (int i = 0; i < total; i++) {
                Job j = jobsList.get(i);
                if (!j.proc.isAlive()) {
                    j.status = "Done";
                    if (j.cmd.endsWith("&")) j.cmd = j.cmd.substring(0, j.cmd.length() - 1).trim();
                }
                
                String marker = (i == total - 1) ? "+" : (i == total - 2) ? "-" : " ";
                String statusPadded = String.format("%-24s", j.status);
                
                out.printf("[%d]%s  %s%s%n", j.id, marker, statusPadded, j.cmd);
                if (j.status.equals("Running")) toKeep.add(j);
            }
            jobsList.clear();
            jobsList.addAll(toKeep);
        }
    }

    public static List<String> parseArguments(String cmdArg) {
        List<String> args = new ArrayList<>();
        StringBuilder currentArg = new StringBuilder();
        boolean inSingleQuotes = false, inDoubleQuotes = false, isEscaping = false;

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
        if (currentArg.length() > 0) args.add(currentArg.toString());
        return args;
    }

    public static String findPath(String cmdName) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        String[] paths = pathEnv.split(File.pathSeparator);
        for (String p : paths) {
            File f = new File(p, cmdName);
            if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    static PrintStream getStream(File f, boolean append, PrintStream fallback) throws Exception {
        if (f == null) return fallback;
        return new PrintStream(new FileOutputStream(f, append));
    }
}