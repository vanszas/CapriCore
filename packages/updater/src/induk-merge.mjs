import { createHash } from 'node:crypto';
import { execFile, spawnSync } from 'node:child_process';
import {
  cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync,
} from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { noLinks } from '../../installer/src/policy.mjs';
import { updateSourcePath } from './payload-policy.mjs';
import { verifyPayload } from './transaction.mjs';

const manifestName = 'SOURCE_MANIFEST.json';
const entry = fileURLToPath(import.meta.url);
const run = promisify(execFile);
const planName = 'config/induk-update-files.json';
const releaseName = 'config/release.json';
const readJson = (file) => JSON.parse(readFileSync(file, 'utf8'));
const read = (root, name) => {
  const file = path.join(root, name);
  noLinks(file);
  return existsSync(file) ? readFileSync(file) : null;
};
const same = (a, b) => a === b || Boolean(a && b && a.equals(b));
const mergeMarkers = /^(?:<<<<<<< |=======\r?$|>>>>>>> )/m;
const normalizedText = (body) => body && !body.includes(0)
  ? Buffer.from(body.toString('utf8').replace(/\r\n/g, '\n'))
  : body;

function write(root, name, value) {
  const file = path.join(root, name);
  noLinks(file);
  mkdirSync(path.dirname(file), { recursive: true });
  writeFileSync(file, value);
}

/** Rebase the owned Induk paths over a verified developer release in a separate directory. */
export function mergeIndukRelease({ root, source, tag, node = process.execPath }) {
  const files = verifyPayload(source, tag);
  const plan = readJson(path.join(root, planName));
  if (!Array.isArray(plan) || !plan.includes(planName)) throw new Error('Invalid Induk merge file list.');
  for (const name of plan) updateSourcePath(name);
  const installedTag = readJson(path.join(root, releaseName)).tag;
  const parent = path.join(root, '.runtime/updates/staged');
  noLinks(parent);
  const staged = readdirSync(parent, { withFileTypes: true });
  const previous = staged
    .filter((entry) => entry.isDirectory() && entry.name.startsWith('release-'))
    .map((entry) => path.join(parent, entry.name))
    .find((folder) => {
      try {
        noLinks(folder);
        const meta = readJson(path.join(folder, releaseName));
        if (meta.tag !== installedTag || meta.distribution) return false;
        verifyPayload(folder, installedTag);
        return true;
      } catch {
        return false;
      }
    });
  if (!previous) throw new Error(`Missing verified developer baseline ${installedTag} for Capri Induk.`);

  const repairs = staged
    .filter((entry) => entry.isDirectory() && entry.name.startsWith('induk-'))
    .map((entry) => path.join(parent, entry.name))
    .filter((folder) => {
      try {
        noLinks(folder);
        const report = readJson(path.join(folder, 'MERGE-REPORT.json'));
        return report.installedTag === installedTag && report.incomingTag === tag;
      } catch {
        return false;
      }
    });
  const job = mkdtempSync(path.join(parent, 'induk-'));
  const candidate = path.join(job, 'candidate');
  const privateGit = path.join(root, '.runtime/git/cmd/git.exe');
  noLinks(privateGit);
  const git = existsSync(privateGit) ? privateGit : 'git';
  cpSync(source, candidate, { recursive: true, errorOnExist: true, force: false });
  const paths = new Set(files.map((file) => file.path));
  const failures = [];
  const changed = [];
  for (const name of new Set(plan)) {
    if (name === releaseName || name === manifestName) continue;
    const base = read(previous, name);
    const ours = read(root, name);
    const theirs = read(source, name);
    const inputs = [ours, base, theirs].map(normalizedText);
    if (same(inputs[0], inputs[1]) || same(inputs[0], inputs[2])) continue;
    let result = ours;
    if (!same(inputs[1], inputs[2])) {
      const labels = ['induk', 'base', 'developer'];
      const repaired = repairs.map((folder) => {
        const resolution = read(folder, `resolutions/${name}`);
        if (!resolution || mergeMarkers.test(resolution.toString('utf8'))) return null;
        return inputs.every((body, index) => same(body, read(folder, `inputs/${labels[index]}/${name}`)))
          ? resolution
          : null;
      }).find(Boolean);
      for (const [index, body] of inputs.entries()) {
        if (body) write(job, `inputs/${labels[index]}/${name}`, body);
      }
      if (repaired) {
        result = repaired;
      } else if (inputs.some((body) => !body || body.includes(0))) {
        failures.push({ file: name, reason: 'Both sides changed a binary, added or deleted file.' });
        continue;
      } else {
        const args = labels.flatMap((label) => ['-L', label]);
        const merged = spawnSync(git, [
          'merge-file', '-p', ...args, ...labels.map((label) => path.join(job, 'inputs', label, name)),
        ], { windowsHide: true, maxBuffer: 8 * 1024 ** 2 });
        if (merged.status !== 0) {
          if (merged.stdout?.length) write(candidate, name, merged.stdout);
          failures.push({ file: name, reason: merged.error ? merged.error.message : 'Git could not merge both changes.' });
          continue;
        }
        result = merged.stdout;
      }
    }
    if (result) {
      write(candidate, name, result);
      paths.add(name);
      changed.push(name);
    } else {
      rmSync(path.join(candidate, name), { force: true });
      paths.delete(name);
    }
  }

  const reject = () => {
    const report = path.join(job, 'MERGE-REPORT.json');
    writeFileSync(report, JSON.stringify({
      installedTag, incomingTag: tag, candidate, failures,
      instructions: 'Use inputs/base, inputs/induk and inputs/developer. Preserve both changes. Write resolved files under resolutions using the original relative paths, then retry Download update. Do not edit the live installation.',
    }, null, 2));
    throw new Error(`Capri Induk update paused. Share this merge report with your coding AI: ${report}`);
  };
  if (failures.length) reject();
  const identity = readJson(path.join(source, releaseName));
  write(candidate, releaseName, JSON.stringify({ ...identity, distribution: 'induk' }, null, 2));
  for (const name of changed.filter((file) => /\.[cm]?js$/i.test(file))) {
    const checked = spawnSync(node, ['--check', path.join(candidate, name)], { windowsHide: true });
    if (checked.status !== 0) failures.push({ file: name, reason: String(checked.stderr || checked.error) });
  }
  for (const name of ['apps/desktop/main.mjs', 'apps/desktop/preload.cjs', 'apps/desktop/renderer/settings.html', 'integrations/mobile_gateway/agent_remote_server.py']) {
    if (!paths.has(name)) failures.push({ file: name, reason: 'Agent Remote runtime file is missing.' });
  }
  for (const [name, required] of [
    ['apps/desktop/main.mjs', 'capri:agent-remote-server'],
    ['apps/desktop/preload.cjs', 'capri:agent-remote-server'],
    ['apps/desktop/renderer/settings.html', 'agent-remote-panel'],
  ]) {
    if (paths.has(name) && !read(candidate, name).includes(required))
      failures.push({ file: name, reason: 'Agent Remote wiring is missing after the merge.' });
  }
  if (failures.length) reject();
  const original = readJson(path.join(source, manifestName));
  const mergedFiles = [...paths].sort().map((name) => {
    const bytes = read(candidate, name);
    return { path: name, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') };
  });
  write(candidate, manifestName, JSON.stringify({ ...original, files: mergedFiles }));
  verifyPayload(candidate, tag);
  return candidate;
}

export async function prepareIndukRelease({ root, source, tag, node, signal }) {
  try {
    const { stdout } = await run(node, [entry, root, source, tag], {
      windowsHide: true,
      signal,
      timeout: 120000,
    });
    return stdout.trim();
  } catch (error) {
    signal?.throwIfAborted();
    throw new Error(error.stderr?.trim() || 'Capri Induk merge did not finish.');
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === entry) {
  const [, , root, source, tag] = process.argv;
  try {
    console.log(mergeIndukRelease({ root, source, tag }));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
