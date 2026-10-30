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
const VERSION = '4.0.51';

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

// v4.0.51: Track all running subprocesses so we can kill them on client
// disconnect or when the /kill endpoint is called. This prevents orphaned
// commands from consuming CPU after the user cancels.
const runningProcs = new Set();

http.createServer((req, res) => {
  res.setHeader('Content-Type', 'application/json');

  const url = new URL(req.url, `http://${HOST}:${PORT}`);
  const cmd = url.searchParams.get('cmd');
  const token = url.searchParams.get('token');
  const timeoutParam = url.searchParams.get('timeout');

  // v4.0.50: Default timeout = 0 (infinite — no kill). AI decides per-command.
  // Omit timeout → command runs until it finishes.
  // Pass timeout=60 → killed after 60s (for risky/hanging commands).
  let timeoutSec = 0;  // 0 = infinite
  if (timeoutParam && /^\d+$/.test(timeoutParam)) {
    timeoutSec = parseInt(timeoutParam, 10);  // no cap — AI picks any value
  }
  const timeoutMs = timeoutSec > 0 ? timeoutSec * 1000 : 0;

  if (token !== 'aira') {
    res.end(JSON.stringify({ success: false, error: 'Invalid token. Use ?token=aira' }));
    return;
  }

  // v4.0.51: /kill endpoint — kills ALL running commands.
  // Called when user sends a new message or taps Stop (client disconnects).
  // Usage: GET /kill?token=aira
  if (url.pathname === '/kill') {
    let killed = 0;
    for (const p of runningProcs) {
      try { p.kill('SIGKILL'); killed++; } catch (e) {}
    }
    runningProcs.clear();
    res.end(JSON.stringify({ success: true, killed: killed, message: 'All running commands killed' }));
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
  let sawPrompt = false;  // v4.0.50: only auto-Y if a prompt char appears in output

  let proc;
  if (ptyMode === 'script') {
    // v4.0.50: Use 'inherit'-style stdio via pipe but flush immediately.
    // script -qfc runs the command; output is captured but NOT buffered
    // because we read 'data' events as they arrive (no coalescing).
    proc = spawn('script', ['-qfc', cmd, '/dev/null'], {
      stdio: ['pipe', 'pipe', 'pipe'],
      env: { ...process.env, TERM: 'dumb', SCRIPT_USE_CRLF: '0' }
    });
  } else {
    // Fallback: direct spawn with /system/bin/sh (no PTY)
    proc = spawn(cmd, { shell: '/system/bin/sh', stdio: ['pipe', 'pipe', 'pipe'] });
  }

  // v4.0.51: Register this proc so /kill endpoint can find it.
  // Also set up disconnect watcher — if client closes the connection
  // (user sent a new message or tapped Stop), kill the subprocess.
  runningProcs.add(proc);
  let clientDisconnected = false;
  req.on('close', () => {
    if (!proc.killed && proc.exitCode === null && proc.signalCode === null) {
      // Process still running + client gone → kill it
      try { proc.kill('SIGKILL'); } catch (e) {}
      clientDisconnected = true;
      console.log(`[v4.0.51] Client disconnected — killed proc for: ${cmd.substring(0, 50)}`);
    }
    runningProcs.delete(proc);
  });

  // v4.0.50: Auto-answer Y/n prompts — but ONLY when a prompt is detected.
  // We watch stdout for '?', '[Y/n]', '[y/N]', '(yes/no)' patterns.
  // When detected, feed 'y\n' once. This prevents sending spurious 'y\n' to
  // commands like 'echo hello' that never asked for input.
  let autoYesSent = false;
  const autoYesChecker = (data) => {
    if (autoYesSent) return;
    const text = data.toString();
    if (/[?].*[Yy]\/[Nn]|[Yy]es\/[Nn]o|press.*enter|continue/i.test(text)) {
      try { proc.stdin.write('y\n'); } catch (e) {}
      autoYesSent = true;
      sawPrompt = true;
    }
  };

  // Collect output + check for prompts
  proc.stdout.on('data', (data) => {
    stdout += data.toString();
    autoYesChecker(data);
  });
  proc.stderr.on('data', (data) => {
    stderr += data.toString();
    autoYesChecker(data);
  });

  // v4.0.50: Fallback auto-Y after 5s IF output has paused (command may be waiting).
  // This catches prompts that didn't match the regex (e.g. localized prompts).
  const autoYesTimer = setTimeout(() => {
    if (!autoYesSent) {
      try { proc.stdin.write('y\n'); } catch (e) {}
      autoYesSent = true;
    }
  }, 5000);

  // v4.0.50: Only set a killer if timeout > 0 (0 = infinite, no kill)
  const killer = timeoutMs > 0 ? setTimeout(() => {
    timedOut = true;
    try { proc.kill('SIGKILL'); } catch (e) {}
  }, timeoutMs) : null;

  proc.on('close', (code) => {
    clearTimeout(autoYesTimer);
    if (killer) clearTimeout(killer);
    // Close stdin to prevent lingering pipe
    try { proc.stdin.end(); } catch (e) {}

    // Strip ANSI escape codes + carriage returns + script headers
    const clean = (s) => s
      .replace(/\x1b\[[0-9;]*[a-zA-Z]/g, '')
      .replace(/\x1b[()][AB01]/g, '')
      .replace(/\r/g, '')
      .replace(/^Script started.*$/gm, '')
      .replace(/^Script done.*$/gm, '')
      .replace(/^\s*\$/gm, '')  // remove shell prompt lines
      .trim();

    const elapsed = Math.round((Date.now() - startTime) / 1000);
    const result = {
      success: code === 0 && !timedOut && !clientDisconnected,
      exitCode: code,
      timedOut: timedOut,
      cancelled: clientDisconnected,  // v4.0.51: client disconnected (user sent new msg)
      output: clean(stdout),
      stderr: clean(stderr),
      elapsed: elapsed + 's',
      pty: ptyMode,
      autoYesUsed: autoYesSent
    };
    res.end(JSON.stringify(result));
  });

  proc.on('error', (err) => {
    clearTimeout(autoYesTimer);
    if (killer) clearTimeout(killer);
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
  console.log(`PTY: ${ptyMode} | Default timeout: infinite (0) | AI controls via ?timeout=N`);
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
- description: "Run any shell command on the phone via the local Aira server. Returns stdout + exit code. Use for: file operations, building projects, installing packages, running scripts, git commands, system info. Server must be running. ALWAYS prefix commands with: export PATH=$PATH:/data/data/com.tom.rv2ide/files/usr/bin; — so pkg/node/java are found. Use command -v (NOT which) to find binaries. Timeout is OPTIONAL: omit it to let commands run until done; pass timeout=60 only for commands that might hang (like ping or interactive prompts)."
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

### Quick Commands (no timeout needed — default is infinite)
- run_command(command="ls -la /sdcard/Download/")
- run_command(command="cat /sdcard/Aira/config.json")
- run_command(command="export PATH=$PATH:/data/data/com.tom.rv2ide/files/usr/bin; command -v pkg node java")

### Long Commands (omit timeout — let them finish)
- run_command(command="export PATH=...; cd ~/MyProject && ./gradlew assembleDebug 2>&1")
- run_command(command="export PATH=...; pkg install -y openjdk-17 2>&1")
- run_command(command="export PATH=...; npm install 2>&1")

### Commands that might hang (pass timeout as safety net)
- run_command(command="ping -c 10 google.com", timeout=15)  ← limit to 15s
- run_command(command="tail -f /sdcard/Aira/server.log", timeout=5)  ← grab 5s of logs

## Timeout Guide (v4.0.50+)

**Timeout is OPTIONAL.** Default = infinite (no kill). AI decides per-command.

| Command type | timeout value | Why |
|--------------|---------------|-----|
| Builds, installs, scripts (should finish) | **omit** | Let it run until done — no kill |
| `echo`, `ls`, `cat` (instant) | **omit** | Finishes immediately anyway |
| `ping`, `tail -f` (runs forever) | `timeout=10` | Limit to 10s |
| Risky commands that might hang | `timeout=60` | Safety net if auto-Y fails |
| Very long builds (>10 min) | **omit** | Let it finish — no cap |

**Rule of thumb:** If the command SHOULD finish (builds, installs, file ops), OMIT timeout.
If the command MIGHT NOT finish (ping, watch, tail -f, interactive REPL), pass a timeout.

## Important Notes

- Server runs in the terminal app's process. If terminal is killed, server stops.
- To restart: open terminal → `node /sdcard/Aira/aira-server.js &`
- Server listens on 127.0.0.1 ONLY (not accessible from other devices — safe).
- Token is 'aira' — change it in server code + custom tool handler if you want security.
- v4.0.50: Default timeout = infinite (no kill). AI decides per-command. Pass `timeout=60` only for commands that might hang.
- PTY mode auto-detected at startup via `script` command.
- ANSI color codes stripped from output for cleaner AI parsing.
- Interactive Y/n prompts auto-answered with 'y' after 3 seconds.
- `idesetup` is bash-only + interactive — even with PTY, prefer telling user to run it manually.

## Troubleshooting

### "Connection refused"
Server not running. Tell user: open terminal and run `node /sdcard/Aira/aira-server.js &`

### "timedOut": true in response
Command was killed by the timeout you passed. Either increase the timeout or omit it (default = infinite).
Example: run_command(command="...", timeout=60) → timed out → re-run without timeout or with higher value.

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
