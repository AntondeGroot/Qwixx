import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: 'game/:sessionId/:playerId',
    loadComponent: () => import('./board/board.component').then((m) => m.BoardComponent),
  },
  {
    path: 'settings',
    loadComponent: () => import('./settings/settings.component').then((m) => m.SettingsComponent),
  },
  {
    // Dev preview of the score screen — must precede score/:sessionId, which would otherwise
    // swallow 'preview' as a session id. See ScoreComponent.runPreview().
    path: 'score/preview',
    loadComponent: () => import('./score/score.component').then((m) => m.ScoreComponent),
    data: { preview: true },
  },
  {
    path: 'score/:sessionId',
    loadComponent: () => import('./score/score.component').then((m) => m.ScoreComponent),
  },
  {
    path: '',
    pathMatch: 'full',
    redirectTo: ({ queryParams }) => {
      const localeParam = queryParams['locale'] ? `?locale=${queryParams['locale']}` : '';
      if (queryParams['sessionid'] && queryParams['playerid']) {
        const roomParam = queryParams['roomid'] ? `${localeParam ? '&' : '?'}roomid=${queryParams['roomid']}` : '';
        return `/game/${queryParams['sessionid']}/${queryParams['playerid']}${localeParam}${roomParam}`;
      }
      return `/settings${localeParam}`;
    },
  },
  {
    path: 'rules',
    loadComponent: () => import('./rules/rules.component').then((m) => m.RulesComponent),
  },
  { path: '**', redirectTo: 'settings' },
];
