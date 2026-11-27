// SPDX-License-Identifier: GPL-3.0-only
import { uiText } from './interface-translations.mjs';
const mutations = {
  filesystem_control: [
    'write_text',
    'replace_text',
    'create_directory',
    'copy',
    'move',
    'undo',
    'trash',
  ],
  time_control: ['remind', 'timer', 'stopwatch', 'pause', 'resume', 'reset', 'cancel', 'snooze'],
  // Opening an app is only a substep of many desktop requests. Let the final
  // reply explain the complete task; do not replace it with a launch receipt.
  desktop_control: ['type_text', 'open_url', 'trash', 'browser_input'],
};

export function youtubeMusicReply(result, language) {
  const status = result?.status;
  let key = 'YouTube Music did not confirm this action. Check the player.';
  if (status === 'disabled') key = 'Enable YouTube Music in Settings → Skills first.';
  else if (status === 'tab_not_found')
    key = 'YouTube Music tab not found. Open it in your browser and select the tab.';
  else if (status === 'player_unavailable') key = 'YouTube Music is not playing anything.';
  else if (result?.ok === true) {
    if (status === 'volume_set' && Number.isFinite(result.volume))
      return uiText('YouTube Music volume: {value}%.', language, { value: result.volume });
    if (result.playing === true && result.track)
      return uiText('Playing on YouTube Music: {track}.', language, { track: result.track });
    switch (status) {
      case 'opened':
        key = 'Opened YouTube Music.';
        break;
      case 'search_opened':
        key = 'Opened the YouTube Music search results.';
        break;
      case 'paused':
        key = 'Paused.';
        break;
      case 'resumed':
        key = 'Resumed.';
        break;
      case 'ready':
        if (result.playing === false) key = 'Paused.';
        else if (!result.track && result.playing !== true)
          key = 'YouTube Music is not playing anything.';
        break;
      case 'playing':
      case 'previous':
      case 'skipped':
        key = 'YouTube Music playback was updated.';
        break;
    }
  }
  return uiText(key, language);
}

export function factualActionReply(tool, args, result, language = 'en') {
  if (
    tool === 'desktop_control' &&
    args.action === 'browser_input' &&
    (result?.typed !== true || result?.submitted !== false)
  )
    return null;
  if (
    result?.ok === true &&
    tool === 'application_control' &&
    args.action === 'calculate' &&
    typeof result.result === 'string'
  ) {
    const answer = uiText('Result: {value}', language, {
      value: (result.displayResult ?? result.result) + (result.unit ? ' ' + result.unit : ''),
    });
    return result.display?.ok === false
      ? `${answer}\n${uiText('I couldn’t show the calculation in Windows Calculator.', language)}`
      : answer;
  }
  if (
    result?.ok === true &&
    tool === 'spotify_control' &&
    args.action === 'volume' &&
    Number.isFinite(result.data?.volumePercent)
  )
    return uiText('Spotify volume: {value}%.', language, { value: result.data.volumePercent });
  if (result?.ok === true && tool === 'spotify_control') {
    const data = result.data ?? {};
    if (
      args.action === 'status' &&
      typeof data.playing === 'boolean' &&
      typeof data.title === 'string' &&
      data.title.trim()
    ) {
      const track =
        typeof data.artist === 'string' && data.artist.trim()
          ? `${data.title} — ${data.artist}`
          : data.title;
      return ['Spotify', track, !data.playing && uiText('Paused.', language)]
        .filter(Boolean)
        .join(' · ');
    }
    const text =
      ['pause', 'resume', 'toggle', 'play', 'next', 'previous'].includes(args.action) &&
      typeof data.playing === 'boolean'
        ? data.playing
          ? 'Resumed.'
          : 'Paused.'
        : null;
    if (text) return uiText(text, language);
  }
  if (
    result?.ok !== true ||
    result.cancelled ||
    result.needsWindow ||
    !mutations[tool]?.includes(args.action)
  )
    return null;
  const keys = {
    write_text: 'Created the text file.',
    replace_text: 'Replaced the existing file.',
    create_directory: 'Created the folder.',
    copy: 'Copied the file.',
    move: 'Moved the file.',
    undo: 'Undid the file action.',
    trash: 'Moved the file to the Recycle Bin.',
    stopwatch: 'Stopwatch started.',
    pause: 'Paused.',
    resume: 'Resumed.',
    reset: 'Stopwatch reset.',
    cancel: 'Cancelled.',
    remind: 'Reminder set.',
    timer: 'Timer started.',
    snooze: 'Reminder snoozed.',
    launch:
      result.windowVerified === false
        ? 'Launch requested. No app window could be verified yet.'
        : 'Opened the application.',
    type_text: 'Entered the text without sending it.',
    browser_input: 'Entered the text without sending it.',
    open_url: 'Opened the address in the browser.',
  };
  return uiText(keys[args.action], language);
}
export function unsupportedDesktopClaim(text, { desktopReady, discordContext, internetRequested }) {
  const sentence = text.trim();
  const noControlTool =
    /(?:tidak (?:punya|memiliki) alat|(?:tidak ada|tanpa) (?:alat|tool)|no (?:desktop|computer|discord|control|voice.output|tools?)\b|(?:don['’]t|doesn['’]t) have (?:a |any )?(?:tool|access)|(?:can['’]t|cannot) (?:control|operate) .{1,70} from this chat)/iu.test(sentence) &&
    /(?:komputer|computer|desktop|discord|kendali|mengendalikan|control)/iu.test(sentence);
  const accessBoilerplate =
    /full computer access/iu.test(sentence) &&
    /(?:bukan berarti|(?:does not|doesn['’]t) (?:mean|give)|flag alone|status saja)/iu.test(sentence);
  const unrequestedWebDisclaimer =
    !internetRequested &&
    /(?:belum (?:bisa )?(?:diverifikasi|(?:ku|saya |aku )verifikasi) (?:online|di internet)|(?:wasn['’]t|was not|couldn['’]t be) verified online)/iu.test(sentence);
  const inventedDiscordAudioState = discordContext &&
    /(?:discord belum terhubung sebagai tujuan suara|cuma bisa mengirim suara lewat fitur .{0,60}terhubung ke chat ini|sesi ini tidak punya koneksi ke voice channel|(?:this chat|this session) has no discord voice connection|discord (?:isn['’]t|is not) connected as (?:an? )?(?:audio|voice) (?:destination|output))/iu.test(sentence);
  const unverifiedDiscordSend =
    discordContext &&
    /^(?:sudah (?:saya |aku )?(?:kirim|terkirim)|(?:saya|aku) (?:sudah|telah) (?:mengirim|kirim)|(?:pesan|rekaman suara) (?:sudah |telah )?(?:dikirim|terkirim)\b|(?:i (?:have )?|i['’]ve )sent\b|(?:message|voice message) sent\b)/iu.test(sentence);
  return (desktopReady && (noControlTool || accessBoilerplate)) ||
    unrequestedWebDisclaimer || inventedDiscordAudioState || unverifiedDiscordSend;
}

/** Filter model text before either subtitles or speech. */
export class ActionReplyFilter {
  constructor(reject) {
    this.buffer = '';
    this.enabled = false;
    this.reject = reject;
    this.rejected = false;
  }
  push(text, final = false) {
    if (!this.enabled && !this.reject) return text;
    this.buffer += text;
    let output = '',
      match;
    while ((match = this.buffer.match(/^([\s\S]*?[.!?。！？](?:\s+|$))/u))) {
      const sentence = match[1];
      this.buffer = this.buffer.slice(sentence.length);
      if (this.keep(sentence)) output += sentence;
    }
    if (final && this.buffer) {
      if (this.keep(this.buffer)) output += this.buffer;
      this.buffer = '';
    }
    return output;
  }
  keep(text) {
    if (this.reject?.(text)) {
      this.rejected = true;
      return false;
    }
    return !this.enabled || !this.generic(text);
  }
  generic(text) {
    return /^(?:(?:and|so|а|ну|и)\s+)?(?:what(?:['’]s| is)\s+(?:next|on your mind)|what (?:else|would you like to do next)|anything else|чт[оё]\s+(?:дальше|ещ[её]|у тебя на уме)|что (?:хочешь|будем) делать дальше|чем ещ[её] (?:помочь|могу помочь)|как(?:ие)?\s+(?:дальше планы|планы))[^.!?。！？]*[?？]?\s*$/iu.test(
      text.trim(),
    );
  }
}
