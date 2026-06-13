import { Component, DestroyRef, inject, OnInit } from '@angular/core';
import { NavigationEnd, RouterOutlet, Router } from '@angular/router';
import { Location } from '@angular/common';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { filter, map, take } from 'rxjs/operators';
import { RULES_COOKIE } from './rules/rules-version';
import { LanguageSelectorComponent } from './language-selector/language-selector.component';
import { RowClosureModalComponent } from './row-closure-modal/row-closure-modal.component';
import { RowClosureModalService } from './services/row-closure-modal.service';
import { TranslationService } from './services/translation.service';
import { ExitConfirmService } from './services/exit-confirm.service';
import { RoomService } from './services/room.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, LanguageSelectorComponent, RowClosureModalComponent],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  private readonly translationService = inject(TranslationService);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly destroyRef = inject(DestroyRef);
  readonly modal = inject(RowClosureModalService);
  readonly roomService = inject(RoomService);
  readonly exitConfirm = inject(ExitConfirmService);

  readonly isOnRules = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map(() => this.router.url.startsWith('/rules')),
    ),
    { initialValue: this.router.url.startsWith('/rules') },
  );

  readonly isOnGame = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map(() => this.router.url.startsWith('/game/')),
    ),
    { initialValue: this.router.url.startsWith('/game/') },
  );

  ngOnInit() {
    this.translationService.initializeLanguage();

    // First-time players have not seen the rules yet — redirect them automatically.
    if (!this.hasSeenRules()) {
      this.router.events
        .pipe(
          filter((e) => e instanceof NavigationEnd),
          take(1),
        )
        .subscribe((e) => {
          const url = (e as NavigationEnd).urlAfterRedirects;
          if (url.startsWith('/game/') || url.startsWith('/settings')) {
            void this.router.navigate(['/rules'], { queryParams: { return: url } });
          }
        });
    }

    this.setupBackButtonGuard();
  }

  private setupBackButtonGuard(): void {
    // Push a phantom history entry (same URL) whenever the player lands on a game route
    // that has an associated lobby room.  The phantom sits on top of the real history so
    // a back-button press consumes the phantom instead of leaving the game.
    let phantomPushed = false;

    const pushPhantom = () => {
      history.pushState({ gameGuard: true }, '');
      phantomPushed = true;
    };

    // Cover the case where the app is opened directly on a game URL.
    if (this.router.url.startsWith('/game/') && this.roomService.roomId()) {
      pushPhantom();
    }

    // Cover in-app navigation to the game page.
    this.router.events
      .pipe(
        filter((e) => e instanceof NavigationEnd),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        const onGame = this.router.url.startsWith('/game/') && !!this.roomService.roomId();
        if (onGame && !phantomPushed) {
          pushPhantom();
        } else if (!onGame) {
          phantomPushed = false;
        }
      });

    // When the phantom is consumed by a back press the URL doesn't change (same URL),
    // so Angular's router never fires — we catch it here instead.
    const onPopState = (event: PopStateEvent) => {
      if ((event.state as { gameGuard?: boolean } | null)?.gameGuard) {
        // Navigating between our own phantoms — ignore.
        return;
      }
      if (this.router.url.startsWith('/game/') && this.roomService.roomId()) {
        // Re-establish the phantom so the next back press is also intercepted,
        // even if the dialog is already open (e.g. double-tap on the back button).
        pushPhantom();
        if (!this.exitConfirm.visible()) {
          void this.exitConfirm.prompt();
        }
      }
    };

    window.addEventListener('popstate', onPopState);
    this.destroyRef.onDestroy(() => window.removeEventListener('popstate', onPopState));
  }

  private hasSeenRules(): boolean {
    return document.cookie.split(';').some((c) => c.trim().startsWith(RULES_COOKIE + '='));
  }

  openRules() {
    if (this.router.url.startsWith('/rules')) {
      this.location.back();
    } else {
      void this.router.navigate(['/rules']);
    }
  }

  t(key: string): string {
    return this.translationService.instant(key);
  }
}
