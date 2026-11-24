import json
import sys

REMOTE_PC_GRANTS = ("screen", "pointer", "keyboard", "shortcut", "audio")

def confirm_remote_pc(client_ip):
    import tkinter as tk
    from tkinter import ttk

    root = tk.Tk()
    root.title("Capri Remote — Windows")
    root.attributes("-topmost", True)
    root.resizable(False, False)
    panel = ttk.Frame(root, padding=16)
    panel.pack(fill="both")
    ttk.Label(panel, text=f"Izinkan perangkat Tailscale {client_ip}?").pack(anchor="w")
    mode = tk.StringVar(value="view")
    custom = ttk.Frame(panel)
    choices = {
        grant: tk.BooleanVar(value=False)
        for grant in ("pointer", "keyboard", "shortcut", "audio")
    }
    for value, label in (("view", "Lihat saja"), ("full", "Full control"), ("custom", "Custom")):
        ttk.Radiobutton(panel, text=label, variable=mode, value=value, command=lambda: (
            custom.pack(anchor="w", before=buttons) if mode.get() == "custom" else custom.pack_forget()
        )).pack(anchor="w")
    for grant, label in (("pointer", "Mouse"), ("keyboard", "Keyboard"), ("shortcut", "Shortcut"), ("audio", "Suara PC host")):
        ttk.Checkbutton(custom, text=label, variable=choices[grant]).pack(anchor="w")
    grants = []

    def approve():
        grants.extend(sorted(REMOTE_PC_GRANTS) if mode.get() == "full" else ["screen"])
        if mode.get() == "custom":
            grants.extend(grant for grant, enabled in choices.items() if enabled.get())
        root.destroy()

    buttons = ttk.Frame(panel)
    buttons.pack(anchor="e", pady=(12, 0))
    ttk.Button(buttons, text="Tolak", command=root.destroy).pack(side="left")
    ttk.Button(buttons, text="Izinkan", command=approve).pack(side="left")
    root.after(35_000, root.destroy)
    root.mainloop()
    return grants



if __name__ == "__main__":
    print(json.dumps(confirm_remote_pc(sys.argv[1])))
