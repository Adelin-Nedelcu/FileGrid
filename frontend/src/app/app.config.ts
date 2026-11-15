import { ApplicationConfig, LOCALE_ID, provideBrowserGlobalErrorListeners } from '@angular/core';
import { registerLocaleData } from '@angular/common';
import { provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
import localeRo from '@angular/common/locales/ro';
import { sessionInterceptor } from './session';

registerLocaleData(localeRo);

export const appConfig: ApplicationConfig = {
  providers: [
    { provide: LOCALE_ID, useValue: 'ro' },
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withXhr(), withInterceptors([sessionInterceptor])),
  ],
};
