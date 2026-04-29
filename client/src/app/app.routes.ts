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
    redirectTo: ({ queryParams }) => {
      const localeParam = queryParams['locale'] ? `?locale=${queryParams['locale']}` : '';
      if (queryParams['sessionid'] && queryParams['playerid']) {
        return `/game/${queryParams['sessionid']}/${queryParams['playerid']}${localeParam}`;
      }
      return `/settings${localeParam}`;
    }
  },
  { path: '**', redirectTo: 'settings' }
];