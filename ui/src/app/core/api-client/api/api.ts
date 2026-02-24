export * from './auth.service';
import { AUTHService } from './auth.service';
export * from './console.service';
import { CONSOLEService } from './console.service';
export * from './filesystem.service';
import { FILESYSTEMService } from './filesystem.service';
export * from './server.service';
import { SERVERService } from './server.service';
export const APIS = [AUTHService, CONSOLEService, FILESYSTEMService, SERVERService];
