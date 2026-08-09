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

describe('SettingsComponent — botCount pills', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<SettingsComponent>>;

  function botCountPills(): HTMLButtonElement[] {
    return Array.from(fixture.nativeElement.querySelectorAll('#botCount .int-pill'));
  }

  beforeEach(async () => {
    fixture = await createFixture();
  });

  it('renders a horizontal pill group with exactly four pills for botCount', () => {
    expect(botCountPills()).toHaveLength(4);
  });

  it('botCount pill labels are 0, 1, 2, 3', () => {
    const texts = botCountPills().map((p) => p.textContent?.trim());
    expect(texts).toEqual(['0', '1', '2', '3']);
  });

  it('botCount form control is initialised as the number 0, not the string "0"', () => {
    const ctrl = fixture.componentInstance.form.get('botCount');
    expect(ctrl).toBeTruthy();
    expect(ctrl!.value).toBe(0);
    expect(typeof ctrl!.value).toBe('number');
  });

  it('the 0 pill is selected by default', () => {
    const pills = botCountPills();
    expect(pills[0]!.classList).toContain('selected');
    expect(pills.slice(1).some((p) => p.classList.contains('selected'))).toBe(false);
  });

  it('clicking the "2" pill updates the form control to the number 2', () => {
    botCountPills()[2]!.click();
    fixture.detectChanges();

    expect(fixture.componentInstance.form.get('botCount')!.value).toBe(2);
    expect(typeof fixture.componentInstance.form.get('botCount')!.value).toBe('number');
    expect(botCountPills()[2]!.classList).toContain('selected');
  });

  it('pills survive an additional detectChanges (simulates translate-pipe re-render)', () => {
    // In the real app a TranslateService lang change triggers an extra change-detection
    // cycle. The pills must still be present afterwards.
    fixture.detectChanges();
    expect(botCountPills()).toHaveLength(4);
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

  // Labels must render via the server-provided labelKey, not a key the client re-derives from opt.key.
  // Server keys follow "gameOption.<key>", but the client must not assume that: here labelKey is
  // deliberately unrelated to the key. In the test harness translate returns the key verbatim, so the
  // rendered text is exactly the key that was looked up.
  it('renders a label via labelKey, not a key derived from opt.key', async () => {
    const option = {
      ...makeOptions()[0],
      key: 'someOption',
      labelKey: 'gameOption.aCustomLabelKey',
      type: GameOption.TypeEnum.BOOLEAN,
      choices: [],
      adminOnly: false,
    } as GameOption;
    const fixture = await createFixture({}, [option]);

    const label = fixture.nativeElement.querySelector('label[for="someOption"]') as HTMLLabelElement;
    expect(label.textContent?.trim()).toBe('gameOption.aCustomLabelKey');
  });
});

// ── Embed mode (real GWT iframe path: ?embed=1) ───────────────────────────────
describe('SettingsComponent — botCount pills in embed mode', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<SettingsComponent>>;

  function botCountPills(): HTMLButtonElement[] {
    return Array.from(fixture.nativeElement.querySelectorAll('#botCount .int-pill'));
  }

  beforeEach(async () => {
    fixture = await createFixture({ embed: '1', lang: 'nl' });
  });

  it('embed mode: botCount still has 4 pills', () => {
    expect(botCountPills()).toHaveLength(4);
  });

  it('embed mode: pill labels are still 0, 1, 2, 3', () => {
    const texts = botCountPills().map((p) => p.textContent?.trim());
    expect(texts).toEqual(['0', '1', '2', '3']);
  });

  it('embed mode: botCount form control is the number 0', () => {
    const ctrl = fixture.componentInstance.form.get('botCount');
    expect(typeof ctrl!.value).toBe('number');
    expect(ctrl!.value).toBe(0);
  });
});

describe('SettingsComponent — mutual exclusion with multiple partners', () => {
  function bool(key: string, incompatibleWith: string[]): GameOption {
    return {
      key,
      labelKey: 'gameOption.' + key,
      type: GameOption.TypeEnum.BOOLEAN,
      defaultValue: 'false',
      choices: [],
      adminOnly: false,
      incompatibleWith,
    };
  }

  it('keeps an incompatible option disabled even when it has multiple exclusion partners', async () => {
    const fixture = await createFixture({}, [
      ...makeOptions(),
      bool('doubleA', ['doubleB', 'bigPoints']),
      bool('doubleB', ['doubleA', 'bigPoints']),
      bool('bigPoints', ['doubleA', 'doubleB']),
    ]);
    const form = fixture.componentInstance.form;

    form.get('doubleA')!.setValue(true);
    fixture.detectChanges();

    // Regression: doubleB must be OFF and DISABLED. A naive per-pair pass re-enabled it via the
    // doubleB/bigPoints pair, letting the user pick doubleA + doubleB (which the server then rejects).
    expect(form.get('doubleB')!.value).toBe(false);
    expect(form.get('doubleB')!.disabled).toBe(true);
    expect(form.get('bigPoints')!.disabled).toBe(true);

    // Clearing doubleA re-enables both partners.
    form.get('doubleA')!.setValue(false);
    fixture.detectChanges();
    expect(form.get('doubleB')!.disabled).toBe(false);
    expect(form.get('bigPoints')!.disabled).toBe(false);
  });
});

describe('SettingsComponent — botCount range in restart mode', () => {
  function botCountOpt(): GameOption {
    return makeOptions().find((o) => o.key === 'botCount')!;
  }

  // Regression: after a 1-human + 2-bot game, "start a new game" must let the human pick a fresh
  // set of bots. The finished game's bots are not humans and must not consume seats, so with a
  // single human in the lobby the full 0..3 range stays selectable (previous value 2 included).
  it('keeps the full bot range with a single human — bots do not count as seats', async () => {
    const fixture = await createFixture();
    const c = fixture.componentInstance;
    c.sessionId.set('sess-1');
    c.playerId.set('me');
    c.lobbyPlayers.set([{ id: 'me', name: 'Alice' }]);

    expect(c.isRestartMode).toBe(true);
    expect(c.effectiveMax(botCountOpt())).toBe(3); // not 0, and capped by the option's own max
    expect(c.integerRange(botCountOpt())).toEqual([0, 1, 2, 3]); // 2 is still selectable/editable
  });

  it('shrinks the bot range as humans fill the table, keeping total players ≤ 5', async () => {
    const fixture = await createFixture();
    const c = fixture.componentInstance;
    c.sessionId.set('sess-1');
    c.playerId.set('me');
    c.lobbyPlayers.set([
      { id: 'a', name: 'A' },
      { id: 'b', name: 'B' },
      { id: 'c', name: 'C' },
      { id: 'd', name: 'D' },
    ]);

    expect(c.effectiveMax(botCountOpt())).toBe(1); // 5 total − 4 humans
    expect(c.integerRange(botCountOpt())).toEqual([0, 1]);
  });
});

describe('SettingsComponent — Variant chip toggle', () => {
  it('renders the Variant enum as two chips (no dropdown) and selecting one updates the form', async () => {
    const fixture = await createFixture();
    const el: HTMLElement = fixture.nativeElement;
    const [standard, longo] = Array.from(el.querySelectorAll<HTMLButtonElement>('.variant-chip'));

    expect(standard && longo).toBeTruthy(); // exactly two chips, STANDARD | LONGO side by side
    expect(el.querySelector('select#base')).toBeNull(); // the dropdown is replaced

    const form = fixture.componentInstance.form;
    expect(form.get('base')!.value).toBe('STANDARD');
    expect(standard!.classList.contains('selected')).toBe(true);

    longo!.click();
    fixture.detectChanges();

    expect(form.get('base')!.value).toBe('LONGO');
    expect(longo!.classList.contains('selected')).toBe(true);
    expect(standard!.classList.contains('selected')).toBe(false);
  });
});

/**
 * The "random game" button rerolls the sheet variant only. These use a variant-rich option
 * set — makeOptions() above deliberately has no mode toggles — including two mutually
 * exclusive pairs so the exclusion wiring is exercised by the reroll.
 */
const MODE_KEYS = ['bigPoints', 'randomOrder', 'xChange', 'luckyNumber', 'doubleA', 'doubleB'];

function variantOptions(): GameOption[] {
  const mode = (key: string, incompatibleWith: string[] = []): GameOption =>
    ({
      key,
      labelKey: `gameOption.${key}`,
      type: GameOption.TypeEnum.BOOLEAN,
      defaultValue: 'false',
      adminOnly: false,
      incompatibleWith,
    }) as GameOption;

  return [
    {
      key: 'base',
      labelKey: 'gameOption.base',
      type: GameOption.TypeEnum.ENUM,
      defaultValue: 'STANDARD',
      choices: ['STANDARD', 'LONGO'],
      adminOnly: false,
      incompatibleWith: [],
    } as GameOption,
    {
      key: 'seeOtherCards',
      labelKey: 'gameOption.seeOtherCards',
      type: GameOption.TypeEnum.BOOLEAN,
      defaultValue: 'true',
      category: GameOption.CategoryEnum.GENERAL,
      adminOnly: false,
      incompatibleWith: [],
    } as GameOption,
    {
      key: 'botCount',
      labelKey: 'gameOption.botCount',
      type: GameOption.TypeEnum.INTEGER,
      defaultValue: '0',
      minValue: 0,
      maxValue: 3,
      category: GameOption.CategoryEnum.GENERAL,
      adminOnly: false,
      incompatibleWith: [],
    } as GameOption,
    mode('bigPoints', ['randomOrder']),
    mode('randomOrder', ['bigPoints']),
    mode('xChange'),
    mode('luckyNumber'),
    mode('doubleA', ['doubleB']),
    mode('doubleB', ['doubleA']),
  ];
}

describe('SettingsComponent — random game button', () => {
  it('enables exactly one extra mode and clears the others', async () => {
    const fixture = await createFixture({}, variantOptions());
    const component = fixture.componentInstance;

    // Start with modes already on so the reroll has to clear previous picks rather than
    // stack on top of them.
    component.form.get('xChange')!.setValue(true);
    component.form.get('luckyNumber')!.setValue(true);

    // randomizeVariant() draws from Math.random, so loop rather than stub: the invariant
    // must hold for every possible draw, not one lucky one.
    for (let roll = 0; roll < 25; roll++) {
      component.randomizeVariant();

      // getRawValue, not value: Angular omits disabled controls from form.value, but
      // buildGameOptions() sends getRawValue() to the server — so a mode left true while
      // disabled by an exclusion would still reach the game and must be counted here.
      const raw = component.form.getRawValue();
      const modesOn = MODE_KEYS.filter((key) => raw[key] === true);

      expect(modesOn, `roll ${roll} enabled [${modesOn.join(', ')}]`).toHaveLength(1);
    }
  });

  it('leaves general settings untouched', async () => {
    const fixture = await createFixture({}, variantOptions());
    const component = fixture.componentInstance;
    const generalKeys = variantOptions()
      .filter((o) => o.category === GameOption.CategoryEnum.GENERAL)
      .map((o) => o.key);
    const generalValues = () => generalKeys.map((key) => component.form.getRawValue()[key]);

    // Run from both values of seeOtherCards. Starting `true` catches a reroll that clears
    // every boolean it can find; starting `false` catches one that treats a general option
    // as an extra mode and switches it on. Either would rewrite a setting the player chose.
    for (const seeOtherCards of [true, false]) {
      component.form.get('seeOtherCards')!.setValue(seeOtherCards);
      component.form.get('botCount')!.setValue(3);
      const before = generalValues();

      for (let roll = 0; roll < 25; roll++) {
        component.randomizeVariant();
        expect(generalValues(), `roll ${roll} changed a general setting`).toEqual(before);
      }
    }
  });
});
