"""Read the currently configured/active Hermes MoA seat models."""
import json
import os
from pathlib import Path


def _profile_models():
    """Return models from Hermes' active MoA preset (aggregator first)."""
    path = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData/Local")) / "hermes" / "profiles" / "vans" / "config.yaml"
    try:
        raw = path.read_text(encoding="utf-8")
        try:
            import yaml
            data = yaml.safe_load(raw) or {}
        except ImportError:
            return _profile_models_text(raw)
        moa = data.get("moa") or {}
        preset_name = moa.get("default_preset")
        preset = (moa.get("presets") or {}).get(preset_name) if preset_name else None
        if not isinstance(preset, dict):
            return []
        result = []
        aggregator = ((preset.get("aggregator") or {}).get("model"))
        if isinstance(aggregator, str) and aggregator.strip():
            result.append(aggregator.strip())
        for item in preset.get("reference_models") or []:
            # Hermes UI persists enabled references as `enabled: true`.
            if not isinstance(item, dict) or item.get("enabled") is not True:
                continue
            model = item.get("model")
            if isinstance(model, str) and model.strip() and model.strip() not in result:
                result.append(model.strip())
        return result
    except (OSError, ValueError, ImportError):
        return []


def _profile_models_text(raw):
    import re
    preset_match = re.search(r"(?m)^\s*default_preset:\s*['\"]?([^'\"\s]+)", raw)
    if not preset_match:
        return []
    name = preset_match.group(1)
    block_match = re.search(rf"(?ms)^    {re.escape(name)}:\s*$.*?(?=^    \S[^:]*:\s*$|^  reference_models:)", raw)
    if not block_match:
        return []
    block = block_match.group(0)
    aggregator = re.search(r"(?ms)^      aggregator:\s*$.*?^        model:\s*([^\s#]+)", block)
    result = [aggregator.group(1).strip()] if aggregator else []
    for item in re.finditer(r"(?ms)^        - provider:.*?^          model:\s*([^\s#]+).*?^          enabled:\s*(true|false)", block):
        if item.group(2).lower() == "true" and item.group(1) not in result:
            result.append(item.group(1))
    return result


def _models_from(value):
    seats = value.get("seats") if isinstance(value, dict) else None
    if not isinstance(seats, dict):
        return []
    result = []
    for seat in seats.values():
        model = seat.get("model") if isinstance(seat, dict) else None
        if isinstance(model, str) and model.strip() and model.strip() not in result:
            result.append(model.strip())
    return result


def active_moa_models():
    """Return seat models only when an HDO council run is actively in progress."""
    root = Path.home() / ".hermes" / "hdo"
    runs = root / "runs"
    if runs.is_dir():
        for run in sorted((p for p in runs.iterdir() if p.is_dir()), key=lambda p: p.stat().st_mtime, reverse=True):
            try:
                state = json.loads((run / "state.json").read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            if str(state.get("status", "")).upper() in {"RUNNING", "QUEUED", "IN_PROGRESS", "DEBATING"}:
                models = _models_from(state)
                if models:
                    return models
    return []

