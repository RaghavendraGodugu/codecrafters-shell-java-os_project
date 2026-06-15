import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {
    private static Path currentDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath();

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            if (!scanner.hasNextLine()) {
                break;
            }

            String input = scanner.nextLine().trim();
            if (input.isEmpty()) {
                continue;
            }

            List<String> argsList = parseArguments(input);
            if (argsList.isEmpty()) {
                continue;
            }

            // --- Redirection Parsing Logic ---
            String redirectFile = null;
            int redirectIndex = -1;
            boolean isStderrRedirection = false; 
            boolean appendMode = false; 

            for (int i = 0; i < argsList.size(); i++) {
                String arg = argsList.get(i);
                
                // Check for overwrite operators
                if (arg.equals(">") || arg.equals("1>") || arg.equals("2>")) {
                    if (i + 1 < argsList.size()) {
                        redirectFile = argsList.get(i + 1);
                        redirectIndex = i;
                        isStderrRedirection = arg.equals("2>");
                        appendMode = false; 
                        break;
                    }
                }
                // Check for append operators (Updated to include 2>>)
                else if (arg.equals(">>") || arg.equals("1>>") || arg.equals("2>>")) {
                    if (i + 1 < argsList.size()) {
                        redirectFile = argsList.get(i + 1);
                        redirectIndex = i;
                        isStderrRedirection = arg.equals("2>>");
                        appendMode = true; 
                        break;
                    }
                }
            }

            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            PrintStream fileOutStream = null;
            File targetFile = null;

            if (redirectIndex != -1) {
                try {
                    if (redirectFile.startsWith("/")) {
                        targetFile = new File(redirectFile);
                    } else {
                        targetFile = new File(currentDirectory.toFile(), redirectFile);
                    }

                    if (targetFile.getParentFile() != null) {
                        targetFile.getParentFile().mkdirs();
                    }
                    
                    fileOutStream = new PrintStream(new FileOutputStream(targetFile, appendMode)); 
                    
                    if (isStderrRedirection) {
                        System.setErr(fileOutStream);
                    } else {
                        System.setOut(fileOutStream);
                    }
                } catch (IOException e) {
                    System.err.println("Shell error: Redirection target could not be opened.");
                }
                
                argsList = new ArrayList<>(argsList.subList(0, redirectIndex));
            }

            String command = argsList.get(0);

            // 1. Handle exit command
            if (command.equals("exit")) {
                if (fileOutStream != null) fileOutStream.close();
                break;
            } 
            // 2. Handle echo command
            else if (command.equals("echo")) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < argsList.size(); i++) {
                    sb.append(argsList.get(i));
                    if (i < argsList.size() - 1) {
                        sb.append(" ");
                    }
                }
                System.out.println(sb.toString());
            } 
            // 3. Handle pwd command
            else if (command.equals("pwd")) {
                System.out.println(currentDirectory.toString());
            }
            // 4. Handle cd command
            else if (command.equals("cd")) {
                String targetPathStr = argsList.size() > 1 ? argsList.get(1) : "~";
                Path targetPath;

                if (targetPathStr.equals("~")) {
                    String homeDir = System.getenv("HOME");
                    if (homeDir == null) {
                        homeDir = System.getenv("USERPROFILE");
                    }
                    targetPath = Paths.get(homeDir);
                } else if (targetPathStr.startsWith("/")) {
                    targetPath = Paths.get(targetPathStr);
                } else {
                    targetPath = currentDirectory.resolve(targetPathStr);
                }

                targetPath = targetPath.normalize().toAbsolutePath();
                File targetDir = targetPath.toFile();

                if (targetDir.exists() && targetDir.isDirectory()) {
                    currentDirectory = targetPath;
                } else {
                    System.out.println("cd: " + targetPathStr + ": No such file or directory");
                }
            }
            // 5. Handle type command
            else if (command.equals("type")) {
                if (argsList.size() > 1) {
                    String commandToCheck = argsList.get(1);
                    if (commandToCheck.equals("echo") || commandToCheck.equals("exit") || 
                        commandToCheck.equals("type") || commandToCheck.equals("pwd") || 
                        commandToCheck.equals("cd")) {
                        System.out.println(commandToCheck + " is a shell builtin");
                    } else {
                        String fullPath = findInPath(commandToCheck);
                        if (fullPath != null) {
                            System.out.println(commandToCheck + " is " + fullPath);
                        } else {
                            System.out.println(commandToCheck + ": not found");
                        }
                    }
                }
            }
            // 6. Try running it as an external program
            else {
                String executablePath = findInPath(command);
                if (executablePath != null) {
                    try {
                        ProcessBuilder pb = new ProcessBuilder(argsList);
                        pb.directory(currentDirectory.toFile());
                        
                        if (redirectIndex != -1 && targetFile != null) {
                            if (isStderrRedirection) {
                                pb.redirectError(appendMode ? ProcessBuilder.Redirect.appendTo(targetFile) : ProcessBuilder.Redirect.to(targetFile));
                                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                            } else {
                                pb.redirectOutput(appendMode ? ProcessBuilder.Redirect.appendTo(targetFile) : ProcessBuilder.Redirect.to(targetFile));
                                pb.redirectError(ProcessBuilder.Redirect.INHERIT); 
                            }
                        } else {
                            pb.inheritIO();
                        }

                        Process process = pb.start();
                        process.waitFor();
                    } catch (IOException | InterruptedException e) {
                        System.err.println(command + ": command not found");
                    }
                } else {
                    System.err.println(command + ": command not found");
                }
            }

            if (fileOutStream != null) {
                fileOutStream.close();
            }
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        
        scanner.close();
    }

    private static List<String> parseArguments(String input) {
        List<String> list = new ArrayList<>();
        StringBuilder currentArg = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean hasContent = false; 

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '\\' && !inSingleQuotes && !inDoubleQuotes) {
                if (i + 1 < input.length()) {
                    i++; 
                    currentArg.append(input.charAt(i));
                    hasContent = true;
                }
            } 
            else if (c == '\\' && inDoubleQuotes) {
                if (i + 1 < input.length()) {
                    char next = input.charAt(i + 1);
                    if (next == '"' || next == '\\' || next == '$' || next == '`') {
                        i++; 
                        currentArg.append(next);
                    } else {
                        currentArg.append(c);
                    }
                    hasContent = true;
                } else {
                    currentArg.append(c);
                    hasContent = true;
                }
            }
            else if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                hasContent = true; 
            } else if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
                hasContent = true;
            } else if (c == ' ' && !inSingleQuotes && !inDoubleQuotes) {
                if (hasContent || currentArg.length() > 0) {
                    list.add(currentArg.toString());
                    currentArg.setLength(0);
                    hasContent = false;
                }
            } else {
                currentArg.append(c);
                hasContent = true;
            }
        }

        if (hasContent || currentArg.length() > 0) {
            list.add(currentArg.toString());
        }

        return list;
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