import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth-guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./pages/login/login').then(m => m.Login)
  },
  {
    path: '',
    loadComponent: () => import('./layout/main-layout/main-layout').then(m => m.MainLayout),
    canActivate: [authGuard],
    children: [
      {
        path: 'servers',
        loadComponent: () => import('./pages/server-list/server-list').then(m => m.ServerList)
      },
      {
        path: 'servers/:id',
        loadComponent: () => import('./pages/server-detail/server-detail').then(m => m.ServerDetail)
      },

      { path: '', redirectTo: 'servers', pathMatch: 'full' }
    ]
  },
  { path: '**', redirectTo: '' }
];