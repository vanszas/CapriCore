# Capri Remote for Windows

Connect both PCs to the same Tailscale network. On the host, open Capri Settings
→ Agent Remote and select **Pair Windows**. This starts the shared gateway in
Tailscale mode and shows the host IPv4 address.

Open Capri Remote on the other PC, select the host from the active Tailscale
account's online Windows peers, and select **Hubungkan**. If needed, enter
the host IPv4 address under **Masukkan alamat manual**.
The host chooses **Lihat saja**, **Full control**, or **Custom** and approves
the device. Windows Credential Manager stores its credential for reconnecting.
Choose a monitor above the preview.

Android uses its existing QR pairing with the same gateway.

Build from the repository root:

```powershell
cargo build --release --locked --manifest-path apps/pc-remote/Cargo.toml
```
