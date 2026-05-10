import { ComponentFixture, TestBed } from '@angular/core/testing';
import { RowClosureModalComponent, RowClosureRequest } from './row-closure-modal.component';
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
    const requests: RowClosureRequest[] = [
      { playerName: 'Player A', rowColor: Color.RED }
    ];
    component.requests = requests;
    fixture.detectChanges();
    const overlay = fixture.nativeElement.querySelector('.modal-overlay');
    expect(overlay).toBeTruthy();
  });

  it('should display multiple closure requests', () => {
    const requests: RowClosureRequest[] = [
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
    const requests: RowClosureRequest[] = [
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
    const requests: RowClosureRequest[] = [
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
    const requests: RowClosureRequest[] = [
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
    const requests: RowClosureRequest[] = [
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
    const requests: RowClosureRequest[] = [
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
});
