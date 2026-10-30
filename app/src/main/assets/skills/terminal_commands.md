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

The HTTP server runs commands in a NON-INTERACTIVE shell. This means:
- PATH may not include `pkg`, `node`, `java`, `gradle` etc.
- ~/.profile is NOT sourced automatically
- The interactive terminal has these, but the server doesn't

**FIRST COMMAND always run:**
```
run_command(command="source ~/.profile 2>/dev/null; source ~/.bashrc 2>/dev/null; echo PATH=$PATH; which pkg node java gradle 2>/dev/null")
```

If `pkg` is found at `/data/data/com.tom.rv2ide/files/usr/bin/pkg` or similar:
- Prefix ALL commands with the full path OR
- Export PATH first: `export PATH=$PATH:/data/data/com.tom.rv2ide/files/usr/bin`

**Example pattern for ALL commands:**
```
run_command(command="source ~/.profile 2>/dev/null; export PATH=$PATH:/data/data/com.tom.rv2ide/files/usr/bin; <YOUR COMMAND HERE>")
```

If `idesetup` is available, tell the user: "Run `idesetup -c` in Android Code Studio terminal to install JDK + build tools"

## Verify Server Is Running
```
run_command(command="echo aira-server-check")
```
If this fails, tell the user: "Terminal server not running. Say 'set up terminal access' to start it."

## Common Commands by Category

### Environment Exploration (run these FIRST)
```
Find available tools:   run_command(command="source ~/.profile 2>/dev/null; which pkg node java javac gradle python3 2>/dev/null")
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
1. Run `idesetup -c` first (installs JDK + build tools in Android Code Studio)
2. Use the IDE's built-in build system

```
Build debug APK:   run_command(command="source ~/.profile 2>/dev/null; cd /sdcard/MyProject && ./gradlew assembleDebug 2>&1")
Build release:     run_command(command="source ~/.profile 2>/dev/null; cd /sdcard/MyProject && ./gradlew assembleRelease 2>&1")
```

### Install Packages (Android Code Studio / Termux)
```
Update packages:   run_command(command="source ~/.profile 2>/dev/null; pkg update -y && pkg upgrade -y")
Install Node.js:   run_command(command="source ~/.profile 2>/dev/null; pkg install -y nodejs")
Install Python:    run_command(command="source ~/.profile 2>/dev/null; pkg install -y python")
Install Java:      run_command(command="source ~/.profile 2>/dev/null; pkg install -y openjdk-17")
Install Git:       run_command(command="source ~/.profile 2>/dev/null; pkg install -y git")
```

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
Memory:         run_command(command="free -h")
```

## Important Notes

- Commands run via the HTTP server in Android Code Studio's terminal environment
- The server's shell is NON-INTERACTIVE — always source ~/.profile first
- /sdcard is mounted with noexec — can't execute binaries from there
- Use `pkg` to install packages (nodejs, python, openjdk-17, git)
- Run `idesetup -c` to set up the full IDE environment
- The server has a 120-second timeout — long builds may be killed
- If the server dies, tell user to restart: open terminal → "node /sdcard/Aira/aira-server.js &"
