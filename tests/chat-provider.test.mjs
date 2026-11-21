import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { mkdtempSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { tmpdir } from 'node:os';
import { routeChat, validateChatProvider } from '../packages/runtime/src/providers/chat-router.mjs';
import { chat } from '../packages/runtime/src/client.mjs';
import { settings, validateCoreSettings } from '../packages/runtime/src/config/settings.mjs';

const adapter = (chat) => ({ available: true, capabilities: { chat: true }, chat });

test('config defaults to OpenClaw; invalid provider rejected', () => {
  const base = { ...settings };
  delete base.chatProvider;
  assert.equal(validateCoreSettings(base).chatProvider, 'openclaw');
  for (const provider of ['openclaw', 'hermes', 'both'])
    assert.equal(validateCoreSettings({ ...base, chatProvider: provider }).chatProvider, provider);
  for (const provider of [null, '', 'auto', 'constructor', {}, 0]) {
    assert.throws(() => validateChatProvider(provider), /Invalid chat provider/);
    assert.throws(() => validateCoreSettings({ ...base, chatProvider: provider }), /Invalid chat provider/);
  }
});

test('provider selection preserves multilingual text and session options', async () => {
  for (const text of ['Buka pengaturan', 'Open settings', 'Открой настройки', '設定を開いて', 'افتح الإعدادات']) {
    const options = { provider: 'hermes', sessionKey: 'test-session' };
    const result = await routeChat(text, options, {
      openclaw: adapter(() => assert.fail('wrong provider')),
      hermes: adapter((input, received) => {
        assert.equal(input, text);
        assert.equal(received, options);
        return { choices: [] };
      }),
    });
    assert.deepEqual(result, { choices: [] });
  }
});

test('both prefers OpenClaw and falls back only before dispatch', async () => {
  let calls = 0;
  const hermes = adapter(() => { calls++; return 'hermes'; });
  assert.equal(await routeChat('x', { provider: 'both' }, {
    openclaw: adapter(() => 'openclaw'), hermes,
  }), 'openclaw');
  assert.equal(calls, 0);
  assert.equal(await routeChat('x', { provider: 'both' }, {
    openclaw: { available: false }, hermes,
  }), 'hermes');
  assert.equal(calls, 1);
});

test('no replay after timeout, disconnect, approval denial, or tool execution error', async () => {
  for (const code of ['ETIMEDOUT', 'ECONNRESET', 'APPROVAL_DENIED', 'TOOL_FAILED']) {
    const error = Object.assign(new Error(code), { code });
    let primaryCalls = 0;
    await assert.rejects(routeChat('x', { provider: 'both' }, {
      openclaw: adapter(() => { primaryCalls++; throw error; }),
      hermes: adapter(() => assert.fail('unsafe replay')),
    }), (received) => received === error);
    assert.equal(primaryCalls, 1);
  }
});

test('missing adapter and unsupported image fail closed', async () => {
  await assert.rejects(chat('x', { provider: 'hermes' }), { code: 'CHAT_PROVIDER_UNAVAILABLE' });
  await assert.rejects(routeChat('x', { provider: 'hermes', image: Buffer.from([1]) }, {
    hermes: adapter(() => assert.fail('unsupported image dispatched')),
  }), { code: 'CHAT_PROVIDER_UNAVAILABLE' });
  await assert.rejects(routeChat('x', {}, Object.create({
    openclaw: adapter(() => assert.fail('inherited adapter dispatched')),
  })), { code: 'CHAT_PROVIDER_UNAVAILABLE' });
});

test('aborted turn never dispatches', async () => {
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(routeChat('x', { signal: controller.signal }, {
    openclaw: adapter(() => assert.fail('aborted turn dispatched')),
  }), { name: 'AbortError' });
});

test('real client keeps existing OpenClaw HTTP contract without live provider', async () => {
  const workspace = mkdtempSync(path.join(process.env.TMPDIR || tmpdir(), 'capri-provider-'));
  const tokenPath = path.join(workspace, 'fixture-token.txt');
  writeFileSync(tokenPath, 'fixture-only');
  writeFileSync(path.join(workspace, 'PERSONALITY.md'), 'Local test fixture.');
  let received;
  const server = createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    received = { url: req.url, headers: req.headers, body: JSON.parse(Buffer.concat(chunks).toString()) };
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify({ choices: [{ message: { content: 'hello' } }] }));
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  try {
    const response = await chat('こんにちは', {
      rt: { gatewayUrl: `http://127.0.0.1:${server.address().port}`, tokenPath, workspace },
      sessionKey: 'fixture-session', channel: 'webchat', provider: 'both',
    });
    assert.equal(received.url, '/v1/chat/completions');
    assert.equal(received.headers['x-openclaw-session-key'], 'fixture-session');
    assert.equal(received.headers['x-openclaw-message-channel'], 'webchat');
    assert.equal(received.body.model, `openclaw/${settings.agentId}`);
    assert.equal(received.body.messages.at(-1).content, 'こんにちは');
    assert.equal(response.choices[0].message.content, 'hello');
  } finally {
    server.closeAllConnections();
    await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  }
});

test('real client dispatches to loopback Hermes HTTP API when configured', async () => {
  const workspace = mkdtempSync(path.join(process.env.TMPDIR || tmpdir(), 'capri-hermes-'));
  writeFileSync(path.join(workspace, 'PERSONALITY.md'), 'Local test fixture.');
  let received;
  const server = createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    received = { url: req.url, headers: req.headers, body: JSON.parse(Buffer.concat(chunks).toString()) };
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify({ choices: [{ message: { content: 'Hermes ok' } }] }));
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  try {
    const response = await chat('Halo dunia', {
      rt: {
        hermesUrl: `http://127.0.0.1:${server.address().port}`,
        hermesToken: 'test-hermes-token',
        workspace,
      },
      sessionKey: 'hermes-sess-1',
      provider: 'hermes',
    });
    assert.equal(received.url, '/v1/chat/completions');
    assert.equal(received.headers.authorization, 'Bearer test-hermes-token');
    assert.equal(received.headers['x-hermes-session-id'], 'hermes-sess-1');
    assert.equal(received.body.model, 'hermes-agent');
    assert.equal(received.body.messages.at(-1).content, 'Halo dunia');
    assert.equal(response.choices[0].message.content, 'Hermes ok');
  } finally {
    server.closeAllConnections();
    await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  }
});
