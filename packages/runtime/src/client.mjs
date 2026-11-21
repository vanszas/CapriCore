import { readFileSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { root } from './host/paths.mjs';
import { nodePath, openclawPath } from './host/executables.mjs';
import { environment } from './host/environment.mjs';
import { settings } from './config/settings.mjs';
import { runtime, headers, personaPrompt } from './context.mjs';
import { request } from './http.mjs';
import { plainDialogueText } from './text.mjs';
import { routeChat } from './providers/chat-router.mjs';

function isLoopbackHttpUrl(value) {
  if (typeof value !== 'string' || !value.trim()) return false;
  try {
    const url = new URL(value);
    return (
      url.protocol === 'http:' &&
      ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) &&
      !url.username &&
      !url.password
    );
  } catch {
    return false;
  }
}

export function chat(text, options = {}) {
  const rt = options.rt ?? runtime();
  return routeChat(
    text,
    { ...options, provider: options.provider === undefined ? settings.chatProvider : options.provider },
    {
      openclaw: {
        available: true,
        capabilities: { chat: true, images: true },
        chat: chatOpenClaw,
      },
      hermes: {
        available: isLoopbackHttpUrl(rt?.hermesUrl),
        capabilities: { chat: true, images: true },
        chat: chatHermes,
      },
    },
  );
}

async function chatHermes(
  text,
  { rt = runtime(), sessionKey = settings.sessionKey, image, signal } = {},
) {
  if (!isLoopbackHttpUrl(rt?.hermesUrl)) {
    const err = new Error('Hermes API server URL must be HTTP loopback');
    err.code = 'CHAT_PROVIDER_UNAVAILABLE';
    throw err;
  }
  const mime = image?.[0] === 0xff && image?.[1] === 0xd8 ? 'image/jpeg' : 'image/png';
  const content = image
    ? [
        { type: 'text', text },
        {
          type: 'image_url',
          image_url: { url: `data:${mime};base64,${image.toString('base64')}` },
        },
      ]
    : text;
  let token = typeof rt.hermesToken === 'string' ? rt.hermesToken.trim() : '';
  if (!token && typeof rt.hermesTokenPath === 'string' && rt.hermesTokenPath.trim()) {
    token = readFileSync(rt.hermesTokenPath, 'utf8').trim();
  }
  const baseUrl = rt.hermesUrl.replace(/\/+$/, '');
  const response = await request(`${baseUrl}/v1/chat/completions`, {
    method: 'POST',
    timeout: 240000,
    signal,
    headers: {
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(sessionKey ? { 'x-hermes-session-id': sessionKey } : {}),
    },
    body: JSON.stringify({
      model: rt.hermesModel || 'hermes-agent',
      stream: false,
      messages: [
        { role: 'system', content: personaPrompt(rt) },
        { role: 'user', content },
      ],
    }),
  });
  for (const choice of response.choices ?? [])
    if (typeof choice.message?.content === 'string')
      choice.message.content = plainDialogueText(choice.message.content).trim();
  return response;
}

async function chatOpenClaw(
  text,
  { rt = runtime(), sessionKey = settings.sessionKey, channel = 'webchat', image, signal } = {},
) {
  const mime = image?.[0] === 0xff && image?.[1] === 0xd8 ? 'image/jpeg' : 'image/png';
  const content = image
    ? [
        { type: 'text', text },
        {
          type: 'image_url',
          image_url: { url: `data:${mime};base64,${image.toString('base64')}` },
        },
      ]
    : text;
  const response = await request(`${rt.gatewayUrl}/v1/chat/completions`, {
    method: 'POST',
    timeout: 240000,
    signal,
    headers: {
      ...headers(rt),
      'x-openclaw-session-key': sessionKey,
      'x-openclaw-message-channel': channel,
    },
    body: JSON.stringify({
      model: `openclaw/${settings.agentId}`,
      stream: false,
      messages: [
        { role: 'system', content: personaPrompt(rt) },
        { role: 'user', content },
      ],
    }),
  });
  for (const choice of response.choices ?? [])
    if (typeof choice.message?.content === 'string')
      choice.message.content = plainDialogueText(choice.message.content).trim();
  return response;
}
export async function startHermesDebate(
  { baseUrl, task, runId = 'ACTIVE-SESSION', token, signal, timeout = 10000 } = {},
) {
  if (typeof task !== 'string' || !task.trim()) throw new TypeError('Debate task is required');
  if (typeof baseUrl !== 'string' || !baseUrl.trim())
    throw new TypeError('Hermes Remote baseUrl is required');
  const url = new URL('/api/debate/start', baseUrl);
  if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) || url.username || url.password)
    throw new TypeError('Hermes Remote must use an HTTP loopback address');
  const auth = token?.trim();
  return request(url, {
    method: 'POST',
    timeout,
    signal,
    headers: {
      'content-type': 'application/json',
      ...(auth ? { 'X-Agent-Remote-Token': auth } : {}),
    },
    body: JSON.stringify({ task: task.trim(), run_id: String(runId) }),
  });
}

export function runCli(args, rt = runtime(), { signal } = {}) {
  signal?.throwIfAborted();
  return new Promise((resolve, reject) => {
    const child = spawn(nodePath, [openclawPath, ...args], {
      cwd: root,
      env: environment(rt),
      windowsHide: true,
      signal,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let stdout = '',
      stderr = '',
      overflow = false;
    const receive = (text, chunk) => {
      if (text.length + chunk.length > 8 * 1024 * 1024) {
        overflow = true;
        child.kill();
        return text;
      }
      return text + chunk;
    };
    child.stdout.on('data', (chunk) => {
      stdout = receive(stdout, chunk);
    });
    child.stderr.on('data', (chunk) => {
      stderr = receive(stderr, chunk);
    });
    child.once('error', reject);
    child.once('exit', (code) =>
      code === 0 && !overflow
        ? resolve({ stdout, stderr })
        : reject(
            new Error(
              overflow
                ? 'OpenClaw output exceeded limit'
                : `OpenClaw ${args[0]} exited ${code}: ${stderr.slice(-3000)}`,
            ),
          ),
    );
  });
}
