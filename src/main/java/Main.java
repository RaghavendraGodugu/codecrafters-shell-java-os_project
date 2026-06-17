import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.TimeUnit; // Added for precise non-blocking exit state flushing

public class Main {

    static File currentDirectory = new File(System.getProperty("user.dir"));

    static class BackgroundJob {
        int javaJobNumber;
        long pid;
        String command;
        String status;
        List<Process> pipelineProcesses;

        BackgroundJob(int javaJobNumber, long pid, String command, List<Process> pipelineProcesses) {
            this.javaJobNumber = javaJobNumber;
            this.pid = pid;
            this.command = command;
            this.status = "Running";
            this.pipelineProcesses = pipelineProcesses;
        }

        boolean isAlive() {
            if (pipelineProcesses == null) return false;
            for (Process p : pipelineProcesses) {
                // Force Java to update process status handles
                try {
                    p.waitFor(0, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    // Ignore
                }
                if (p.isAlive()) return true;
            }
            return false;
        }
    }

    static List<BackgroundJob> activeJobs = new ArrayList<>();

    private static int getSmallestAvailableJobNumber() {
        int candidate = 1;
        while (true) {
            boolean taken = false;
            for (BackgroundJob job : activeJobs) {
                if (job.javaJobNumber == candidate) {
                    taken = true;
                    break;
                }
            }
            if (!taken) return candidate;
            candidate++;
        }
    }

    private static void updateJobStatuses() {
        for (BackgroundJob job : activeJobs) {
            if (job.status.equals("Running") && !job.isAlive()) {
                job.status = "Done";
                if (job.command.endsWith(" &")) {
                    job.command = job.command.substring(0, job.command.length() - 2);
                }
            }
        }
    }

    private static void printAndReapJobs(boolean onlyPrintDone) {
        updateJobStatuses();
        int numJobs = activeJobs.size();
        List<BackgroundJob> jobsToRemove = new ArrayList<>();

        for (int i = 0; i < numJobs; i++) {
            BackgroundJob job = activeJobs.get(i);
            boolean isDone = job.status.equals("Done");

            char marker = ' ';
            if (i == numJobs - 1) marker = '+';
            else if (i == numJobs - 2) marker = '-';

            String paddedStatus = String.format("%-24s", job.status);
            String line = "[" + job.javaJobNumber + "]" + marker + "  " + paddedStatus + job.command;

            if (onlyPrintDone) {
                if (isDone) {
                    System.out.println(line);
                    jobsToRemove.add(job);
                }
            } else {
                System.out.println(line);
                if (isDone) jobsToRemove.add(job);
            }
        }
        activeJobs.removeAll(jobsToRemove);
    }

    public static File findExecutable(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        String[] paths = pathEnv.split(File.pathSeparator);
        for (String path : paths) {
            File file = new File(path, command);
            if (file.exists() && file.canExecute()) return file;
        }
        return null;
    }

    public static boolean isBuiltin(String cmd) {
        return cmd.equals("echo") || cmd.equals("exit") || cmd.equals("type") || 
               cmd.equals("pwd") || cmd.equals("cd") || cmd.equals("jobs");
    }

    private static void executeBuiltin(List<String> cmdTokens, PrintStream outStream) {
        String command = cmdTokens.get(0);
        if (command.equals("echo")) {
            for (int i = 1; i < cmdTokens.size(); i++) {
                if (i > 1) outStream.print(" ");
                outStream.print(cmdTokens.get(i));
            }
            outStream.println();
        } else if (command.equals("type")) {
            if (cmdTokens.size() < 2) {
                outStream.println("type: missing operand");
                return;
            }
            String target = cmdTokens.get(1);
            if (isBuiltin(target)) {
                outStream.println(target + " is a shell builtin");
            } else {
                File executable = findExecutable(target);
                if (executable != null) outStream.println(target + " is " + executable.getAbsolutePath());
                else outStream.println(target + ": not found");
            }
        } else if (command.equals("pwd")) {
            try { outStream.println(currentDirectory.getCanonicalPath()); } 
            catch (Exception e) { outStream.println(currentDirectory.getAbsolutePath()); }
        } else if (command.equals("cd")) {
            try {
                String path = cmdTokens.size() > 1 ? cmdTokens.get(1) : System.getenv("HOME");
                if (path.equals("~")) path = System.getenv("HOME");
                File newDir = path.startsWith("/") ? new File(path) : new File(currentDirectory, path);
                newDir = newDir.getCanonicalFile();
                if (newDir.exists() && newDir.isDirectory()) currentDirectory = newDir;
                else outStream.println("cd: " + path + ": No such file or directory");
            } catch (Exception e) {
                outStream.println("cd: error tracking directory change");
            }
        } else if (command.equals("jobs")) {
            PrintStream originalOut = System.out;
            System.setOut(outStream);
            printAndReapJobs(false);
            System.setOut(originalOut);
        }
    }

    public static List<String> parseCommand(String input) {
        List<String> arguments = new ArrayList<>();
        StringBuilder currentWord = new StringBuilder();
        boolean insideSingleQuotes = false;
        boolean insideDoubleQuotes = false;
        boolean escaped = false;

        for (int i = 0; i < input.length(); i++) {
            char currentChar = input.charAt(i);
            if (escaped) {
                currentWord.append(currentChar);
                escaped = false;
            } else if (insideDoubleQuotes && currentChar == '\\' && i + 1 < input.length()) {
                char nextChar = input.charAt(i + 1);
                if (nextChar == '"' || nextChar == '\\' || nextChar == '$' || nextChar == '`') {
                    currentWord.append(nextChar);
                    i++;
                } else {
                    currentWord.append('\\');
                }
            } else if (currentChar == '\\' && !insideSingleQuotes && !insideDoubleQuotes) {
                escaped = true;
            } else if (currentChar == '\'' && !insideDoubleQuotes) {
                insideSingleQuotes = !insideSingleQuotes;
            } else if (currentChar == '"' && !insideSingleQuotes) {
                insideDoubleQuotes = !insideDoubleQuotes;
            } else if (Character.isWhitespace(currentChar) && !insideSingleQuotes && !insideDoubleQuotes) {
                if (currentWord.length() > 0) {
                    arguments.add(currentWord.toString());
                    currentWord.setLength(0);
                }
            } else {
                currentWord.append(currentChar);
            }
        }
        if (currentWord.length() > 0) arguments.add(currentWord.toString());
        return arguments;
    }

    private static void runWithRedirect(Runnable action, String outPath, boolean appendOut, String errPath, boolean appendErr) throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        FileOutputStream fosOut = null; FileOutputStream fosErr = null;
        PrintStream psOut = null; PrintStream psErr = null;

        try {
            if (outPath != null) {
                File outFile = new File(outPath);
                if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();
                fosOut = new FileOutputStream(outFile, appendOut);
                psOut = new PrintStream(fosOut);
                System.setOut(psOut);
            }
            if (errPath != null) {
                File errFile = new File(errPath);
                if (errFile.getParentFile() != null) errFile.getParentFile().mkdirs();
                fosErr = new FileOutputStream(errFile, appendErr);
                psErr = new PrintStream(fosErr);
                System.setErr(psErr);
            }
            action.run();
            System.out.flush(); System.err.flush();
        } finally {
            if (psOut != null) psOut.close();
            if (fosOut != null) fosOut.close();
            if (psErr != null) psErr.close();
            if (fosErr != null) fosErr.close();
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            printAndReapJobs(true);

            System.out.print("$ ");
            if (!scanner.hasNextLine()) break;
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;
            if (input.equals("exit")) break;

            List<String> tokens = parseCommand(input);
            boolean isBackground = false;
            if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).equals("&")) {
                isBackground = true;
                tokens.remove(tokens.size() - 1);
            }

            List<List<String>> stagesTokens = new ArrayList<>();
            List<String> currentStage = new ArrayList<>();
            for (String token : tokens) {
                if (token.equals("|")) {
                    stagesTokens.add(currentStage);
                    currentStage = new ArrayList<>();
                } else {
                    currentStage.add(token);
                }
            }
            stagesTokens.add(currentStage);

            if (stagesTokens.size() > 1) {
                boolean containsBuiltin = false;
                for (List<String> stage : stagesTokens) {
                    if (!stage.isEmpty() && isBuiltin(stage.get(0))) {
                        containsBuiltin = true;
                        break;
                    }
                }

                List<String> lastStageTokens = stagesTokens.get(stagesTokens.size() - 1);
                String globalOutFile = null;
                boolean globalAppendOut = false;
                List<String> cleanLastCmd = new ArrayList<>();
                for (int i = 0; i < lastStageTokens.size(); i++) {
                    String t = lastStageTokens.get(i);
                    if (t.equals(">") || t.equals("1>")) {
                        if (i + 1 < lastStageTokens.size()) { globalOutFile = lastStageTokens.get(i + 1); globalAppendOut = false; i++; }
                    } else if (t.equals(">>") || t.equals("1>>")) {
                        if (i + 1 < lastStageTokens.size()) { globalOutFile = lastStageTokens.get(i + 1); globalAppendOut = true; i++; }
                    } else {
                        cleanLastCmd.add(t);
                    }
                }
                stagesTokens.set(stagesTokens.size() - 1, cleanLastCmd);

                if (!containsBuiltin) {
                    List<ProcessBuilder> builders = new ArrayList<>();
                    for (int i = 0; i < stagesTokens.size(); i++) {
                        List<String> stageCmd = stagesTokens.get(i);
                        if (stageCmd.isEmpty()) continue;
                        
                        ProcessBuilder pb = new ProcessBuilder(stageCmd).directory(currentDirectory);
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                        
                        if (i == 0) {
                            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                        }
                        if (i == stagesTokens.size() - 1) {
                            if (globalOutFile != null) {
                                File outF = new File(globalOutFile);
                                if (outF.getParentFile() != null) outF.getParentFile().mkdirs();
                                pb.redirectOutput(globalAppendOut ? ProcessBuilder.Redirect.appendTo(outF) : ProcessBuilder.Redirect.to(outF));
                            } else {
                                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                            }
                        }
                        builders.add(pb);
                    }

                    List<Process> pipelineProcesses = ProcessBuilder.startPipeline(builders);
                    if (isBackground) {
                        long leadPid = pipelineProcesses.get(0).pid();
                        int assignedJobNumber = getSmallestAvailableJobNumber();
                        System.out.println("[" + assignedJobNumber + "] " + leadPid);
                        activeJobs.add(new BackgroundJob(assignedJobNumber, leadPid, input, pipelineProcesses));
                    } else {
                        for (Process p : pipelineProcesses) p.waitFor();
                    }
                } 
                else if (stagesTokens.size() == 2) {
                    List<String> leftCmd = stagesTokens.get(0);
                    List<String> rightCmd = stagesTokens.get(1);

                    String leftCommandName = leftCmd.get(0);
                    String rightCommandName = rightCmd.get(0);

                    if (isBuiltin(leftCommandName) && !isBuiltin(rightCommandName)) {
                        ProcessBuilder pb2 = new ProcessBuilder(rightCmd).directory(currentDirectory);
                        pb2.redirectInput(ProcessBuilder.Redirect.PIPE);
                        pb2.redirectError(ProcessBuilder.Redirect.INHERIT);

                        if (globalOutFile != null) {
                            File outF = new File(globalOutFile);
                            if (outF.getParentFile() != null) outF.getParentFile().mkdirs();
                            pb2.redirectOutput(globalAppendOut ? ProcessBuilder.Redirect.appendTo(outF) : ProcessBuilder.Redirect.to(outF));
                        } else {
                            pb2.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        }

                        Process p2 = pb2.start();
                        try (PrintStream ps = new PrintStream(p2.getOutputStream())) {
                            executeBuiltin(leftCmd, ps);
                        }

                        if (isBackground) {
                            int assignedJobNumber = getSmallestAvailableJobNumber();
                            System.out.println("[" + assignedJobNumber + "] " + p2.pid());
                            activeJobs.add(new BackgroundJob(assignedJobNumber, p2.pid(), input, List.of(p2)));
                        } else {
                            p2.waitFor();
                        }
                    } else if (!isBuiltin(leftCommandName) && isBuiltin(rightCommandName)) {
                        ProcessBuilder pb1 = new ProcessBuilder(leftCmd).directory(currentDirectory);
                        pb1.redirectInput(ProcessBuilder.Redirect.INHERIT);
                        pb1.redirectOutput(ProcessBuilder.Redirect.PIPE);
                        pb1.redirectError(ProcessBuilder.Redirect.INHERIT);

                        Process p1 = pb1.start();
                        try (InputStream is = p1.getInputStream();
                             BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                            while (br.readLine() != null) {}
                        }

                        PrintStream outTarget = System.out;
                        FileOutputStream fos = null;
                        if (globalOutFile != null) {
                            File outF = new File(globalOutFile);
                            if (outF.getParentFile() != null) outF.getParentFile().mkdirs();
                            fos = new FileOutputStream(outF, globalAppendOut);
                            outTarget = new PrintStream(fos);
                        }

                        executeBuiltin(rightCmd, outTarget);
                        if (fos != null) outTarget.close();

                        if (isBackground) {
                            int assignedJobNumber = getSmallestAvailableJobNumber();
                            System.out.println("[" + assignedJobNumber + "] " + p1.pid());
                            activeJobs.add(new BackgroundJob(assignedJobNumber, p1.pid(), input, List.of(p1)));
                        } else {
                            p1.waitFor();
                        }
                    } else {
                        PrintStream outTarget = System.out;
                        FileOutputStream fos = null;
                        if (globalOutFile != null) {
                            File outF = new File(globalOutFile);
                            if (outF.getParentFile() != null) outF.getParentFile().mkdirs();
                            fos = new FileOutputStream(outF, globalAppendOut);
                            outTarget = new PrintStream(fos);
                        }
                        executeBuiltin(rightCmd, outTarget);
                        if (fos != null) outTarget.close();
                    }
                }
                continue;
            }

            // --- Single Command Handling Block ---
            String outFile = null; String errFile = null;
            boolean appendOut = false; boolean appendErr = false;
            List<String> cmdTokens = new ArrayList<>();

            for (int i = 0; i < tokens.size(); i++) {
                String t = tokens.get(i);
                if (t.equals(">") || t.equals("1>")) {
                    if (i + 1 < tokens.size()) { outFile = tokens.get(i + 1); appendOut = false; i++; }
                } else if (t.equals(">>") || t.equals("1>>")) {
                    if (i + 1 < tokens.size()) { outFile = tokens.get(i + 1); appendOut = true; i++; }
                } else if (t.equals("2>")) {
                    if (i + 1 < tokens.size()) { errFile = tokens.get(i + 1); appendErr = false; i++; }
                } else if (t.equals("2>>")) {
                    if (i + 1 < tokens.size()) { errFile = tokens.get(i + 1); appendErr = true; i++; }
                } else {
                    cmdTokens.add(t);
                }
            }

            if (cmdTokens.isEmpty()) continue;
            String command = cmdTokens.get(0);

            if (isBuiltin(command)) {
                String finalOutFile = outFile;
                boolean finalAppendOut = appendOut;
                String finalErrFile = errFile;
                boolean finalAppendErr = appendErr;
                runWithRedirect(() -> executeBuiltin(cmdTokens, System.out), finalOutFile, finalAppendOut, finalErrFile, finalAppendErr);
            } else {
                File executable = findExecutable(command);
                if (executable != null) {
                    ProcessBuilder processBuilder = new ProcessBuilder(cmdTokens).directory(currentDirectory);

                    if (outFile != null) {
                        File outF = new File(outFile);
                        if (outF.getParentFile() != null) outF.getParentFile().mkdirs();
                        processBuilder.redirectOutput(appendOut ? ProcessBuilder.Redirect.appendTo(outF) : ProcessBuilder.Redirect.to(outF));
                    } else {
                        processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }

                    if (errFile != null) {
                        File errF = new File(errFile);
                        if (errF.getParentFile() != null) errF.getParentFile().mkdirs();
                        processBuilder.redirectError(appendErr ? ProcessBuilder.Redirect.appendTo(errF) : ProcessBuilder.Redirect.to(errF));
                    } else {
                        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }

                    if (isBackground && outFile == null && errFile == null) {
                        processBuilder.inheritIO();
                    }

                    Process process = processBuilder.start();

                    if (isBackground) {
                        long pid = process.pid();
                        int assignedJobNumber = getSmallestAvailableJobNumber();
                        System.out.println("[" + assignedJobNumber + "] " + pid);
                        String fullCmdStr = String.join(" ", cmdTokens) + " &";
                        activeJobs.add(new BackgroundJob(assignedJobNumber, pid, fullCmdStr, List.of(process)));
                    } else {
                        process.waitFor();
                    }
                } else {
                    runWithRedirect(() -> System.out.println(command + ": command not found"), outFile, appendOut, errFile, appendErr);
                }
            }
        }
    }
}