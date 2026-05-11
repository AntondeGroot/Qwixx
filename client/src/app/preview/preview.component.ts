import { Component, OnInit, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

interface ScenarioSummary {
  id: string;
  label: string;
  isScoreScreen: boolean;
}

interface PreviewResult {
  description: string;
  redirectUrl: string;
  otherPlayerUrls: Record<string, string>;
}

@Component({
  selector: 'app-preview',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './preview.component.html',
  styleUrl: './preview.component.css',
})
export class PreviewComponent implements OnInit {
  private route  = inject(ActivatedRoute);
  private router = inject(Router);
  private http   = inject(HttpClient);

  protected id: string | null = null;
  protected loading    = false;
  protected result: PreviewResult | null = null;
  protected scenarios: ScenarioSummary[] = [];
  protected error: string | null = null;

  protected otherEntries(): { name: string; url: string }[] {
    if (!this.result) return [];
    return Object.entries(this.result.otherPlayerUrls)
        .map(([name, url]) => ({ name, url }));
  }

  ngOnInit() {
    this.id = this.route.snapshot.paramMap.get('id');
    if (this.id) {
      this.loading = true;
      this.http.get<PreviewResult>(`/preview/${this.id}`).subscribe({
        next: r => {
          this.loading = false;
          this.result = r;
        },
        error: () => {
          this.loading = false;
          this.error = `Scenario ${this.id} not found.`;
        },
      });
    } else {
      this.http.get<ScenarioSummary[]>('/preview').subscribe({
        next: s => { this.scenarios = s; },
        error: () => { this.error = 'Could not load scenario list from server.'; },
      });
    }
  }

  protected open(url: string) {
    this.router.navigateByUrl(url);
  }
}
