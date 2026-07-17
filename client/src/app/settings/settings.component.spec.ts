import { TestBed } from '@angular/core/testing';
import { provideRouter, ActivatedRoute } from '@angular/router';
import { of } from 'rxjs';
import { provideTranslateService } from '@ngx-translate/core';
import { GamesService, PlayersService } from '../../generated/api/api';
import { LobbyService } from '../services/lobby.service';
import { GameOption } from '../../generated/model/models';
import { SettingsComponent } from './settings.component';

function makeOptions(): GameOption[] {
  return [
    {
      key: 'base',
      labelKey: 'gameOption.base',
      type: GameOption.TypeEnum.ENUM,
      defaultValue: 'STANDARD',
      choices: ['STANDARD', 'LONGO'],
      adminOnly: false,
      incompatibleWith: [],
    },
    {
      key: 'botCount',
      labelKey: 'gameOption.botCount',
      type: GameOption.TypeEnum.INTEGER,
      defaultValue: '0',
      minValue: 0,
      maxValue: 3,
      adminOnly: false,
      incompatibleWith: [],
    },
    {
      key: 'botStrategy',
      labelKey: 'gameOption.botStrategy',
      type: GameOption.TypeEnum.ENUM,
      defaultValue: 'BALANCED',
      choices: ['UNTRAINED', 'MOST_POINTS', 'MOST_WINS', 'BALANCED'],
      adminOnly: false,
      incompatibleWith: [],
    },
  ];
}

function createFixture(queryParams: Record<string, string> = {}, options: GameOption[] = makeOptions()) {
  const gamesServiceMock = {
    getGameOptions: () => of(options) as any,
    previewLayout: () => of(null) as any,
  };

  return TestBed.configureTestingModule({
    imports: [SettingsComponent],
    providers: [
      provideRouter([]),
      provideTranslateService({ fallbackLang: 'en' }),
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { queryParamMap: { get: (k: string) => queryParams[k] ?? null } } },
      },
      { provide: GamesService, useValue: gamesServiceMock },
      { provide: PlayersService, useValue: {} },
      { provide: LobbyService, useValue: {} },
    ],
  })
    .compileComponents()
    .then(() => {
      const f = TestBed.createComponent(SettingsComponent);
      f.detectChanges(); // ngOnInit → of() synchronous → options loaded
      f.detectChanges(); // re-render with populated availableGameOptions
      return f;
    });
}

describe('SettingsComponent — botCount select', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<SettingsComponent>>;

  function botCountSelect(): HTMLSelectElement | undefined {
    return fixture.nativeElement.querySelector('select#botCount') ?? undefined;
  }

  beforeEach(async () => {
    fixture = await createFixture();
  });

  it('renders a <select> with exactly four options for botCount', () => {
    const sel = botCountSelect();
    expect(sel).toBeTruthy();
    expect(sel!.options.length).toBe(4);
  });

  it('botCount option labels are 0, 1, 2, 3', () => {
    const texts = Array.from(botCountSelect()!.options).map((o) => o.textContent?.trim());
    expect(texts).toEqual(['0', '1', '2', '3']);
  });

  it('botCount form control is initialised as the number 0, not the string "0"', () => {
    const ctrl = fixture.componentInstance.form.get('botCount');
    expect(ctrl).toBeTruthy();
    expect(ctrl!.value).toBe(0);
    expect(typeof ctrl!.value).toBe('number');
  });

  it('option 0 is selected by default', () => {
    expect(botCountSelect()!.selectedIndex).toBe(0);
  });

  it('changing selection to index 2 updates the form control to the number 2', () => {
    const sel = botCountSelect()!;
    sel.selectedIndex = 2;
    sel.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(Number(fixture.componentInstance.form.get('botCount')!.value)).toBe(2);
  });

  it('options survive an additional detectChanges (simulates translate-pipe re-render)', () => {
    // In the real app a TranslateService lang change triggers an extra change-detection
    // cycle. The options must still be present afterwards.
    fixture.detectChanges();
    expect(botCountSelect()!.options.length).toBe(4);
  });
});

/**
 * The server stops sending adminOnly once the trial variants are released (see AdminOptionRelease on
 * the server). These pin the client half of that contract: what a non-admin may pick is decided
 * purely by the flag the server sends, so the release needs no client change to take effect.
 */
describe('SettingsComponent — admin-only options', () => {
  const optionKeys = async (over: Partial<GameOption>, isAdmin: boolean): Promise<string[]> => {
    const trial = { ...makeOptions()[0], key: 'bonusB', labelKey: 'gameOption.bonusB', ...over } as GameOption;
    const fixture = await createFixture({}, [trial]);
    fixture.componentInstance.embedMode.isAdmin.set(isAdmin);
    return fixture.componentInstance.availableGameOptions().map((o) => o.key);
  };

  it('hides an adminOnly option from a non-admin', async () => {
    expect(await optionKeys({ adminOnly: true }, false)).not.toContain('bonusB');
  });

  it('shows an adminOnly option to an admin', async () => {
    expect(await optionKeys({ adminOnly: true }, true)).toContain('bonusB');
  });

  it('shows the option to everyone once the server stops flagging it — the release path', async () => {
    // A released option arrives with adminOnly absent entirely, not false.
    expect(await optionKeys({ adminOnly: undefined }, false)).toContain('bonusB');
  });

  it('shows an explicitly non-admin option to a non-admin', async () => {
    expect(await optionKeys({ adminOnly: false }, false)).toContain('bonusB');
  });
});

// ── Embed mode (real GWT iframe path: ?embed=1) ───────────────────────────────
describe('SettingsComponent — botCount select in embed mode', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<SettingsComponent>>;

  function botCountSelect(): HTMLSelectElement | undefined {
    return fixture.nativeElement.querySelector('select#botCount') ?? undefined;
  }

  beforeEach(async () => {
    fixture = await createFixture({ embed: '1', lang: 'nl' });
  });

  it('embed mode: botCount select still has 4 options', () => {
    expect(botCountSelect()).toBeTruthy();
    expect(botCountSelect()!.options.length).toBe(4);
  });

  it('embed mode: option labels are still 0, 1, 2, 3', () => {
    const texts = Array.from(botCountSelect()!.options).map((o) => o.textContent?.trim());
    expect(texts).toEqual(['0', '1', '2', '3']);
  });

  it('embed mode: botCount form control is the number 0', () => {
    const ctrl = fixture.componentInstance.form.get('botCount');
    expect(typeof ctrl!.value).toBe('number');
    expect(ctrl!.value).toBe(0);
  });
});
