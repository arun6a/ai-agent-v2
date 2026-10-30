---
name: terminal-commands
description: Common shell commands to run after terminal access is set up. Use when user asks to build, install, check system, manage files, or run scripts via the terminal.
keywords: build, install, command, shell, terminal, run, script, git, system, check, compile, make
---
# Terminal Commands Reference

## Prerequisites
This skill assumes you've already set up terminal access via the setup-terminal-access skill.
The run_command custom tool should already exist + the server should be running.

## CRITICAL: Environment Setup (read this first!)

The HTTP server runs commands with PTY support (v4.0.50+). Key facts:
1. PTY mode: if `script` command is available, commands run in a real pseudo-terminal.
   Interactive Y/n prompts are auto-answered with 'y' (only when prompt detected).
2. Fallback mode: if `script` not available, uses direct spawn (no PTY).
3. `which` is NOT available — use `command -v` instead (POSIX builtin, always works).
4. `pkg`, `node`, `java`, `git` live in `/data/data/com.tom.rv2ide/files/usr/bin/`.
5. The server does NOT source `~/.profile` — you must set PATH explicitly.
6. **Timeout is OPTIONAL** (v4.0.50+): default = infinite (no kill). Pass `timeout=60`
   ONLY for commands that might hang (ping, tail -f). OMIT timeout for builds/installs.

**FIRST COMMAND always run to find tools:**
```
run_command(command="export PATH=$PATH:/data/data/com.tom.rv2ide/files/usr/bin; command -v pkg node java git 2>/dev/null; echo DONE")
```

If you see output like `/data/data/com.tom.rv2ide/files/usr/bin/pkg` etc., tools are present.

**Universal command pattern for ALL commands that need pkg/node/java:**
```
run_command(command="export PATH=$PATH:/data/data/com.tom.rv2ide/files/usr/bin; <YOUR COMMAND HERE>")
```

## CRITICAL: Known Shell Limitations

### `which` does NOT exist
AndroidIDE-Rv2's minimal shell has no `which` binary. ALWAYS use `command -v` instead:
```
WRONG:  which pkg node java
RIGHT:  command -v pkg node java
```

### `idesetup` CANNOT run via run_command
The `idesetup` script uses bash-specific syntax (the minimal `sh` is dash and can't parse it),
and `idesetup -c` is interactive (downloads JDK + build tools with prompts).
If you run `idesetup -c` via run_command, you will see:
```
Syntax error: word unexpected (expecting ")")
```
OR the command will time out after 120 seconds.

If the user wants to set up the IDE, tell them:
"Open AndroidIDE-Rv2 terminal and run `idesetup -c` manually — it's interactive and needs bash."

### `apt` warnings are EXPECTED NOISE
When you run `pkg install` or `pkg search`, you'll see this 4 times:
```
WARNING: apt does not have a stable CLI interface. Use with caution in scripts.
```
This is NOT an error — `pkg` wraps `apt` and apt prints this warning.
IGNORE these warning lines. Only check:
- The actual output (package names, "done", etc.)
- The exit code (Exit: 0 = success, Exit: 1 = failure)

### /sdcard is mounted with noexec
You CANNOT execute binaries from /sdcard. To build:
1. Copy the project to the app's home directory: `cp -r /sdcard/MyProject ~/MyProject`
2. Run gradle from there: `cd ~/MyProject && ./gradlew assembleDebug`

## Verify Server Is Running
```
run_command(command="echo aira-server-check")
```
If this fails with "Connection refused": the server is not running.
Tell the user: "Open AndroidIDE-Rv2 terminal and run: `node /sdcard/Aira/aira-server.js &`"

## Common Commands by Category

### Environment Exploration (run these FIRST)
```
Find available tools:   run_command(command="export PATH=$PATH:/data/data/com.tom.rv2ide/files/usr/bin; command -v pkg node java javac git python3 2>/dev/null; echo DONE")
Check PATH:            run_command(command="echo $PATH")
List installed bins:   run_command(command="ls /data/data/com.tom.rv2ide/files/usr/bin/ 2>/dev/null | head -30")
Check home directory:  run_command(command="echo HOME=$HOME && ls -la ~ 2>/dev/null")
```

### File Operations
```
List files:        run_command(command="ls -la /sdcard/Download/")
Read file:         run_command(command="cat /sdcard/Aira/config.json")
Copy file:         run_command(command="cp /sdcard/file1.txt /sdcard/file2.txt")
Move file:         run_command(command="mv /sdcard/old.txt /sdcard/new.txt")
Delete file:       run_command(command="rm /sdcard/unwanted.txt")
Create directory:  run_command(command="mkdir -p /sdcard/Aira/projects")
Find files:         run_command(command="find /sdcard -name '*.apk' -type f")
```

### Build Android Projects
IMPORTANT: /sdcard is mounted with noexec on Android. You CANNOT execute binaries from /sdcard.
To build, either:
1. Copy the project to the app's home directory: `cp -r /sdcard/MyProject ~/MyProject`
2. Run gradle from there: `cd ~/MyProject && ./gradlew assembleDebug`
OR:
1. Tell user: "Open AndroidIDE-Rv2 terminal and run `idesetup -c` to install JDK + build tools"
2. Use the IDE's built-in build system

**IMPORTANT: OMIT timeout for builds** (v4.0.50+ default is infinite — let them finish):
```
Build debug APK:   run_command(command="export PATH=$PATH:/data/data/com.tom.rv2ide/files/usr/bin; cd ~/MyProject && ./gradlew assembleDebug 2>&1")
Build release:     run_command(command="export PATH=$PATH:/data/data/com.tom.rv2ide/files/usr/bin; cd ~/MyProject && ./gradlew assembleRelease 2>&1")
```

### Install Packages (AndroidIDE-Rv2 / Termux)
NOTE: `pkg install` prints 4 "apt WARNING" lines — IGNORE them. They are not errors.
NOTE: OMIT timeout (v4.0.50+ default is infinite — let installs finish).
```
Update packages:   run_command(command="export PATH=$PATH:/data/data/com.tom.rv2ide/files/usr/bin; pkg update -y 2>&1; echo EXIT=$?")
Install Node.js:   run_command(command="export PATH=$PATH:/data/data/com.tom.rv2ide/files/usr/bin; pkg install -y nodejs 2>&1; echo EXIT=$?")
Install Python:    run_command(command="export PATH=$PATH:/data/data/com.tom.rv2ide/files/usr/bin; pkg install -y python 2>&1; echo EXIT=$?")
Install Java:      run_command(command="export PATH=$PATH:/data/data/com.tom.rv2ide/files/usr/bin; pkg install -y openjdk-17 2>&1; echo EXIT=$?")
Install Git:       run_command(command="export PATH=$PATH:/data/data/com.tom.rv2ide/files/usr/bin; pkg install -y git 2>&1; echo EXIT=$?")
Search packages:   run_command(command="export PATH=$PATH:/data/data/com.tom.rv2ide/files/usr/bin; pkg search openjdk 2>&1")
```
NOTE: `gradle` is NOT in the pkg repository. To build Gradle projects, you must:
1. Download gradle manually: `curl -L https://services.gradle.org/distributions/gradle-8.5-bin.zip -o /tmp/gradle.zip`
2. Unzip: `cd /tmp && unzip gradle.zip`
3. Use: `/tmp/gradle-8.5/bin/gradle build`

### Git Commands
```
Status:    run_command(command="cd /sdcard/MyProject && git status")
Clone:     run_command(command="cd /sdcard && git clone https://github.com/user/repo.git")
Commit:    run_command(command="cd /sdcard/MyProject && git add -A && git commit -m 'update'")
Push:      run_command(command="cd /sdcard/MyProject && git push")
```

### System Info
```
Device model:   run_command(command="getprop ro.product.model")
Android version: run_command(command="getprop ro.build.version.release")
Storage:        run_command(command="df -h /sdcard")
Memory:         run_command(command="free -h 2>/dev/null || cat /proc/meminfo | head -5")
```

## How to Interpret Command Results (v4.0.51+)

When run_command returns, the response JSON now includes:
1. `"success": true/false` — command completed (exit code 0 + not timed out + not cancelled)
2. `"exitCode": 0` — shell exit code (0 = success, non-zero = failure)
3. `"timedOut": true/false` — if true, command exceeded the timeout YOU set and was killed
4. `"cancelled": true/false` — v4.0.51: if true, user sent a new message or tapped Stop
5. `"output": "..."` — cleaned stdout (ANSI codes stripped)
6. `"stderr": "..."` — cleaned stderr
7. `"elapsed": "5s"` — how long the command took
8. `"pty": "script"` or `"spawn"` — PTY mode used
9. `"autoYesUsed": true/false` — whether auto-Y was sent for a detected prompt

### Decision logic:
- If `timedOut: true` → you set a timeout that was too short. Re-run WITHOUT timeout (default = infinite).
- If `cancelled: true` → user interrupted (new message or Stop). DON'T retry — user moved on.
- If `exitCode: 0` → command succeeded (even if stderr has apt warnings — IGNORE those)
- If `exitCode: 1` and output has "not found" → check PATH, prefix with `export PATH=...`
- If `success: false` but output looks correct → check exitCode; some commands return non-zero on success
- If response takes a long time → that's expected for builds/installs (no step timeout kill in v4.0.50+)

### What happens when user sends a new message mid-command (v4.0.51+):
1. Aira cancels the current agent loop (`loopJob.cancel()`)
2. The HTTP call to aira-server.js is cancelled immediately (`call.cancel()`)
3. The server detects client disconnect (`req.on('close')`) and kills the subprocess (`proc.kill('SIGKILL')`)
4. The phone stops running the command — no orphaned processes consuming CPU

This means: if a 5-minute build is running and the user types "stop", the build is killed within seconds.

### Old response format (pre-v4.0.49):
If the response doesn't have `exitCode`/`timedOut`/`pty` fields, the server is old.
Tell the user: "Please restart the terminal server: open terminal, Ctrl+C, then run: node /sdcard/Aira/aira-server.js &"

## Important Notes

- Commands run via the HTTP server in AndroidIDE-Rv2's terminal environment
- v4.0.50: Default timeout = INFINITE (no kill). AI decides per-command.
- v4.0.50: Step timeout in ChatViewModel bumped from 60s → 600s (was killing run_command)
- v4.0.50: Auto-Y now only fires when a prompt pattern is detected (not blindly after 3s)
- v4.0.50: PTY uses `script -qfc` for unbuffered output (was hanging on simple commands)
- DO NOT use `which` — use `command -v`
- DO NOT use `idesetup` via run_command — it's interactive + bash-only (even with PTY, prefer manual)
- /sdcard is mounted with noexec — can't execute binaries from there
- Use `pkg` to install packages (nodejs, python, openjdk-17, git)
- `apt` warnings from `pkg` are expected — IGNORE them
- If `timedOut: true` in response → you set a timeout that was too short. Re-run WITHOUT timeout.
- If response has no `pty`/`exitCode` fields → server is old, tell user to restart it
- If the server dies, tell user to restart: open terminal → "node /sdcard/Aira/aira-server.js &"
