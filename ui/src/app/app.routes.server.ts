import { RenderMode, ServerRoute } from '@angular/ssr';

export const serverRoutes: ServerRoute[] = [
  {
    path: 'login',
    renderMode: RenderMode.Prerender   // login può essere pre-renderizzata
  },
  {
    path: 'servers',
    renderMode: RenderMode.Client      // ← solo browser, ha accesso a localStorage
  },
  {
    path: 'servers/:id',
    renderMode: RenderMode.Client
  },
  {
    path: '**',
    renderMode: RenderMode.Client
  }
];