import { HttpContextToken } from '@angular/common/http';

/** Set to true on requests whose errors the caller shows itself (e.g. the login form). */
export const SKIP_ERROR_NOTIFICATION = new HttpContextToken<boolean>(() => false);
