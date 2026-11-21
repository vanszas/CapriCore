# DirectInput hardware scancode controller bridge for Unreal Engine / DirectX games
# Pure Python ctypes implementation (64-bit Win32 SendInput)

import ctypes
import ctypes.wintypes
import logging
import threading
import time

logger = logging.getLogger("agent_remote.controller")

# DirectInput scan codes (set 1)
SCANCODES: dict[str, int] = {
    "w": 0x11,
    "a": 0x1E,
    "s": 0x1F,
    "d": 0x20,
    "space": 0x39,
    "e": 0x12,
    "f": 0x21,
}

INPUT_KEYBOARD = 1
KEYEVENTF_KEYDOWN = 0x0000
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_SCANCODE = 0x0008

class KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ("wVk", ctypes.wintypes.WORD),
        ("wScan", ctypes.wintypes.WORD),
        ("dwFlags", ctypes.wintypes.DWORD),
        ("time", ctypes.wintypes.DWORD),
        ("dwExtraInfo", ctypes.c_ulonglong),
    ]

class MOUSEINPUT(ctypes.Structure):
    _fields_ = [
        ("dx", ctypes.wintypes.LONG),
        ("dy", ctypes.wintypes.LONG),
        ("mouseData", ctypes.wintypes.DWORD),
        ("dwFlags", ctypes.wintypes.DWORD),
        ("time", ctypes.wintypes.DWORD),
        ("dwExtraInfo", ctypes.c_ulonglong),
    ]

class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [
        ("uMsg", ctypes.wintypes.DWORD),
        ("wParamL", ctypes.wintypes.WORD),
        ("wParamH", ctypes.wintypes.WORD),
    ]

class INPUT_UNION(ctypes.Union):
    _fields_ = [
        ("ki", KEYBDINPUT),
        ("mi", MOUSEINPUT),
        ("hi", HARDWAREINPUT),
    ]

class INPUT(ctypes.Structure):
    _fields_ = [
        ("type", ctypes.wintypes.DWORD),
        ("u", INPUT_UNION),
    ]

ctypes.windll.user32.SendInput.argtypes = [
    ctypes.wintypes.UINT,
    ctypes.POINTER(INPUT),
    ctypes.c_int,
]
ctypes.windll.user32.SendInput.restype = ctypes.wintypes.UINT

_active_keys: set[str] = set()
_lock = threading.Lock()
_last_activity_time: float = 0.0
_watchdog_thread: threading.Thread | None = None
_watchdog_running = True
SAFETY_LEASE_SECONDS = 0.600  # Auto-release if no packet received within 600ms

def _raw_send_scancode(scancode: int, is_up: bool) -> bool:
    flags = KEYEVENTF_SCANCODE | (KEYEVENTF_KEYUP if is_up else KEYEVENTF_KEYDOWN)
    ki = KEYBDINPUT(0, scancode, flags, 0, 0)
    union_obj = INPUT_UNION(ki=ki)
    inp = INPUT(type=INPUT_KEYBOARD, u=union_obj)
    ret = ctypes.windll.user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))
    return ret == 1

def inject_controller_key(key: str, state: str) -> dict:
    global _last_activity_time
    k = key.strip().lower()
    st = state.strip().lower()

    if k not in SCANCODES:
        return {"ok": False, "error": f"Invalid key '{key}'. Whitelist: {list(SCANCODES.keys())}"}
    if st not in {"down", "up"}:
        return {"ok": False, "error": f"Invalid state '{state}'. Expected 'down' or 'up'"}

    scancode = SCANCODES[k]
    with _lock:
        if st == "down":
            if k in _active_keys:
                _last_activity_time = time.time()
                return {"ok": True, "key": k, "state": "down", "redundant": True}
            success = _raw_send_scancode(scancode, is_up=False)
            if success:
                _active_keys.add(k)
                _last_activity_time = time.time()
                return {"ok": True, "key": k, "state": "down"}
            return {"ok": False, "error": "SendInput failed on keydown"}
        else:
            if k not in _active_keys:
                _last_activity_time = time.time()
                return {"ok": True, "key": k, "state": "up", "redundant": True}
            success = _raw_send_scancode(scancode, is_up=True)
            _active_keys.discard(k)
            _last_activity_time = time.time()
            if success:
                return {"ok": True, "key": k, "state": "up"}
            return {"ok": False, "error": "SendInput failed on keyup"}

def release_all_keys() -> int:
    global _last_activity_time
    released_count = 0
    with _lock:
        for k in list(_active_keys):
            code = SCANCODES.get(k)
            if code:
                _raw_send_scancode(code, is_up=True)
                released_count += 1
        _active_keys.clear()
        _last_activity_time = time.time()
    return released_count

def get_active_keys() -> list[str]:
    with _lock:
        return sorted(list(_active_keys))

def _watchdog_loop():
    while _watchdog_running:
        time.sleep(0.1)
        with _lock:
            if _active_keys and (time.time() - _last_activity_time > SAFETY_LEASE_SECONDS):
                for k in list(_active_keys):
                    code = SCANCODES.get(k)
                    if code:
                        _raw_send_scancode(code, is_up=True)
                _active_keys.clear()
                logger.warning("Controller safety lease expired: all active keys released.")

def start_watchdog():
    global _watchdog_thread, _watchdog_running
    if _watchdog_thread is None or not _watchdog_thread.is_alive():
        _watchdog_running = True
        _watchdog_thread = threading.Thread(target=_watchdog_loop, daemon=True, name="ControllerWatchdog")
        _watchdog_thread.start()

start_watchdog()
