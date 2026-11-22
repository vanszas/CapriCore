import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import path from 'node:path';
import { root } from '../packages/runtime/src/index.mjs';
import { isClipCommand } from '../apps/desktop/clip-command.mjs';
import { ScreenshotFiles, screenshotDimensions } from '../apps/desktop/screenshot-files.mjs';
import { ScreenClipFrames } from '../apps/desktop/screen-clip-frames.mjs';
import { computerIntentClassifier } from '../apps/desktop/computer-planner.mjs';
import { visionImage } from './fixtures/vision-image.mjs';

test('screen routing uses the classifier with original owner context and rejects ungrounded follow-ups', async () => {
  const recentTurns = [{ text: 'Kannst du den Inhalt meines Bildschirms beschreiben?', screenRead: true }];
  const text = 'Und jetzt?';
  let intent = 'screen_once';
  let received;
  const classify = computerIntentClassifier({
    url: 'http://127.0.0.1:1',
    model: 'fixture',
    screenActions: true,
    fetchImpl: async (_url, options) => {
      const request = JSON.parse(options.body);
      assert.deepEqual(Object.keys(request.format).sort(), [
        'additionalProperties',
        'properties',
        'required',
        'type',
      ]);
      assert.ok(request.format.properties.intent.enum.includes(intent));
      const systemMessages = request.messages.filter((m) => m.role === 'system');
      assert.ok(systemMessages.length >= 2, 'screenActions must inject a system message into messages');
      assert.ok(systemMessages.some((m) => m.content.includes('screen_once') && m.content.includes('screen_watch')));
      assert.ok(!systemMessages[0].content.includes('screen descriptions, '));
      received = JSON.parse(request.messages.at(-1).content);
      return Response.json({ done: true, message: { content: JSON.stringify({ intent, usesPrevious: true }) } });
    },
  });
  assert.equal((await classify(text, { recentTurns })).intent, 'screen_once');
  assert.equal(received.latestOwnerMessage, text);
  assert.deepEqual(received.recentOwnerTurns, recentTurns);
  assert.equal(await classify(text), null);
  intent = 'screen_watch';
  assert.equal(await classify(text, { recentTurns }), null);
});

test('only deliberate clip/screenshot requests activate the local command', () => {
  for (const text of [
    'Capri clip that',
    'Hey, Capri, clip that.',
    'clip this',
    'CAPREE, CLIP IT!',
    'take a screenshot',
    'Capri, please save a screen shot.',
  ])
    assert.equal(isClipCommand(text), true, text);
  for (const text of [
    'Do not clip that',
    'Capri, do not clip that',
    'How do I take a screenshot?',
    'Say "Capri clip that"',
    'What does hey Capri clip that mean',
    'Do not say hey Capri clip that',
    'play Clip That on Spotify',
    'Capri clip that and delete the old ones',
  ])
    assert.equal(isClipCommand(text), false, text);
});

test('screenshot files are bounded PNGs, cannot escape the private directory and never overwrite', async (t) => {
  const parent = path.join(root, '.runtime/test-data');
  mkdirSync(parent, { recursive: true });
  const folder = mkdtempSync(path.join(parent, 'clips-'));
  t.after(() => rmSync(folder, { recursive: true, force: true }));
  const files = new ScreenshotFiles({ root, directory: path.join(folder, 'screenshots') }),
    id = randomUUID(),
    png = visionImage();
  assert.deepEqual(screenshotDimensions(png), { width: 320, height: 180 });
  const saved = await files.save(id, png);
  assert.deepEqual(readFileSync(saved.file), png);
  assert.equal(files.saved(id).name, saved.name);
  await assert.rejects(files.save(id, png), /EEXIST/);
  assert.throws(() => files.file('../outside'));
  await assert.rejects(files.save(randomUUID(), Buffer.from('not PNG')));
  await assert.rejects(files.save(randomUUID(), png, { signal: AbortSignal.abort() }), {
    name: 'AbortError',
  });
  assert.throws(() => new ScreenshotFiles({ root, directory: path.dirname(root) }).folder());
  const huge = Buffer.from(png);
  huge.writeUInt32BE(30000, 16);
  assert.throws(() => screenshotDimensions(huge));
});

test('live clip requests require the same active capture and cannot be replayed after stop', async () => {
  let capture = { id: 'lease', phase: 'capturing', expiresAt: Date.now() + 60000 };
  const events = [],
    frames = new ScreenClipFrames({ current: () => capture, emit: (e) => events.push(e) }),
    controller = new AbortController();
  const promise = frames.request(capture, controller.signal),
    event = events.at(-1);
  assert.equal(frames.accept({ ...event, png: visionImage() }), true);
  assert.deepEqual(await promise, visionImage());
  assert.equal(frames.accept({ ...event, png: visionImage() }), false);
  const stale = frames.request(capture, controller.signal),
    request = events.at(-1);
  capture = null;
  assert.equal(frames.accept({ ...request, png: visionImage() }), false);
  await assert.rejects(stale);
  capture = { id: 'new', phase: 'capturing', expiresAt: Date.now() + 60000 };
  const cancelled = frames.request(capture, controller.signal);
  controller.abort();
  await assert.rejects(cancelled);
  assert.equal(frames.pending.size, 0);
});
