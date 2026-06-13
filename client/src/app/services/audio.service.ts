import { Injectable } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class AudioService {
  static readonly CROSS = 'zapsplat_office_pencil_drawing_line_fast_on_paper_pad_004_95195.mp3';
  static readonly UNDO_CROSS = 'zapsplat_office_stationery_eraser_rubber_rubbing_out_pencil_on_paper_003_96019.mp3';
  static readonly PUNISHMENT = 'zapsplat_foley_hand_on_carpet_several_fast_rubs_as_if_cleaning_004_67519.mp3';
  static readonly DICE = 'zapsplat_leisure_small_dice_x2_pick_up_001_81084.mp3';
  static readonly ROW_CLOSURE_BELL =
    'zapsplat_multimedia_ui_notification_classic_bell_synth_generic_chime_001_107501.mp3';

  play(filename: string): void {
    if (typeof Audio === 'undefined') return;
    const audio = new Audio(`assets/audio/${filename}`);
    audio.play()?.catch(() => {
      /* autoplay blocked, ignore */
    });
  }
}
