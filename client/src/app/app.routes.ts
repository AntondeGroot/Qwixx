import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: 'game/:sessionId/:playerId',
    loadComponent: () => import('./board/board.component').then(m => m.BoardComponent)
  },
  {
    path: 'settings',
    loadComponent: () => import('./settings/settings.component').then(m => m.SettingsComponent)
  },
  {
    path: '',
    pathMatch: 'full',
    redirectTo: ({ queryParams }) =>
      queryParams['sessionid'] && queryParams['playerid']
        ? `/game/${queryParams['sessionid']}/${queryParams['playerid']}`
        : '/settings'
  },
  { path: '**', redirectTo: 'settings' }
];