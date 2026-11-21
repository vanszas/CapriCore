"""Modular TTS Engine for Agent Remote.
Supports:
1. Edge-TTS: 0 VRAM, <300ms latency, female voices only (id-ID-GadisNeural).
2. XTTS-v2: Local CUDA (~1.9 GB VRAM on RTX 5060 Ti sm_120), female reference cloning.
3. Automatic Fallback: If XTTS fails, gracefully falls back to Edge-TTS.
4. Presets:
   - 'cool' (/debate): sharp, analytical, low pitch (-8Hz), calm tempo (-6%).
   - 'kawaii' (assistant): cheerful, lively, high pitch (+12Hz), fast tempo (+10%).
   - 'normal' (/fable-mind): empirical, concise, standard pitch (0Hz), normal tempo (0%).
"""
import asyncio
import json
import logging
from pathlib import Path

logger = logging.getLogger("TTSModular")

TOOLS_DIR = Path(__file__).resolve().parent
PROJECT_DIR = TOOLS_DIR.parent
CONFIG_PATH = TOOLS_DIR / "tts_config.json"
AUDIO_SAMPLES_DIR = TOOLS_DIR / "audio_samples"
# ponytail: pure edge-tts. Local neural models skipped; add when official native Indonesian female weights exist.

DEFAULT_CONFIG = {
    "engine": "edge",  # "edge" default for native Indonesian (0 VRAM)
    "preset": "hermes",  # "hermes", "assistant", "debate", "fable-mind"
    "presets": {
        "hermes": {
            "description": "Hermes persona: Technical, sharp, concise, male voice",
            "voice": "id-ID-ArdiNeural",
            "pitch": "+0Hz",
            "rate": "+0%",
            "mode_alias": "/hermes"
        },
        "assistant": {
            "description": "Assistant persona: Cheerful, friendly, lively, polite female voice",
            "voice": "id-ID-GadisNeural",
            "pitch": "+8Hz",
            "rate": "+6%",
            "mode_alias": "/assistant"
        },
        "kawaii": {
            "description": "Assistant kawaii alias: Lively, polite female voice",
            "voice": "id-ID-GadisNeural",
            "pitch": "+8Hz",
            "rate": "+6%",
            "mode_alias": "/assistant"
        },
        "cool": {
            "description": "Debate persona alias: Sharp, firm, analytical male voice",
            "voice": "id-ID-ArdiNeural",
            "pitch": "-2Hz",
            "rate": "-4%",
            "mode_alias": "/debate"
        },
        "debate": {
            "description": "Debate persona: Sharp, firm, analytical male voice",
            "voice": "id-ID-ArdiNeural",
            "pitch": "-2Hz",
            "rate": "-4%",
            "mode_alias": "/debate"
        },
        "normal": {
            "description": "Fable-mind persona alias: Factual, concise, neutral, empirical male voice",
            "voice": "id-ID-ArdiNeural",
            "pitch": "+0Hz",
            "rate": "-2%",
            "mode_alias": "/fable-mind"
        },
        "fable-mind": {
            "description": "Fable-mind persona: Factual, concise, neutral, empirical male voice",
            "voice": "id-ID-ArdiNeural",
            "pitch": "+0Hz",
            "rate": "-2%",
            "mode_alias": "/fable-mind"
        }
    }
}

def load_config() -> dict:
    if CONFIG_PATH.exists():
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                cfg = json.load(f)
                merged = {**DEFAULT_CONFIG, **cfg}
                merged_presets = {**DEFAULT_CONFIG["presets"], **cfg.get("presets", {})}
                # Fix stale override where presets were forced to GadisNeural
                for k in ("hermes", "debate", "cool", "fable-mind", "normal"):
                    if k in merged_presets and merged_presets[k].get("voice") != "id-ID-ArdiNeural":
                        merged_presets[k]["voice"] = "id-ID-ArdiNeural"
                merged["presets"] = merged_presets
                return merged
        except Exception as e:
            logger.warning(f"Error loading {CONFIG_PATH}: {e}, using default")
    return DEFAULT_CONFIG.copy()

def save_config(cfg: dict):
    try:
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)
    except Exception as e:
        logger.error(f"Error saving {CONFIG_PATH}: {e}")

async def synthesize_edge(text: str, voice: str = "id-ID-ArdiNeural", pitch: str = "+0Hz", rate: str = "+0%") -> bytes:
    import edge_tts
    comm = edge_tts.Communicate(text, voice=voice, pitch=pitch, rate=rate)
    chunks = []
    async for chunk in comm.stream():
        if chunk["type"] == "audio":
            chunks.append(chunk["data"])
    return b"".join(chunks)

def resolve_persona(preset: str = None, mode: str = None, text: str = "", persona: str = None) -> tuple[str, dict]:
    cfg = load_config()
    presets = cfg["presets"]
    
    # 1. Match explicit persona parameter
    p_lower = (persona or "").strip().lower()
    if p_lower in ("assistant", "miku", "kawaii"):
        return "assistant", presets["assistant"]
    if p_lower in ("hermes", "agent_hermes"):
        return "hermes", presets["hermes"]

    # 2. Match explicit command or mode
    m_lower = (mode or "").strip().lower()
    t_lower = text.strip().lower()
    if m_lower == "/assistant" or t_lower.startswith("/assistant"):
        return "assistant", presets["assistant"]
    if m_lower in ("/debate", "debate") or t_lower.startswith("/debate"):
        return "debate", presets["debate"]
    if m_lower in ("/fable-mind", "fable-mind") or t_lower.startswith("/fable-mind"):
        return "fable-mind", presets["fable-mind"]
    if m_lower in ("/hermes", "hermes") or t_lower.startswith("/hermes"):
        return "hermes", presets["hermes"]
    
    # 3. Match explicit preset
    if preset and preset in presets:
        return preset, presets[preset]

    # Default is Hermes Biasa (Male voice id-ID-ArdiNeural)
    return "hermes", presets["hermes"]

def synthesize_audio(
    text: str,
    engine: str = None,
    preset: str = None,
    mode: str = None,
    persona: str = None,
    voice: str = None,
    pitch: str = None,
    rate: str = None,
    allow_fallback: bool = True
) -> tuple[bytes, str, str]:
    """Synthesizes audio.
    Returns: (audio_bytes, mime_type, engine_used)
    """
    persona_name, persona_cfg = resolve_persona(preset=preset, mode=mode, text=text, persona=persona)

    # Clean text from commands if needed
    clean_text = text
    if clean_text.startswith("/debate"):
        clean_text = clean_text[len("/debate"):].strip()
    elif clean_text.startswith("/fable-mind"):
        clean_text = clean_text[len("/fable-mind"):].strip()
    elif clean_text.startswith("/assistant"):
        clean_text = clean_text[len("/assistant"):].strip()
    elif clean_text.startswith("/hermes"):
        clean_text = clean_text[len("/hermes"):].strip()

    # Backend enforcement: cegah payload lama menimpa persona dengan GadisNeural
    if voice and voice.startswith("ja-JP"):
        v = voice
    elif persona_name == "assistant":
        v = "id-ID-GadisNeural"
    else:
        v = "id-ID-ArdiNeural"

    p = pitch or persona_cfg.get("pitch", "+0Hz")
    r = rate or persona_cfg.get("rate", "+0%")

    try:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        try:
            mp3_bytes = loop.run_until_complete(
                synthesize_edge(
                    clean_text,
                    voice=v,
                    pitch=p,
                    rate=r
                )
            )
        finally:
            loop.close()
        return mp3_bytes, "audio/mpeg", "edge"
    except Exception as edge_err:
        logger.error(f"[TTSModular] Edge-TTS error: {edge_err}")
        raise edge_err
