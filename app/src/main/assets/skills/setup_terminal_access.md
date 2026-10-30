---
name: setup-terminal-access
description: Set up a local HTTP server in Termux/terminal so Aira can run shell commands directly (no UI needed). Creates a persistent run_command custom tool with PTY support + configurable timeout.
keywords: terminal, setup, server, command, termux, shell, run command, build, install, access, setup terminal, enable terminal, pty, timeout
---
# Setup Terminal Access

## What This Does
Sets up a local HTTP server (Node.js) in your terminal app (Termux or Android Code Studio).
Once running, Aira can execute ANY shell command directly via 1 API call — no need to
open the terminal app or use accessibility.

## v4.0.49 Improvements
1. **PTY support** — uses `script` command to allocate a real pseudo-terminal, so interactive
   commands (apt, pkg, idesetup prompts) work correctly.
2. **Timeout parameter** — AI can pass `timeout=300` for long builds (default 120s, max 600s).
3. **Auto-answer Y/n** — feeds `y\n` to stdin after 3 seconds, auto-confirming prompts.
4. **ANSI stripping** — removes color codes from output for cleaner AI parsing.
5. **No `source ~/.profile`** — removed the auto-prepend that caused "Syntax error: end of file".

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
const { spawn, execSync } = require('child_process');

const PORT = 8080;
const HOST = '127.0.0.1';
const VERSION = '4.0.49';

// v4.0.49: Detect PTY support via 'script' command at startup.
// If 'script' is available, commands run in a real PTY (interactive prompts work).
// If not, falls back to direct spawn (still better than execSync).
let ptyMode = 'spawn';
try {
  const test = execSync('script -qc "echo __pty_ok__" /dev/null', {
    stdio: 'pipe', timeout: 5000, encoding: 'utf8'
  });
  if (test.includes('__pty_ok__')) {
    ptyMode = 'script';
  }
} catch (e) {
  ptyMode = 'spawn';
}
console.log(`PTY mode: ${ptyMode}`);

http.createServer((req, res) => {
  res.setHeader('Content-Type', 'application/json');

  const url = new URL(req.url, `http://${HOST}:${PORT}`);
  const cmd = url.searchParams.get('cmd');
  const token = url.searchParams.get('token');
  const timeoutParam = url.searchParams.get('timeout');

  // Parse timeout: default 120s, max 600s (10 min)
  let timeoutSec = 120;
  if (timeoutParam && /^\d+$/.test(timeoutParam)) {
    timeoutSec = Math.min(parseInt(timeoutParam, 10), 600);
  }
  const timeoutMs = timeoutSec * 1000;

  if (token !== 'aira') {
    res.end(JSON.stringify({ success: false, error: 'Invalid token. Use ?token=aira' }));
    return;
  }

  if (!cmd) {
    res.end(JSON.stringify({
      success: true,
      output: `Aira Shell Server v${VERSION}. PTY: ${ptyMode}. Usage: /exec?token=aira&cmd=CMD&timeout=120`
    }));
    return;
  }

  const startTime = Date.now();
  let stdout = '';
  let stderr = '';
  let timedOut = false;

  let proc;
  if (ptyMode === 'script') {
    // PTY mode: real pseudo-terminal via 'script' command.
    // This makes interactive commands work (apt, pkg, idesetup prompts).
    proc = spawn('script', ['-qc', cmd, '/dev/null'], {
      stdio: ['pipe', 'pipe', 'pipe'],
      env: { ...process.env, TERM: 'dumb' }
    });
  } else {
    // Fallback: direct spawn with /system/bin/sh (no PTY)
    proc = spawn(cmd, { shell: '/system/bin/sh', stdio: ['pipe', 'pipe', 'pipe'] });
  }

  // v4.0.49: Auto-answer Y/n prompts — feed 'y\n' to stdin after 3 seconds.
  // This handles interactive prompts like "Do you want to continue? [Y/n]"
  const autoYesTimer = setTimeout(() => {
    try { proc.stdin.write('y\n'); } catch (e) {}
  }, 3000);

  // Collect output
  proc.stdout.on('data', (data) => { stdout += data.toString(); });
  proc.stderr.on('data', (data) => { stderr += data.toString(); });

  // Timeout killer
  const killer = setTimeout(() => {
    timedOut = true;
    try { proc.kill('SIGKILL'); } catch (e) {}
  }, timeoutMs);

  proc.on('close', (code) => {
    clearTimeout(autoYesTimer);
    clearTimeout(killer);

    // Strip ANSI escape codes + carriage returns + script headers
    const clean = (s) => s
      .replace(/\x1b\[[0-9;]*[a-zA-Z]/g, '')
      .replace(/\x1b[()][AB01]/g, '')
      .replace(/\r/g, '')
      .replace(/^Script started.*$/gm, '')
      .replace(/^Script done.*$/gm, '')
      .trim();

    const elapsed = Math.round((Date.now() - startTime) / 1000);
    const result = {
      success: code === 0 && !timedOut,
      exitCode: code,
      timedOut: timedOut,
      output: clean(stdout),
      stderr: clean(stderr),
      elapsed: elapsed + 's',
      pty: ptyMode
    };
    res.end(JSON.stringify(result));
  });

  proc.on('error', (err) => {
    clearTimeout(autoYesTimer);
    clearTimeout(killer);
    res.end(JSON.stringify({
      success: false,
      error: err.message,
      output: stdout,
      stderr: stderr,
      pty: ptyMode
    }));
  });
}).listen(PORT, HOST, () => {
  console.log(`Aira Shell Server v${VERSION} at http://${HOST}:${PORT}`);
  console.log(`PTY: ${ptyMode} | Default timeout: 120s | Max: 600s (10 min)`);
  console.log(`Test: curl "http://127.0.0.1:8080/exec?token=aira&cmd=echo%20hello"`);
});
```

## Step 2: Start the Server

### IMPORTANT: Aira CANNOT start the server itself
Aira's `runShellCommand` tool uses Android's `Runtime.exec()` with `/system/bin/sh`.
That shell does NOT have `node` in PATH (node lives in `/data/data/com.tom.rv2ide/files/usr/bin/`).
So `runShellCommand(command="node /sdcard/Aira/aira-server.js &")` fails with:
`sh: node: inaccessible or not found`

The user MUST start the server manually. Tell them:
"Open AndroidIDE-Rv2 (or Termux) terminal and run: `node /sdcard/Aira/aira-server.js &`"

### If you want to try launching via accessibility:
1. launchApp(package="com.tom.rv2ide")
2. wait(seconds=2)
3. type(text="node /sdcard/Aira/aira-server.js &")
4. pressEnter()
5. wait(seconds=2)
6. readScreen() — you should see "Aira Shell Server v4.0.49 at http://127.0.0.1:8080"
   + "PTY: script" or "PTY: spawn"

If the terminal didn't receive input, tell the user to run it manually.

## Step 3: Create the Custom Tool

Call createCustomTool with these EXACT parameters:

- name: "run_command"
- description: "Run any shell command on the phone via the local Aira server. Returns stdout + exit code. Use for: file operations, building projects, installing packages, running scripts, git commands, system info. Server must be running (started via setup-terminal-access skill). ALWAYS prefix commands with: export PATH=$PATH:/data/data/com.tom.rv2ide/files/usr/bin; — so pkg/node/java are found. Use command -v (NOT which) to find binaries. Pass timeout=300 for long builds (default 120s, max 600s). Interactive Y/n prompts are auto-answered with 'y'."
- handlerType: "http"
- handler: '{"method":"GET","url":"http://127.0.0.1:8080/exec?token=aira&cmd={command}&timeout={timeout}"}'
- parameters: "command,timeout"

NOTE: `timeout` is OPTIONAL — if you omit it, the server uses 120s default.
The Aira app (v4.0.49+) strips unresolved {timeout} from the URL automatically.

## Step 4: Test the Tool

Call run_command(command="echo hello from aira")
Expected result: {"success": true, "output": "hello from aira", "exitCode": 0, ...}

If it fails:
- "Connection refused" → server not running. Tell user to run: node /sdcard/Aira/aira-server.js &
- "Invalid token" → check token matches 'aira' in the URL

## Step 5: Verify Environment

After setup, find what tools are available:
```
run_command(command="export PATH=$PATH:/data/data/com.tom.rv2ide/files/usr/bin; command -v pkg node java javac git 2>/dev/null; echo DONE")
```
Expected: paths to pkg, node, java, etc., then "DONE"

DO NOT use `which` — it does not exist in the minimal shell and will fail with "which: not found".

## Step 6: Check PTY Mode

```
run_command(command="echo pty_check")
```
Look at the response's `pty` field:
- `"pty": "script"` → PTY active, interactive commands work
- `"pty": "spawn"` → fallback mode (no PTY), interactive commands may hang

If PTY is "spawn" and you need interactive commands, tell the user:
"PTY support not available. For interactive commands like idesetup, run them manually in the terminal."

## Step 7: Complete

Call complete(success=true, reason="Terminal access set up. run_command tool created + tested. Server running at http://127.0.0.1:8080 with PTY: <mode>. Remember to prefix commands with: export PATH=$PATH:/data/data/com.tom.rv2ide/files/usr/bin;")

## After Setup — What You Can Do

### Quick Commands (default 120s timeout)
- run_command(command="ls -la /sdcard/Download/")
- run_command(command="cat /sdcard/Aira/config.json")
- run_command(command="export PATH=$PATH:/data/data/com.tom.rv2ide/files/usr/bin; command -v pkg node java")

### Long Commands (pass timeout for builds, installs)
- run_command(command="export PATH=...; cd ~/MyProject && ./gradlew assembleDebug 2>&1", timeout=300)
- run_command(command="export PATH=...; pkg install -y openjdk-17 2>&1", timeout=180)
- run_command(command="export PATH=...; npm install 2>&1", timeout=300)

### Interactive Commands (PTY auto-answers Y/n after 3s)
- run_command(command="export PATH=...; pkg install python 2>&1", timeout=120)
- apt/apt-get prompts are auto-answered with 'y'

## Timeout Guide

| Command type | timeout value | Example |
|--------------|-------------|---------|
| echo, ls, cat (instant) | omit (uses 120s default) | run_command(command="echo hi") |
| pkg install (downloads) | 180 | run_command(command="... pkg install -y nodejs", timeout=180) |
| gradle build (compiles) | 300 | run_command(command="... ./gradlew assembleDebug", timeout=300) |
| Large npm install | 300 | run_command(command="... npm install", timeout=300) |
| Max allowed | 600 | run_command(command="...", timeout=600) |

## Important Notes

- Server runs in the terminal app's process. If terminal is killed, server stops.
- To restart: open terminal → `node /sdcard/Aira/aira-server.js &`
- Server listens on 127.0.0.1 ONLY (not accessible from other devices — safe).
- Token is 'aira' — change it in server code + custom tool handler if you want security.
- Default timeout: 120s. Max: 600s (10 min). Client-side cap: 305s (in the APK).
- PTY mode auto-detected at startup via `script` command.
- ANSI color codes stripped from output for cleaner AI parsing.
- Interactive Y/n prompts auto-answered with 'y' after 3 seconds.
- `idesetup` is bash-only + interactive — even with PTY, prefer telling user to run it manually.

## Troubleshooting

### "Connection refused"
Server not running. Tell user: open terminal and run `node /sdcard/Aira/aira-server.js &`

### "timedOut": true in response
Command took longer than the timeout. Pass a higher timeout:
`run_command(command="...", timeout=300)`

### "pty": "spawn" (no PTY)
`script` command not available. Interactive commands may hang. For interactive tasks,
tell user to run manually in terminal.

### Command returns empty output
Check `stderr` field in response — the error might be there.
Also check `exitCode` — non-zero means the command failed.

### "apt WARNING" lines in output
These are EXPECTED NOISE from `pkg` (which wraps `apt`). IGNORE them.
Only check `success`, `exitCode`, and the actual `output`.

### Server dies after closing terminal
Run with nohup: `nohup node /sdcard/Aira/aira-server.js > /sdcard/Aira/server.log 2>&1 &`
