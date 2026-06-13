import { DestroyRef, Injectable, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class EmbedModeService {
  readonly isActive = signal(false);
  readonly isAdmin = signal(false);

  /** Call once when ?embed=1 is detected. Registers the message listener and cleans it up on destroy. */
  enable(onOverrides: (overrides: Record<string, unknown>) => void, destroyRef: DestroyRef): void {
    this.isActive.set(true);
    const handler = (event: MessageEvent) => {
      const data = event.data;
      if (!data) return;
      if (data.type === 'qwixx-options-set') {
        const overrides = data.options as Record<string, unknown>;
        if (overrides) onOverrides(overrides);
      } else if (data.type === 'qwixx-admin-status') {
        this.isAdmin.set(data.isAdmin === true);
      }
    };
    window.addEventListener('message', handler);
    destroyRef.onDestroy(() => window.removeEventListener('message', handler));
  }

  postOptions(options: Record<string, string>): void {
    window.parent.postMessage({ type: 'qwixx-options-changed', options }, '*');
  }
}
