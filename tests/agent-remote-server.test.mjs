import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { runInNewContext } from 'node:vm';
import { createAgentRemoteServer } from '../apps/desktop/main/agent-remote-server.mjs';

const png = Buffer.from('89504e470d0a1a0a', 'hex');
function fixture() {
  const folder = mkdtempSync(join(process.env.TMPDIR || process.cwd(), 'remote-test-'));
  const file = join(folder, 'network.json');
  let running = false, mode = 'desktop', child, spawns = 0, image = png;
  const fetchRemote = async (url) => {
    assert(url.startsWith('http://127.0.0.1:9120/'));
    if (!running) throw new Error('offline');
    if (url.endsWith('/api/status')) return Response.json({ ok: true, features: ['sessions'], network_mode: mode, pairing_payload: 'not-forwarded' });
    return new Response(image, { headers: { 'content-type': 'image/png' } });
  };
  const spawnRemote = (_python, _args, options) => {
    spawns++;
    assert.equal(options.env.AGENT_REMOTE_HOST, '127.0.0.1');
    assert.equal(options.env.AGENT_REMOTE_MANAGED, '1');
    mode = options.env.AGENT_REMOTE_MODE;
    running = true;
    child = new EventEmitter();
    child.kill = () => {
      running = false;
      queueMicrotask(() => child.emit('exit', 0));
      return true;
    };
    return child;
  };
  const options = { file, persist: (path, value) => writeFileSync(path, JSON.stringify(value)), fetchRemote, spawnRemote, script: fileURLToPath(import.meta.url) };
  return {
    options, controller: createAgentRemoteServer(options),
    external() { running = true; mode = 'external'; },
    badImage() { image = Buffer.from('not a PNG'); },
    get spawns() { return spawns; },
  };
}

test('Agent Remote persists validated mode, starts once, proxies PNG and stops owned child', async () => {
  const f = fixture(), server = f.controller;
  assert.equal((await server.run('status')).running, false);
  await assert.rejects(server.run({ action: 'mode', mode: '0.0.0.0' }), /Invalid/);
  await server.run({ action: 'mode', mode: 'tailscale' });
  assert.equal((await createAgentRemoteServer(f.options).run('status')).selectedMode, 'tailscale');
  const [first, second] = await Promise.all([server.run('start'), server.run('start')]);
  assert(first.running && second.running && first.owned);
  assert.equal(first.mode, 'tailscale');
  assert.equal(f.spawns, 1);
  assert(!('pairing_payload' in first));
  await assert.rejects(server.run({ action: 'mode', mode: 'desktop' }), /Stop server/);
  assert.equal((await server.run('qr')).src, `data:image/png;base64,${png.toString('base64')}`);
  f.badImage();
  await assert.rejects(server.run('qr'), /Invalid pairing QR/);
  assert.equal((await server.run('stop')).running, false);
  await assert.rejects(server.run('qr'), /not running/);
  await assert.rejects(server.run('bogus'), /Invalid/);
});

test('Agent Remote never kills external server or claims ownership', async () => {
  const f = fixture();
  f.external();
  const status = await f.controller.run('start');
  assert.equal(status.running, true);
  assert.equal(status.owned, false);
  assert.equal(status.mode, 'external');
  assert.equal(f.spawns, 0);
  await assert.rejects(f.controller.run('stop'), /outside Capri/);
});

test('Agent Remote spawn errors clear owned handle without false readiness', async () => {
  const f = fixture();
  const server = createAgentRemoteServer({ ...f.options, spawnRemote: () => {
    const child = new EventEmitter();
    queueMicrotask(() => child.emit('error', new Error('ENOENT')));
    return child;
  } });
  await assert.rejects(server.run('start'), /Python could not start/);
  assert.equal((await server.run('status')).owned, false);
});

test('Settings commands prefill only; renderer refuses to overwrite a draft', () => {
  const settings = readFileSync(new URL('../apps/desktop/renderer/settings-app.mjs', import.meta.url), 'utf8');
  const command = settings.slice(settings.indexOf('function sendAgentRemoteCommand('), settings.indexOf("document.getElementById('agent-remote-settings-mode')"));
  assert(command.includes("action: 'prefill'"));
  assert(!command.includes('raw.send('));
  const main = readFileSync(new URL('../apps/desktop/main.mjs', import.meta.url), 'utf8');
  const handler = main.slice(main.indexOf("handle('capri:agent-remote-server'"), main.indexOf("handle('capri:skill-settings'"));
  assert(handler.includes("['/debate', '/fable-mind'].includes(action.command)"));
  assert(!handler.includes('chat.send'));
  const app = readFileSync(new URL('../apps/desktop/renderer/app.js', import.meta.url), 'utf8');
  const start = app.indexOf('function onEvent(event) {');
  const end = app.indexOf("  if (event.type === 'skills.settings')", start);
  let calls = 0, panel = 0;
  const message = { value: '' }, status = { textContent: '' };
  const context = { elements: { message, 'agent-remote-status': status }, runAgentRemoteCommand: () => calls++, setAgentRemotePanel: () => panel++ };
  runInNewContext(`${app.slice(start, end)}\n}`, context);
  context.onEvent({ type: 'composer.prefill', command: '/debate' });
  assert.equal(calls, 1);
  message.value = 'unsent draft';
  context.onEvent({ type: 'composer.prefill', command: '/fable-mind' });
  assert.equal(message.value, 'unsent draft');
  assert.equal(calls, 1);
  assert.equal(panel, 1);
  context.onEvent({ type: 'composer.prefill', command: 'bad command' });
  assert.equal(calls, 1);
});
