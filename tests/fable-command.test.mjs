import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { ChatService } from '../apps/desktop/chat-service.mjs';

test('fable command supplies evidence instruction to internal model', async () => {
  const messages = [];
  const store = { snapshot: () => messages, put: (item) => { const index = messages.findIndex(x => x.id === item.id); if (index < 0) messages.push(item); else messages[index] = item; } };
  let payload;
  const service = new ChatService({ gatewayUrl: 'http://127.0.0.1:18789', agentId: 'capri', sessionKey: 'agent:capri:main', token: () => 'test', persona: () => 'Test', store, fetchImpl: async (_url, options) => { payload = JSON.parse(options.body); return new Response('data: {"choices":[{"delta":{"content":"Answer"}}]}\n\ndata: [DONE]\n\n', { headers: { 'content-type': 'text/event-stream' } }); } });
  const events = [];
  await service.send({ id: randomUUID(), text: '/fable-mind explain gravity' }, event => events.push(event));
  assert.equal(events.at(-1).type, 'turn.completed');
  assert.match(JSON.stringify(payload.messages), /Ground claims in actual evidence/);
});
