import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public class Main {
    static class Job {
        int jobId;
        Process process;
        String command;

        Job(int jobId, Process process, String command) {
            this.jobId = jobId;
            this.process = process;
            this.command = command;
        }
    }

    private static final List<Job> jobs = new ArrayList<>();
    private static int nextJobId = 1;

    public static void main(String[] args) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            reapFinishedJobs(); // reaping before each prompt
            System.out.print("$ ");
            System.out.flush();

            String line = reader.readLine();
            if (line == null) break;

            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.equals("exit 0")) {
                break;
            }

            if (line.equals("jobs")) {
                reapFinishedJobs(); // also reap inside jobs builtin
                printJobs();
                continue;
            }

            boolean background = line.endsWith("&");
            String commandLine = background ? line.substring(0, line.length() - 1).trim() : line;

            List<String> parts = Arrays.stream(commandLine.split("\\s+"))
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());

            if (parts.isEmpty()) {
                continue;
            }

            ProcessBuilder pb = new ProcessBuilder(parts);
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);

            try {
                Process process = pb.start();

                if (background) {
                    Job job = new Job(nextJobId++, process, commandLine);
                    jobs.add(job);
                    System.out.println("[" + job.jobId + "] " + process.pid());
                } else {
                    process.waitFor();
                }
            } catch (IOException e) {
                System.out.println(parts.get(0) + ": command not found");
            }
        }
    }

    private static void reapFinishedJobs() {
        Iterator<Job> it = jobs.iterator();
        while (it.hasNext()) {
            Job job = it.next();
            if (!job.process.isAlive()) {
                System.out.println("[" + job.jobId + "] " + markerFor(job) + " Done " + job.command);
                it.remove();
            }
        }
    }

    private static void printJobs() {
        for (Job job : jobs) {
            String status = job.process.isAlive() ? "Running" : "Done";
            System.out.println("[" + job.jobId + "] " + markerFor(job) + " " + status + " " + job.command + " &");
        }
    }

    private static String markerFor(Job target) {
        if (jobs.isEmpty()) return "+";
        Job current = jobs.get(jobs.size() - 1);
        if (current == target) return "+";
        if (jobs.size() >= 2 && jobs.get(jobs.size() - 2) == target) return "-";
        return " ";
    }
}