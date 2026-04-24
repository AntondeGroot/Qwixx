import { TestBed } from '@angular/core/testing';
import { PlayerListComponent } from './player-list.component';

const P1 = { id: 'p1', name: 'Alice' };
const P2 = { id: 'p2', name: 'Bob Smith' };
const P3 = { id: 'p3', name: 'Charlie' };

describe('PlayerListComponent', () => {
  let component: PlayerListComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [PlayerListComponent] }).compileComponents();
    const fixture = TestBed.createComponent(PlayerListComponent);
    component = fixture.componentInstance;
    TestBed.runInInjectionContext(() => {
      (component as any).players    = () => [P1, P2, P3];
      (component as any).myPlayerId = () => 'p1';
    });
  });

  describe('otherPlayers', () => {
    it('excludes the current player', () => {
      const ids = component.otherPlayers().map(p => p.id);
      expect(ids).not.toContain('p1');
      expect(ids).toContain('p2');
      expect(ids).toContain('p3');
    });

    it('returns empty when only one player', () => {
      TestBed.runInInjectionContext(() => {
        (component as any).players = () => [P1];
      });
      expect(component.otherPlayers()).toHaveLength(0);
    });
  });

  describe('isActive', () => {
    it('returns true for the active player', () => {
      TestBed.runInInjectionContext(() => {
        (component as any).activePlayerId = () => 'p2';
      });
      expect(component.isActive('p2')).toBe(true);
    });

    it('returns false for non-active player', () => {
      TestBed.runInInjectionContext(() => {
        (component as any).activePlayerId = () => 'p2';
      });
      expect(component.isActive('p3')).toBe(false);
    });

    it('returns false when activePlayerId is null', () => {
      TestBed.runInInjectionContext(() => {
        (component as any).activePlayerId = () => null;
      });
      expect(component.isActive('p1')).toBe(false);
    });
  });

  describe('initials', () => {
    it('returns first letter of single-word name', () => {
      expect(component.initials('Alice')).toBe('A');
    });

    it('returns first letters of two-word name', () => {
      expect(component.initials('Bob Smith')).toBe('BS');
    });

    it('caps at 2 characters for long names', () => {
      expect(component.initials('Anne Marie Jones')).toHaveLength(2);
    });

    it('returns uppercase', () => {
      expect(component.initials('alice')).toBe('A');
    });
  });

  describe('isRowClosed', () => {
    it('returns true when row is in closedRows', () => {
      TestBed.runInInjectionContext(() => {
        (component as any).closedRows = () => ({ 'row-1': 'p1' });
      });
      expect(component.isRowClosed('row-1')).toBe(true);
    });

    it('returns false when row is not closed', () => {
      TestBed.runInInjectionContext(() => {
        (component as any).closedRows = () => ({});
      });
      expect(component.isRowClosed('row-1')).toBe(false);
    });
  });

  describe('isCrossed', () => {
    it('returns true when cell is in crossedCells', () => {
      TestBed.runInInjectionContext(() => {
        (component as any).sheetProgress = () => ({
          p2: { rowStates: { 'r1': { crossedCells: ['c1'], lockCrossed: false } }, punishments: 0 },
        });
      });
      expect(component.isCrossed('p2', 'r1', 'c1')).toBe(true);
    });

    it('returns false when cell is not crossed', () => {
      TestBed.runInInjectionContext(() => {
        (component as any).sheetProgress = () => ({
          p2: { rowStates: { 'r1': { crossedCells: [], lockCrossed: false } }, punishments: 0 },
        });
      });
      expect(component.isCrossed('p2', 'r1', 'c1')).toBe(false);
    });

    it('returns false when player has no progress', () => {
      TestBed.runInInjectionContext(() => {
        (component as any).sheetProgress = () => ({});
      });
      expect(component.isCrossed('p2', 'r1', 'c1')).toBe(false);
    });
  });
});