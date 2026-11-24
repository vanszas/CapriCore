import { execFile, spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
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
  remoteMic = null,
  captureHostAudio = null,
  confirmHostAudio = null,
  autoStartCapri = null,
} = {}) {
  let mode = 'desktop';
  let autoStartServer = false;
  try {
    const saved = JSON.parse(readFileSync(file, 'utf8'));
    if (MODES.has(saved.mode)) mode = saved.mode;
    if (typeof saved.autoStartServer === 'boolean') autoStartServer = saved.autoStartServer;
  } catch { /* First launch defaults to loopback. */ }
  let child = null;
  let queue = Promise.resolve();
  const voiceToken = randomUUID();
  const voiceClips = [];
  let voiceSeq = 0;
  let voiceServer = null;
  let voiceUrl = '';
  let voiceListenPromise = null;
  let systemAudio = null;
  let systemAudioSeq = 0;
  let askingForAudio = false;

  function stopSystemAudio() {
    if (!systemAudio) return;
    const capture = systemAudio;
    systemAudio = null;
    clearTimeout(capture.idle);
    capture.controller.abort();
  }

  async function readSystemAudio(after) {
    if (!captureHostAudio) throw new Error('Host audio is unavailable.');
    if (!systemAudio) {
      const capture = { controller: new AbortController(), packets: [], idle: null };
      systemAudio = capture;
      capture.ready = Promise.resolve().then(() => captureHostAudio({
        signal: capture.controller.signal,
        onPacket(pcm) {
          if (systemAudio !== capture) return;
          capture.packets.push({ seq: ++systemAudioSeq, pcmBase64: Buffer.from(pcm).toString('base64') });
          if (capture.packets.length > 12) capture.packets.shift();
        },
        onError(error) {
          capture.error = error;
          if (systemAudio === capture) stopSystemAudio();
        },
      })).catch((error) => {
        if (systemAudio === capture) stopSystemAudio();
        throw error;
      });
    }
    const capture = systemAudio;
    clearTimeout(capture.idle);
    capture.idle = setTimeout(stopSystemAudio, 10_000).unref();
    await capture.ready;
    if (capture.error) throw capture.error;
    return {
      sampleRate: 16000,
      seq: systemAudioSeq,
      packets: after > 0 && after <= systemAudioSeq
        ? capture.packets.filter((packet) => packet.seq > after)
        : capture.packets.slice(-2),
    };
  }

  async function ensureVoiceBridge() {
    if (voiceUrl) return voiceUrl;
    if (voiceListenPromise) return voiceListenPromise;
    voiceListenPromise = new Promise((resolve, reject) => {
      const server = createServer(async (req, res) => {
        const respondJson = (code, payload) => {
          const body = JSON.stringify(payload);
          res.writeHead(code, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) });
          res.end(body);
        };
        if (req.headers['x-capri-voice-token'] !== voiceToken) {
          respondJson(401, { ok: false, error: 'unauthorized' });
          return;
        }
        const parsed = new URL(req.url || '/', 'http://127.0.0.1');
        try {
          if (req.method === 'POST' && parsed.pathname === '/remote-pc/audio-permission') {
            if (!confirmHostAudio || askingForAudio) {
              respondJson(409, { error: 'An audio approval is already pending or unavailable.' });
              return;
            }
            askingForAudio = true;
            try {
              let body = '';
              for await (const chunk of req) {
                body += chunk.toString('utf8');
                if (body.length > 2048) throw new Error('Invalid approval request.');
              }
              respondJson(200, { approved: await confirmHostAudio(JSON.parse(body)) });
            } finally {
              askingForAudio = false;
            }
            return;
          }
          if (req.method === 'GET' && parsed.pathname === '/remote-pc/audio') {
            respondJson(200, await readSystemAudio(Number(parsed.searchParams.get('after')) || 0));
            return;
          }
          if (req.method === 'POST' && parsed.pathname === '/remote-mic/start') {
            if (!remoteMic?.start) {
              respondJson(503, { ok: false, error: 'Remote mic bridge unavailable' });
              return;
            }
            const started = await remoteMic.start();
            respondJson(200, { ok: true, sessionId: started?.sessionId || null, seq: voiceSeq });
            return;
          }
          if (req.method === 'POST' && parsed.pathname === '/remote-mic/pcm') {
            if (!remoteMic?.pcm) {
              respondJson(503, { ok: false, error: 'Remote mic bridge unavailable' });
              return;
            }
            const chunks = [];
            let total = 0;
            for await (const chunk of req) {
              total += chunk.length;
              if (total > 262144) {
                respondJson(413, { ok: false, error: 'PCM payload too large' });
                return;
              }
              chunks.push(chunk);
            }
            if (!(await remoteMic.pcm(Buffer.concat(chunks)))) {
              respondJson(409, { ok: false, error: 'Capri is no longer listening to the remote microphone. Restart the remote mic.' });
              return;
            }
            respondJson(200, { ok: true });
            return;
          }
          if (req.method === 'POST' && parsed.pathname === '/remote-mic/stop') {
            remoteMic?.stop?.();
            respondJson(200, { ok: true });
            return;
          }
          if (req.method === 'GET' && parsed.pathname === '/remote-mic/audio') {
            const after = Number.parseInt(parsed.searchParams.get('after') || '0', 10) || 0;
            respondJson(200, {
              ok: true,
              listening: Boolean(remoteMic?.listening?.()),
              seq: voiceSeq,
              clips: voiceClips.filter((clip) => clip.seq > after),
            });
            return;
          }
          respondJson(404, { ok: false, error: 'not found' });
        } catch (error) {
          respondJson(500, { ok: false, error: error?.message || 'Remote mic error' });
        }
      });
      server.once('error', reject);
      server.listen(0, '127.0.0.1', () => {
        const addr = server.address();
        voiceServer = server;
        voiceUrl = `http://127.0.0.1:${addr.port}`;
        resolve(voiceUrl);
      });
    });
    return voiceListenPromise;
  }
  async function request(path, options) {
    return fetchRemote(`http://127.0.0.1:9120${path}`, {
      ...options, signal: AbortSignal.timeout(4000), redirect: 'error',
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
        tailscaleIp: value.tailscale_ip,
        autoStartServer,
        autoStartCapri: Boolean(autoStartCapri?.get?.()),
      };
    } catch {
      return {
        running: false, owned: Boolean(child), selectedMode: mode, mode,
        autoStartServer,
        autoStartCapri: Boolean(autoStartCapri?.get?.()),
      };
    }
  }
  async function stop() {
    stopSystemAudio();
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
    if (action === 'pc-pair' || action === 'android-pair') {
      let current = await status();
      if (current.running && current.mode === 'external') {
        throw new Error('Stop the external Agent Remote server before pairing here.');
      }
      if (!current.running || current.mode !== 'tailscale') {
        if (current.running) await stop();
        await execute({ action: 'mode', mode: 'tailscale' });
        current = await execute('start');
      }
      return current;
    }
    if (action === 'auto-start-server') {
      if (typeof value?.enabled !== 'boolean') throw new Error('Invalid auto-start server setting.');
      autoStartServer = value.enabled;
      persist(file, { mode, autoStartServer });
      return status();
    }
    if (action === 'auto-start-capri') {
      if (typeof value?.enabled !== 'boolean') throw new Error('Invalid auto-start Capri setting.');
      if (!autoStartCapri?.set) throw new Error('Auto-start Capri is unavailable.');
      autoStartCapri.set(value.enabled);
      return status();
    }
    if (action === 'mode') {
      if (!MODES.has(value?.mode)) throw new Error('Invalid Agent Remote network mode.');
      if ((await status()).running) throw new Error('Stop server before changing network mode.');
      persist(file, { mode: value.mode, autoStartServer });
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
    const activeVoiceUrl = await ensureVoiceBridge().catch(() => '');
    const process = spawnRemote(executable, [script], {
      windowsHide: true, stdio: ['pipe', 'ignore', 'ignore'],
      env: {
        ...globalThis.process.env,
        AGENT_REMOTE_MANAGED: '1',
        AGENT_REMOTE_MODE: mode,
        AGENT_REMOTE_HOST: '127.0.0.1',
        AGENT_REMOTE_PARENT_PIPE: '1',
        ...(activeVoiceUrl ? { CAPRI_REMOTE_VOICE_URL: activeVoiceUrl, CAPRI_REMOTE_VOICE_TOKEN: voiceToken } : {}),
      },
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
    shouldAutoStartServer() {
      return autoStartServer;
    },
    observeVoice(event) {
      if (event?.type !== 'voice.audio' || !event.pcm) return;
      voiceClips.push({
        seq: ++voiceSeq,
        text: String(event.text || ''),
        sampleRate: Number(event.sampleRate) || 24000,
        pcmBase64: Buffer.from(event.pcm).toString('base64'),
      });
      if (voiceClips.length > 16) voiceClips.splice(0, voiceClips.length - 16);
    },
    close() {
      stopSystemAudio();
      child?.kill();
      voiceServer?.close();
    },
  };
}
