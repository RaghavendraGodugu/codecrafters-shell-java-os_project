import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Main {
    private static Path currentDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    private static final List<String> BUILTINS = Arrays.asList("echo", "exit", "pwd", "cd", "type", "complete", "jobs");
    private static final Map<String, String> completionSpecs = new LinkedHashMap<>();

    private static String lastTabInput = null;
    private static List<String> lastTabDisplayOptions = new ArrayList<>();

    private static final List<BackgroundJob> backgroundJobs = new ArrayList<>();
    private static int nextJobNumber = 1;

    public static void main(String[] args) {
        try {
            Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", "stty -icanon -echo < /dev/tty"}).waitFor();
        } catch (Exception ignored) {
        }

        InputStream inputReader = System.in;

        while (true) {
            try {
                reapCompletedJobsForPrompt(System.out);

                System.out.print("$ ");
                System.out.flush();

                StringBuilder inputBuilder = new StringBuilder();
                resetTabState();

                while (true) {
                    int code = inputReader.read();

                    if (code == -1) {
                        return;
                    }

                    char c = (char) code;

                    if (c == '\n' || c == '\r') {
                        System.out.print("\n");
                        System.out.flush();
                        resetTabState();
                        break;
                    } else if (c == '\t') {
                        handleTabCompletion(inputBuilder);
                    } else if (code == 127) {
                        if (inputBuilder.length() > 0) {
                            inputBuilder.deleteCharAt(inputBuilder.length() - 1);
                            System.out.print("\b \b");
                            System.out.flush();
                        }
                        resetTabState();
                    } else {
                        inputBuilder.append(c);
                        System.out.print(c);
                        System.out.flush();
                        resetTabState();
                    }
                }

                String input = inputBuilder.toString();
                if (input.trim().isEmpty()) {
                    continue;
                }

                List<String> tokens = tokenize(input);
                ParsedCommand parsed = parseCommand(tokens);

                if (parsed.args.isEmpty()) {
                    continue;
                }

                String command = parsed.args.get(0);

                if ("exit".equals(command)) {
                    return;
                } else if ("echo".equals(command)) {
                    executeEcho(parsed);
                } else if ("pwd".equals(command)) {
                    executePwd(parsed);
                } else if ("cd".equals(command)) {
                    executeCd(parsed);
                } else if ("type".equals(command)) {
                    executeType(parsed);
                } else if ("complete".equals(command)) {
                    executeComplete(parsed);
                } else if ("jobs".equals(command)) {
                    executeJobs(parsed);
                } else {
                    runExternal(parsed);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void handleTabCompletion(StringBuilder inputBuilder) {
        String currentInput = inputBuilder.toString();
        int lastSpace = currentInput.lastIndexOf(' ');
        boolean isCommandPosition = lastSpace == -1;
        String prefix = isCommandPosition ? currentInput : currentInput.substring(lastSpace + 1);

        if (!isCommandPosition) {
            List<String> args = tokenize(currentInput.trim());
            if (!args.isEmpty()) {
                String commandName = args.get(0);
                String scriptPath = completionSpecs.get(commandName);

                if (scriptPath != null) {
                    String currentWord = currentInput.endsWith(" ") ? "" : prefix;
                    String previousWord = currentInput.endsWith(" ") ? args.get(args.size() - 1) :
                            (args.size() >= 2 ? args.get(args.size() - 2) : commandName);

                    List<String> scriptCandidates = runCompleterScript(
                            scriptPath, commandName, currentWord, previousWord, currentInput
                    );

                    if (scriptCandidates.isEmpty()) {
                        ringBell();
                        resetTabState();
                        return;
                    }

                    if (scriptCandidates.size() == 1) {
                        applyCompletion(inputBuilder, currentInput, lastSpace, prefix,
                                      scriptCandidates.get(0) + " ");
                        resetTabState();
                    } else {
                        String lcp = longestCommonPrefixStrings(scriptCandidates);
                        if (lcp.length() > currentWord.length()) {
                            applyCompletion(inputBuilder, currentInput, lastSpace, prefix, lcp);
                            resetTabState();
                        } else {
                            ringBell();
                            lastTabInput = currentInput;
                            lastTabDisplayOptions = new ArrayList<>(scriptCandidates);
                        }
                    }
                }
            }
        }

        List<CompletionMatch> matches = isCommandPosition
                ? findCommandMatches(prefix)
                : findArgumentMatches(prefix);

        if (matches.isEmpty()) {
            ringBell();
            resetTabState();
            return;
        }

        if (matches.size() == 1) {
            CompletionMatch match = matches.get(0);
            applyCompletion(inputBuilder, currentInput, lastSpace, prefix,
                          match.value + (match.isDirectory ? "/" : " "));
            resetTabState();
        } else {
            String commonPrefix = longestCommonPrefix(matches);
            if (commonPrefix.length() > prefix.length()) {
                applyCompletion(inputBuilder, currentInput, lastSpace, prefix, commonPrefix);
                resetTabState();
            } else {
                List<String> displayOptions = new ArrayList<>();
                for (CompletionMatch match : matches) {
                    displayOptions.add(match.value + (match.isDirectory ? "/" : ""));
                }
                
                if (currentInput.equals(lastTabInput) && displayOptions.equals(lastTabDisplayOptions)) {
                    System.out.print("\n");
                    for (int i = 0; i < displayOptions.size(); i++) {
                        System.out.print((i > 0 ? "  " : "") + displayOptions.get(i));
                    }
                    System.out.print("\n$ " + currentInput);
                    System.out.flush();
                    resetTabState();
                } else {
                    ringBell();
                    lastTabInput = currentInput;
                    lastTabDisplayOptions = displayOptions;
                }
            }
        }
    }

    private static List<String> runCompleterScript(String scriptPath, String a1, String a2, String a3, String fullLine) {
        List<String> candidates = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(scriptPath, a1, a2, a3);
            pb.directory(currentDirectory.toFile());
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Map<String, String> env = pb.environment();
            env.put("COMP_LINE", fullLine);
            env.put("COMP_POINT", String.valueOf(fullLine.getBytes(StandardCharsets.UTF_8).length));
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) if (!line.isEmpty()) candidates.add(line);
            }
            p.waitFor();
        } catch (Exception ignored) {}
        Collections.sort(candidates);
        return candidates;
    }

    private static void resetTabState() {
        lastTabInput = null;
        lastTabDisplayOptions = new ArrayList<>();
    }

    private static void ringBell() { System.out.print("\u0007"); System.out.flush(); }

    private static void applyCompletion(StringBuilder b, String cur, int lastSpace, String prefix, String repl) {
        String before = lastSpace == -1 ? "" : cur.substring(0, lastSpace + 1);
        for (int i = 0; i < prefix.length(); i++) System.out.print("\b \b");
        b.setLength(0);
        b.append(before).append(repl);
        System.out.print(repl);
        System.out.flush();
    }

    private static String longestCommonPrefix(List<CompletionMatch> matches) {
        if (matches.isEmpty()) return "";
        String p = matches.get(0).value;
        for (int i = 1; i < matches.size(); i++) {
            String c = matches.get(i).value;
            int j = 0;
            while (j < p.length() && j < c.length() && p.charAt(j) == c.charAt(j)) j++;
            p = p.substring(0, j);
            if (p.isEmpty()) break;
        }
        return p;
    }

    private static String longestCommonPrefixStrings(List<String> vals) {
        if (vals.isEmpty()) return "";
        String p = vals.get(0);
        for (int i = 1; i < vals.size(); i++) {
            String c = vals.get(i);
            int j = 0;
            while (j < p.length() && j < c.length() && p.charAt(j) == c.charAt(j)) j++;
            p = p.substring(0, j);
            if (p.isEmpty()) break;
        }
        return p;
    }

    private static List<CompletionMatch> findCommandMatches(String prefix) {
        Set<String> seen = new LinkedHashSet<>();
        List<CompletionMatch> matches = new ArrayList<>();
        for (String b : BUILTINS) if (b.startsWith(prefix) && seen.add(b)) matches.add(new CompletionMatch(b, false));
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null && !pathEnv.isEmpty()) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                File folder = new File(dir);
                if (!folder.exists() || !folder.isDirectory()) continue;
                File[] files = folder.listFiles();
                if (files == null) continue;
                for (File f : files) {
                    String name = f.getName();
                    if (f.isFile() && f.canExecute() && name.startsWith(prefix) && seen.add(name))
                        matches.add(new CompletionMatch(name, false));
                }
            }
        }
        Collections.sort(matches, (a, b) -> a.value.compareTo(b.value));
        return matches;
    }

    private static List<CompletionMatch> findArgumentMatches(String prefix) {
        List<CompletionMatch> matches = new ArrayList<>();
        String dirPart = "", namePrefix = prefix;
        int lastSlash = prefix.lastIndexOf('/');
        if (lastSlash >= 0) { dirPart = prefix.substring(0, lastSlash + 1); namePrefix = prefix.substring(lastSlash + 1); }
        Path searchDir = dirPart.isEmpty() ? currentDirectory : currentDirectory.resolve(dirPart).normalize();
        File[] files = searchDir.toFile().listFiles();
        if (files != null) {
            for (File f : files) if (f.getName().startsWith(namePrefix))
                matches.add(new CompletionMatch(dirPart + f.getName(), f.isDirectory()));
        }
        Collections.sort(matches, (a, b) -> a.value.compareTo(b.value));
        return matches;
    }

    private static void executeEcho(ParsedCommand p) throws Exception {
        ensureBuiltinStderrTargetExists(p);
        PrintStream out = getStdoutStream(p);
        for (int i = 1; i < p.args.size(); i++) { if (i > 1) out.print(" "); out.print(p.args.get(i)); }
        out.println(); out.flush();
        if (out != System.out) out.close();
    }

    private static void executePwd(ParsedCommand p) throws Exception {
        ensureBuiltinStderrTargetExists(p);
        PrintStream out = getStdoutStream(p);
        out.println(currentDirectory); out.flush();
        if (out != System.out) out.close();
    }

    private static void executeType(ParsedCommand p) throws Exception {
        ensureBuiltinStderrTargetExists(p);
        PrintStream out = getStdoutStream(p);
        if (p.args.size() < 2) { if (out != System.out) out.close(); return; }
        String target = p.args.get(1);
        if (BUILTINS.contains(target)) out.println(target + " is a shell builtin");
        else {
            String exe = findExecutable(target);
            if (exe != null) out.println(target + " is " + exe);
            else out.println(target + ": not found");
        }
        out.flush();
        if (out != System.out) out.close();
    }

    private static void executeComplete(ParsedCommand p) throws Exception {
        ensureBuiltinStderrTargetExists(p);
        PrintStream out = getStdoutStream(p);
        PrintStream err = getStderrStream(p);
        try {
            if (p.args.size() >= 4 && "-C".equals(p.args.get(1))) {
                completionSpecs.put(p.args.get(3), p.args.get(2)); out.flush(); return;
            }
            if (p.args.size() >= 3 && "-r".equals(p.args.get(1))) {
                completionSpecs.remove(p.args.get(2)); out.flush(); return;
            }
            if (p.args.size() >= 3 && "-p".equals(p.args.get(1))) {
                String cmd = p.args.get(2), script = completionSpecs.get(cmd);
                if (script == null) err.println("complete: " + cmd + ": no completion specification");
                else out.println("complete -C '" + script + "' " + cmd);
                out.flush(); return;
            }
            out.flush();
        } finally {
            if (out != System.out) out.close();
            if (err != System.err) err.close();
        }
    }

    private static void executeJobs(ParsedCommand p) throws Exception {
        ensureBuiltinStderrTargetExists(p);
        PrintStream out = getStdoutStream(p);
        reapCompletedJobsSilently();
        for (int i = 0; i < backgroundJobs.size(); i++) {
            BackgroundJob j = backgroundJobs.get(i);
            out.println(formatJobLine(j.jobNumber, getJobMarker(i, backgroundJobs.size()), "Running", j.commandLine));
        }
        out.flush();
        if (out != System.out) out.close();
    }

    private static void reapCompletedJobsForPrompt(PrintStream out) {
        for (int i = 0; i < backgroundJobs.size(); ) {
            BackgroundJob j = backgroundJobs.get(i);
            if (!j.process.isAlive()) {
                out.println(formatJobLine(j.jobNumber, getJobMarker(i, backgroundJobs.size()), "Done", stripTrailingAmpersand(j.commandLine)));
                backgroundJobs.remove(i);
            } else i++;
        }
        out.flush();
    }

    private static void reapCompletedJobsSilently() {
        for (int i = 0; i < backgroundJobs.size(); ) {
            if (!backgroundJobs.get(i).process.isAlive()) backgroundJobs.remove(i);
            else i++;
        }
    }

    private static String getJobMarker(int index, int size) {
        if (size <= 0) return " ";
        if (size == 1) return "+";
        if (size == 2) return index == 0 ? " " : "+";
        int last = size - 1, secondLast = size - 2;
        return index == last ? "+" : (index == secondLast ? "-" : " ");
    }

    private static String formatJobLine(int jobNum, String marker, String status, String cmdLine) {
        return "[" + jobNum + "]" + marker + " " + status + " " + cmdLine;
    }

    private static String stripTrailingAmpersand(String s) {
        s = s.trim();
        if (s.endsWith("&")) s = s.substring(0, s.length() - 1).trim();
        return s;
    }

    private static void executeCd(ParsedCommand p) throws Exception {
        PrintStream err = getStderrStream(p);
        try {
            String target; String home = System.getenv("HOME"); if (home == null) home = System.getProperty("user.home");
            if (p.args.size() < 2 || "~".equals(p.args.get(1))) target = home;
            else if (p.args.get(1).startsWith("~/")) target = home + p.args.get(1).substring(1);
            else target = p.args.get(1);
            Path np = Paths.get(target);
            if (!np.isAbsolute()) np = currentDirectory.resolve(np);
            np = np.normalize();
            if (Files.exists(np) && Files.isDirectory(np)) currentDirectory = np;
            else err.println("cd: " + p.args.get(1) + ": No such file or directory");
            err.flush();
        } finally { if (err != System.err) err.close(); }
    }

    private static String findExecutable(String cmd) {
        String pathEnv = System.getenv("PATH"); if (pathEnv == null || pathEnv.isEmpty()) return null;
        for (String dir : pathEnv.split(File.pathSeparator)) {
            File f = new File(dir, cmd);
            if (f.exists() && f.isFile() && f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    private static void runExternal(ParsedCommand p) {
        try {
            ProcessBuilder pb = new ProcessBuilder(p.args);
            pb.directory(currentDirectory.toFile());
            if (p.background) {
                if (p.stdoutFile != null) {
                    File f = prepareFile(p.stdoutFile).toFile();
                    pb.redirectOutput(p.stdoutAppend ? ProcessBuilder.Redirect.appendTo(f) : ProcessBuilder.Redirect.to(f));
                } else pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                if (p.stderrFile != null) {
                    File f = prepareFile(p.stderrFile).toFile();
                    pb.redirectError(p.stderrAppend ? ProcessBuilder.Redirect.appendTo(f) : ProcessBuilder.Redirect.to(f));
                } else pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                Process proc = pb.start();
                long pid = proc.pid(); int jobNum = nextJobNumber++;
                backgroundJobs.add(new BackgroundJob(jobNum, pid, proc, p.originalCommandForJobs != null ? p.originalCommandForJobs : String.join(" ", p.args)));
                System.out.println("[" + jobNum + "] " + pid); System.out.flush(); return;
            }
            if (p.stdoutFile != null) { File f = prepareFile(p.stdoutFile).toFile(); pb.redirectOutput(p.stdoutAppend ? ProcessBuilder.Redirect.appendTo(f) : ProcessBuilder.Redirect.to(f)); }
            else pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            if (p.stderrFile != null) { File f = prepareFile(p.stderrFile).toFile(); pb.redirectError(p.stderrAppend ? ProcessBuilder.Redirect.appendTo(f) : ProcessBuilder.Redirect.to(f)); }
            else pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.start().waitFor();
        } catch (Exception e) { System.out.println(p.args.get(0) + ": command not found"); }
    }

    private static PrintStream getStdoutStream(ParsedCommand p) throws Exception {
        if (p.stdoutFile == null) return System.out;
        return new PrintStream(new FileOutputStream(prepareFile(p.stdoutFile).toFile(), p.stdoutAppend));
    }

    private static PrintStream getStderrStream(ParsedCommand p) throws Exception {
        if (p.stderrFile == null) return System.err;
        return new PrintStream(new FileOutputStream(prepareFile(p.stderrFile).toFile(), p.stderrAppend));
    }

    private static void ensureBuiltinStderrTargetExists(ParsedCommand p) throws Exception {
        if (p.stderrFile == null) return;
        new FileOutputStream(prepareFile(p.stderrFile).toFile(), p.stderrAppend).close();
    }

    private static Path prepareFile(String file) throws Exception {
        Path fp = resolvePath(file); File parent = fp.toFile().getParentFile();
        if (parent != null) parent.mkdirs();
        if (!Files.exists(fp)) Files.createFile(fp);
        return fp;
    }

    private static Path resolvePath(String file) { Path p = Paths.get(file); return p.isAbsolute() ? p.normalize() : currentDirectory.resolve(p).normalize(); }

    private static ParsedCommand parseCommand(List<String> tokens) {
        ParsedCommand p = new ParsedCommand();
        List<String> work = new ArrayList<>(tokens);
        if (!work.isEmpty() && "&".equals(work.get(work.size() - 1))) {
            p.background = true; work.remove(work.size() - 1);
            p.originalCommandForJobs = String.join(" ", work) + " &";
        }
        for (int i = 0; i < work.size(); i++) {
            String t = work.get(i);
            if (t.equals(">") || t.equals("1>")) { if (i + 1 < work.size()) { p.stdoutFile = work.get(++i); p.stdoutAppend = false; } }
            else if (t.equals(">>") || t.equals("1>>")) { if (i + 1 < work.size()) { p.stdoutFile = work.get(++i); p.stdoutAppend = true; } }
            else if (t.equals("2>")) { if (i + 1 < work.size()) { p.stderrFile = work.get(++i); p.stderrAppend = false; } }
            else if (t.equals("2>>")) { if (i + 1 < work.size()) { p.stderrFile = work.get(++i); p.stderrAppend = true; } }
            else p.args.add(t);
        }
        if (p.originalCommandForJobs == null) p.originalCommandForJobs = String.join(" ", p.args);
        return p;
    }

    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>(); StringBuilder cur = new StringBuilder();
        boolean sq = false, dq = false, esc = false;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (esc) { if (dq && (ch == '\\' || ch == '"' || ch == '$' || ch == '\n')) cur.append(ch); else if (!dq) cur.append(ch); esc = false; continue; }
            if (ch == '\\' && !sq) { esc = true; continue; }
            if (ch == '\'' && !dq) { sq = !sq; continue; }
            if (ch == '"' && !sq) { dq = !dq; continue; }
            if (Character.isWhitespace(ch) && !sq && !dq) { if (cur.length() > 0) { tokens.add(cur.toString()); cur.setLength(0); } }
            else cur.append(ch);
        }
        if (esc) cur.append('\\');
        if (cur.length() > 0) tokens.add(cur.toString());
        return tokens;
    }

    private static class ParsedCommand {
        List<String> args = new ArrayList<>(); String stdoutFile; boolean stdoutAppend; String stderrFile; boolean stderrAppend;
        boolean background; String originalCommandForJobs;
    }

    private static class CompletionMatch {
        String value; boolean isDirectory; CompletionMatch(String v, boolean d) { value = v; isDirectory = d; }
    }

    private static class BackgroundJob {
        int jobNumber; long pid; Process process; String commandLine;
        BackgroundJob(int j, long p, Process proc, String cmd) { jobNumber = j; pid = p; process = proc; commandLine = cmd; }
    }
}