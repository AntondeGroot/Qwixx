import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslationService } from '../services/translation.service';

@Component({
  selector: 'app-language-selector',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="language-selector">
      <button class="language-btn" (click)="toggleDropdown()" [attr.aria-label]="'Change language'">
        <svg class="language-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="10"></circle>
          <path d="M2 12h20M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"></path>
        </svg>
      </button>
      <div class="dropdown" *ngIf="showDropdown">
        <button
          *ngFor="let lang of translationService.languages"
          class="dropdown-item"
          [class.active]="lang.code === translationService.currentLanguage()"
          (click)="selectLanguage(lang.code)"
        >
          {{ lang.label }}
        </button>
      </div>
    </div>
  `,
  styles: [`
    .language-selector {
      position: relative;
      display: flex;
      align-items: center;
    }

    .language-btn {
      background: none;
      border: none;
      padding: 8px;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      color: inherit;
      width: 40px;
      height: 40px;

      &:hover {
        opacity: 0.7;
      }
    }

    .language-icon {
      width: 24px;
      height: 24px;
    }

    .dropdown {
      position: absolute;
      top: 100%;
      right: 0;
      background: white;
      border: 1px solid #ddd;
      border-radius: 4px;
      min-width: 180px;
      box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
      z-index: 1000;
      margin-top: 4px;
    }

    @media (orientation: portrait) {
      .language-icon {
        transform: rotate(90deg);
      }
      /* Button is at physical bottom-right (= game top-right).
         Rotate the dropdown 90° CW so items are readable from the game's perspective,
         and anchor its top-right corner to the button's top-left edge. */
      .dropdown {
        top: 0;
        right: 100%;
        bottom: auto;
        left: auto;
        margin-top: 0;
        margin-right: 4px;
        transform: rotate(90deg);
        transform-origin: top right;
      }
    }

    .dropdown-item {
      display: block;
      width: 100%;
      padding: 10px 16px;
      border: none;
      background: none;
      text-align: left;
      cursor: pointer;
      color: #333;

      &:hover {
        background-color: #f5f5f5;
      }

      &.active {
        background-color: #e8f4f8;
        font-weight: 600;
        color: #0066cc;
      }
    }
  `]
})
export class LanguageSelectorComponent {
  translationService = inject(TranslationService);
  showDropdown = false;

  toggleDropdown() {
    this.showDropdown = !this.showDropdown;
  }

  selectLanguage(code: string) {
    this.translationService.setLanguage(code);
    this.showDropdown = false;
  }
}