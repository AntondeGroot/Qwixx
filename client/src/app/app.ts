import { Component, inject, OnInit, signal } from '@angular/core';
import { NavigationEnd, RouterOutlet, Router } from '@angular/router';
import { Location } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';
import { filter, map, take } from 'rxjs/operators';
import { RULES_COOKIE } from './rules/rules-version';
import { LanguageSelectorComponent } from './language-selector/language-selector.component';
import { RowClosureModalComponent } from './row-closure-modal/row-closure-modal.component';
import { RowClosureModalService } from './services/row-closure-modal.service';
import { TranslationService } from './services/translation.service';
import { RoomService } from './services/room.service';
import { TranslateModule } from '@ngx-translate/core';
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, LanguageSelectorComponent, RowClosureModalComponent, TranslateModule],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  private readonly translationService = inject(TranslationService);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  readonly modal = inject(RowClosureModalService);
  readonly roomService = inject(RoomService);
  readonly showExitConfirm = signal(false);

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

  confirmExit() {
    this.showExitConfirm.set(false);
    this.roomService.exit();
  }
}
