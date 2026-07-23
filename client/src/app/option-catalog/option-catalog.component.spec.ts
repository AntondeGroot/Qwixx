import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { GamesService } from '../../generated/api/api';
import { GameOption, SheetLayout } from '../../generated/model/models';
import { OptionCatalogComponent } from './option-catalog.component';

function opt(key: string, type: GameOption.TypeEnum, category: GameOption.CategoryEnum): GameOption {
  return {
    key,
    labelKey: `gameOption.${key}`,
    type,
    defaultValue: type === GameOption.TypeEnum.BOOLEAN ? 'false' : 'STANDARD',
    choices: [],
    adminOnly: false,
    incompatibleWith: [],
    category,
  };
}

const EMPTY_LAYOUT: SheetLayout = { rows: [] };

function setup(options: GameOption[]) {
  const previewLayout = vi.fn().mockReturnValue(of(EMPTY_LAYOUT));
  const gamesServiceMock = {
    getGameOptions: () => of(options),
    previewLayout,
  };
  TestBed.configureTestingModule({
    imports: [OptionCatalogComponent],
    providers: [{ provide: GamesService, useValue: gamesServiceMock }],
  });
  const fixture = TestBed.createComponent(OptionCatalogComponent);
  fixture.detectChanges();
  return { fixture, previewLayout };
}

describe('OptionCatalogComponent', () => {
  it('lists only the sheet-changing (MODE) options, skipping GENERAL ones', () => {
    const { fixture } = setup([
      opt('base', GameOption.TypeEnum.ENUM, GameOption.CategoryEnum.MODE),
      opt('doubleA', GameOption.TypeEnum.BOOLEAN, GameOption.CategoryEnum.MODE),
      opt('seeOtherCards', GameOption.TypeEnum.BOOLEAN, GameOption.CategoryEnum.GENERAL),
    ]);
    const keys = fixture.componentInstance.items().map((i) => i.key);
    expect(keys).toEqual(['base', 'doubleA']);
    expect(fixture.componentInstance.ready()).toBe(true);
  });

  it('requests LONGO for base and toggles the flag on for other variants', () => {
    const { previewLayout } = setup([
      opt('base', GameOption.TypeEnum.ENUM, GameOption.CategoryEnum.MODE),
      opt('bigPoints', GameOption.TypeEnum.BOOLEAN, GameOption.CategoryEnum.MODE),
    ]);
    expect(previewLayout).toHaveBeenCalledWith({ base: 'LONGO' });
    expect(previewLayout).toHaveBeenCalledWith({ base: 'STANDARD', bigPoints: true });
  });

  it('maps doubleA/doubleB to the row double-variant', () => {
    const { fixture } = setup([
      opt('doubleA', GameOption.TypeEnum.BOOLEAN, GameOption.CategoryEnum.MODE),
      opt('doubleB', GameOption.TypeEnum.BOOLEAN, GameOption.CategoryEnum.MODE),
      opt('bigPoints', GameOption.TypeEnum.BOOLEAN, GameOption.CategoryEnum.MODE),
    ]);
    const variants = Object.fromEntries(fixture.componentInstance.items().map((i) => [i.key, i.doubleVariant]));
    expect(variants['doubleA']).toBe('A');
    expect(variants['doubleB']).toBe('B');
    expect(variants['bigPoints']).toBeNull();
  });
});
