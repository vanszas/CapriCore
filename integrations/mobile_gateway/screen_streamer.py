"""Screen and audio streamer with input injection for HermesRemote."""

import ctypes
import ctypes.wintypes
import io
import threading
import time
from typing import Generator

try:
    import mss
    import mss.windows
    HAS_MSS = True
except ImportError:
    HAS_MSS = False

try:
    from PIL import ImageGrab, Image
    HAS_PIL = True
except ImportError:
    HAS_PIL = False

# Enable Win32 Per-Monitor DPI Awareness so mouse coordinates are 1:1 on scaled displays
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception:
    try:
        ctypes.windll.user32.SetProcessDPIAware()
    except Exception:
        pass

# Win32 Mouse & Keyboard constants
INPUT_MOUSE = 0
INPUT_KEYBOARD = 1
MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_MIDDLEDOWN = 0x0020
MOUSEEVENTF_MIDDLEUP = 0x0040
MOUSEEVENTF_WHEEL = 0x0800
MOUSEEVENTF_ABSOLUTE = 0x8000

KEYEVENTF_KEYDOWN = 0x0000
KEYEVENTF_EXTENDEDKEY = 0x0001
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004
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

ctypes.windll.user32.SendInput.argtypes = [ctypes.wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int]
ctypes.windll.user32.SendInput.restype = ctypes.wintypes.UINT

VK_BACK = 0x08
VK_TAB = 0x09
VK_RETURN = 0x0D
VK_ESCAPE = 0x1B
VK_SPACE = 0x20
VK_LEFT = 0x25
VK_UP = 0x26
VK_RIGHT = 0x27
VK_DOWN = 0x28

RESOLUTIONS = {
    "540p": (960, 540),
    "720p": (1280, 720),
    "1080p": (1920, 1080),
    "native": (0, 0),
}

STREAM_PROFILES: dict[str, dict[str, object]] = {
    "hemat": {
        "id": "hemat",
        "name": "Hemat (540p / 15 FPS / Q50)",
        "resolution": "540p",
        "max_dim": 540,
        "quality": 50,
        "fps": 15,
        "max_kbps": 800,
    },
    "seimbang": {
        "id": "seimbang",
        "name": "Seimbang (720p / 24 FPS / Q75)",
        "resolution": "720p",
        "max_dim": 720,
        "quality": 75,
        "fps": 24,
        "max_kbps": 2500,
    },
    "tajam": {
        "id": "tajam",
        "name": "Tajam (1080p / 30 FPS / Q88)",
        "resolution": "1080p",
        "max_dim": 1080,
        "quality": 88,
        "fps": 30,
        "max_kbps": 6000,
    },
    "ultra": {
        "id": "ultra",
        "name": "Ultra Native (Asli / 30 FPS / Q95)",
        "resolution": "native",
        "max_dim": 0,
        "quality": 95,
        "fps": 30,
        "max_kbps": 0,
    },
}


def list_monitors() -> list[dict[str, object]]:
    """Enumerate all active displays on Windows."""
    monitors: list[dict[str, object]] = []
    try:
        user32 = ctypes.windll.user32

        class _RECT(ctypes.Structure):
            _fields_ = [
                ("left", ctypes.c_long),
                ("top", ctypes.c_long),
                ("right", ctypes.c_long),
                ("bottom", ctypes.c_long),
            ]

        class _MONITORINFO(ctypes.Structure):
            _fields_ = [
                ("cbSize", ctypes.c_ulong),
                ("rcMonitor", _RECT),
                ("rcWork", _RECT),
                ("dwFlags", ctypes.c_ulong),
            ]

        def callback(h_monitor, hdc_monitor, lprc_monitor, dw_data):
            info = _MONITORINFO()
            info.cbSize = ctypes.sizeof(_MONITORINFO)
            user32.GetMonitorInfoW(h_monitor, ctypes.byref(info))
            rc = info.rcMonitor
            is_primary = bool(info.dwFlags & 1)
            w = rc.right - rc.left
            h = rc.bottom - rc.top
            idx = len(monitors) + 1
            monitors.append({
                "id": "primary" if is_primary else f"monitor_{idx}",
                "index": idx,
                "name": f"Monitor {idx} (Utama)" if is_primary else f"Monitor {idx}",
                "width": w,
                "height": h,
                "left": rc.left,
                "top": rc.top,
                "right": rc.right,
                "bottom": rc.bottom,
                "is_primary": is_primary,
            })
            return 1

        mon_enum_proc = ctypes.WINFUNCTYPE(
            ctypes.c_int,
            ctypes.c_void_p,
            ctypes.c_void_p,
            ctypes.c_void_p,
            ctypes.c_void_p,
        )
        proc = mon_enum_proc(callback)
        user32.EnumDisplayMonitors(None, None, proc, 0)

        # Virtual / Combined display
        vx = user32.GetSystemMetrics(76)
        vy = user32.GetSystemMetrics(77)
        vw = user32.GetSystemMetrics(78)
        vh = user32.GetSystemMetrics(79)
        monitors.append({
            "id": "all",
            "index": 0,
            "name": f"Semua Layar ({vw}x{vh})",
            "width": vw,
            "height": vh,
            "left": vx,
            "top": vy,
            "right": vx + vw,
            "bottom": vy + vh,
            "is_primary": False,
        })
    except Exception:
        monitors = [
            {
                "id": "primary",
                "index": 1,
                "name": "Monitor Utama",
                "width": 1920,
                "height": 1080,
                "left": 0,
                "top": 0,
                "right": 1920,
                "bottom": 1080,
                "is_primary": True,
            }
        ]

    return monitors


def get_screen_bounds(monitor: str = "primary") -> tuple[int, int, int, int]:
    """Returns (left, top, width, height) of requested monitor."""
    monitors = list_monitors()
    target = monitor.strip().lower().replace(" ", "_").replace("-", "_")

    if target in {"all", "virtual", "dual"}:
        for m in monitors:
            if m["id"] == "all":
                return int(m["left"]), int(m["top"]), int(m["width"]), int(m["height"])

    # Match by id, index, or common aliases
    for m in monitors:
        m_id = str(m.get("id", "")).lower()
        m_idx = str(m.get("index", ""))
        if m_id == target or m_idx == target:
            return int(m["left"]), int(m["top"]), int(m["width"]), int(m["height"])
        if target in {f"monitor_{m_idx}", f"monitor{m_idx}", f"display_{m_idx}"}:
            return int(m["left"]), int(m["top"]), int(m["width"]), int(m["height"])

    # Explicit second monitor fallback matching
    if target in {"2", "second", "secondary", "monitor_2", "monitor2", "display_2"}:
        for m in monitors:
            if not m.get("is_primary") and m.get("id") != "all":
                return int(m["left"]), int(m["top"]), int(m["width"]), int(m["height"])

    # Default to primary monitor
    for m in monitors:
        if m.get("is_primary"):
            return int(m["left"]), int(m["top"]), int(m["width"]), int(m["height"])

    if monitors:
        m = monitors[0]
        return int(m["left"]), int(m["top"]), int(m["width"]), int(m["height"])

    return 0, 0, 1920, 1080


_pointer_lock = threading.Lock()
_android_pointers = {}
_overlay_started = False


def _show_android_pointers():
    import tkinter as tk

    user = ctypes.windll.user32
    user.GetParent.restype = ctypes.wintypes.HWND
    root = tk.Tk()
    root.withdraw()
    windows = {}
    transparent = "#ff00ff"

    def update():
        with _pointer_lock:
            now = time.monotonic()
            for pointer, (_, _, seen) in list(_android_pointers.items()):
                if now - seen > 30:
                    del _android_pointers[pointer]
            positions = _android_pointers.copy()
        for pointer in windows.keys() - positions.keys():
            windows.pop(pointer)[0].destroy()
        for pointer, (x, y, _) in positions.items():
            if pointer not in windows:
                win = tk.Toplevel(root)
                win.withdraw()
                win.overrideredirect(True)
                win.attributes("-transparentcolor", transparent)
                canvas = tk.Canvas(win, width=141, height=31, bg=transparent, highlightthickness=0)
                canvas.pack()
                color = ("#58e6ff", "#92f58a", "#ffbe58", "#e6a3ff")[int(pointer[-1], 16) % 4]
                canvas.create_polygon(0, 0, 5, 21, 9, 13, 17, 10, fill=color, outline="#10242b")
                canvas.create_rectangle(16, 12, 140, 30, fill="#10242b", outline=color)
                canvas.create_text(21, 21, text=f"Android · {pointer[-4:]}", anchor="w", fill=color)
                win.update_idletasks()
                hwnd = ctypes.wintypes.HWND(user.GetParent(ctypes.wintypes.HWND(win.winfo_id())))
                style = user.GetWindowLongW(hwnd, -20)
                user.SetWindowLongW(hwnd, -20, style | 0x080800a0)
                win.deiconify()
                windows[pointer] = win, hwnd
            _, hwnd = windows[pointer]
            # Keep the layered window click-through and show it without activation.
            user.SetWindowPos(hwnd, ctypes.wintypes.HWND(-1), x, y, 141, 31, 0x0050)
        root.after(33, update)

    update()
    root.mainloop()


def _window_pointer(action, x, y, button, delta, monitor, pointer="", key="", text=""):
    global _overlay_started
    if not (0 <= x <= 1 and 0 <= y <= 1):
        raise ValueError("Pointer outside monitor")
    if pointer and (len(pointer) != 32 or any(c not in "0123456789abcdef" for c in pointer)):
        raise ValueError("Invalid Android pointer ID")
    user = ctypes.windll.user32
    left, top, width, height = get_screen_bounds(monitor)
    pt = ctypes.wintypes.POINT(left + min(int(x * width), width - 1),
                              top + min(int(y * height), height - 1))
    # Each phone supplies its own coordinates. Queue each gesture as one batch.
    with _pointer_lock:
        if pointer:
            if pointer not in _android_pointers and len(_android_pointers) >= 32:
                raise ValueError("Too many active Android pointers")
            _android_pointers[pointer] = (pt.x, pt.y, time.monotonic())
            if not _overlay_started:
                threading.Thread(target=_show_android_pointers, daemon=True).start()
                _overlay_started = True
        if action == "move":
            return {"status": "ok", "action": action}
        user.WindowFromPoint.argtypes = [ctypes.wintypes.POINT]
        user.WindowFromPoint.restype = ctypes.wintypes.HWND
        hwnd = ctypes.wintypes.HWND(user.WindowFromPoint(pt))
        if not hwnd:
            raise ValueError("No window under remote pointer")
        if action == "hotkey":
            class_name = ctypes.create_unicode_buffer(128)
            user.GetClassNameW(hwnd, class_name, len(class_name))
            if class_name.value.lower() != "edit" and not class_name.value.lower().startswith("richedit"):
                raise ValueError("This window does not support Android keyboard shortcuts")
            shortcuts = {"ctrl+a": (0x00b1, 0, -1), "ctrl+c": (0x0301, 0, 0),
                         "ctrl+v": (0x0302, 0, 0), "ctrl+z": (0x0304, 0, 0)}
            if key.lower() not in shortcuts:
                raise ValueError("Shortcut unsupported by Android pointer")
            events = [shortcuts[key.lower()]]
        elif action in ("key", "type", "text"):
            if action == "key":
                vk = KEY_MAP.get(key.lower())
                if vk is None:
                    raise ValueError("Key unsupported by Android pointer")
                scan = user.MapVirtualKeyW(vk, 0) << 16
                events = [(0x0100, vk, scan | 1), (0x0101, vk, scan | 0xc0000001)]
            else:
                encoded = text[:8000].encode("utf-16-le")
                events = [(0x0102, encoded[i] | (encoded[i + 1] << 8), 1)
                          for i in range(0, len(encoded), 2)]
        elif action == "scroll":
            events = [(0x020A, ((delta or 120) & 0xffff) << 16)]
        else:
            if not user.ScreenToClient(hwnd, ctypes.byref(pt)):
                raise OSError("Window coordinates unavailable")
            right = action == "rclick" or button == "right"
            down, up, mask = (0x0204, 0x0205, 2) if right else (0x0201, 0x0202, 1)
            if action == "double_click":
                events = [(down, mask), (up, 0), (0x0206 if right else 0x0203, mask), (up, 0)]
            elif action in ("click", "rclick"):
                events = [(down, mask), (up, 0)]
            else:
                raise ValueError("Unsupported isolated pointer action")
        coords = (pt.x & 0xffff) | ((pt.y & 0xffff) << 16)
        for event in events:
            message, flags, param = event if len(event) == 3 else (*event, coords)
            if not user.PostMessageW(hwnd, message, flags, ctypes.c_ssize_t(param)):
                raise OSError("Window rejected remote pointer")
    return {"status": "queued", "action": action}


def inject_input(
    action: str = "move",
    x: float = 0.0,
    y: float = 0.0,
    button: str = "left",
    delta: int = 0,
    key: str = "",
    text: str = "",
    monitor: str = "primary",
    pointer: str = "",
    **kwargs: object,
) -> dict:
    """Injects mouse and keyboard events into Windows."""
    if action.startswith("pointer_") or action in ("move", "click", "double_click", "rclick", "scroll", "down", "up"):
        return _window_pointer(action.removeprefix("pointer_"), x, y, button, delta, monitor, pointer, key, text)
    if action in ("key", "type", "text"):
        target_key = text if text else key
        _inject_key(target_key)
    elif action in ("hotkey", "shortcut"):
        _inject_hotkey(key)
    else:
        raise ValueError(f"unknown action: {action}")

    return {
        "status": "ok",
        "action": action,

    }


VK_CONTROL = 0x11
VK_SHIFT = 0x10
VK_MENU = 0x12  # Alt
VK_LWIN = 0x5B
VK_RWIN = 0x5C
VK_DELETE = 0x2E

KEY_MAP = {
    "ctrl": VK_CONTROL,
    "control": VK_CONTROL,
    "alt": VK_MENU,
    "shift": VK_SHIFT,
    "win": VK_LWIN,
    "windows": VK_LWIN,
    "cmd": VK_LWIN,
    "enter": VK_RETURN,
    "return": VK_RETURN,
    "backspace": VK_BACK,
    "tab": VK_TAB,
    "esc": VK_ESCAPE,
    "escape": VK_ESCAPE,
    "space": VK_SPACE,
    "delete": VK_DELETE,
    "del": VK_DELETE,
    "up": VK_UP,
    "down": VK_DOWN,
    "left": VK_LEFT,
    "right": VK_RIGHT,
}
for i in range(1, 13):
    KEY_MAP[f"f{i}"] = 0x70 + (i - 1)


def _inject_hotkey(combo: str) -> None:
    user32 = ctypes.windll.user32
    if not combo:
        return
    parts = [p.strip().lower() for p in combo.replace("+", " ").replace("-", " ").split() if p.strip()]
    if not parts:
        return
    vk_list = []
    for part in parts:
        if part in KEY_MAP:
            vk_list.append(KEY_MAP[part])
        elif len(part) == 1:
            vk_list.append(ord(part.upper()))

    pressed = []
    try:
        for vk in vk_list:
            user32.keybd_event(vk, 0, KEYEVENTF_KEYDOWN, 0)
            pressed.append(vk)
            time.sleep(0.01)
        time.sleep(0.02)
    finally:
        for vk in reversed(pressed):
            user32.keybd_event(vk, 0, KEYEVENTF_KEYUP, 0)
            time.sleep(0.005)


def _inject_key(key: str) -> None:
    user32 = ctypes.windll.user32
    if not key:
        return

    special_keys = {
        "Enter": VK_RETURN,
        "enter": VK_RETURN,
        "\n": VK_RETURN,
        "\r": VK_RETURN,
        "Backspace": VK_BACK,
        "backspace": VK_BACK,
        "BackSpace": VK_BACK,
        "Delete": 0x2E,
        "delete": 0x2E,
        "Del": 0x2E,
        "del": 0x2E,
        "Tab": VK_TAB,
        "tab": VK_TAB,
        "Escape": VK_ESCAPE,
        "escape": VK_ESCAPE,
        "Esc": VK_ESCAPE,
        "esc": VK_ESCAPE,
        "Space": VK_SPACE,
        "space": VK_SPACE,
        " ": VK_SPACE,
        "ArrowLeft": VK_LEFT,
        "ArrowUp": VK_UP,
        "ArrowRight": VK_RIGHT,
        "ArrowDown": VK_DOWN,
    }

    VK_SHIFT = 0x10

    # ponytail: keybd_event bypasses x64 SendInput struct alignment bugs, handles standard VK and Unicode
    def send_vk(vk: int, with_shift: bool = False) -> None:
        if with_shift:
            user32.keybd_event(VK_SHIFT, 0x2A, KEYEVENTF_KEYDOWN, 0)
            time.sleep(0.005)

        scan = user32.MapVirtualKeyW(vk, 0)
        user32.keybd_event(vk, scan, KEYEVENTF_KEYDOWN, 0)
        time.sleep(0.01)
        user32.keybd_event(vk, scan, KEYEVENTF_KEYUP, 0)
        time.sleep(0.005)

        if with_shift:
            user32.keybd_event(VK_SHIFT, 0x2A, KEYEVENTF_KEYUP, 0)
            time.sleep(0.005)

    # 1. Cek special key tunggal
    if key in special_keys:
        send_vk(special_keys[key])
        return

    # 2. String / karakter per karakter
    for ch in key:
        if ch in special_keys:
            send_vk(special_keys[ch])
            continue

        code = ord(ch)
        vks = user32.VkKeyScanW(code)
        if vks != -1 and vks != 0xFFFF:
            vk = vks & 0xFF
            need_shift = bool((vks >> 8) & 1)
            send_vk(vk, with_shift=need_shift)
        else:
            # Fallback unicode via keybd_event (KEYEVENTF_UNICODE = 0x0004)
            user32.keybd_event(0, code, KEYEVENTF_UNICODE, 0)
            time.sleep(0.005)
            user32.keybd_event(0, code, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP, 0)
            time.sleep(0.005)


class ScreenStreamer:
    """Manages high-fps screen capturing, adaptive scaling, and background streaming."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.latest_frame: bytes = b""
        self.running = False
        self.active_clients = 0
        self.worker_thread: threading.Thread | None = None
        self.monitor = "primary"
        self.resolution = "720p"
        self.quality = 65

    def _ensure_worker(self) -> None:
        with self.lock:
            if not self.running or not self.worker_thread or not self.worker_thread.is_alive():
                self.running = True
                self.worker_thread = threading.Thread(target=self._worker_loop, daemon=True)
                self.worker_thread.start()

    def _worker_loop(self) -> None:
        target_fps = 30
        interval = 1.0 / target_fps
        last_active = time.time()
        sct = None
        if HAS_MSS:
            try:
                sct = mss.MSS()
            except Exception:
                sct = None

        try:
            while self.running:
                t0 = time.perf_counter()
                with self.lock:
                    clients = self.active_clients
                if clients <= 0:
                    if time.time() - last_active > 10.0:
                        break
                else:
                    last_active = time.time()

                try:
                    frame = self._capture_frame(sct)
                    if frame:
                        with self.lock:
                            self.latest_frame = frame
                except Exception:
                    time.sleep(0.05)

                elapsed = time.perf_counter() - t0
                sleep_time = interval - elapsed
                if sleep_time > 0:
                    time.sleep(sleep_time)
                else:
                    time.sleep(0.001)
        finally:
            if sct:
                try:
                    sct.close()
                except Exception:
                    pass
            with self.lock:
                self.running = False
                self.worker_thread = None

    def _capture_frame(
        self,
        sct=None,
        target_max: int | None = None,
        quality: int | None = None,
        monitor: str | None = None,
    ) -> bytes:
        target_mon = monitor if monitor else self.monitor
        left, top, width, height = get_screen_bounds(target_mon)
        if target_max is None:
            target_max = {"540p": 540, "720p": 720, "1080p": 1080, "native": 0}.get(self.resolution, 720)
        eff_quality = self.quality if quality is None else quality

        if sct:
            mon = {"left": left, "top": top, "width": width, "height": height}
            raw = sct.grab(mon)
            img = Image.frombytes("RGB", raw.size, raw.bgra, "raw", "BGRX")
        elif HAS_PIL:
            bbox = (left, top, left + width, top + height)
            img = ImageGrab.grab(bbox=bbox, all_screens=True)
        else:
            raise RuntimeError("No capture engine available")

        # Modular scaling: target_max == 0 preserves 100% native unscaled sharp pixels
        if target_max and target_max > 0 and max(img.size) > target_max:
            scale = target_max / max(img.size)
            new_size = (max(1, int(img.width * scale)), max(1, int(img.height * scale)))
            img = img.resize(new_size, Image.Resampling.BILINEAR)

        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=eff_quality, optimize=False)
        return buf.getvalue()

    def grab_jpeg(
        self,
        resolution: str = "720p",
        quality: int = 70,
        monitor: str = "primary",
        profile: str | None = None,
    ) -> bytes:
        if profile and profile in STREAM_PROFILES:
            cfg = STREAM_PROFILES[profile]
            resolution = str(cfg["resolution"])
            quality = int(cfg["quality"])

        self.resolution = resolution
        self.quality = quality
        self.monitor = monitor
        target_max = {"540p": 540, "720p": 720, "1080p": 1080, "native": 0}.get(resolution, 720)
        return self._capture_frame(None, target_max=target_max, quality=quality, monitor=monitor)

    def stream_mjpeg(
        self,
        fps: int = 30,
        resolution: str = "720p",
        quality: int = 65,
        monitor: str = "primary",
        profile: str | None = None,
    ) -> Generator[bytes, None, None]:
        max_kbps = 0
        if profile and profile in STREAM_PROFILES:
            cfg = STREAM_PROFILES[profile]
            resolution = str(cfg["resolution"])
            quality = int(cfg["quality"])
            fps = int(cfg["fps"])
            max_kbps = int(cfg.get("max_kbps", 0))

        self.resolution = resolution
        self.quality = quality
        self.monitor = monitor
        interval = 1.0 / max(1, min(60, fps))

        with self.lock:
            self.active_clients += 1
        self._ensure_worker()

        try:
            while True:
                t0 = time.perf_counter()
                frame = self.grab_jpeg(resolution=resolution, quality=quality, monitor=monitor)

                yield (
                    b"--frame\r\n"
                    b"Content-Type: image/jpeg\r\n"
                    b"Content-Length: " + str(len(frame)).encode() + b"\r\n\r\n"
                    + frame + b"\r\n"
                )

                elapsed = time.perf_counter() - t0
                sleep_time = interval - elapsed
                # Bitrate pacing if bandwidth ceiling is set
                if max_kbps > 0 and len(frame) > 0:
                    bitrate_delay = (len(frame) * 8) / (max_kbps * 1000)
                    sleep_time = max(sleep_time, bitrate_delay)

                if sleep_time > 0:
                    time.sleep(sleep_time)
                else:
                    time.sleep(0.001)
        finally:
            with self.lock:
                self.active_clients = max(0, self.active_clients - 1)


# Global singleton instance
STREAMER = ScreenStreamer()
