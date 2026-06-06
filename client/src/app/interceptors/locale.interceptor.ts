import { Injectable } from '@angular/core';
import { HttpInterceptor, HttpRequest, HttpHandler, HttpEvent } from '@angular/common/http';
import { Observable } from 'rxjs';

@Injectable()
export class LocaleInterceptor implements HttpInterceptor {
  intercept(req: HttpRequest<unknown>, next: HttpHandler): Observable<HttpEvent<unknown>> {
    // Remove locale parameter from API requests
    const url = new URL(req.url, window.location.origin);
    if (url.searchParams.has('locale')) {
      url.searchParams.delete('locale');
      req = req.clone({ url: url.toString() });
    }
    return next.handle(req);
  }
}
