import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.TreeMap;
import java.util.Iterator;

class BackgroundJob {
    int jobId;
    long pid;
    Process process;
    String commandLine;

    public BackgroundJob(int jobId, long pid, Process process, String commandLine) {
        this.jobId = jobId;
        this.pid = pid;
        this.process = process;
        this.commandLine = commandLine;
    }
}

public class Main {
    private static final List<String> BUILTINS = List.of("echo", "exit", "type", "pwd", "cd", "complete", "jobs");
    private static final Map<String, String> completionRegistry = new TreeMap<>();
    
    // Asynchronous state management
    private static final List<BackgroundJob> activeJobs = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        setTerminalRawMode();

        InputStream in = System.in;
        StringBuilder currentLine = new StringBuilder();
        boolean lastWasTab = false;

        while (true) {
            // REAP BEFORE PROMPT
            checkAndReapBackgroundJobs(false);

            System.out.print("$ ");
            System.out.flush();
            currentLine.setLength(0);
            lastWasTab = false;

            while (true) {
                int readByte = in.read();
                if (readByte == -1) break;

                char c = (char) readByte;

                // Handle Tab Keypress
                if (c == '\t') {
                    String currentText = currentLine.toString();
                    
                    if (!currentText.isEmpty()) {
                        if (!currentText.contains(" ")) {
                            // --- Command Completion ---
                            Set<String> matchSet = new HashSet<>();
                            for (String builtin : BUILTINS) {
                                if (builtin.startsWith(currentText)) matchSet.add(builtin);
                            }

                            String pathEnv = System.getenv("PATH");
                            if (pathEnv != null) {
                                String[] directories = pathEnv.split(File.pathSeparator);
                                for (String dir : directories) {
                                    File folder = new File(dir);
                                    if (folder.exists() && folder.isDirectory()) {
                                        File[] files = folder.listFiles();
                                        if (files != null) {
                                            for (File file : files) {
                                                if (file.isFile() && file.canExecute() && file.getName().startsWith(currentText)) {
                                                    matchSet.add(file.getName());
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            List<String> matches = new ArrayList<>(matchSet);
                            Collections.sort(matches);

                            if (matches.size() == 1) {
                                String remaining = matches.get(0).substring(currentText.length()) + " ";
                                System.out.print(remaining);
                                System.out.flush();
                                currentLine.append(remaining);
                                lastWasTab = false;
                            } else if (matches.size() > 1) {
                                String lcp = findLCP(matches);
                                if (lcp.length() > currentText.length()) {
                                    String remaining = lcp.substring(currentText.length());
                                    System.out.print(remaining);
                                    System.out.flush();
                                    currentLine.append(remaining);
                                    lastWasTab = false;
                                } else {
                                    if (!lastWasTab) {
                                        System.out.print("\u0007");
                                        System.out.flush();
                                        lastWasTab = true;
                                    } else {
                                        System.out.print("\r\n");
                                        for (int i = 0; i < matches.size(); i++) {
                                            System.out.print(matches.get(i));
                                            if (i < matches.size() - 1) System.out.print("  ");
                                        }
                                        System.out.print("\r\n$ " + currentText);
                                        System.out.flush();
                                        lastWasTab = false;
                                    }
                                }
                            } else {
                                System.out.print("\u0007");
                                System.out.flush();
                                lastWasTab = false;
                            }
                        } else {
                            // --- Argument Position Completion ---
                            int firstSpaceIdx = currentText.indexOf(' ');
                            String cmdName = currentText.substring(0, firstSpaceIdx);
                            int lastSpaceIdx = currentText.lastIndexOf(' ');
                            String currentWord = currentText.substring(lastSpaceIdx + 1);

                            if (completionRegistry.containsKey(cmdName)) {
                                String scriptPath = completionRegistry.get(cmdName);
                                
                                String prevWord = "";
                                String textBeforeCurrent = currentText.substring(0, lastSpaceIdx).trim();
                                if (!textBeforeCurrent.isEmpty()) {
                                    int prevSpaceIdx = textBeforeCurrent.lastIndexOf(' ');
                                    if (prevSpaceIdx == -1) {
                                        prevWord = textBeforeCurrent;
                                    } else {
                                        prevWord = textBeforeCurrent.substring(prevSpaceIdx + 1);
                                    }
                                }

                                try {
                                    ProcessBuilder pb = new ProcessBuilder(scriptPath, cmdName, currentWord, prevWord);
                                    Map<String, String> env = pb.environment();
                                    env.put("COMP_LINE", currentText);
                                    env.put("COMP_POINT", String.valueOf(currentText.length()));

                                    Process process = pb.start();
                                    
                                    List<String> scriptCompletions = new ArrayList<>();
                                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                                        String line;
                                        while ((line = reader.readLine()) != null) {
                                            if (!line.trim().isEmpty()) {
                                                scriptCompletions.add(line.trim());
                                            }
                                        }
                                    }
                                    process.waitFor();
                                    Collections.sort(scriptCompletions);

                                    if (scriptCompletions.size() == 1) {
                                        String matchedName = scriptCompletions.get(0);
                                        String remaining = matchedName.substring(currentWord.length()) + " ";
                                        System.out.print(remaining);
                                        System.out.flush();
                                        currentLine.append(remaining);
                                        lastWasTab = false;
                                    } else if (scriptCompletions.size() > 1) {
                                        String lcp = findLCP(scriptCompletions);
                                        if (lcp.length() > currentWord.length()) {
                                            String remaining = lcp.substring(currentWord.length());
                                            System.out.print(remaining);
                                            System.out.flush();
                                            currentLine.append(remaining);
                                            lastWasTab = false;
                                        } else {
                                            if (!lastWasTab) {
                                                System.out.print("\u0007");
                                                System.out.flush();
                                                lastWasTab = true;
                                            } else {
                                                System.out.print("\r\n");
                                                for (int i = 0; i < scriptCompletions.size(); i++) {
                                                    System.out.print(scriptCompletions.get(i));
                                                    if (i < scriptCompletions.size() - 1) System.out.print("  ");
                                                }
                                                System.out.print("\r\n$ " + currentText);
                                                System.out.flush();
                                                lastWasTab = false;
                                            }
                                        }
                                    } else {
                                        System.out.print("\u0007");
                                        System.out.flush();
                                        lastWasTab = false;
                                    }
                                } catch (Exception e) {
                                    System.out.print("\u0007");
                                    System.out.flush();
                                    lastWasTab = false;
                                }
                            } else {
                                // --- Fallback to Path, File & Directory Completion ---
                                File targetDir = new File(System.getProperty("user.dir"));
                                String filePrefix = currentWord;

                                if (currentWord.contains("/")) {
                                    int lastSlashIdx = currentWord.lastIndexOf('/');
                                    String dirPath = currentWord.substring(0, lastSlashIdx + 1);
                                    filePrefix = currentWord.substring(lastSlashIdx + 1);
                                    targetDir = new File(targetDir, dirPath);
                                }

                                File[] files = targetDir.listFiles();
                                List<String> fileMatches = new ArrayList<>();
                                List<String> rawMatches = new ArrayList<>();

                                if (files != null) {
                                    for (File f : files) {
                                        if (f.getName().startsWith(filePrefix)) {
                                            rawMatches.add(f.getName());
                                            if (f.isDirectory()) {
                                                fileMatches.add(f.getName() + "/");
                                            } else {
                                                fileMatches.add(f.getName());
                                            }
                                        }
                                    }
                                }
                                Collections.sort(fileMatches);
                                Collections.sort(rawMatches);

                                if (fileMatches.size() == 1) {
                                    String matchedName = fileMatches.get(0);
                                    String remaining = matchedName.substring(filePrefix.length());
                                    
                                    if (!matchedName.endsWith("/")) {
                                        remaining += " ";
                                    }

                                    System.out.print(remaining);
                                    System.out.flush();
                                    currentLine.append(remaining);
                                    lastWasTab = false;
                                } else if (fileMatches.size() > 1) {
                                    String lcp = findLCP(rawMatches);
                                    
                                    if (lcp.length() > filePrefix.length()) {
                                        String remaining = lcp.substring(filePrefix.length());
                                        System.out.print(remaining);
                                        System.out.flush();
                                        currentLine.append(remaining);
                                        lastWasTab = false;
                                    } else {
                                        if (!lastWasTab) {
                                            System.out.print("\u0007");
                                            System.out.flush();
                                            lastWasTab = true;
                                        } else {
                                            System.out.print("\r\n");
                                            for (int i = 0; i < fileMatches.size(); i++) {
                                                System.out.print(fileMatches.get(i));
                                                if (i < fileMatches.size() - 1) System.out.print("  ");
                                            }
                                            System.out.print("\r\n$ " + currentText);
                                            System.out.flush();
                                            lastWasTab = false;
                                        }
                                    }
                                } else {
                                    System.out.print("\u0007");
                                    System.out.flush();
                                    lastWasTab = false;
                                }
                            }
                        }
                    } else {
                        System.out.print("\u0007");
                        System.out.flush();
                        lastWasTab = false;
                    }
                }
                // Handle Enter Keypress
                else if (c == '\n' || c == '\r') {
                    System.out.print("\r\n");
                    break;
                }
                // Handle Backspace Keypress
                else if (c == 127 || c == '\b') {
                    lastWasTab = false;
                    if (currentLine.length() > 0) {
                        currentLine.setLength(currentLine.length() - 1);
                        System.out.print("\b \b");
                        System.out.flush();
                    }
                }
                // Handle standard text characters
                else {
                    lastWasTab = false;
                    currentLine.append(c);
                    System.out.print(c);
                    System.out.flush();
                }
            }

            String input = currentLine.toString().trim();
            if (input.isEmpty()) continue;

            executeCommand(input);
        }
    }

    private static String findLCP(List<String> matches) {
        if (matches == null || matches.isEmpty()) return "";
        String prefix = matches.get(0);
        for (int i = 1; i < matches.size(); i++) {
            while (!matches.get(i).startsWith(prefix)) {
                prefix = prefix.substring(0, prefix.length() - 1);
                if (prefix.isEmpty()) return "";
            }
        }
        return prefix;
    }

    private static void setTerminalRawMode() {
        try {
            String[] cmd = {"/bin/sh", "-c", "stty -echo -icanon min 1 < /dev/tty"};
            Runtime.getRuntime().exec(cmd).waitFor();
        } catch (Exception e) {
            // Fallback
        }
    }

    private static void checkAndReapBackgroundJobs(boolean showRunning) {
        Iterator<BackgroundJob> iterator = activeJobs.iterator();
        int index = 0;

        while (iterator.hasNext()) {
            BackgroundJob job = iterator.next();
            int currentSize = activeJobs.size();
            
            String marker = " ";
            if (index == currentSize - 1) {
                marker = "+";
            } else if (index == currentSize - 2) {
                marker = "-";
            }

            if (job.process.isAlive()) {
                if (showRunning) {
                    System.out.println("[" + job.jobId + "]" + marker + "  Running                 " + job.commandLine + " &");
                }
                index++;
            } else {
                System.out.println("[" + job.jobId + "]" + marker + "  Done                 " + job.commandLine);
                iterator.remove();
            }
        }
    }

    private static ProcessBuilder createProcessBuilderForSegment(List<String> tokens) {
        if (tokens.isEmpty()) return null;
        String firstToken = tokens.get(0);

        if (BUILTINS.contains(firstToken)) {
            StringBuilder shCmd = new StringBuilder();
            if (firstToken.equals("echo")) {
                shCmd.append("echo");
                for (int i = 1; i < tokens.size(); i++) {
                    shCmd.append(" ").append(tokens.get(i));
                }
            } else if (firstToken.equals("type")) {
                shCmd.append("type");
                for (int i = 1; i < tokens.size(); i++) {
                    shCmd.append(" ").append(tokens.get(i));
                }
            } else if (firstToken.equals("pwd")) {
                shCmd.append("pwd");
            } else {
                shCmd.append(firstToken);
            }

            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", shCmd.toString());
            pb.directory(new File(System.getProperty("user.dir")));
            return pb;
        }

        String exePath = findInPath(firstToken);
        if (exePath == null) {
            System.err.println(firstToken + ": command not found");
            return null;
        }
        ProcessBuilder pb = new ProcessBuilder(tokens);
        pb.directory(new File(System.getProperty("user.dir")));
        return pb;
    }

    private static void executeCommand(String originalInput) throws Exception {
        String workingInput = originalInput;
        boolean isBackground = false;
        
        if (workingInput.endsWith("&")) {
            isBackground = true;
            workingInput = workingInput.substring(0, workingInput.length() - 1).trim();
        }

        List<String> rawTokens = parseArguments(workingInput);
        if (rawTokens.isEmpty()) return;

        // MULTI-COMMAND PIPELINES: Parse out ALL tokens separated by pipe operators sequentially
        List<List<String>> segments = new ArrayList<>();
        List<String> currentSegment = new ArrayList<>();

        for (String token : rawTokens) {
            if (token.equals("|")) {
                if (!currentSegment.isEmpty()) {
                    segments.add(currentSegment);
                    currentSegment = new ArrayList<>();
                }
            } else {
                currentSegment.add(token);
            }
        }
        if (!currentSegment.isEmpty()) {
            segments.add(currentSegment);
        }

        if (segments.size() > 1) {
            List<ProcessBuilder> builders = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                ProcessBuilder pb = createProcessBuilderForSegment(segments.get(i));
                if (pb == null) return; // Command not found error already printed

                // The last process segment in the chain must explicitly pass its output to the screen
                if (i == segments.size() - 1) {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }
                builders.add(pb);
            }

            // Fire up the full multi-command sequential pipeline chain natively
            List<Process> pipeline = ProcessBuilder.startPipeline(builders);

            if (isBackground) {
                int jobId = 1;
                if (!activeJobs.isEmpty()) {
                    int maxId = 0;
                    for (BackgroundJob job : activeJobs) {
                        if (job.jobId > maxId) maxId = job.jobId;
                    }
                    jobId = maxId + 1;
                }
                Process lastProc = pipeline.get(pipeline.size() - 1);
                System.out.println("[" + jobId + "] " + lastProc.pid());
                System.out.flush();
                activeJobs.add(new BackgroundJob(jobId, lastProc.pid(), lastProc, workingInput));
            } else {
                for (Process p : pipeline) {
                    p.waitFor();
                }
            }
            return;
        }

        // --- Standard Single-Command Processing Execution Flow ---
        List<String> tokens = rawTokens;
        String stdoutFile = null;
        String stderrFile = null;
        boolean appendStdout = false;
        boolean appendStderr = false;
        int redirectIndex = -1;

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.equals(">") || token.equals("1>")) {
                if (i + 1 < tokens.size()) {
                    stdoutFile = tokens.get(i + 1);
                    appendStdout = false;
                    redirectIndex = i;
                    break;
                }
            } else if (token.equals(">>") || token.equals("1>>")) {
                if (i + 1 < tokens.size()) {
                    stdoutFile = tokens.get(i + 1);
                    appendStdout = true;
                    redirectIndex = i;
                    break;
                }
            } else if (token.equals("2>")) {
                if (i + 1 < tokens.size()) {
                    stderrFile = tokens.get(i + 1);
                    appendStderr = false;
                    redirectIndex = i;
                    break;
                }
            } else if (token.equals("2>>")) {
                if (i + 1 < tokens.size()) {
                    stderrFile = tokens.get(i + 1);
                    appendStderr = true;
                    redirectIndex = i;
                    break;
                }
            }
        }

        List<String> commandTokens = tokens;
        if (redirectIndex != -1) {
            commandTokens = new ArrayList<>(tokens.subList(0, redirectIndex));
        }

        if (commandTokens.isEmpty()) return;
        String command = commandTokens.get(0);

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        FileOutputStream fosOut = null;
        FileOutputStream fosErr = null;

        try {
            if (stdoutFile != null) {
                File file = new File(stdoutFile);
                if (file.getParentFile() != null) file.getParentFile().mkdirs();
                fosOut = new FileOutputStream(file, appendStdout);
                System.setOut(new PrintStream(fosOut));
            }

            if (stderrFile != null) {
                File file = new File(stderrFile);
                if (file.getParentFile() != null) file.getParentFile().mkdirs();
                fosErr = new FileOutputStream(file, appendStderr);
                System.setErr(new PrintStream(fosErr));
            }

            if (workingInput.equals("exit 0") || workingInput.equals("exit")) {
                String[] cmd = {"/bin/sh", "-c", "stty sane < /dev/tty"};
                Runtime.getRuntime().exec(cmd).waitFor();
                System.exit(0);
            } else if (command.equals("jobs")) {
                checkAndReapBackgroundJobs(true);
            } else if (command.equals("complete")) {
                if (commandTokens.size() >= 3 && commandTokens.get(1).equals("-r")) {
                    String cmdName = commandTokens.get(2);
                    completionRegistry.remove(cmdName);
                } else if (commandTokens.size() == 2 && commandTokens.get(1).equals("-p")) {
                    for (Map.Entry<String, String> entry : completionRegistry.entrySet()) {
                        System.out.println("complete -C '" + entry.getValue() + "' " + entry.getKey());
                    }
                } else if (commandTokens.size() >= 3 && commandTokens.get(1).equals("-p")) {
                    String cmdName = commandTokens.get(2);
                    if (completionRegistry.containsKey(cmdName)) {
                        String scriptPath = completionRegistry.get(cmdName);
                        System.out.println("complete -C '" + scriptPath + "' " + cmdName);
                    } else {
                        System.out.println("complete: " + cmdName + ": no completion specification");
                    }
                } else if (commandTokens.size() >= 4 && (commandTokens.get(1).equals("-c") || commandTokens.get(1).equals("-C"))) {
                    String scriptPath = commandTokens.get(2);
                    String cmdName = commandTokens.get(3);
                    completionRegistry.put(cmdName, scriptPath);
                }
            } else if (command.equals("echo")) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < commandTokens.size(); i++) {
                    sb.append(commandTokens.get(i));
                    if (i < commandTokens.size() - 1) sb.append(" ");
                }
                System.out.println(sb.toString());
            } else if (command.equals("pwd")) {
                System.out.println(System.getProperty("user.dir"));
            } else if (command.equals("cd")) {
                String targetPath = commandTokens.size() > 1 ? commandTokens.get(1) : "~";
                File directory;
                if (targetPath.equals("~")) {
                    String homeDir = System.getenv("HOME");
                    directory = new File(homeDir != null ? homeDir : "/");
                } else if (targetPath.startsWith("/")) {
                    directory = new File(targetPath);
                } else {
                    directory = new File(new File(System.getProperty("user.dir")), targetPath);
                }

                if (directory.exists() && directory.isDirectory()) {
                    System.setProperty("user.dir", directory.getCanonicalPath());
                } else {
                    System.err.println("cd: " + targetPath + ": No such file or directory");
                }
            } else if (command.equals("type")) {
                String arg = commandTokens.get(1);
                if (BUILTINS.contains(arg)) {
                    System.out.println(arg + " is a shell builtin");
                } else {
                    String path = findInPath(arg);
                    if (path != null) {
                        System.out.println(arg + " is " + path);
                    } else {
                        System.out.println(arg + ": not found");
                    }
                }
            } else {
                String executablePath = findInPath(command);
                if (executablePath != null) {
                    ProcessBuilder pb = new ProcessBuilder(commandTokens);
                    pb.directory(new File(System.getProperty("user.dir")));
                    
                    if (stdoutFile != null) {
                        if (appendStdout) {
                            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(stdoutFile)));
                        } else {
                            pb.redirectOutput(new File(stdoutFile));
                        }
                    } else {
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }
                    
                    if (stderrFile != null) {
                        if (appendStderr) {
                            pb.redirectError(ProcessBuilder.Redirect.appendTo(new File(stderrFile)));
                        } else {
                            pb.redirectError(new File(stderrFile));
                        }
                    } else {
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }

                    Process process = pb.start();
                    
                    if (isBackground) {
                        int jobId = 1;
                        if (!activeJobs.isEmpty()) {
                            int maxId = 0;
                            for (BackgroundJob job : activeJobs) {
                                if (job.jobId > maxId) maxId = job.jobId;
                            }
                            jobId = maxId + 1;
                        }

                        long pid = process.pid();
                        System.out.println("[" + jobId + "] " + pid);
                        System.out.flush();
                        activeJobs.add(new BackgroundJob(jobId, pid, process, workingInput));
                    } else {
                        process.waitFor();
                    }
                } else {
                    System.err.println(command + ": command not found");
                }
            }
        } finally {
            if (fosOut != null) fosOut.close();
            if (fosErr != null) fosErr.close();
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static List<String> parseArguments(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder currentToken = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean hasContent = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '\\' && !inSingleQuotes && !inDoubleQuotes) {
                if (i + 1 < input.length()) {
                    i++; 
                    currentToken.append(input.charAt(i));
                    hasContent = true;
                }
            } else if (c == '\\' && inDoubleQuotes) {
                if (i + 1 < input.length()) {
                    char nextChar = input.charAt(i + 1);
                    if (nextChar == '"' || nextChar == '\\' || nextChar == '$' || nextChar == '`') {
                        i++; 
                        currentToken.append(nextChar);
                    } else {
                        currentToken.append(c);
                    }
                    hasContent = true;
                } else {
                    currentToken.append(c);
                }
            } else if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                hasContent = true;
            } else if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
                hasContent = true;
            } else if (c == ' ' && !inSingleQuotes && !inDoubleQuotes) {
                if (currentToken.length() > 0 || hasContent) {
                    tokens.add(currentToken.toString());
                    currentToken.setLength(0);
                    hasContent = false;
                }
            } else if (c == '|' && !inSingleQuotes && !inDoubleQuotes) {
                if (currentToken.length() > 0 || hasContent) {
                    tokens.add(currentToken.toString());
                    currentToken.setLength(0);
                    hasContent = false;
                }
                tokens.add("|");
            } else {
                currentToken.append(c);
            }
        }

        if (currentToken.length() > 0 || hasContent) {
            tokens.add(currentToken.toString());
        }

        return tokens;
    }

    private static String findInPath(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String[] directories = pathEnv.split(File.pathSeparator);
            for (String dir : directories) {
                File file = new File(dir, command);
                if (file.exists() && file.canExecute()) {
                    return file.getAbsolutePath();
                }
            }
        }
        return null;
    }
}