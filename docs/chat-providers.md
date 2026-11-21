# Runtime chat providers

Scope: `packages/runtime/src/client.mjs` exported `chat()` only. This does not add a desktop Settings selector, change Desktop ChatService routing, or start a Hermes service.

`chatProvider` in existing `config/core.local.json` accepts `openclaw`, `hermes`, or `both`. Absent field defaults to `openclaw`. Merge this field into existing private overrides; do not replace the file. Per-call `chat(text, { provider })` overrides the config.

- `openclaw`: existing OpenClaw HTTP request, headers, persona and response sanitization.
- `hermes`: fails with `CHAT_PROVIDER_UNAVAILABLE` until a verified connector exists. No network request or silent OpenClaw substitution.
- `both`: OpenClaw first. The router may select another adapter only if the first adapter explicitly declares itself unavailable or lacks required capabilities before dispatch. Current production registry contains OpenClaw only. Its network errors propagate without fallback.

No retry after dispatch, including timeout, disconnect, approval denial or tool failure. A timeout is not proof that the remote action did not run. Both mode never broadcasts a turn to two providers.

The internal router supports fake adapters for tests. Availability requires literal `true`, callable `chat`, and explicit chat capability. Images require explicit image capability. This is not a computer-control capability grant. Existing approval and executor paths remain unchanged. No new resources are created, so no new lifecycle manager exists.

Hermes connector remains intentionally absent until auth, sessions, cancellation, approval events, tool results and reconnect contracts have been verified against the installed runtime. Neither fake adapters nor a local HTTP fixture prove Hermes integration or real computer control.

Run focused checks:

```sh
node --test tests/chat-provider.test.mjs tests/runtime-foundation.test.mjs tests/inference-api.test.mjs tests/chatgpt-plan.test.mjs tests/computer-chat.test.mjs
```
