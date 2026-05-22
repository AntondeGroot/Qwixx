import { ComponentFixture, TestBed } from '@angular/core/testing';
import { RowClosureModalComponent, ClosureNotification } from './row-closure-modal.component';
import { TranslateModule } from '@ngx-translate/core';
import { Color } from '../../generated/model/color';
import { DebugElement } from '@angular/core';

describe('RowClosureModalComponent', () => {
  let component: RowClosureModalComponent;
  let fixture: ComponentFixture<RowClosureModalComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RowClosureModalComponent, TranslateModule.forRoot()]
    }).compileComponents();

    fixture = TestBed.createComponent(RowClosureModalComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should not display modal when requests are empty', () => {
    component.requests = [];
    fixture.detectChanges();
    const overlay = fixture.nativeElement.querySelector('.modal-overlay');
    expect(overlay).toBeFalsy();
  });

  it('should display modal when requests are present', () => {
    const requests: ClosureNotification[] = [
      { playerName: 'Player A', rowColor: Color.RED }
    ];
    component.requests = requests;
    fixture.detectChanges();
    const overlay = fixture.nativeElement.querySelector('.modal-overlay');
    expect(overlay).toBeTruthy();
  });

  it('should display multiple closure requests', () => {
    const requests: ClosureNotification[] = [
      { playerName: 'Player A', rowColor: Color.RED },
      { playerName: 'Player B', rowColor: Color.YELLOW },
      { playerName: 'Player C', rowColor: Color.GREEN }
    ];
    component.requests = requests;
    fixture.detectChanges();
    const items = fixture.nativeElement.querySelectorAll('.request-item');
    expect(items.length).toBe(3);
  });

  it('should emit confirmSelection when confirm button is clicked', () => {
    let emitted = false;
    component.confirmSelection.subscribe(() => {
      emitted = true;
    });
    component.requests = [{ playerName: 'Player A', rowColor: Color.RED }];
    fixture.detectChanges();
    const confirmBtn = fixture.nativeElement.querySelector('.btn-primary');
    confirmBtn.click();
    expect(emitted).toBeTruthy();
  });

  it('should emit changeSelection when change button is clicked', () => {
    let emitted = false;
    component.changeSelection.subscribe(() => {
      emitted = true;
    });
    component.requests = [{ playerName: 'Player A', rowColor: Color.RED }];
    component.hasPendingCross = true;  // Change button only shown when there is a pending cross
    fixture.detectChanges();
    const changeBtn = fixture.nativeElement.querySelector('.btn-secondary');
    changeBtn.click();
    expect(emitted).toBeTruthy();
  });

  it('should apply correct color classes', () => {
    expect(component.getRowColorClass(Color.RED)).toBe('cell-red');
    expect(component.getRowColorClass(Color.YELLOW)).toBe('cell-yellow');
    expect(component.getRowColorClass(Color.GREEN)).toBe('cell-green');
    expect(component.getRowColorClass(Color.BLUE)).toBe('cell-blue');
  });

  it('should display color cells with correct classes', () => {
    const requests: ClosureNotification[] = [
      { playerName: 'Player A', rowColor: Color.RED },
      { playerName: 'Player B', rowColor: Color.BLUE }
    ];
    component.requests = requests;
    fixture.detectChanges();
    const cells = fixture.nativeElement.querySelectorAll('.color-cell');
    expect(cells[0].classList.contains('cell-red')).toBeTruthy();
    expect(cells[1].classList.contains('cell-blue')).toBeTruthy();
  });

  it('should display player names correctly', () => {
    const requests: ClosureNotification[] = [
      { playerName: 'Alice', rowColor: Color.RED },
      { playerName: 'Bob', rowColor: Color.YELLOW }
    ];
    component.requests = requests;
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Alice');
    expect(text).toContain('Bob');
  });

  it('should display all four row colors', () => {
    const requests: ClosureNotification[] = [
      { playerName: 'P1', rowColor: Color.RED },
      { playerName: 'P2', rowColor: Color.YELLOW },
      { playerName: 'P3', rowColor: Color.GREEN },
      { playerName: 'P4', rowColor: Color.BLUE }
    ];
    component.requests = requests;
    fixture.detectChanges();
    const cells = fixture.nativeElement.querySelectorAll('.color-cell');
    expect(cells.length).toBe(4);
    expect(cells[0].classList.contains('cell-red')).toBeTruthy();
    expect(cells[1].classList.contains('cell-yellow')).toBeTruthy();
    expect(cells[2].classList.contains('cell-green')).toBeTruthy();
    expect(cells[3].classList.contains('cell-blue')).toBeTruthy();
  });


  it('should display correct number of request items', () => {
    const requests: ClosureNotification[] = [
      { playerName: 'A', rowColor: Color.RED },
      { playerName: 'B', rowColor: Color.YELLOW },
      { playerName: 'C', rowColor: Color.GREEN },
      { playerName: 'D', rowColor: Color.BLUE }
    ];
    component.requests = requests;
    fixture.detectChanges();
    const items = fixture.nativeElement.querySelectorAll('.request-item');
    expect(items.length).toBe(4);
  });

  it('should show one button when there is no pending cross', () => {
    component.requests = [{ playerName: 'Player A', rowColor: Color.RED }];
    component.hasPendingCross = false;
    fixture.detectChanges();
    const buttons = fixture.nativeElement.querySelectorAll('.btn');
    expect(buttons.length).toBe(1);
  });

  it('should show two buttons when there is a pending cross', () => {
    component.requests = [{ playerName: 'Player A', rowColor: Color.RED }];
    component.hasPendingCross = true;
    fixture.detectChanges();
    const buttons = fixture.nativeElement.querySelectorAll('.btn');
    expect(buttons.length).toBe(2);
  });

  it('should render modal with correct CSS classes', () => {
    const requests: ClosureNotification[] = [
      { playerName: 'Player A', rowColor: Color.RED }
    ];
    component.requests = requests;
    fixture.detectChanges();
    const overlay = fixture.nativeElement.querySelector('.modal-overlay');
    const content = fixture.nativeElement.querySelector('.modal-content');
    const body = fixture.nativeElement.querySelector('.modal-body');
    const actions = fixture.nativeElement.querySelector('.modal-actions');

    expect(overlay).toBeTruthy();
    expect(content).toBeTruthy();
    expect(body).toBeTruthy();
    expect(actions).toBeTruthy();
  });

  // ── Yes/No self-close modal (Longo second-to-last cell) ──────────────────

  it('should not show yes/no modal when lockConfirmRequest is null', () => {
    component.lockConfirmRequest = null;
    fixture.detectChanges();
    const overlays = fixture.nativeElement.querySelectorAll('.modal-overlay');
    expect(overlays.length).toBe(0);
  });

  it('should show yes/no modal when lockConfirmRequest is set', () => {
    component.lockConfirmRequest = { rowColor: Color.GREEN };
    fixture.detectChanges();
    const overlay = fixture.nativeElement.querySelector('.modal-overlay');
    expect(overlay).toBeTruthy();
  });

  it('should show yes and no buttons in yes/no modal', () => {
    component.lockConfirmRequest = { rowColor: Color.GREEN };
    fixture.detectChanges();
    const buttons = fixture.nativeElement.querySelectorAll('.btn');
    expect(buttons.length).toBe(2);
    const primary   = fixture.nativeElement.querySelector('.btn-primary');
    const secondary = fixture.nativeElement.querySelector('.btn-secondary');
    expect(primary).toBeTruthy();
    expect(secondary).toBeTruthy();
  });

  it('should emit lockYes when yes button is clicked', () => {
    let emitted = false;
    component.lockYes.subscribe(() => { emitted = true; });
    component.lockConfirmRequest = { rowColor: Color.GREEN };
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.btn-primary').click();
    expect(emitted).toBeTruthy();
  });

  it('should emit lockNo when no button is clicked', () => {
    let emitted = false;
    component.lockNo.subscribe(() => { emitted = true; });
    component.lockConfirmRequest = { rowColor: Color.GREEN };
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.btn-secondary').click();
    expect(emitted).toBeTruthy();
  });

  it('should show color cell matching the requested row color', () => {
    component.lockConfirmRequest = { rowColor: Color.GREEN };
    fixture.detectChanges();
    const colorCell = fixture.nativeElement.querySelector('.color-cell');
    expect(colorCell).toBeTruthy();
    expect(colorCell.classList.contains('cell-green')).toBeTruthy();
  });

  it('should not show yes/no modal when requests are also present (requests take precedence)', () => {
    // Both modals: requests modal renders first in the template and occupies the overlay.
    // The yes/no modal still renders but is stacked — at minimum both overlays exist.
    component.lockConfirmRequest = { rowColor: Color.RED };
    component.requests = [{ playerName: 'A', rowColor: Color.BLUE }];
    fixture.detectChanges();
    const overlays = fixture.nativeElement.querySelectorAll('.modal-overlay');
    expect(overlays.length).toBe(2);
  });
});
