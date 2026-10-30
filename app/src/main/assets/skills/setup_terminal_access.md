---
name: setup-terminal-access
description: Set up a local HTTP server in Termux/terminal so Aira can run shell commands directly (no UI needed). Creates a persistent run_command custom tool.
keywords: terminal, setup, server, command, termux, shell, run command, build, install, access, setup terminal, enable terminal
---
# Setup Terminal Access

## What This Does
Sets up a local HTTP server (Node.js) in your terminal app (Termux or Android Code Studio).
Once running, Aira can execute ANY shell command directly via 1 API call — no need to
open the terminal app or use accessibility.

## Prerequisites
- A terminal app installed: Termux (com.termux) OR Android Code Studio (com.tom.rv2ide)
- Node.js installed in the terminal (check: `node --version`)
  - Termux: `pkg install nodejs`
  - Android Code Studio: comes with Node.js

## Step 1: Write the Server File

Write this exact content to /sdcard/Aira/aira-server.js:

### Method A: Use writeFile tool (if Aira has storage permission)
Call writeFile(path="/sdcard/Aira/aira-server.js", content=<SERVER CODE BELOW>)

### Method B: Use the terminal (if writeFile fails)
1. launchApp(package="com.termux") OR launchApp(package="com.tom.rv2ide")
2. wait(seconds=2)
3. readScreen() — verify terminal is open
4. type(text="cat > /sdcard/Aira/aira-server.js << 'AIRA_EOF'")
5. pressEnter()
6. Type each line of the server code below, pressing Enter after each line
7. type(text="AIRA_EOF")
8. pressEnter()

### SERVER CODE (write this exact content to the file):

```javascript
const http = require('http');
const { execSync } = require('child_process');

const PORT = 8080;
const HOST = '127.0.0.1';

http.createServer((req, res) => {
  res.setHeader('Content-Type', 'application/json');
  
  // Parse URL: /exec?cmd=ENCODED_COMMAND
  const url = new URL(req.url, `http://${HOST}:${PORT}`);
  const cmd = url.searchParams.get('cmd');
  const token = url.searchParams.get('token');
  
  // Simple token check (change this to a secret only you know)
  if (token !== 'aira') {
    res.end(JSON.stringify({ success: false, error: 'Invalid token. Use ?token=aira' }));
    return;
  }
  
  if (!cmd) {
    res.end(JSON.stringify({ 
      success: true, 
      output: 'Aira Shell Server running. Use /exec?token=aira&cmd=YOUR_COMMAND' 
    }));
    return;
  }
  
  try {
    // v4.0.48: Source profile first so pkg/node/java are in PATH
    const fullCmd = 'source ~/.profile 2>/dev/null; source ~/.bashrc 2>/dev/null; ' + cmd;
    const output = execSync(fullCmd, { 
      timeout: 120000, 
      encoding: 'utf8',
      maxBuffer: 1024 * 1024 * 10  // 10MB max output
    });
    res.end(JSON.stringify({ success: true, output }));
  } catch (e) {
    res.end(JSON.stringify({ 
      success: false, 
      error: e.message,
      output: e.stdout || '',
      stderr: e.stderr || ''
    }));
  }
}).listen(PORT, HOST, () => {
  console.log(`Aira Shell Server running at http://${HOST}:${PORT}`);
  console.log(`Test: curl "http://127.0.0.1:8080/exec?token=aira&cmd=echo%20hello"`);
});
```

## Step 2: Start the Server

### Method A: If you wrote the file via writeFile (no terminal needed)
1. launchApp(package="com.termux") OR launchApp(package="com.tom.rv2ide")
2. wait(seconds=2)
3. type(text="node /sdcard/Aira/aira-server.js &")
4. pressEnter()
5. wait(seconds=2)
6. readScreen() — you should see "Aira Shell Server running at http://127.0.0.1:8080"

### Method B: If you wrote the file via terminal (already in terminal)
1. type(text="node /sdcard/Aira/aira-server.js &")
2. pressEnter()
3. wait(seconds=2)
4. readScreen() — verify server started

## Step 3: Create the Custom Tool

Call createCustomTool with these EXACT parameters:

- name: "run_command"
- description: "Run any shell command on the phone via the local Aira server. Returns stdout. Use for: file operations, building projects, installing packages, running scripts, git commands, system info. Server must be running (started via setup-terminal-access skill)."
- handlerType: "http"
- handler: '{"method":"GET","url":"http://127.0.0.1:8080/exec?token=aira&cmd={command}"}'
- parameters: "command"

## Step 4: Test the Tool

Call run_command(command="echo hello from aira")
Expected result: {"success": true, "output": "hello from aira\n"}

If it fails:
- Check if server is running: run_command(command="echo test")
- If connection refused: server not started → go back to Step 2
- If invalid token: check the token matches 'aira' in the URL

## Step 5: Complete

Call complete(success=true, reason="Terminal access set up. run_command tool created + tested. Server running at http://127.0.0.1:8080. You can now run any command directly.")

## After Setup — What You Can Do

Once set up, these work with 1 API call (no terminal UI needed):

### File Operations
- run_command(command="ls -la /sdcard/Download/")
- run_command(command="cat /sdcard/Aira/config.json")
- run_command(command="cp /sdcard/file1.txt /sdcard/file2.txt")

### Build Projects
- run_command(command="cd /sdcard/MyProject && ./gradlew assembleDebug")
- run_command(command="cd /sdcard/MyProject && ./gradlew assembleRelease")

### Install Packages (Termux)
- run_command(command="pkg install python")
- run_command(command="pkg install openjdk-17")
- run_command(command="npm install -g typescript")

### Git
- run_command(command="cd /sdcard/MyProject && git status")
- run_command(command="cd /sdcard/MyProject && git add -A && git commit -m 'update' && git push")

### System Info
- run_command(command="getprop ro.product.model")
- run_command(command="dumpsys battery")
- run_command(command="pm list packages -3")

### Run Scripts
- run_command(command="python /sdcard/Aira/script.py")
- run_command(command="node /sdcard/Aira/app.js")

### Network
- run_command(command="curl -s https://api.github.com/repos/termux/termux-app/releases/latest")
- run_command(command="ping -c 3 google.com")

## Important Notes

- The server runs in the terminal app's process. If the terminal app is killed, the server stops.
- To restart: open terminal → run "node /sdcard/Aira/aira-server.js &"
- The server listens on 127.0.0.1 ONLY (not accessible from other devices — safe).
- Token is 'aira' — change it in the server code + custom tool handler if you want security.
- Commands have a 120-second timeout (long builds will be killed if they take longer).
- Max output is 10MB (larger outputs will error).

## Troubleshooting

### "Connection refused" error
Server not running. Open terminal and run: node /sdcard/Aira/aira-server.js &

### "Invalid token" error
Token mismatch. Check that the URL in the custom tool handler matches the server code.

### Command takes too long
Increase timeout in server.js (change 120000 to 300000 for 5 minutes).

### Server dies after closing terminal
Run with nohup: nohup node /sdcard/Aira/aira-server.js > /sdcard/Aira/server.log 2>&1 &
