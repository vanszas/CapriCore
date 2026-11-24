// SPDX-License-Identifier: GPL-3.0-only
/** A live local-only control. Slider changes are coalesced and never restart a model. */
export class LocalLoadControls {
  constructor({ api, ui, value, onLayout = () => {} }) {
    Object.assign(this, { api, ui, onLayout });
    this.value = value;
    this.element = document.createElement('fieldset');
    this.element.className = 'inference-load-controls';
    this.element.setAttribute('role', 'group');
    const legend = document.createElement('legend');
    legend.textContent = ui.text('Local model load');
    const label = document.createElement('label');
    this.enabled = document.createElement('input');
    this.enabled.id = 'local-load-enabled';
    this.enabled.type = 'checkbox';
    this.enabled.checked = value.enabled;
    label.append(this.enabled, document.createTextNode(ui.text('Limit CPU and GPU load')));
    const target = document.createElement('label');
    target.htmlFor = 'local-load-percent';
    target.textContent = ui.text('Target load');
    this.output = document.createElement('output');
    target.append(this.output);
    this.range = document.createElement('input');
    Object.assign(this.range, {
      id: 'local-load-percent',
      type: 'range',
      min: '10',
      max: '100',
      step: '5',
      value: String(value.percent),
    });
    const idleLabel = document.createElement('label');
    this.idleUnloadEnabled = document.createElement('input');
    this.idleUnloadEnabled.id = 'local-idle-unload-enabled';
    this.idleUnloadEnabled.type = 'checkbox';
    this.idleUnloadEnabled.checked = value.idleUnloadEnabled !== false;
    idleLabel.append(
      this.idleUnloadEnabled,
      document.createTextNode(ui.text('Auto-unload voice & local VRAM when idle')),
    );
    const idleTarget = document.createElement('label');
    idleTarget.htmlFor = 'local-idle-unload-minutes';
    idleTarget.textContent = ui.text('Idle unload timeout ');
    this.idleUnloadOutput = document.createElement('output');
    idleTarget.append(this.idleUnloadOutput);
    this.idleUnloadRange = document.createElement('input');
    Object.assign(this.idleUnloadRange, {
      id: 'local-idle-unload-minutes',
      type: 'range',
      min: '1',
      max: '60',
      step: '1',
      value: String(value.idleUnloadMinutes ?? 10),
    });
    this.status = document.createElement('p');
    this.status.className = 'inference-load-status';
    this.status.setAttribute('role', 'status');
    const help = document.createElement('p');
    help.textContent = ui.text(
      'CPU quota and adaptive GPU target. Brief GPU peaks can exceed the target. Lower limits slow replies. Idle auto-unload frees voice and Ollama VRAM after inactivity.',
    );
    this.element.append(
      legend,
      label,
      target,
      this.range,
      idleLabel,
      idleTarget,
      this.idleUnloadRange,
      help,
      this.status,
    );
    this.enabled.onchange = () => {
      this.sync();
      this.queue(true);
    };
    this.range.oninput = () => {
      this.sync();
      this.queue();
    };
    this.range.onchange = () => this.queue(true);
    this.idleUnloadEnabled.onchange = () => {
      this.sync();
      this.queue(true);
    };
    this.idleUnloadRange.oninput = () => {
      this.sync();
      this.queue();
    };
    this.idleUnloadRange.onchange = () => this.queue(true);
    this.sync();
  }
  sync() {
    this.enabled.disabled = !this.value.supported;
    this.range.disabled = !this.enabled.checked || !this.value.supported;
    this.output.textContent = this.range.value + '%';
    this.range.setAttribute('aria-valuetext', this.output.textContent);
    this.idleUnloadRange.disabled = !this.idleUnloadEnabled.checked;
    this.idleUnloadOutput.textContent = `${this.idleUnloadRange.value} min`;
    this.idleUnloadRange.setAttribute('aria-valuetext', this.idleUnloadOutput.textContent);
    this.status.textContent = this.ui.text(
      this.value.warning ??
        (!this.value.supported
          ? 'Load control requires Windows.'
          : this.value.state === 'unavailable'
            ? 'Local model load control is unavailable.'
            : this.value.state === 'active' && !this.value.gpuAvailable
              ? 'GPU usage is unavailable. CPU limit only.'
              : 'Applies only to Capri’s managed local models.'),
    );
  }
  queue(immediate = false) {
    this.pending = {
      enabled: this.enabled.checked,
      percent: this.range.valueAsNumber,
      idleUnloadEnabled: this.idleUnloadEnabled.checked,
      idleUnloadMinutes: this.idleUnloadRange.valueAsNumber,
    };
    clearTimeout(this.timer);
    if (immediate) void this.save();
    else this.timer = setTimeout(() => this.save(), 200);
  }
  async save() {
    if (this.saving || !this.pending) return;
    const value = this.pending;
    this.pending = null;
    this.saving = true;
    try {
      this.value = await this.api.setLocalModelLoad(value);
      this.sync();
    } catch {
      this.status.textContent = this.ui.text('Could not save load limit. Try again.');
    } finally {
      this.saving = false;
      this.onLayout();
      if (this.pending) void this.save();
    }
  }
  flush() {
    clearTimeout(this.timer);
    if (this.pending) void this.save();
  }
}
