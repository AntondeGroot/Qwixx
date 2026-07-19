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

    // Handshake: tell the host we're now listening so it (re)sends state such as
    // the admin status. Without this, the host's on-load post can arrive before
    // Angular has bootstrapped and registered the listener above, and be lost.
    // Target origin is '*' by necessity: the host origin is unknown until it replies, and this
    // ping (and the options broadcast below) carry only non-sensitive lobby UI state.
    // eslint-disable-next-line sonarjs/post-message
    window.parent.postMessage({ type: 'qwixx-embed-ready' }, '*');
  }

  postOptions(options: Record<string, string>): void {
    // eslint-disable-next-line sonarjs/post-message -- non-sensitive UI options to the embed host (see enable())
    window.parent.postMessage({ type: 'qwixx-options-changed', options }, '*');
  }
}
