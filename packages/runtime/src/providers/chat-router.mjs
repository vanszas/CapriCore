export function validateChatProvider(provider = 'openclaw') {
  if (!['openclaw', 'hermes', 'both'].includes(provider))
    throw new TypeError('Invalid chat provider');
  return provider;
}

export async function routeChat(text, options = {}, adapters = {}) {
  const provider = validateChatProvider(options.provider);
  options.signal?.throwIfAborted();
  const candidates = provider === 'both' ? ['openclaw', 'hermes'] : [provider];
  for (const name of candidates) {
    const adapter = Object.hasOwn(adapters, name) ? adapters[name] : undefined;
    if (adapter?.available !== true || typeof adapter.chat !== 'function') continue;
    if (adapter.capabilities?.chat !== true) continue;
    if (options.image && adapter.capabilities?.images !== true) continue;
    // ponytail: only pre-dispatch selection; never retry an ambiguous dispatched turn.
    return adapter.chat(text, options);
  }
  const error = new Error('Selected chat provider unavailable or unsupported');
  error.code = 'CHAT_PROVIDER_UNAVAILABLE';
  throw error;
}
