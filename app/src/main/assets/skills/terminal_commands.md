---
name: terminal-commands
description: Common shell commands to run after terminal access is set up. Use when user asks to build, install, check system, manage files, or run scripts via the terminal.
keywords: build, install, command, shell, terminal, run, script, git, system, check, compile, make
---
# Terminal Commands Reference

## Prerequisites
This skill assumes you've already set up terminal access via the setup-terminal-access skill.
The run_command custom tool should already exist + the server should be running.

## Verify Server Is Running
Before running commands, verify:
run_command(command="echo aira-server-check")
If this fails, tell the user: "Terminal server not running. Say 'set up terminal access' to start it."

## Common Commands by Category

### File Operations
```
List files:        run_command(command="ls -la /sdcard/Download/")
Read file:         run_command(command="cat /sdcard/Aira/config.json")
Copy file:         run_command(command="cp /sdcard/file1.txt /sdcard/file2.txt")
Move file:         run_command(command="mv /sdcard/old.txt /sdcard/new.txt")
Delete file:       run_command(command="rm /sdcard/unwanted.txt")
Create directory:  run_command(command="mkdir -p /sdcard/Aira/projects")
Find files:         run_command(command="find /sdcard -name '*.apk' -type f")
Disk usage:         run_command(command="du -sh /sdcard/Aira/*")
```

### Build Android Projects
```
Build debug APK:   run_command(command="cd /sdcard/MyProject && ./gradlew assembleDebug")
Build release:     run_command(command="cd /sdcard/MyProject && ./gradlew assembleRelease")
Clean build:       run_command(command="cd /sdcard/MyProject && ./gradlew clean assembleDebug")
Check errors:       run_command(command="cd /sdcard/MyProject && ./gradlew assembleDebug 2>&1 | tail -50")
List tasks:         run_command(command="cd /sdcard/MyProject && ./gradlew tasks")
```

### Install Packages (Termux)
```
Update packages:   run_command(command="pkg update && pkg upgrade")
Install Node.js:   run_command(command="pkg install nodejs")
Install Python:    run_command(command="pkg install python")
Install Java:      run_command(command="pkg install openjdk-17")
Install Git:       run_command(command="pkg install git")
Install curl:      run_command(command="pkg install curl")
Install vim:       run_command(command="pkg install vim")
```

### Git Commands
```
Status:            run_command(command="cd /sdcard/MyProject && git status")
Add all:           run_command(command="cd /sdcard/MyProject && git add -A")
Commit:            run_command(command="cd /sdcard/MyProject && git commit -m 'update'")
Push:              run_command(command="cd /sdcard/MyProject && git push")
Pull:              run_command(command="cd /sdcard/MyProject && git pull")
Clone:             run_command(command="cd /sdcard && git clone https://github.com/user/repo.git")
Log:               run_command(command="cd /sdcard/MyProject && git log --oneline -10")
```

### System Info
```
Device model:      run_command(command="getprop ro.product.model")
Android version:   run_command(command="getprop ro.build.version.release")
Battery:           run_command(command="dumpsys battery")
Storage:           run_command(command="df -h /sdcard")
Memory:            run_command(command="free -h")
CPU info:          run_command(command="cat /proc/cpuinfo | head -20")
Network:           run_command(command="ip addr show wlan0")
IP address:        run_command(command="ip addr show wlan0 | grep 'inet '")
Uptime:            run_command(command="uptime")
Installed apps:    run_command(command="pm list packages -3")
```

### Run Scripts
```
Python:            run_command(command="python /sdcard/Aira/script.py")
Node.js:           run_command(command="node /sdcard/Aira/app.js")
Shell script:      run_command(command="sh /sdcard/Aira/build.sh")
With args:         run_command(command="python /sdcard/Aira/script.py --arg1 value1")
```

### Network
```
HTTP GET:          run_command(command="curl -s https://api.github.com/repos/termux/termux-app/releases/latest")
Ping:              run_command(command="ping -c 3 google.com")
Download:          run_command(command="curl -o /sdcard/Aira/file.zip https://example.com/file.zip")
DNS lookup:        run_command(command="nslookup google.com")
Port check:        run_command(command="curl -s -o /dev/null -w '%{http_code}' https://google.com")
```

### Process Management
```
List processes:    run_command(command="ps aux | head -20")
Kill by name:      run_command(command="pkill -f node")
Find process:      run_command(command="ps aux | grep node")
```

## How to Handle Build Errors

When a build fails, the output will contain error messages. Common patterns:

### Gradle build errors
```
ERROR: Failed to resolve: com.squareup.retrofit2:retrofit:2.9.0
→ Dependency not found. Check build.gradle for correct version.

ERROR: Manifest merger failed
→ AndroidManifest.xml conflict. Check for duplicate permissions/activities.

ERROR: Compilation failed
→ Java/Kotlin code error. The output will show the file + line number.
```

### What to do when build fails
1. Read the error output carefully
2. Identify the file + line number
3. Use readFile to read the file
4. Use writeFile to fix the error
5. Run the build again: run_command(command="cd /project && ./gradlew assembleDebug")

## Important Notes

- Commands run in the terminal app's environment (Termux or Android Code Studio)
- The server has a 120-second timeout — long builds may be killed
- For long builds, increase timeout in /sdcard/Aira/aira-server.js
- Max output is 10MB — very large outputs will error
- The server runs on 127.0.0.1:8080 (localhost only — not accessible from other devices)
- If the server dies, restart: open terminal → "node /sdcard/Aira/aira-server.js &"
