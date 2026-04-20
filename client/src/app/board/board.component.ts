import { Component, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

@Component({
  selector: 'app-board',
  imports: [RouterLink],
  templateUrl: './board.component.html'
})
export class BoardComponent implements OnInit {
  private route = inject(ActivatedRoute);

  sessionId = signal('');
  playerId  = signal('');

  ngOnInit() {
    this.sessionId.set(this.route.snapshot.paramMap.get('sessionId') ?? '');
    this.playerId.set(this.route.snapshot.paramMap.get('playerId') ?? '');
  }
}
