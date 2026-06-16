import sys
import os
import subprocess
job_counter = 1
jobs_list = []
def find_path(cmd_name):
    path_env = os.environ.get("PATH", "")
    for path_dir in path_env.split(os.pathsep):
        full_path = os.path.join(path_dir, cmd_name)
        if os.path.isfile(full_path) and os.access(full_path, os.X_OK):
            return full_path
    return None
def parse_arguments(cmd_arg):
    args = []
    current_arg = []
    in_single_quotes = False
    in_double_quotes = False
    is_escaping = False
    for char in cmd_arg:
        if is_escaping:
            if in_double_quotes:
                if char in ['"','\\','$','`','\n']:
                    current_arg.append(char)
                else:
                    current_arg.append('\\')
                    current_arg.append(char)
            else:
                current_arg.append(char)
            is_escaping = False
        elif char == "\\" and not in_single_quotes:
            is_escaping = True
        elif char == "'" and not in_double_quotes:
            in_single_quotes = not in_single_quotes
        elif char == '"' and not in_single_quotes:
            in_double_quotes = not in_double_quotes
        elif char.isspace() and not in_single_quotes and not in_double_quotes:
            if current_arg:
                args.append("".join(current_arg))
                current_arg = []
        else:
            current_arg.append(char)
    if current_arg:
        args.append("".join(current_arg))
    return args
def execute_command(args, out_fp, err_fp, in_fd=None):
    """Executes a single command, routing its I/O to the provided pointers."""
    global job_counter, jobs_list
    cmd = args[0]
    builtins = ["echo", "exit", "type", "pwd", "cd", "jobs"]
    if cmd == "exit":
        sys.exit(0)
    elif cmd == "echo":
        print(" ".join(args[1:]), file=out_fp)
        out_fp.flush()
    elif cmd == "type":
        if len(args) > 1:
            target_command = args[1]
            if target_command in builtins:
                print(f"{target_command} is a shell builtin", file=out_fp)
            else:
                found_path = find_path(target_command)
                if found_path:
                    print(f"{target_command} is {found_path}", file=out_fp)
                else:
                    print(f"{target_command}: not found", file=out_fp)
        out_fp.flush()
    elif cmd == "pwd":
        print(os.getcwd(), file=out_fp)
        out_fp.flush()
    elif cmd == "cd":
        if len(args) > 1:
            directory = args[1]
            if directory == "~":
                directory = os.environ.get("HOME", "")
            if os.path.exists(directory):
                os.chdir(directory)
            else:
                print(f"cd: {directory}: No such file or directory", file=err_fp)
        err_fp.flush()
    elif cmd == "jobs":
        total_jobs = len(jobs_list)
        jobs_to_keep = []
        for index, job in enumerate(jobs_list):
            if job["proc"].poll() is not None:
                job["status"] = "Done"
                if job["cmd"].endswith("&"):
                    job["cmd"] = job["cmd"][:-1].rstrip()
            status_padded = job["status"].ljust(24)
            if index == total_jobs - 1:
                marker = "+"
            elif index == total_jobs - 2:
                marker = "-"
            else:
                marker = " "
            print(f"[{job['id']}]{marker}  {status_padded}{job['cmd']}", file=out_fp)
            if job["status"] == "Running":
                jobs_to_keep.append(job)
        jobs_list.clear()
        jobs_list.extend(jobs_to_keep)
        out_fp.flush()
    else:
        found_path = find_path(cmd)
        if found_path:
            proc = subprocess.Popen(args, executable=found_path, stdin=in_fd, stdout=out_fp, stderr=err_fp)
            return proc 
        else:
            print(f"{cmd}: command not found", file=err_fp)
            err_fp.flush()
        return None

def main():
    global job_counter, jobs_list
    
    while True:
        sys.stdout.write("$ ")
        sys.stdout.flush()
        
        try:
            command = input().strip()
            if not command: continue
        except EOFError:
            break
        args = parse_arguments(command)
        if not args: continue
        run_in_background = False
        if args and args[-1] == "&":
            run_in_background = True
            args.pop()
            if not args: continue
        redirect_stdout = None
        redirect_stderr = None
        mode_stdout = "w"
        mode_stderr = "w"
        if "2>>" in args:
            idx = args.index("2>>")
            redirect_stderr = args[idx+1]
            mode_stderr = "a"
            args.pop(idx); args.pop(idx)
        if "2>" in args:
            idx = args.index("2>")
            mode_stderr = "w"
            redirect_stderr = args[idx+1]
            args.pop(idx); args.pop(idx)
        if ">>" in args:
            idx = args.index(">>")
            redirect_stdout = args[idx+1]
            mode_stdout = "a"
            args.pop(idx); args.pop(idx)
        elif "1>>" in args:
            idx = args.index("1>>")
            redirect_stdout = args[idx+1]
            mode_stdout = "a"
            args.pop(idx); args.pop(idx)
        elif ">" in args:
            idx = args.index(">")
            redirect_stdout = args[idx+1]
            args.pop(idx); args.pop(idx)
        elif "1>" in args:
            idx = args.index("1>")
            redirect_stdout = args[idx+1]
            args.pop(idx); args.pop(idx)
        out_fp = open(redirect_stdout, mode_stdout) if redirect_stdout else sys.stdout
        err_fp = open(redirect_stderr, mode_stderr) if redirect_stderr else sys.stderr
        if "|" in args:
            commands = []
            current_cmd = []
            for arg in args:
                if arg == "|":
                    if current_cmd:
                        commands.append(current_cmd)
                    current_cmd = []
                else:
                    current_cmd.append(arg)
            if current_cmd:
                commands.append(current_cmd)
            processes = []
            prev_r = None
            for i, cmd_args in enumerate(commands):
                is_last = (i == len(commands) - 1)
                if not is_last:
                    r, w = os.pipe()
                    current_out = os.fdopen(w, "w")
                else:
                    current_out = out_fp
                p = execute_command(cmd_args, out_fp=current_out, err_fp=err_fp, in_fd=prev_r)
                processes.append(p)
                if not is_last:
                    current_out.close()
                if prev_r is not None:
                    os.close(prev_r)
                if not is_last:
                    prev_r = r
            for p in processes:
                if p:
                    p.wait()
            if redirect_stdout: out_fp.close()
            if redirect_stderr: err_fp.close()
            continue
        proc = execute_command(args, out_fp=out_fp, err_fp=err_fp)
        if proc: 
            if run_in_background:
                print(f"[{job_counter}] {proc.pid}")
                jobs_list.append({
                    "id": job_counter,
                    "pid": proc.pid,
                    "cmd": command,
                    "status": "Running",
                    "proc": proc
                })
                job_counter += 1
            else:
                proc.wait()
        if redirect_stdout: out_fp.close()
        if redirect_stderr: err_fp.close()

if __name__ == "__main__":
    main()