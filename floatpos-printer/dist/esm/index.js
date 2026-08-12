import { registerPlugin } from '@capacitor/core';
// Web fallback is deliberately empty: on a device with no built-in printer the
// plugin simply reports unavailable and FloatPOS uses window.print() as before.
export const FloatPrinter = registerPlugin('FloatPrinter');
