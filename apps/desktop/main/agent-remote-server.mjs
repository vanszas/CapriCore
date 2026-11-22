import { execFile, spawn } from 'node:child_process';
import { promisify } from 'node:util';
import { existsSync, readFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const MODES = new Set(['desktop', 'tailscale']);
const PNG = Buffer.from('89504e470d0a1a0a', 'hex');
const DEFAULT_SCRIPT = fileURLToPath(
  new URL('../../../integrations/mobile_gateway/agent_remote_server.py', import.meta.url),
);
const SETUP_SCRIPT = fileURLToPath(
  new URL('../../../tools/setup/setup-mobile-gateway.ps1', import.meta.url),
);
const PRIVATE_PYTHON = fileURLToPath(
  new URL('../../../.runtime/mobile-gateway-venv/Scripts/python.exe', import.meta.url),
);
const execFileAsync = promisify(execFile);

// ponytail: one loopback service; add discovery only when multiple hosts are supported.
export function createAgentRemoteServer({
  file, persist, fetchRemote = fetch, spawnRemote = spawn,
  script = process.env.AGENT_REMOTE_SERVER_SCRIPT || DEFAULT_SCRIPT,
  python = process.env.AGENT_REMOTE_PYTHON,
  startupTimeout = 15000,
} = {}) {
  let mode = 'desktop';
  try {
    const saved = JSON.parse(readFileSync(file, 'utf8'));
    if (MODES.has(saved.mode)) mode = saved.mode;
  } catch { /* First launch defaults to loopback. */ }
  let child = null;
  let queue = Promise.resolve();
  async function request(path) {
    return fetchRemote(`http://127.0.0.1:9120${path}`, {
      signal: AbortSignal.timeout(4000), redirect: 'error',
    });
  }
  async function status() {
    try {
      const response = await request('/api/status');
      if (!response.ok) throw new Error('Status unavailable.');
      const value = await response.json();
      if (value.ok !== true || !value.features?.includes('sessions')) throw new Error('Unexpected server.');
      return {
        running: true, owned: Boolean(child), selectedMode: mode,
        mode: MODES.has(value.network_mode) ? value.network_mode : 'external',
      };
    } catch {
      return { running: false, owned: Boolean(child), selectedMode: mode, mode };
    }
  }
  async function stop() {
    if (!child) {
      if ((await status()).running) throw new Error('Server started outside Capri. Stop it from ServerStop first.');
      return status();
    }
    const process = child;
    const exited = new Promise((resolve) => process.once('exit', resolve));
    if (!process.kill()) throw new Error('Could not stop Agent Remote.');
    await Promise.race([exited, delay(5000)]);
    if (child === process) throw new Error('Agent Remote has not exited.');
    return status();
  }
  async function execute(value) {
    const action = typeof value === 'string' ? value : value?.action;
    if (action === 'status') return status();
    if (action === 'mode') {
      if (!MODES.has(value?.mode)) throw new Error('Invalid Agent Remote network mode.');
      if ((await status()).running) throw new Error('Stop server before changing network mode.');
      persist(file, { mode: value.mode });
      mode = value.mode;
      return status();
    }
    if (action === 'qr') {
      if (!(await status()).running) throw new Error('Agent Remote is not running.');
      const response = await request('/api/pairing/qr.png');
      if (!response.ok || !response.headers.get('content-type')?.startsWith('image/png'))
        throw new Error('Pairing QR unavailable.');
      const bytes = Buffer.from(await response.arrayBuffer());
      if (bytes.length > 262144 || !bytes.subarray(0, 8).equals(PNG)) throw new Error('Invalid pairing QR.');
      return { src: `data:image/png;base64,${bytes.toString('base64')}` };
    }
    if (action === 'stop') return stop();
    if (action !== 'start') throw new Error('Invalid Agent Remote server action.');
    const before = await status();
    if (before.running) return before;
    if (child) throw new Error('Agent Remote is still starting or stopping.');
    if (!existsSync(script)) throw new Error('Agent Remote server script not found.');
    let executable = python || 'python3';
    if (!python && script === DEFAULT_SCRIPT && globalThis.process.platform === 'win32') {
      executable = PRIVATE_PYTHON;
      const options = { windowsHide: true, timeout: 120000 };
      try {
        await execFileAsync(executable, ['-c', 'import PIL.ImageGrab, qrcode'], options);
      } catch {
        try {
          await execFileAsync('powershell.exe', ['-NoProfile', '-File', SETUP_SCRIPT], options);
        } catch {
          throw new Error('Could not install Agent Remote Python dependencies. Check the connection and retry.');
        }
      }
    }
    const process = spawnRemote(executable, [script], {
      windowsHide: true, stdio: ['pipe', 'ignore', 'ignore'],
      env: { ...globalThis.process.env, AGENT_REMOTE_MANAGED: '1', AGENT_REMOTE_MODE: mode, AGENT_REMOTE_HOST: '127.0.0.1', AGENT_REMOTE_PARENT_PIPE: '1' },
    });
    child = process;
    process.stdin?.on('error', () => {});
    let failure = null;
    const clear = () => { if (child === process) child = null; };
    process.once('error', () => { failure = new Error('Agent Remote Python could not start. Check AGENT_REMOTE_PYTHON.'); clear(); });
    process.once('exit', clear);
    const deadline = Date.now() + startupTimeout;
    while (Date.now() < deadline) {
      await delay(200);
      if (failure) throw failure;
      if (child !== process) throw new Error('Agent Remote exited before readiness. Check Python dependencies and Tailscale.');
      const result = await status();
      if (result.running && result.mode === mode) return result;
    }
    await stop();
    throw new Error('Agent Remote readiness timed out.');
  }
  return {
    run(value) {
      const task = queue.then(() => execute(value));
      queue = task.catch(() => {});
      return task;
    },
    close() { child?.kill(); },
  };
}
