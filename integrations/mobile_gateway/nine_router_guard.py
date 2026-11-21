"""
9Router Watchdog Guard.
Memantau port 20128 (9Router). Jika server mati/crash, otomatis respawn di background.
Single-instance via Windows Mutex.
"""
from __future__ import annotations

import ctypes
import os
import shutil
import socket
import subprocess
import sys
import time
from ctypes import wintypes
from datetime import datetime
from pathlib import Path

GUARD_MUTEX_NAME = "Local\\AgentRemote9RouterGuard"
NINE_ROUTER_HOST = "127.0.0.1"
NINE_ROUTER_PORT = 20128
SMART_ROUTER_PORT = 20130
LITELLM_PORT = 4010
CHECK_INTERVAL_SEC = 15
SPAWN_GRACE_PERIOD_SEC = 90
LITELLM_SPAWN_GRACE_SEC = 45
MAX_RESPAWN_ATTEMPTS = 5
COOLDOWN_AFTER_MAX_FAILURES_SEC = 300

CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)
DETACHED_PROCESS = getattr(subprocess, "DETACHED_PROCESS", 0x00000008)
CREATE_NEW_PROCESS_GROUP = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0x00000200)


def _get_silent_startupinfo():
    if sys.platform != "win32":
        return None
    si = subprocess.STARTUPINFO()
    si.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    si.wShowWindow = 0  # SW_HIDE
    return si


def log_file_path() -> Path:
    local_app_data = os.environ.get("LOCALAPPDATA")
    if local_app_data:
        target_dir = Path(local_app_data) / "AgentRemote"
    else:
        target_dir = Path.home() / ".agent_remote"
    target_dir.mkdir(parents=True, exist_ok=True)
    return target_dir / "9router_guard.log"


def log_guard(message: str) -> None:
    now_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    line = f"[{now_str}] {message}\n"
    try:
        path = log_file_path()
        # Truncate if larger than 2MB
        if path.exists() and path.stat().st_size > 2 * 1024 * 1024:
            content = path.read_text(encoding="utf-8", errors="ignore")
            path.write_text(content[-500000:], encoding="utf-8", errors="ignore")
        with open(path, "a", encoding="utf-8") as f:
            f.write(line)
    except Exception:
        pass


def acquire_guard_mutex():
    if os.name != "nt":
        return True
    try:
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel32.CreateMutexW.argtypes = [ctypes.c_void_p, wintypes.BOOL, wintypes.LPCWSTR]
        kernel32.CreateMutexW.restype = wintypes.HANDLE
        handle = kernel32.CreateMutexW(None, False, GUARD_MUTEX_NAME)
        if not handle:
            return None
        if ctypes.get_last_error() == 183:  # ERROR_ALREADY_EXISTS
            kernel32.CloseHandle(handle)
            return None
        return handle
    except (AttributeError, OSError):
        return True


def release_guard_mutex(handle) -> None:
    if os.name != "nt" or handle is True or handle is None:
        return
    try:
        ctypes.WinDLL("kernel32", use_last_error=True).CloseHandle(handle)
    except (AttributeError, OSError):
        pass


def is_nine_router_alive(timeout_sec: float = 1.5) -> bool:
    try:
        with socket.create_connection((NINE_ROUTER_HOST, NINE_ROUTER_PORT), timeout=timeout_sec):
            return True
    except (OSError, socket.timeout):
        return False


def is_smart_router_alive(timeout_sec: float = 1.5) -> bool:
    try:
        with socket.create_connection((NINE_ROUTER_HOST, SMART_ROUTER_PORT), timeout=timeout_sec):
            return True
    except (OSError, socket.timeout):
        return False


def is_litellm_alive(timeout_sec: float = 1.5) -> bool:
    try:
        with socket.create_connection((NINE_ROUTER_HOST, LITELLM_PORT), timeout=timeout_sec):
            return True
    except (OSError, socket.timeout):
        return False


def spawn_litellm_proxy() -> bool:
    try:
        script_dir = Path(__file__).resolve().parent
        launcher_script = script_dir / "litellm_proxy" / "launcher.py"
        if not launcher_script.is_file():
            return False

        python_bin = sys.executable if not getattr(sys, "frozen", False) else (shutil.which("python") or "python")
        flags = CREATE_NO_WINDOW
        if sys.platform == "win32":
            flags |= DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP

        subprocess.Popen(
            [str(python_bin), str(launcher_script), "--port", str(LITELLM_PORT)],
            cwd=str(launcher_script.parent),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            close_fds=True,
            creationflags=flags,
            startupinfo=_get_silent_startupinfo(),
        )
        log_guard(f"Respawned SmartHermes LiteLLM proxy launcher (port {LITELLM_PORT}) successfully.")
        return True
    except Exception as exc:
        log_guard(f"Failed to respawn SmartHermes LiteLLM proxy: {exc}")
        return False


def spawn_smart_router() -> bool:
    try:
        script_dir = Path(__file__).resolve().parent
        proxy_script = script_dir / "smart_router_proxy.py"
        if not proxy_script.is_file():
            return False

        python_bin = sys.executable if not getattr(sys, "frozen", False) else (shutil.which("python") or "python")
        flags = CREATE_NO_WINDOW
        if sys.platform == "win32":
            flags |= DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP

        subprocess.Popen(
            [str(python_bin), str(proxy_script)],
            cwd=str(script_dir),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            close_fds=True,
            creationflags=flags,
            startupinfo=_get_silent_startupinfo(),
        )
        log_guard("Respawned SmartRouter proxy (port 20130) successfully.")
        return True
    except Exception as exc:
        log_guard(f"Failed to respawn SmartRouter proxy: {exc}")
        return False


def nine_router_executable() -> Path | None:
    for cmd in ("9router.cmd", "9router"):
        res = shutil.which(cmd)
        if res:
            return Path(res).resolve()
    appdata = os.environ.get("APPDATA")
    if appdata:
        fallback = Path(appdata) / "npm" / "9router.cmd"
        if fallback.is_file():
            return fallback.resolve()
    return None


def nine_router_command() -> list[str]:
    appdata = os.environ.get("APPDATA")
    if appdata:
        cli_js = Path(appdata) / "npm" / "node_modules" / "9router" / "cli.js"
        node_exe = shutil.which("node") or r"C:\Program Files\nodejs\node.exe"
        if cli_js.is_file() and os.path.exists(node_exe):
            return [str(node_exe), str(cli_js), "--no-browser", "--skip-update", "--tray"]

    exe = nine_router_executable()
    if exe is None:
        raise RuntimeError("9Router executable tidak ditemukan di sistem / PATH.")
    args = ["--no-browser", "--skip-update", "--tray"]
    if exe.suffix.lower() in {".cmd", ".bat"}:
        return ["cmd.exe", "/d", "/c", str(exe), *args]
    return [str(exe), *args]


def spawn_nine_router() -> bool:
    try:
        cmd = nine_router_command()
        exe = nine_router_executable()
        cwd_target = str(exe.parent) if exe else None

        flags = CREATE_NO_WINDOW
        if sys.platform == "win32":
            flags |= DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP

        subprocess.Popen(
            cmd,
            cwd=cwd_target,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            close_fds=True,
            creationflags=flags,
            startupinfo=_get_silent_startupinfo(),
        )
        log_guard("Respawned 9Router process successfully.")
        return True
    except Exception as exc:
        log_guard(f"Failed to respawn 9Router: {exc}")
        return False



AUTO_UPDATE_ENABLED = False  # DILARANG auto-update saat runtime agar proses node tidak mati di tengah jalan
UPDATE_CHECK_INTERVAL_SEC = 3600 * 24  # Cek update hanya jika diaktifkan manual


def get_installed_9router_version() -> str | None:
    appdata = os.environ.get("APPDATA")
    if not appdata:
        return None
    pkg_path = Path(appdata) / "npm" / "node_modules" / "9router" / "package.json"
    if not pkg_path.is_file():
        return None
    try:
        import json
        data = json.loads(pkg_path.read_text(encoding="utf-8"))
        return str(data.get("version"))
    except Exception:
        return None


def get_latest_9router_version() -> str | None:
    try:
        import json, urllib.request
        req = urllib.request.Request(
            "https://registry.npmjs.org/9router/latest",
            headers={"User-Agent": "9router-guard"}
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            return str(data.get("version"))
    except Exception as exc:
        log_guard(f"Failed to check 9router latest version: {exc}")
        return None


def patch_9router_combo_fallback() -> bool:
    appdata = os.environ.get("APPDATA")
    if not appdata:
        return False
    chunks_dir = Path(appdata) / "npm" / "node_modules" / "9router" / "app" / ".next-cli-build" / "server" / "chunks"
    if not chunks_dir.is_dir():
        return False
    import re
    pattern = re.compile(
        r'if\(!([a-zA-Z0-9_]+)\)return ([a-zA-Z0-9_]+)\.warn\("COMBO",`Model \${([a-zA-Z0-9_]+)} failed \(no fallback\)`,{[^}]+}\),([a-zA-Z0-9_]+);'
    )
    patched_any = False
    for js_file in chunks_dir.glob("*.js"):
        if js_file.name.endswith(".bak"):
            continue
        try:
            content = js_file.read_text(encoding="utf-8")
            m = pattern.search(content)
            if m:
                bak_path = js_file.with_suffix(".js.bak")
                if not bak_path.exists():
                    bak_path.write_text(content, encoding="utf-8")
                repl = f'if(!{m.group(1)}){m.group(2)}.warn("COMBO",`Model ${{{m.group(3)}}} failed (${{{m.group(4)}.status}}), trying next`);'
                new_content = pattern.sub(repl, content, count=1)
                js_file.write_text(new_content, encoding="utf-8")
                log_guard(f"Patched combo fallback in {js_file.name}")
                patched_any = True
        except Exception as exc:
            log_guard(f"Error checking/patching {js_file.name}: {exc}")
    return patched_any


def kill_9router_processes() -> None:
    # PERMANEN DILARANG: Fungsi ini dimatikan total agar tidak pernah mematikan proses 9router
    log_guard("kill_9router_processes() dipanggil tetapi diabaikan demi stabilitas sesi.")
    return


def check_and_update_9router(force: bool = False) -> bool:
    log_guard("Automatic 9Router update disabled during runtime to protect active sessions.")
    return False


def run_watchdog() -> None:
    handle = acquire_guard_mutex()
    if handle is None:
        # Instance guard sudah aktif berjalan di latar belakang
        return

    log_guard("9Router Watchdog Guard started.")
    consecutive_failures = 0
    respawn_attempts = 0
    last_update_check = time.time()

    if AUTO_UPDATE_ENABLED:
        try:
            check_and_update_9router(force=False)
        except Exception as exc:
            log_guard(f"Startup update check failed: {exc}")

    try:
        while True:
            alive = is_nine_router_alive()
            if alive:
                consecutive_failures = 0
                respawn_attempts = 0
            else:
                consecutive_failures += 1
                if consecutive_failures >= 3:
                    respawn_attempts += 1
                    if respawn_attempts > MAX_RESPAWN_ATTEMPTS:
                        log_guard(f"9Router exceeded max respawn attempts ({MAX_RESPAWN_ATTEMPTS}). Cooling down for {COOLDOWN_AFTER_MAX_FAILURES_SEC}s to prevent lag...")
                        time.sleep(COOLDOWN_AFTER_MAX_FAILURES_SEC)
                        respawn_attempts = 0
                        consecutive_failures = 0
                        continue

                    log_guard(f"9Router port 20128 detected DOWN (attempt {respawn_attempts}/{MAX_RESPAWN_ATTEMPTS}). Triggering auto-restart...")
                    spawn_nine_router()
                    consecutive_failures = 0
                    time.sleep(SPAWN_GRACE_PERIOD_SEC)
                    continue

            # Check SmartHermes LiteLLM proxy (port 4010)
            if is_nine_router_alive() and not is_litellm_alive():
                log_guard(f"SmartHermes LiteLLM proxy port {LITELLM_PORT} detected DOWN. Triggering auto-restart...")
                spawn_litellm_proxy()
                time.sleep(LITELLM_SPAWN_GRACE_SEC)

            # Periodic update check (hanya jika AUTO_UPDATE_ENABLED aktif)
            now = time.time()
            if AUTO_UPDATE_ENABLED and (now - last_update_check >= UPDATE_CHECK_INTERVAL_SEC):
                last_update_check = now
                try:
                    check_and_update_9router(force=False)
                except Exception as exc:
                    log_guard(f"Periodic update check failed: {exc}")

            time.sleep(CHECK_INTERVAL_SEC)
    finally:
        log_guard("9Router Watchdog Guard stopped.")
        release_guard_mutex(handle)


def start_guard_detached() -> None:
    """Helper untuk memanggil guard ini secara detached dari script lain."""
    flags = CREATE_NO_WINDOW
    if sys.platform == "win32":
        flags |= DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP

    script_path = Path(__file__).resolve()
    python_exe = sys.executable

    subprocess.Popen(
        [python_exe, str(script_path)],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        close_fds=True,
        creationflags=flags,
        startupinfo=_get_silent_startupinfo(),
    )


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--status":
        alive = is_nine_router_alive()
        print("ALIVE" if alive else "DEAD")
        sys.exit(0 if alive else 1)
    elif len(sys.argv) > 1 and sys.argv[1] == "--respawn-now":
        spawn_nine_router()
        sys.exit(0)
    elif len(sys.argv) > 1 and sys.argv[1] in ("--update-now", "--check-update"):
        force = (sys.argv[1] == "--update-now")
        res = check_and_update_9router(force=force)
        print("UPDATED" if res else "NO_UPDATE_OR_FAILED")
        sys.exit(0 if res else 1)
    else:
        run_watchdog()

# ponytail: simple socket poll loop + single instance mutex; add IPC heartbeat only if required.
