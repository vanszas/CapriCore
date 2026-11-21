"""Desktop automation & application controller for Agent Remote (Windows).

Features:
- Native Win32 API via ctypes (zero external dependencies).
- Application launcher via safe ShellExecuteW (no cmd/shell string injection).
- Window finder and foreground focus with AttachThreadInput lock bypass.
- Safe Unicode typing into target window via clipboard paste (Ctrl+V) with state restoration.
- Guardrails:
  * Strict target window validation: refuses typing if target is not confirmed in foreground.
  * Secret text rejection: blocks API keys, tokens, and passwords.
  * Confirmation required for Enter/submit (default is draft-only typing).
"""

from __future__ import annotations

import ctypes
from ctypes import wintypes
import os
import re
import time
from typing import Any, Dict, List, Optional

# Non-Windows guard
IS_WINDOWS = os.name == "nt"

# Win32 Constants
SW_RESTORE = 9
SW_SHOWNORMAL = 1
SW_SHOW = 5
CF_UNICODETEXT = 13
GMEM_MOVEABLE = 0x0002

VK_CONTROL = 0x11
VK_V = 0x56
VK_RETURN = 0x0D
KEYEVENTF_KEYUP = 0x0002

PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

if IS_WINDOWS:
    user32 = ctypes.windll.user32
    kernel32 = ctypes.windll.kernel32
    shell32 = ctypes.windll.shell32

    kernel32.GlobalAlloc.restype = wintypes.HGLOBAL
    kernel32.GlobalAlloc.argtypes = [wintypes.UINT, ctypes.c_size_t]
    kernel32.GlobalLock.restype = wintypes.LPVOID
    kernel32.GlobalLock.argtypes = [wintypes.HGLOBAL]
    kernel32.GlobalUnlock.argtypes = [wintypes.HGLOBAL]

    user32.SetClipboardData.restype = wintypes.HANDLE
    user32.SetClipboardData.argtypes = [wintypes.UINT, wintypes.HANDLE]
    user32.GetClipboardData.restype = wintypes.HANDLE
    user32.GetClipboardData.argtypes = [wintypes.UINT]
else:
    user32 = None
    kernel32 = None
    shell32 = None

# Secret rejection patterns
RE_SECRET_PATTERNS = [
    re.compile(r"(?i)\b(?:sk-[a-zA-Z0-9_\-]{16,}|ghp_[a-zA-Z0-9]{20,}|Bearer\s+[a-zA-Z0-9_\-\.]{16,})\b"),
    re.compile(r"(?i)\b(?:password|passwd|pin)\s*[:=]\s*\S+"),
    re.compile(r"(?i)\b(?:private_key|secret_key)\b"),
]


def _get_process_name_by_pid(pid: int) -> str:
    """Retrieve process executable name by PID."""
    if not IS_WINDOWS or not kernel32:
        return ""
    h_proc = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not h_proc:
        return ""
    try:
        buf = ctypes.create_unicode_buffer(1024)
        size = wintypes.DWORD(1024)
        if kernel32.QueryFullProcessImageNameW(h_proc, 0, buf, ctypes.byref(size)):
            return os.path.basename(buf.value)
        return ""
    finally:
        kernel32.CloseHandle(h_proc)


def list_running_windows() -> List[Dict[str, Any]]:
    """List visible top-level desktop windows with hwnd, title, process, and pid."""
    if not IS_WINDOWS or not user32:
        return []

    windows: List[Dict[str, Any]] = []

    def enum_windows_callback(hwnd: int, lparam: int) -> bool:
        if not user32.IsWindowVisible(hwnd):
            return True
        length = user32.GetWindowTextLengthW(hwnd)
        if length <= 0:
            return True

        buf = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, buf, length + 1)
        title = buf.value.strip()
        if not title:
            return True

        # Skip dummy background windows
        if title in ("Program Manager", "Settings", "Windows Input Experience", "Setup"):
            return True

        pid = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        pname = _get_process_name_by_pid(pid.value)

        windows.append({
            "hwnd": int(hwnd),
            "title": title,
            "process": pname,
            "pid": int(pid.value),
        })
        return True

    EnumWindowsProc = ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)
    user32.EnumWindows(EnumWindowsProc(enum_windows_callback), 0)
    return windows


def get_known_app_registry() -> Dict[str, Dict[str, Any]]:
    """Return catalog of safe, known applications and their launch methods."""
    local_app_data = os.environ.get("LOCALAPPDATA", "")
    program_files = os.environ.get("ProgramFiles", "C:\\Program Files")
    program_files_x86 = os.environ.get("ProgramFiles(x86)", "C:\\Program Files (x86)")

    return {
        "browser": {
            "name": "Default Web Browser",
            "type": "protocol",
            "target": "https://www.google.com",
        },
        "chrome": {
            "name": "Google Chrome",
            "type": "exe",
            "paths": [
                os.path.join(program_files, "Google", "Chrome", "Application", "chrome.exe"),
                os.path.join(program_files_x86, "Google", "Chrome", "Application", "chrome.exe"),
                os.path.join(local_app_data, "Google", "Chrome", "Application", "chrome.exe"),
            ],
            "fallback_uri": "chrome://newtab",
        },
        "brave": {
            "name": "Brave Browser",
            "type": "exe",
            "paths": [
                os.path.join(program_files, "BraveSoftware", "Brave-Browser", "Application", "brave.exe"),
                os.path.join(local_app_data, "BraveSoftware", "Brave-Browser", "Application", "brave.exe"),
            ],
        },
        "edge": {
            "name": "Microsoft Edge",
            "type": "exe",
            "paths": [
                os.path.join(program_files_x86, "Microsoft", "Edge", "Application", "msedge.exe"),
                os.path.join(program_files, "Microsoft", "Edge", "Application", "msedge.exe"),
            ],
            "fallback_uri": "microsoft-edge:",
        },
        "whatsapp": {
            "name": "WhatsApp",
            "type": "protocol",
            "target": "whatsapp:",
            "paths": [
                os.path.join(local_app_data, "WhatsApp", "WhatsApp.exe"),
            ],
        },
        "hermes": {
            "name": "Hermes Desktop",
            "type": "exe",
            "paths": [
                os.path.join(local_app_data, "Programs", "Hermes", "Hermes.exe"),
                os.path.join(local_app_data, "Programs", "hermes-desktop", "Hermes.exe"),
                os.path.join(local_app_data, "Hermes", "Hermes.exe"),
            ],
        },
        "notepad": {
            "name": "Notepad",
            "type": "exe",
            "paths": [
                os.path.join(os.environ.get("WINDIR", "C:\\Windows"), "System32", "notepad.exe"),
                os.path.join(os.environ.get("WINDIR", "C:\\Windows"), "notepad.exe"),
            ],
        },
        "terminal": {
            "name": "Windows Terminal",
            "type": "exe",
            "paths": [
                os.path.join(local_app_data, "Microsoft", "WindowsApps", "wt.exe"),
                os.path.join(os.environ.get("WINDIR", "C:\\Windows"), "System32", "cmd.exe"),
            ],
        },
        "vscode": {
            "name": "Visual Studio Code",
            "type": "exe",
            "paths": [
                os.path.join(local_app_data, "Programs", "Microsoft VS Code", "Code.exe"),
                os.path.join(program_files, "Microsoft VS Code", "Code.exe"),
            ],
        },
    }


def open_app(app_key_or_name: str, args: str = "") -> Dict[str, Any]:
    """Safely launch an application or protocol via ShellExecuteW."""
    if not IS_WINDOWS or not shell32:
        return {"ok": False, "error": "desktop automation only available on Windows"}

    query = app_key_or_name.strip().lower()
    registry = get_known_app_registry()

    target_to_launch: Optional[str] = None

    # 1. Match known app key
    if query in registry:
        spec = registry[query]
        for p in spec.get("paths", []):
            if os.path.exists(p):
                target_to_launch = p
                break
        if not target_to_launch and spec.get("type") == "protocol":
            target_to_launch = spec.get("target") or spec.get("fallback_uri")
        elif not target_to_launch and spec.get("fallback_uri"):
            target_to_launch = spec.get("fallback_uri")

    # 2. Check if query is an allowed protocol handler
    elif query.startswith(("https://", "http://", "whatsapp:", "mailto:", "tel:")):
        target_to_launch = app_key_or_name.strip()

    # 3. Direct executable path verification (strictly .exe or .lnk, forbid .bat / .cmd / .ps1)
    elif os.path.isabs(app_key_or_name) and os.path.exists(app_key_or_name):
        ext = os.path.splitext(app_key_or_name)[1].lower()
        if ext in (".exe", ".lnk"):
            target_to_launch = app_key_or_name
        else:
            return {"ok": False, "error": f"Refused: file extension '{ext}' not permitted for direct execution"}

    # 4. Search in PATH / WindowsApps for strictly approved binary names
    else:
        for approved_bin in ("notepad.exe", "calc.exe", "wt.exe", "code.cmd", "explorer.exe"):
            if query == approved_bin.replace(".exe", "").replace(".cmd", "") or query == approved_bin:
                target_to_launch = approved_bin
                break

    if not target_to_launch:
        return {
            "ok": False,
            "error": f"App '{app_key_or_name}' not found or not in allowed catalog",
            "available_catalog": list(registry.keys()),
        }

    # Execute via ShellExecuteW (no cmd /c string concatenation)
    res = shell32.ShellExecuteW(None, "open", target_to_launch, args if args else None, None, SW_SHOWNORMAL)
    if res <= 32:
        return {
            "ok": False,
            "error": f"ShellExecute error code: {res}",
            "target": target_to_launch,
        }

    return {
        "ok": True,
        "launched": target_to_launch,
        "app": app_key_or_name,
    }


def find_matching_window(target_query: str | int) -> Optional[Dict[str, Any]]:
    """Find visible window matching query by exact HWND, process name, or window title."""
    windows = list_running_windows()

    # Match by integer HWND directly
    if isinstance(target_query, int) or (isinstance(target_query, str) and target_query.strip().isdigit()):
        target_hwnd = int(target_query)
        for w in windows:
            if w["hwnd"] == target_hwnd:
                return w
        return None

    q = str(target_query).strip().lower()

    # 1. Exact or partial process name match (e.g. 'whatsapp', 'chrome.exe', 'hermes')
    for w in windows:
        p = w["process"].lower()
        if p and (q == p or q in p or q == p.replace(".exe", "")):
            return w

    # 2. Substring window title match (e.g. 'WhatsApp', 'Hermes', 'Google Chrome')
    for w in windows:
        t = w["title"].lower()
        if q in t:
            return w

    return None


def focus_window(target_query: str | int) -> Dict[str, Any]:
    """Bring target window to the foreground with thread-input attachment."""
    if not IS_WINDOWS or not user32 or not kernel32:
        return {"ok": False, "error": "desktop automation only available on Windows"}

    win = find_matching_window(target_query)
    if not win:
        return {
            "ok": False,
            "error": f"No running window found matching '{target_query}'",
        }

    hwnd = win["hwnd"]

    fg_hwnd = user32.GetForegroundWindow()
    fg_thread = user32.GetWindowThreadProcessId(fg_hwnd, None)
    curr_thread = kernel32.GetCurrentThreadId()

    # Attach input queue to bypass Windows foreground lock
    user32.AttachThreadInput(curr_thread, fg_thread, True)
    try:
        if user32.IsIconic(hwnd):
            user32.ShowWindow(hwnd, SW_RESTORE)
        else:
            user32.ShowWindow(hwnd, SW_SHOW)

        user32.BringWindowToTop(hwnd)
        user32.SetForegroundWindow(hwnd)
    finally:
        user32.AttachThreadInput(curr_thread, fg_thread, False)

    # Verification polling (up to 250ms)
    confirmed = False
    for _ in range(5):
        time.sleep(0.05)
        if user32.GetForegroundWindow() == hwnd:
            confirmed = True
            break

    return {
        "ok": True,
        "hwnd": hwnd,
        "title": win["title"],
        "process": win["process"],
        "pid": win["pid"],
        "foreground_confirmed": confirmed,
    }


def _get_clipboard_unicode() -> str:
    """Read Unicode string from Windows clipboard."""
    if not user32.OpenClipboard(None):
        return ""
    try:
        h_mem = user32.GetClipboardData(CF_UNICODETEXT)
        if not h_mem:
            return ""
        p_mem = kernel32.GlobalLock(h_mem)
        if not p_mem:
            return ""
        try:
            return ctypes.wstring_at(p_mem)
        finally:
            kernel32.GlobalUnlock(h_mem)
    finally:
        user32.CloseClipboard()


def _set_clipboard_unicode(text: str) -> bool:
    """Write Unicode string to Windows clipboard."""
    if not user32.OpenClipboard(None):
        return False
    try:
        user32.EmptyClipboard()
        buf = (text + "\0").encode("utf-16le")
        h_mem = kernel32.GlobalAlloc(GMEM_MOVEABLE, len(buf))
        if not h_mem:
            return False
        p_mem = kernel32.GlobalLock(h_mem)
        if not p_mem:
            return False
        ctypes.memmove(p_mem, buf, len(buf))
        kernel32.GlobalUnlock(h_mem)
        user32.SetClipboardData(CF_UNICODETEXT, h_mem)
        return True
    finally:
        user32.CloseClipboard()


def type_text_into_target(
    target_query: str,
    text: str,
    confirm_send: bool = False,
    confirm_token: str = "",
) -> Dict[str, Any]:
    """Type text into target window with strict validation and clipboard restoration."""
    if not IS_WINDOWS or not user32 or not kernel32:
        return {"ok": False, "error": "desktop automation only available on Windows"}

    if not text:
        return {"ok": False, "error": "Empty text provided"}

    # Guardrail 1: Refuse secret patterns
    for pat in RE_SECRET_PATTERNS:
        if pat.search(text):
            return {
                "ok": False,
                "error": "Security rejection: input text matches token/key/password pattern",
            }

    # Guardrail 2: Focus target window first
    focus_res = focus_window(target_query)
    if not focus_res.get("ok"):
        return focus_res

    target_hwnd = focus_res["hwnd"]

    # Guardrail 3: Verify foreground window matches target_hwnd
    current_fg = user32.GetForegroundWindow()
    if current_fg != target_hwnd:
        return {
            "ok": False,
            "error": "Window focus verification failed: target window is not active foreground",
            "target": target_query,
            "target_hwnd": target_hwnd,
            "current_fg_hwnd": current_fg,
        }

    # Perform safe paste via clipboard
    prev_clipboard = _get_clipboard_unicode()
    try:
        if not _set_clipboard_unicode(text):
            return {"ok": False, "error": "Failed to set clipboard data"}

        time.sleep(0.02)
        # Simulate Ctrl+V
        user32.keybd_event(VK_CONTROL, 0, 0, 0)
        time.sleep(0.01)
        user32.keybd_event(VK_V, 0, 0, 0)
        time.sleep(0.01)
        user32.keybd_event(VK_V, 0, KEYEVENTF_KEYUP, 0)
        time.sleep(0.01)
        user32.keybd_event(VK_CONTROL, 0, KEYEVENTF_KEYUP, 0)
        time.sleep(0.05)

        # Guardrail 4: Enter / Send confirmation check
        sent = False
        if confirm_send:
            if confirm_token != "CONFIRM":
                return {
                    "ok": True,
                    "typed": True,
                    "sent": False,
                    "warning": "Text was typed into draft, but not sent: confirm_token must be 'CONFIRM'",
                    "target": focus_res["process"] or focus_res["title"],
                }

            user32.keybd_event(VK_RETURN, 0, 0, 0)
            time.sleep(0.01)
            user32.keybd_event(VK_RETURN, 0, KEYEVENTF_KEYUP, 0)
            sent = True

        return {
            "ok": True,
            "typed": True,
            "sent": sent,
            "target": focus_res["process"] or focus_res["title"],
            "chars_count": len(text),
        }
    finally:
        # Restore user's previous clipboard content
        time.sleep(0.05)
        _set_clipboard_unicode(prev_clipboard)
