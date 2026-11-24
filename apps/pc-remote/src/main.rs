use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use eframe::egui;
use serde_json::{json, Value};
use std::{
    io::{BufRead, BufReader, Read},
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        mpsc, Arc, Mutex,
    },
    time::{Duration, Instant},
};

#[repr(C)]
struct DevModeW {
    dm_device_name: [u16; 32],
    dm_spec_version: u16,
    dm_driver_version: u16,
    dm_size: u16,
    dm_driver_extra: u16,
    dm_fields: u32,
    _pad: [u8; 112],
    dm_display_flags: u32,
    dm_display_frequency: u32,
    _tail: [u8; 32],
}

#[link(name = "user32")]
extern "system" {
    fn EnumDisplaySettingsW(
        lpsz_device_name: *const u16,
        i_mode_num: u32,
        lp_dev_mode: *mut DevModeW,
    ) -> i32;
}

fn local_monitor_hz() -> u32 {
    unsafe {
        let mut dm: DevModeW = std::mem::zeroed();
        dm.dm_size = std::mem::size_of::<DevModeW>() as u16;
        if EnumDisplaySettingsW(std::ptr::null(), u32::MAX, &mut dm) != 0
            && (24..=500).contains(&dm.dm_display_frequency)
        {
            return dm.dm_display_frequency;
        }
    }
    60
}

fn tailscale_status_json() -> Option<Value> {
    use std::os::windows::process::CommandExt;
    ["tailscale.exe", r"C:\Program Files\Tailscale\tailscale.exe"]
        .iter()
        .find_map(|exe| {
            std::process::Command::new(exe)
                .args(["status", "--json"])
                .creation_flags(0x08000000)
                .output()
                .ok()
                .filter(|output| output.status.success())
        })
        .and_then(|output| serde_json::from_slice::<Value>(&output.stdout).ok())
}

fn detect_tailscale_self_name() -> String {
    if let Some(status) = tailscale_status_json() {
        if let Some(name) = status["Self"]["HostName"].as_str() {
            let trimmed = name.trim();
            if !trimmed.is_empty() {
                return trimmed.to_owned();
            }
        }
    }
    std::env::var("COMPUTERNAME")
        .ok()
        .map(|s| s.trim().to_owned())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "Capri Remote Windows".into())
}

#[derive(Clone)]
struct StreamConfig {
    endpoint: String,
    device: String,
    device_name: String,
    token: String,
    monitor: String,
    resolution: String,
    quality: u8,
    fps_cap: u32,
}

enum VoiceEvent {
    Status(String),
    Error(String),
}

fn decode_base64(input: &str) -> Vec<u8> {
    let mut out = Vec::with_capacity(input.len() * 3 / 4);
    let mut buf: u32 = 0;
    let mut bits: u8 = 0;
    for b in input.bytes() {
        let val = match b {
            b'A'..=b'Z' => b - b'A',
            b'a'..=b'z' => b - b'a' + 26,
            b'0'..=b'9' => b - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            b'=' | b'\r' | b'\n' | b' ' => continue,
            _ => continue,
        };
        buf = (buf << 6) | u32::from(val);
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push(((buf >> bits) & 0xFF) as u8);
        }
    }
    out
}

fn resample_f32_to_16k(samples: &[f32], src_rate: u32, channels: u16) -> Vec<u8> {
    let ch = usize::from(channels.max(1));
    let frame_count = samples.len() / ch;
    if frame_count == 0 || src_rate == 0 {
        return Vec::new();
    }
    let mono: Vec<f32> = if ch == 1 {
        samples.to_vec()
    } else {
        (0..frame_count)
            .map(|i| {
                let sum: f32 = samples[i * ch..(i + 1) * ch].iter().sum();
                sum / ch as f32
            })
            .collect()
    };
    let out_len = ((frame_count as u64 * 16_000) / u64::from(src_rate)) as usize;
    if out_len == 0 {
        return Vec::new();
    }
    let mut bytes = Vec::with_capacity(out_len * 4);
    if src_rate == 16_000 {
        for s in mono {
            bytes.extend_from_slice(&s.to_le_bytes());
        }
        return bytes;
    }
    let ratio = (frame_count - 1) as f64 / (out_len.saturating_sub(1).max(1)) as f64;
    for i in 0..out_len {
        let pos = i as f64 * ratio;
        let idx = (pos as usize).min(frame_count - 1);
        let next = (idx + 1).min(frame_count - 1);
        let frac = (pos - idx as f64) as f32;
        let sample = mono[idx] + (mono[next] - mono[idx]) * frac;
        bytes.extend_from_slice(&sample.to_le_bytes());
    }
    bytes
}

fn build_request(
    agent: &ureq::Agent,
    method: &str,
    endpoint: &str,
    route: &str,
    token: &str,
    device: &str,
    device_name: &str,
) -> ureq::Request {
    let request = agent.request(method, &format!("http://{}:9120{}", endpoint.trim(), route));
    let request = if matches!(route, "/api/screen/frame" | "/api/screen/input") {
        request
    } else {
        request.set("Connection", "close")
    };
    request
        .set("Authorization", &format!("Bearer {}", token))
        .set("X-Device-Id", device)
        .set("X-Device-Name", device_name)
}

struct Remote {
    endpoint: String,
    hosts: Vec<(String, String)>,
    device: String,
    device_name: String,
    token: String,
    monitors: Vec<(String, String, u32)>,
    monitor: String,
    local_hz: u32,
    resolution: String,
    quality: u8,
    fps_setting: u32, // 0 = Auto (follows min(host_hz, local_hz))
    fullscreen: bool,
    screen_zoom: f32,
    screen_center: egui::Pos2,
    windows_mouse: bool,
    supports_windows_mouse: bool,
    pan_screen: bool,
    frame: Option<egui::TextureHandle>,
    next_pointer: Instant,
    status: String,
    voice_status: String,
    grants: Vec<String>,
    connected: bool,
    agent: ureq::Agent,
    stream_cfg: Arc<Mutex<Option<StreamConfig>>>,
    stream_gen: Arc<AtomicU64>,
    latest_frame: Option<Arc<Mutex<Option<Result<egui::ColorImage, String>>>>>,
    input_tx: Option<mpsc::Sender<Value>>,
    stop_workers: Arc<AtomicBool>,
    mic_stream: Option<cpal::Stream>,
    mic_stop: Arc<AtomicBool>,
    voice_rx: Option<mpsc::Receiver<VoiceEvent>>,
    host_audio: Option<(Arc<AtomicBool>, mpsc::Receiver<String>)>,
    audio_permission: Option<mpsc::Receiver<Result<Vec<String>, String>>>,
}

impl Remote {
    fn ask_audio_permission(&mut self, ctx: &egui::Context) {
        let (tx, rx) = mpsc::channel();
        self.audio_permission = Some(rx);
        self.voice_status = "Menunggu persetujuan Suara PC host…".into();
        let endpoint = self.endpoint.clone();
        let token = self.token.clone();
        let device = self.device.clone();
        let name = self.device_name.clone();
        let ctx = ctx.clone();
        std::thread::spawn(move || {
            let request = || -> Result<Vec<String>, Box<dyn std::error::Error>> {
                let agent = ureq::AgentBuilder::new().redirects(0).build();
                let response: Value = match build_request(
                    &agent,
                    "POST",
                    &endpoint,
                    "/api/remote-pc/audio-permission",
                    &token,
                    &device,
                    &name,
                )
                .timeout(Duration::from_secs(45))
                .send_json(json!({}))
                {
                    Ok(response) => response.into_json()?,
                    Err(ureq::Error::Status(code, response)) => {
                        let body: Value = response.into_json().unwrap_or_default();
                        return Err(format!(
                            "Izin suara (HTTP {code}): {}",
                            body["error"]
                                .as_str()
                                .unwrap_or("Perbarui atau buka ulang Capri host.")
                        )
                        .into());
                    }
                    Err(error) => return Err(error.into()),
                };
                Ok(serde_json::from_value(response["grants"].clone())?)
            };
            let _ = tx.send(request().map_err(|error| error.to_string()));
            ctx.request_repaint();
        });
    }

    fn stop_host_audio(&mut self) {
        if let Some((stop, _)) = self.host_audio.take() {
            stop.store(true, Ordering::Relaxed);
        }
    }

    fn start_host_audio(&mut self, ctx: &egui::Context) {
        let endpoint = self.endpoint.clone();
        let token = self.token.clone();
        let device = self.device.clone();
        let name = self.device_name.clone();
        let stop = Arc::new(AtomicBool::new(false));
        let worker_stop = Arc::clone(&stop);
        let (tx, rx) = mpsc::channel();
        let ctx = ctx.clone();
        self.host_audio = Some((stop, rx));
        std::thread::spawn(move || {
            let run = || -> Result<(), Box<dyn std::error::Error>> {
                let (_output, handle) = rodio::OutputStream::try_default()?;
                let sink = rodio::Sink::try_new(&handle)?;
                let agent = ureq::AgentBuilder::new()
                    .redirects(0)
                    .timeout(Duration::from_secs(10))
                    .build();
                let mut after = 0;
                while !worker_stop.load(Ordering::Relaxed) {
                    let value: Value = build_request(
                        &agent,
                        "GET",
                        &endpoint,
                        "/api/remote-pc/audio",
                        &token,
                        &device,
                        &name,
                    )
                    .query("after", &after.to_string())
                    .call()?
                    .into_json()?;
                    if worker_stop.load(Ordering::Relaxed) {
                        break;
                    }
                    after = value["seq"].as_u64().unwrap_or(after);
                    for packet in value["packets"].as_array().into_iter().flatten() {
                        if sink.len() >= 10 {
                            break;
                        }
                        if let Some(encoded) = packet["pcmBase64"].as_str() {
                            let pcm = decode_base64(encoded);
                            let samples: Vec<f32> = pcm
                                .chunks_exact(4)
                                .map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]]))
                                .collect();
                            if !samples.is_empty() {
                                sink.append(rodio::buffer::SamplesBuffer::new(1, 16000, samples));
                            }
                        }
                    }
                    std::thread::sleep(Duration::from_millis(150));
                }
                Ok(())
            };
            if let Err(error) = run() {
                let message = if matches!(
                    error.downcast_ref::<ureq::Error>(),
                    Some(ureq::Error::Status(401 | 403, _))
                ) {
                    "Izin suara PC host belum tersedia. Pair ulang dengan izin Suara PC host."
                        .into()
                } else {
                    format!("Suara PC host terputus: {error}")
                };
                let _ = tx.send(message);
                ctx.request_repaint();
            }
        });
    }

    fn apply_theme(ctx: &egui::Context) {
        let mut style = (*ctx.style()).clone();
        let mut visuals = egui::Visuals::dark();

        let bg_canvas = egui::Color32::from_rgb(11, 13, 16);
        let bg_card = egui::Color32::from_rgb(19, 23, 29);
        let bg_elevated = egui::Color32::from_rgb(26, 32, 40);
        let bg_hover = egui::Color32::from_rgb(34, 42, 53);
        let border = egui::Color32::from_rgb(38, 46, 58);
        let accent = egui::Color32::from_rgb(59, 130, 246);
        let text_primary = egui::Color32::from_rgb(243, 245, 248);
        let text_muted = egui::Color32::from_rgb(154, 164, 178);

        visuals.panel_fill = bg_canvas;
        visuals.window_fill = bg_card;
        visuals.extreme_bg_color = egui::Color32::from_rgb(15, 18, 23);
        visuals.faint_bg_color = bg_elevated;
        visuals.selection.bg_fill = accent;
        visuals.selection.stroke = egui::Stroke::new(1.0_f32, text_primary);

        visuals.widgets.noninteractive.bg_fill = bg_card;
        visuals.widgets.noninteractive.bg_stroke = egui::Stroke::new(1.0_f32, border);
        visuals.widgets.noninteractive.fg_stroke = egui::Stroke::new(1.0_f32, text_muted);
        visuals.widgets.noninteractive.corner_radius = egui::CornerRadius::same(8);

        visuals.widgets.inactive.bg_fill = bg_elevated;
        visuals.widgets.inactive.bg_stroke = egui::Stroke::new(1.0_f32, border);
        visuals.widgets.inactive.fg_stroke = egui::Stroke::new(1.0_f32, text_primary);
        visuals.widgets.inactive.corner_radius = egui::CornerRadius::same(6);

        visuals.widgets.hovered.bg_fill = bg_hover;
        visuals.widgets.hovered.bg_stroke =
            egui::Stroke::new(1.0_f32, egui::Color32::from_rgb(71, 85, 105));
        visuals.widgets.hovered.fg_stroke = egui::Stroke::new(1.0_f32, text_primary);
        visuals.widgets.hovered.corner_radius = egui::CornerRadius::same(6);

        visuals.widgets.active.bg_fill = accent;
        visuals.widgets.active.bg_stroke = egui::Stroke::new(1.0_f32, accent);
        visuals.widgets.active.fg_stroke = egui::Stroke::new(1.0_f32, text_primary);
        visuals.widgets.active.corner_radius = egui::CornerRadius::same(6);

        style.visuals = visuals;
        style.spacing.item_spacing = egui::vec2(8.0, 8.0);
        style.spacing.button_padding = egui::vec2(12.0, 7.0);
        style.spacing.interact_size.y = 32.0;
        ctx.set_style(style);
    }

    fn effective_fps_cap(&self) -> u32 {
        let host_hz = self
            .monitors
            .iter()
            .find(|(id, _, _)| id == &self.monitor)
            .map(|(_, _, hz)| *hz)
            .unwrap_or(60);
        let monitor_cap = host_hz.min(self.local_hz).max(1);
        if self.fps_setting == 0 {
            monitor_cap
        } else {
            self.fps_setting.min(monitor_cap)
        }
    }

    fn sync_stream_cfg(&self) {
        if let Ok(mut guard) = self.stream_cfg.lock() {
            if self.connected {
                *guard = Some(StreamConfig {
                    endpoint: self.endpoint.clone(),
                    device: self.device.clone(),
                    device_name: self.device_name.clone(),
                    token: self.token.clone(),
                    monitor: self.monitor.clone(),
                    resolution: self.resolution.clone(),
                    quality: self.quality,
                    fps_cap: self.effective_fps_cap(),
                });
            } else {
                *guard = None;
            }
        }
        self.stream_gen.fetch_add(1, Ordering::Relaxed);
    }

    fn start_workers(&mut self, ctx: &egui::Context) {
        self.stop_workers.store(true, Ordering::Relaxed);
        self.stop_workers = Arc::new(AtomicBool::new(false));

        let frame_slot = Arc::new(Mutex::new(None));
        let (input_tx, input_rx) = mpsc::channel::<Value>();
        self.latest_frame = Some(Arc::clone(&frame_slot));
        self.input_tx = Some(input_tx);

        // Background MJPEG stream reader (continuous push, zero per-frame handshake latency)
        let cfg_arc = Arc::clone(&self.stream_cfg);
        let gen_arc = Arc::clone(&self.stream_gen);
        let stop_f = Arc::clone(&self.stop_workers);
        let ctx_f = ctx.clone();
        std::thread::spawn(move || {
            let agent = ureq::AgentBuilder::new()
                .redirects(0)
                .timeout_connect(Duration::from_secs(6))
                .timeout_read(Duration::from_secs(6))
                .build();
            while !stop_f.load(Ordering::Relaxed) {
                let active_gen = gen_arc.load(Ordering::Relaxed);
                let maybe_cfg = cfg_arc.lock().ok().and_then(|g| g.clone());
                let Some(cfg) = maybe_cfg else {
                    std::thread::sleep(Duration::from_millis(40));
                    continue;
                };
                let q = cfg.quality.to_string();
                let fps = cfg.fps_cap.max(1).to_string();
                let res = build_request(
                    &agent,
                    "GET",
                    &cfg.endpoint,
                    "/api/screen/stream",
                    &cfg.token,
                    &cfg.device,
                    &cfg.device_name,
                )
                .query("monitor", &cfg.monitor)
                .query("res", &cfg.resolution)
                .query("quality", &q)
                .query("fps", &fps)
                .call();

                let Ok(resp) = res else {
                    if let Err(e) = res {
                        if let Ok(mut slot) = frame_slot.lock() {
                            *slot = Some(Err(e.to_string()));
                        }
                        ctx_f.request_repaint();
                    }
                    std::thread::sleep(Duration::from_millis(150));
                    continue;
                };

                let mut reader = BufReader::with_capacity(64 * 1024, resp.into_reader());
                let mut line = String::new();
                while !stop_f.load(Ordering::Relaxed)
                    && gen_arc.load(Ordering::Relaxed) == active_gen
                {
                    let mut content_len: usize = 0;
                    let mut got_headers = false;
                    loop {
                        line.clear();
                        match reader.read_line(&mut line) {
                            Ok(0) => {
                                got_headers = false;
                                break;
                            }
                            Ok(_) => {
                                let trimmed = line.trim();
                                if trimmed.is_empty() {
                                    if got_headers && content_len > 0 {
                                        break;
                                    }
                                    continue;
                                }
                                if trimmed.starts_with("--") {
                                    got_headers = true;
                                    content_len = 0;
                                    continue;
                                }
                                if let Some((k, v)) = trimmed.split_once(':') {
                                    got_headers = true;
                                    if k.trim().eq_ignore_ascii_case("content-length") {
                                        content_len = v.trim().parse::<usize>().unwrap_or(0);
                                    }
                                }
                            }
                            Err(_) => {
                                got_headers = false;
                                break;
                            }
                        }
                    }
                    if !got_headers || content_len == 0 || content_len > 16 * 1024 * 1024 {
                        break;
                    }
                    let mut jpeg = vec![0_u8; content_len];
                    if reader.read_exact(&mut jpeg).is_err() {
                        break;
                    }
                    if let Ok(dyn_img) = image::load_from_memory(&jpeg) {
                        let pixels = dyn_img.into_rgba8();
                        let color_img = egui::ColorImage::from_rgba_unmultiplied(
                            [pixels.width() as usize, pixels.height() as usize],
                            pixels.as_raw(),
                        );
                        if let Ok(mut slot) = frame_slot.lock() {
                            *slot = Some(Ok(color_img));
                        }
                        ctx_f.request_repaint();
                    }
                }
            }
        });

        // Background keep-alive input sender (never blocks UI thread)
        let cfg_in = Arc::clone(&self.stream_cfg);
        let stop_i = Arc::clone(&self.stop_workers);
        std::thread::spawn(move || {
            let agent = ureq::AgentBuilder::new()
                .redirects(0)
                .timeout(Duration::from_secs(4))
                .build();
            let send_once = |cfg: &StreamConfig, payload: &Value| -> bool {
                build_request(
                    &agent,
                    "POST",
                    &cfg.endpoint,
                    "/api/screen/input",
                    &cfg.token,
                    &cfg.device,
                    &cfg.device_name,
                )
                .send_json(payload.clone())
                .and_then(|response| response.into_json::<Value>().map_err(ureq::Error::from))
                .is_ok()
            };
            while !stop_i.load(Ordering::Relaxed) {
                let Ok(mut payload) = input_rx.recv() else {
                    break;
                };
                // Coalesce queued pointer_move events so we only send the latest move
                while let Ok(next) = input_rx.try_recv() {
                    if payload["action"] == "pointer_move" && next["action"] == "pointer_move" {
                        payload = next;
                    } else {
                        if let Some(cfg) = cfg_in.lock().ok().and_then(|g| g.clone()) {
                            if !send_once(&cfg, &payload) {
                                let _ = send_once(&cfg, &payload);
                            }
                        }
                        payload = next;
                    }
                }
                if let Some(cfg) = cfg_in.lock().ok().and_then(|g| g.clone()) {
                    if !send_once(&cfg, &payload) {
                        let _ = send_once(&cfg, &payload);
                    }
                }
            }
        });
    }

    fn send_input(&self, mut payload: Value) {
        payload["mouse_mode"] = json!(if self.windows_mouse {
            "windows"
        } else {
            "virtual"
        });
        if let Some(tx) = &self.input_tx {
            let _ = tx.send(payload);
        }
    }

    fn discover_hosts(&mut self) {
        let Some(status) = tailscale_status_json() else {
            self.status = "Tailscale belum terdeteksi. Buka Tailscale dan login.".into();
            return;
        };
        if let Some(name) = status["Self"]["HostName"].as_str() {
            let trimmed = name.trim();
            if !trimmed.is_empty() {
                self.device_name = trimmed.to_owned();
            }
        }
        let owner = status["Self"]["UserID"].as_u64();
        self.hosts = status["Peer"]
            .as_object()
            .into_iter()
            .flat_map(|peers| peers.values())
            .filter(|peer| {
                owner.is_some()
                    && peer["UserID"].as_u64() == owner
                    && peer["Online"] == true
                    && peer["OS"] == "windows"
            })
            .filter_map(|peer| {
                let ip = peer["TailscaleIPs"]
                    .as_array()?
                    .iter()
                    .filter_map(Value::as_str)
                    .find(|ip| ip.parse::<std::net::Ipv4Addr>().is_ok())?;
                Some((
                    peer["HostName"].as_str().unwrap_or(ip).to_owned(),
                    ip.to_owned(),
                ))
            })
            .collect();
        self.hosts.sort();
        if self.hosts.len() == 1 && self.endpoint.is_empty() {
            self.endpoint = self.hosts[0].1.clone();
        }
        self.status = if self.hosts.is_empty() {
            "Belum ada PC Windows online dari akun Tailscale ini.".into()
        } else {
            String::new()
        };
    }

    fn request(&self, method: &str, route: &str) -> ureq::Request {
        build_request(
            &self.agent,
            method,
            &self.endpoint,
            route,
            &self.token,
            &self.device,
            &self.device_name,
        )
    }

    fn request_pairing(
        &mut self,
        credential: &keyring::Entry,
    ) -> Result<(), Box<dyn std::error::Error>> {
        let paired: Value = self
            .request("POST", "/api/remote-pc/pair")
            .timeout(Duration::from_secs(45))
            .send_json(json!({"name": self.device_name}))?
            .into_json()?;
        credential.set_password(&paired.to_string())?;
        self.load_pairing(&paired)
    }

    fn connect(
        &mut self,
        allow_pairing: bool,
        ctx: &egui::Context,
    ) -> Result<(), Box<dyn std::error::Error>> {
        let address: std::net::Ipv4Addr = self.endpoint.trim().parse()?;
        let [first, second, ..] = address.octets();
        if first != 100 || !(64..=127).contains(&second) {
            return Err("Masukkan IPv4 Tailscale host.".into());
        }
        let credential = keyring::Entry::new("Capri Remote", &address.to_string())?;
        match credential.get_password() {
            Ok(saved) => self.load_pairing(&serde_json::from_str(&saved)?)?,
            Err(keyring::Error::NoEntry) if allow_pairing => {
                self.request_pairing(&credential)?;
            }
            Err(error) => return Err(error.into()),
        }
        let info: Value = self
            .request("GET", "/api/screen/info")
            .call()?
            .into_json()?;
        self.windows_mouse = false;
        self.supports_windows_mouse = info["mouse_modes"]
            .as_array()
            .is_some_and(|modes| modes.iter().any(|mode| mode == "windows"));
        self.monitors = info["monitors"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(|item| {
                let id = item["id"].as_str()?;
                let hz = item["refresh_hz"].as_u64().unwrap_or(60) as u32;
                Some((
                    id.to_owned(),
                    item["name"].as_str().unwrap_or(id).to_owned(),
                    hz,
                ))
            })
            .collect();
        self.connected = true;
        self.sync_stream_cfg();
        self.start_workers(ctx);
        Ok(())
    }

    fn load_pairing(&mut self, paired: &Value) -> Result<(), Box<dyn std::error::Error>> {
        self.device = paired["device_id"]
            .as_str()
            .ok_or("ID perangkat kosong")?
            .into();
        self.token = paired["token"]
            .as_str()
            .ok_or("Token perangkat kosong")?
            .into();
        self.grants = paired["grants"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(Value::as_str)
            .map(str::to_owned)
            .collect();
        Ok(())
    }

    fn start_mic(&mut self, ctx: &egui::Context) -> Result<(), String> {
        let started: Value = self
            .request("POST", "/api/remote-mic/start")
            .timeout(Duration::from_secs(15))
            .send_json(json!({}))
            .map_err(|e| format!("Gagal aktifkan listening Capri: {e}"))?
            .into_json()
            .map_err(|e| format!("Respon Capri mic tidak valid: {e}"))?;
        let initial_seq = started["seq"].as_u64().unwrap_or(0);

        let host = cpal::default_host();
        let device = host
            .default_input_device()
            .ok_or_else(|| "Mikrofon tidak ditemukan.".to_owned())?;
        let config = device
            .default_input_config()
            .map_err(|e| format!("Gagal baca config mic: {e}"))?;
        let sample_rate = config.sample_rate().0;
        let channels = config.channels();
        let samples = Arc::new(Mutex::new(Vec::<f32>::new()));
        let buf_ref = Arc::clone(&samples);

        let stream = match config.sample_format() {
            cpal::SampleFormat::F32 => device.build_input_stream(
                &config.into(),
                move |data: &[f32], _: &cpal::InputCallbackInfo| {
                    if let Ok(mut guard) = buf_ref.lock() {
                        guard.extend_from_slice(data);
                    }
                },
                |_| {},
                None,
            ),
            cpal::SampleFormat::I16 => device.build_input_stream(
                &config.into(),
                move |data: &[i16], _: &cpal::InputCallbackInfo| {
                    if let Ok(mut guard) = buf_ref.lock() {
                        guard.extend(data.iter().map(|s| f32::from(*s) / 32768.0));
                    }
                },
                |_| {},
                None,
            ),
            cpal::SampleFormat::U16 => device.build_input_stream(
                &config.into(),
                move |data: &[u16], _: &cpal::InputCallbackInfo| {
                    if let Ok(mut guard) = buf_ref.lock() {
                        guard.extend(data.iter().map(|s| (f32::from(*s) - 32768.0) / 32768.0));
                    }
                },
                |_| {},
                None,
            ),
            fmt => return Err(format!("Format mic tidak didukung: {fmt:?}")),
        }
        .map_err(|e| format!("Gagal buka mic: {e}"))?;

        stream
            .play()
            .map_err(|e| format!("Gagal mulai rekam mic: {e}"))?;

        self.mic_stop.store(true, Ordering::Relaxed);
        let mic_stop = Arc::new(AtomicBool::new(false));
        self.mic_stop = Arc::clone(&mic_stop);
        self.mic_stream = Some(stream);

        let (tx, rx) = mpsc::channel();
        self.voice_rx = Some(rx);
        self.voice_status = "Capri Listening aktif — silakan berbicara...".into();

        // Thread 1: stream resampled 16kHz mono float32 PCM to Capri ASR
        let endpoint_s = self.endpoint.clone();
        let token_s = self.token.clone();
        let dev_s = self.device.clone();
        let name_s = self.device_name.clone();
        let stop_s = Arc::clone(&mic_stop);
        let tx_s = tx.clone();
        let ctx_s = ctx.clone();
        std::thread::spawn(move || {
            let agent = ureq::AgentBuilder::new()
                .redirects(0)
                .timeout(Duration::from_secs(6))
                .build();
            while !stop_s.load(Ordering::Relaxed) {
                std::thread::sleep(Duration::from_millis(200));
                if stop_s.load(Ordering::Relaxed) {
                    break;
                }
                let drained = samples
                    .lock()
                    .map(|mut g| std::mem::take(&mut *g))
                    .unwrap_or_default();
                if drained.is_empty() {
                    continue;
                }
                let pcm_bytes = resample_f32_to_16k(&drained, sample_rate, channels);
                for chunk in pcm_bytes.chunks(12800) {
                    if stop_s.load(Ordering::Relaxed) {
                        break;
                    }
                    let aligned_len = chunk.len() - (chunk.len() % 4);
                    if aligned_len == 0 {
                        continue;
                    }
                    if let Err(e) = build_request(
                        &agent,
                        "POST",
                        &endpoint_s,
                        "/api/remote-mic/pcm",
                        &token_s,
                        &dev_s,
                        &name_s,
                    )
                    .set("Content-Type", "application/octet-stream")
                    .send_bytes(&chunk[..aligned_len])
                    {
                        let _ = tx_s.send(VoiceEvent::Error(format!("Kirim mic gagal: {e}")));
                        ctx_s.request_repaint();
                        break;
                    }
                }
            }
        });

        // Thread 2: poll Capri voice.audio clips and play directly on controller speakers
        let endpoint_p = self.endpoint.clone();
        let token_p = self.token.clone();
        let dev_p = self.device.clone();
        let name_p = self.device_name.clone();
        let stop_p = Arc::clone(&mic_stop);
        let ctx_p = ctx.clone();
        std::thread::spawn(move || {
            let agent = ureq::AgentBuilder::new()
                .redirects(0)
                .timeout(Duration::from_secs(6))
                .build();
            let audio_out = rodio::OutputStream::try_default().ok();
            let sink = audio_out
                .as_ref()
                .and_then(|(_, handle)| rodio::Sink::try_new(handle).ok());
            let mut after = initial_seq;
            while !stop_p.load(Ordering::Relaxed) {
                let route = format!("/api/remote-mic/audio?after={after}");
                if let Ok(resp) = build_request(
                    &agent,
                    "GET",
                    &endpoint_p,
                    &route,
                    &token_p,
                    &dev_p,
                    &name_p,
                )
                .call()
                {
                    if let Ok(val) = resp.into_json::<Value>() {
                        if let Some(seq) = val["seq"].as_u64() {
                            if seq > after {
                                after = seq;
                            }
                        }
                        if let Some(clips) = val["clips"].as_array() {
                            for clip in clips {
                                if let Some(seq) = clip["seq"].as_u64() {
                                    after = after.max(seq);
                                }
                                let text = clip["text"].as_str().unwrap_or("").trim();
                                if !text.is_empty() {
                                    let _ = tx.send(VoiceEvent::Status(format!("Capri: {text}")));
                                    ctx_p.request_repaint();
                                }
                                let sr = clip["sampleRate"].as_u64().unwrap_or(24_000) as u32;
                                if let Some(b64) = clip["pcmBase64"].as_str() {
                                    let raw = decode_base64(b64);
                                    let floats: Vec<f32> = raw
                                        .chunks_exact(4)
                                        .map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]]))
                                        .collect();
                                    if !floats.is_empty() {
                                        if let Some(ref s) = sink {
                                            s.append(rodio::buffer::SamplesBuffer::new(
                                                1, sr, floats,
                                            ));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                std::thread::sleep(Duration::from_millis(250));
            }
            if let Some(ref s) = sink {
                s.sleep_until_end();
            }
        });

        Ok(())
    }

    fn stop_mic(&mut self) {
        self.mic_stop.store(true, Ordering::Relaxed);
        self.mic_stream = None;
        let endpoint = self.endpoint.clone();
        let token = self.token.clone();
        let dev = self.device.clone();
        let name = self.device_name.clone();
        std::thread::spawn(move || {
            let agent = ureq::AgentBuilder::new()
                .redirects(0)
                .timeout(Duration::from_secs(5))
                .build();
            let _ = build_request(
                &agent,
                "POST",
                &endpoint,
                "/api/remote-mic/stop",
                &token,
                &dev,
                &name,
            )
            .send_json(json!({}));
        });
        self.voice_status = "Mic dimatikan.".into();
    }
}

impl eframe::App for Remote {
    fn save(&mut self, storage: &mut dyn eframe::Storage) {
        storage.set_string("host", self.endpoint.clone());
        storage.set_string("resolution", self.resolution.clone());
        storage.set_string("quality", self.quality.to_string());
        storage.set_string("fps", self.fps_setting.to_string());
    }

    fn update(&mut self, ctx: &egui::Context, _: &mut eframe::Frame) {
        if let Some(result) = self
            .audio_permission
            .as_ref()
            .and_then(|rx| rx.try_recv().ok())
        {
            self.audio_permission = None;
            match result {
                Ok(grants) if grants.iter().any(|grant| grant == "audio") => {
                    self.grants = grants;
                    let saved = json!({"device_id": self.device, "token": self.token, "grants": self.grants});
                    let persisted = keyring::Entry::new("Capri Remote", self.endpoint.trim())
                        .and_then(|entry| entry.set_password(&saved.to_string()));
                    self.voice_status = persisted
                        .err()
                        .map(|error| error.to_string())
                        .unwrap_or_default();
                    self.start_host_audio(ctx);
                }
                Ok(_) => self.voice_status = "Host belum mengizinkan suara PC.".into(),
                Err(error) => self.voice_status = error,
            }
        }
        if let Some(message) = self
            .host_audio
            .as_ref()
            .and_then(|(_, rx)| rx.try_recv().ok())
        {
            self.stop_host_audio();
            self.voice_status = message;
        }
        let bg_card = egui::Color32::from_rgb(19, 23, 29);
        let bg_elevated = egui::Color32::from_rgb(26, 32, 40);
        let border = egui::Color32::from_rgb(38, 46, 58);
        let accent = egui::Color32::from_rgb(37, 99, 235);
        let emerald = egui::Color32::from_rgb(16, 185, 129);
        let text_primary = egui::Color32::from_rgb(243, 245, 248);
        let text_muted = egui::Color32::from_rgb(154, 164, 178);

        if let Some(rx) = &self.voice_rx {
            while let Ok(ev) = rx.try_recv() {
                match ev {
                    VoiceEvent::Status(s) | VoiceEvent::Error(s) => self.voice_status = s,
                }
            }
        }

        if !self.connected {
            egui::CentralPanel::default()
                .frame(egui::Frame::central_panel(&ctx.style()).inner_margin(egui::Margin::same(24)))
                .show(ctx, |ui| {
                    ui.vertical_centered(|ui| {
                        let available_h = ui.available_height();
                        if available_h > 460.0 {
                            ui.add_space((available_h - 420.0) * 0.35);
                        }
                        egui::Frame::new()
                            .fill(bg_card)
                            .stroke(egui::Stroke::new(1.0_f32, border))
                            .corner_radius(egui::CornerRadius::same(12))
                            .inner_margin(egui::Margin::same(24))
                            .show(ui, |ui| {
                                ui.set_width(420.0);
                                ui.with_layout(egui::Layout::top_down(egui::Align::Min), |ui| {
                                    ui.label(
                                        egui::RichText::new("Capri Remote")
                                            .size(22.0)
                                            .strong()
                                            .color(text_primary),
                                    );
                                    ui.label(
                                        egui::RichText::new("Kendalikan PC Windows host melalui jaringan Tailscale.")
                                            .size(13.0)
                                            .color(text_muted),
                                    );
                                    ui.add_space(10.0);
                                    ui.separator();
                                    ui.add_space(6.0);

                                    ui.horizontal(|ui| {
                                        ui.label(
                                            egui::RichText::new("PC Tailscale Online")
                                                .size(13.0)
                                                .strong()
                                                .color(text_primary),
                                        );
                                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                                            if ui.small_button("Muat ulang").clicked() {
                                                self.discover_hosts();
                                            }
                                        });
                                    });

                                    if self.hosts.is_empty() {
                                        egui::Frame::new()
                                            .fill(bg_elevated)
                                            .stroke(egui::Stroke::new(1.0_f32, border))
                                            .corner_radius(egui::CornerRadius::same(8))
                                            .inner_margin(egui::Margin::same(12))
                                            .show(ui, |ui| {
                                                ui.set_width(ui.available_width());
                                                ui.label(
                                                    egui::RichText::new("Belum ada PC Windows online terdeteksi.")
                                                        .size(13.0)
                                                        .color(text_muted),
                                                );
                                            });
                                    } else {
                                        for (name, ip) in &self.hosts {
                                            let selected = &self.endpoint == ip;
                                            let fill = if selected {
                                                egui::Color32::from_rgb(23, 37, 64)
                                            } else {
                                                bg_elevated
                                            };
                                            let stroke = if selected {
                                                egui::Stroke::new(1.5_f32, egui::Color32::from_rgb(59, 130, 246))
                                            } else {
                                                egui::Stroke::new(1.0_f32, border)
                                            };
                                            let row = egui::Frame::new()
                                                .fill(fill)
                                                .stroke(stroke)
                                                .corner_radius(egui::CornerRadius::same(8))
                                                .inner_margin(egui::Margin::symmetric(12, 10))
                                                .show(ui, |ui| {
                                                    ui.set_width(ui.available_width());
                                                    ui.horizontal(|ui| {
                                                        let (rect, _) = ui.allocate_exact_size(
                                                            egui::vec2(8.0, 8.0),
                                                            egui::Sense::hover(),
                                                        );
                                                        ui.painter().circle_filled(rect.center(), 4.0, emerald);
                                                        ui.label(
                                                            egui::RichText::new(name)
                                                                .size(14.0)
                                                                .strong()
                                                                .color(text_primary),
                                                        );
                                                        ui.with_layout(
                                                            egui::Layout::right_to_left(egui::Align::Center),
                                                            |ui| {
                                                                ui.label(
                                                                    egui::RichText::new(ip)
                                                                        .size(12.5)
                                                                        .monospace()
                                                                        .color(text_muted),
                                                                );
                                                            },
                                                        );
                                                    });
                                                });
                                            if row.response.interact(egui::Sense::click()).clicked() {
                                                self.endpoint = ip.clone();
                                            }
                                        }
                                    }

                                    ui.collapsing("Masukkan alamat IP manual", |ui| {
                                        ui.add(
                                            egui::TextEdit::singleline(&mut self.endpoint)
                                                .hint_text("100.x.x.x")
                                                .desired_width(f32::INFINITY),
                                        );
                                    });

                                    ui.add_space(4.0);
                                    let connect_btn = egui::Button::new(
                                        egui::RichText::new("Hubungkan")
                                            .size(14.0)
                                            .strong()
                                            .color(text_primary),
                                    )
                                    .fill(accent)
                                    .min_size(egui::vec2(ui.available_width(), 38.0));
                                    if ui.add(connect_btn).clicked() {
                                        self.status = match self.connect(true, ctx) {
                                            Ok(()) => String::new(),
                                            Err(error) => error.to_string(),
                                        };
                                    }
                                    if ui.button("Pair ulang").clicked() {
                                        let result = (|| -> Result<(), Box<dyn std::error::Error>> {
                                            let entry = keyring::Entry::new("Capri Remote", self.endpoint.trim())?;
                                            match entry.delete_credential() {
                                                Ok(()) | Err(keyring::Error::NoEntry) => self.connect(true, ctx),
                                                Err(error) => Err(error.into()),
                                            }
                                        })();
                                        self.status = result.err().map(|e| e.to_string()).unwrap_or_default();
                                    }
                                    ui.label(
                                        egui::RichText::new("Koneksi pertama memerlukan persetujuan di PC host.")
                                            .size(12.0)
                                            .color(text_muted),
                                    );

                                    if !self.status.is_empty() {
                                        egui::Frame::new()
                                            .fill(egui::Color32::from_rgb(45, 22, 26))
                                            .stroke(egui::Stroke::new(1.0_f32, egui::Color32::from_rgb(153, 27, 27)))
                                            .corner_radius(egui::CornerRadius::same(8))
                                            .inner_margin(egui::Margin::symmetric(12, 8))
                                            .show(ui, |ui| {
                                                ui.set_width(ui.available_width());
                                                ui.label(
                                                    egui::RichText::new(&self.status)
                                                        .size(12.5)
                                                        .color(egui::Color32::from_rgb(254, 202, 202)),
                                                );
                                            });
                                    }
                                });
                            });
                    });
                });
            return;
        }

        // Drain incoming frames from background fetcher
        let mut latest_image = None;
        if let Some(slot) = &self.latest_frame {
            if let Some(res) = slot.lock().ok().and_then(|mut frame| frame.take()) {
                match res {
                    Ok(img) => {
                        latest_image = Some(img);
                        self.status.clear();
                    }
                    Err(e) => self.status = e,
                }
            }
        }
        if let Some(img) = latest_image {
            if let Some(frame) = &mut self.frame {
                frame.set(img, egui::TextureOptions::LINEAR);
            } else {
                self.frame =
                    Some(ctx.load_texture("Layar host", img, egui::TextureOptions::LINEAR));
            }
        }

        let host_hz = self
            .monitors
            .iter()
            .find(|(id, _, _)| id == &self.monitor)
            .map(|(_, _, hz)| *hz)
            .unwrap_or(60);
        let monitor_cap = host_hz.min(self.local_hz).max(1);

        egui::TopBottomPanel::top("remote_toolbar")
            .frame(
                egui::Frame::new()
                    .fill(bg_card)
                    .stroke(egui::Stroke::new(1.0_f32, border))
                    .inner_margin(egui::Margin::symmetric(
                        12,
                        if self.fullscreen { 5 } else { 8 },
                    )),
            )
            .show(ctx, |ui| {
                ui.horizontal_wrapped(|ui| {
                    let (dot, _) =
                        ui.allocate_exact_size(egui::vec2(8.0, 8.0), egui::Sense::hover());
                    ui.painter().circle_filled(dot.center(), 4.0, emerald);
                    ui.label(
                        egui::RichText::new(&self.device_name)
                            .size(13.0)
                            .strong()
                            .color(text_primary),
                    );
                    ui.label(
                        egui::RichText::new(format!("→ {}", self.endpoint.trim()))
                            .size(12.0)
                            .monospace()
                            .color(text_muted),
                    );
                    ui.separator();

                    let selected = self
                        .monitors
                        .iter()
                        .find(|(id, _, _)| id == &self.monitor)
                        .map(|(_, name, hz)| format!("{name} ({hz}Hz)"))
                        .unwrap_or_else(|| "Monitor utama".into());
                    let before_mon = self.monitor.clone();
                    egui::ComboBox::from_id_salt("monitor_select")
                        .selected_text(selected)
                        .show_ui(ui, |ui| {
                            for (id, name, hz) in &self.monitors {
                                ui.selectable_value(
                                    &mut self.monitor,
                                    id.clone(),
                                    format!("{name} ({hz}Hz)"),
                                );
                            }
                        });
                    if before_mon != self.monitor {
                        self.frame = None;
                        self.sync_stream_cfg();
                    }

                    let res_label = match self.resolution.as_str() {
                        "540p" => "540p",
                        "720p" => "720p",
                        "1080p" => "1080p",
                        "native" => "Native",
                        other => other,
                    };
                    let before_res = self.resolution.clone();
                    egui::ComboBox::from_id_salt("res_select")
                        .width(72.0)
                        .selected_text(res_label)
                        .show_ui(ui, |ui| {
                            ui.selectable_value(&mut self.resolution, "540p".into(), "540p");
                            ui.selectable_value(&mut self.resolution, "720p".into(), "720p");
                            ui.selectable_value(&mut self.resolution, "1080p".into(), "1080p");
                            ui.selectable_value(&mut self.resolution, "native".into(), "Native");
                        });
                    if before_res != self.resolution {
                        self.sync_stream_cfg();
                    }

                    let bitrate_label = match self.quality {
                        0..=45 => "Hemat (Q40)",
                        46..=65 => "Sedang (Q60)",
                        66..=82 => "Tinggi (Q75)",
                        _ => "Ultra (Q90)",
                    };
                    let before_q = self.quality;
                    egui::ComboBox::from_id_salt("quality_select")
                        .width(106.0)
                        .selected_text(bitrate_label)
                        .show_ui(ui, |ui| {
                            ui.selectable_value(&mut self.quality, 40, "Hemat (Q40)");
                            ui.selectable_value(&mut self.quality, 60, "Sedang (Q60)");
                            ui.selectable_value(&mut self.quality, 75, "Tinggi (Q75)");
                            ui.selectable_value(&mut self.quality, 90, "Ultra (Q90)");
                        });
                    if before_q != self.quality {
                        self.sync_stream_cfg();
                    }

                    let fps_label = if self.fps_setting == 0 {
                        format!("Auto ({monitor_cap} FPS)")
                    } else {
                        format!("{} FPS", self.fps_setting.min(monitor_cap))
                    };
                    let before_fps = self.fps_setting;
                    egui::ComboBox::from_id_salt("fps_select")
                        .width(114.0)
                        .selected_text(fps_label)
                        .show_ui(ui, |ui| {
                            ui.selectable_value(
                                &mut self.fps_setting,
                                0,
                                format!("Auto ({monitor_cap} FPS)"),
                            );
                            for rate in [15_u32, 30, 60, 75, 100, 120, 144, 165, 240] {
                                if rate <= monitor_cap {
                                    ui.selectable_value(
                                        &mut self.fps_setting,
                                        rate,
                                        format!("{rate} FPS"),
                                    );
                                }
                            }
                            if ![15, 30, 60, 75, 100, 120, 144, 165, 240].contains(&monitor_cap) {
                                ui.selectable_value(
                                    &mut self.fps_setting,
                                    monitor_cap,
                                    format!("{monitor_cap} FPS (Monitor)"),
                                );
                            }
                        });
                    if before_fps != self.fps_setting {
                        self.sync_stream_cfg();
                    }

                    // Live Mic & Voice bridge directly to Capri Desktop Listening
                    ui.add_enabled_ui(
                        self.supports_windows_mouse
                            && self.grants.iter().any(|grant| grant == "pointer"),
                        |ui| {
                            egui::ComboBox::from_label("Mouse")
                                .selected_text(if self.windows_mouse {
                                    "Mouse Windows"
                                } else {
                                    "Mouse virtual"
                                })
                                .show_ui(ui, |ui| {
                                    ui.selectable_value(
                                        &mut self.windows_mouse,
                                        false,
                                        "Mouse virtual",
                                    );
                                    ui.selectable_value(
                                        &mut self.windows_mouse,
                                        true,
                                        "Mouse Windows",
                                    )
                                    .on_hover_text("Menggerakkan kursor fisik PC host.");
                                });
                        },
                    );
                    let can_hear_host = self.grants.iter().any(|grant| grant == "audio");
                    if !can_hear_host
                        && ui
                            .add_enabled(
                                self.audio_permission.is_none(),
                                egui::Button::new("Minta izin suara PC"),
                            )
                            .clicked()
                    {
                        self.ask_audio_permission(ctx);
                    }
                    if ui
                        .add_enabled(
                            can_hear_host,
                            egui::Button::new(if self.host_audio.is_some() {
                                "Matikan suara PC"
                            } else {
                                "Suara PC host"
                            }),
                        )
                        .on_disabled_hover_text("Pair ulang dan izinkan Suara PC host.")
                        .clicked()
                    {
                        if self.host_audio.is_some() {
                            self.stop_host_audio();
                        } else {
                            self.start_host_audio(ctx);
                        }
                    }
                    let mic_active = self.mic_stream.is_some();
                    let mic_btn = if mic_active {
                        egui::Button::new(
                            egui::RichText::new("Matikan Mic")
                                .size(12.5)
                                .strong()
                                .color(text_primary),
                        )
                        .fill(egui::Color32::from_rgb(185, 28, 28))
                    } else {
                        egui::Button::new(
                            egui::RichText::new("Mic Capri")
                                .size(12.5)
                                .color(text_primary),
                        )
                        .fill(egui::Color32::from_rgb(30, 58, 138))
                    };
                    if ui.add(mic_btn).clicked() {
                        if mic_active {
                            self.stop_mic();
                        } else if let Err(e) = self.start_mic(ctx) {
                            self.voice_status = e;
                        }
                    }

                    if !self.status.is_empty() {
                        ui.label(
                            egui::RichText::new(&self.status)
                                .size(12.0)
                                .color(egui::Color32::from_rgb(252, 165, 165)),
                        );
                    }

                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        let disconnect_btn = egui::Button::new(
                            egui::RichText::new("Putuskan")
                                .size(12.5)
                                .color(egui::Color32::from_rgb(254, 202, 202)),
                        )
                        .fill(egui::Color32::from_rgb(69, 26, 32));
                        if ui.add(disconnect_btn).clicked() {
                            if self.fullscreen {
                                self.fullscreen = false;
                                ctx.send_viewport_cmd(egui::ViewportCommand::Fullscreen(false));
                            }
                            if self.mic_stream.is_some() {
                                self.stop_mic();
                            }
                            self.connected = false;
                            self.audio_permission = None;
                            self.stop_host_audio();
                            self.sync_stream_cfg();
                            self.stop_workers.store(true, Ordering::Relaxed);
                            self.frame = None;
                            self.status.clear();
                        }

                        let fs_text = if self.fullscreen {
                            "Keluar Fullscreen (Esc)"
                        } else {
                            "Fullscreen (F11)"
                        };
                        if ui.button(egui::RichText::new(fs_text).size(12.5)).clicked() {
                            self.fullscreen = !self.fullscreen;
                            ctx.send_viewport_cmd(egui::ViewportCommand::Fullscreen(
                                self.fullscreen,
                            ));
                        }
                    });
                });
            });

        if !self.voice_status.is_empty() {
            egui::TopBottomPanel::bottom("voice_bar")
                .frame(
                    egui::Frame::new()
                        .fill(bg_card)
                        .stroke(egui::Stroke::new(1.0_f32, border))
                        .inner_margin(egui::Margin::symmetric(14, 6)),
                )
                .show(ctx, |ui| {
                    ui.horizontal(|ui| {
                        ui.label(
                            egui::RichText::new("Suara Capri:")
                                .size(12.0)
                                .strong()
                                .color(emerald),
                        );
                        ui.label(
                            egui::RichText::new(&self.voice_status)
                                .size(12.0)
                                .color(text_primary),
                        );
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui.small_button("Tutup").clicked() {
                                self.voice_status.clear();
                            }
                        });
                    });
                });
        }

        if ctx.input(|i| i.key_pressed(egui::Key::F11)) {
            self.fullscreen = !self.fullscreen;
            ctx.send_viewport_cmd(egui::ViewportCommand::Fullscreen(self.fullscreen));
        } else if self.fullscreen && ctx.input(|i| i.key_pressed(egui::Key::Escape)) {
            self.fullscreen = false;
            ctx.send_viewport_cmd(egui::ViewportCommand::Fullscreen(false));
        }

        egui::CentralPanel::default()
            .frame(
                egui::Frame::central_panel(&ctx.style())
                    .inner_margin(egui::Margin::same(if self.fullscreen { 2 } else { 8 })),
            )
            .show(ctx, |ui| {
                if let Some(frame) = self.frame.clone() {
                    ui.horizontal(|ui| {
                        if ui.small_button("−").on_hover_text("Zoom out").clicked() {
                            self.screen_zoom = (self.screen_zoom / 1.25).max(0.5);
                        }
                        if ui
                            .small_button(format!("{:.0}%", self.screen_zoom * 100.0))
                            .on_hover_text("Reset zoom")
                            .clicked()
                        {
                            self.screen_zoom = 1.0;
                            self.screen_center = egui::pos2(0.5, 0.5);
                        }
                        if ui.small_button("+").on_hover_text("Zoom in").clicked() {
                            self.screen_zoom = (self.screen_zoom * 1.25).min(6.0);
                        }
                        if self.screen_zoom > 1.0 {
                            ui.toggle_value(&mut self.pan_screen, "Geser tampilan")
                                .on_hover_text("Tarik dengan klik kiri. Tombol tengah juga bisa dipakai kapan saja.");
                        }
                    });
                    let avail = ui.available_size();
                    let tex = frame.size_vec2();
                    let scale = if tex.x > 0.0 && tex.y > 0.0 {
                        (avail.x / tex.x).min(avail.y / tex.y).max(0.01)
                    } else {
                        1.0
                    };
                    let display_size = tex * scale * self.screen_zoom.min(1.0);
                    let pad_x = ((avail.x - display_size.x) * 0.5).max(0.0);
                    let pad_y = ((avail.y - display_size.y) * 0.5).max(0.0);
                    ui.add_space(pad_y);
                    ui.horizontal(|ui| {
                        ui.add_space(pad_x);
                        let half = 0.5 / self.screen_zoom.max(1.0);
                        self.screen_center.x = self.screen_center.x.clamp(half, 1.0 - half);
                        self.screen_center.y = self.screen_center.y.clamp(half, 1.0 - half);
                        let uv = egui::Rect::from_center_size(
                            self.screen_center,
                            egui::Vec2::splat(2.0 * half),
                        );
                        let response = ui.add(
                            egui::Image::new(&frame)
                                .fit_to_exact_size(display_size)
                                .uv(uv)
                                .corner_radius(egui::CornerRadius::same(if self.fullscreen {
                                    0
                                } else {
                                    8
                                }))
                                .sense(egui::Sense::click_and_drag()),
                        );
                        if response.dragged_by(egui::PointerButton::Middle)
                            || (self.pan_screen && self.screen_zoom > 1.0 && response.dragged_by(egui::PointerButton::Primary))
                        {
                            let delta = ui.input(|input| input.pointer.delta());
                            self.screen_center -=
                                egui::vec2(delta.x / display_size.x, delta.y / display_size.y)
                                    * (2.0 * half);
                            return;
                        }
                        if self.pan_screen && self.screen_zoom > 1.0 {
                            return;
                        }
                        if let Some(pos) = response.hover_pos() {
                            let x = uv.left()
                                + uv.width()
                                    * ((pos.x - response.rect.left()) / response.rect.width())
                                        .clamp(0.0, 1.0);
                            let y = uv.top()
                                + uv.height()
                                    * ((pos.y - response.rect.top()) / response.rect.height())
                                        .clamp(0.0, 1.0);
                            let action = if response.double_clicked() {
                                "pointer_double_click"
                            } else if response.clicked() {
                                "pointer_click"
                            } else if response.secondary_clicked() {
                                "pointer_rclick"
                            } else if Instant::now() >= self.next_pointer {
                                self.next_pointer = Instant::now() + Duration::from_millis(16);
                                "pointer_move"
                            } else {
                                ""
                            };
                            if !action.is_empty()
                                && self.grants.iter().any(|grant| grant == "pointer")
                            {
                                self.send_input(json!({
                                    "action": action,
                                    "monitor": self.monitor,
                                    "x": x,
                                    "y": y,
                                    "full_control": true
                                }));
                            }
                            let scroll_y = ui.input(|i| i.smooth_scroll_delta.y);
                            if scroll_y.abs() >= 1.0 && self.grants.iter().any(|g| g == "pointer") {
                                self.send_input(json!({
                                    "action": "pointer_scroll",
                                    "monitor": self.monitor,
                                    "x": x,
                                    "y": y,
                                    "delta": scroll_y as i32,
                                    "full_control": true
                                }));
                            }
                            let events = ui.input(|input| input.events.clone());
                            for event in events {
                                let (action, field, value) = match event {
                                    egui::Event::Text(text)
                                        if self.grants.iter().any(|g| g == "keyboard") =>
                                    {
                                        ("pointer_text", "text", text)
                                    }
                                    egui::Event::Key {
                                        key,
                                        pressed: true,
                                        modifiers,
                                        ..
                                    } if modifiers.ctrl
                                        && self.grants.iter().any(|g| g == "shortcut") =>
                                    {
                                        let key = match key {
                                            egui::Key::A => "ctrl+a",
                                            egui::Key::C => "ctrl+c",
                                            egui::Key::V => "ctrl+v",
                                            egui::Key::Z => "ctrl+z",
                                            _ => continue,
                                        };
                                        ("pointer_hotkey", "key", key.to_owned())
                                    }
                                    egui::Event::Key {
                                        key,
                                        pressed: true,
                                        modifiers,
                                        ..
                                    } if modifiers.is_none()
                                        && self.grants.iter().any(|g| g == "keyboard") =>
                                    {
                                        let key = match key {
                                            egui::Key::Enter => "enter",
                                            egui::Key::Tab => "tab",
                                            egui::Key::Backspace => "backspace",
                                            egui::Key::Delete => "delete",
                                            egui::Key::ArrowUp => "up",
                                            egui::Key::ArrowDown => "down",
                                            egui::Key::ArrowLeft => "left",
                                            egui::Key::ArrowRight => "right",
                                            _ => continue,
                                        };
                                        ("pointer_key", "key", key.to_owned())
                                    }
                                    _ => continue,
                                };
                                let mut data = json!({
                                    "action": action,
                                    "monitor": self.monitor,
                                    "x": x,
                                    "y": y,
                                    "full_control": true
                                });
                                data[field] = json!(value);
                                self.send_input(data);
                            }
                        }
                    });
                } else {
                    ui.centered_and_justified(|ui| {
                        ui.label(
                            egui::RichText::new("Memuat tampilan layar host...")
                                .size(14.0)
                                .color(text_muted),
                        );
                    });
                }
            });
    }
}

fn main() -> eframe::Result {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([1120.0, 720.0])
            .with_min_inner_size([640.0, 460.0]),
        ..Default::default()
    };
    eframe::run_native(
        "Capri Remote",
        options,
        Box::new(|creation| {
            Remote::apply_theme(&creation.egui_ctx);
            let device_name = detect_tailscale_self_name();
            let local_hz = local_monitor_hz();
            let resolution = creation
                .storage
                .and_then(|s| s.get_string("resolution"))
                .filter(|s| matches!(s.as_str(), "540p" | "720p" | "1080p" | "native"))
                .unwrap_or_else(|| "720p".into());
            let quality = creation
                .storage
                .and_then(|s| s.get_string("quality"))
                .and_then(|s| s.parse::<u8>().ok())
                .filter(|q| (25..=95).contains(q))
                .unwrap_or(75);
            let fps_setting = creation
                .storage
                .and_then(|s| s.get_string("fps"))
                .and_then(|s| s.parse::<u32>().ok())
                .filter(|f| *f <= 500)
                .unwrap_or(0);
            let mut remote = Remote {
                endpoint: creation
                    .storage
                    .and_then(|storage| storage.get_string("host"))
                    .unwrap_or_default(),
                hosts: Vec::new(),
                device: String::new(),
                device_name,
                token: String::new(),
                monitors: Vec::new(),
                monitor: "primary".into(),
                local_hz,
                resolution,
                quality,
                fps_setting,
                fullscreen: false,
                screen_zoom: 1.0,
                screen_center: egui::pos2(0.5, 0.5),
                windows_mouse: false,
                supports_windows_mouse: false,
                pan_screen: false,
                frame: None,
                next_pointer: Instant::now(),
                status: String::new(),
                voice_status: String::new(),
                grants: Vec::new(),
                connected: false,
                agent: ureq::AgentBuilder::new()
                    .redirects(0)
                    .timeout(Duration::from_secs(6))
                    .build(),
                stream_cfg: Arc::new(Mutex::new(None)),
                stream_gen: Arc::new(AtomicU64::new(0)),
                latest_frame: None,
                input_tx: None,
                stop_workers: Arc::new(AtomicBool::new(false)),
                mic_stream: None,
                mic_stop: Arc::new(AtomicBool::new(true)),
                voice_rx: None,
                host_audio: None,
                audio_permission: None,
            };
            if !remote.endpoint.is_empty() {
                if let Err(error) = remote.connect(false, &creation.egui_ctx) {
                    remote.status = error.to_string();
                }
            }
            if !remote.connected {
                remote.discover_hosts();
            }
            Ok(Box::new(remote))
        }),
    )
}
