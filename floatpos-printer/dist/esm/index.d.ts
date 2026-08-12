export interface FloatPrinterPlugin {
  isAvailable(): Promise<{ available: boolean; error?: string }>;
  status(): Promise<{ available: boolean; printer?: number; paperOk?: boolean;
                      tempOk?: boolean; voltage?: number; powerOk?: boolean;
                      lastError?: number; error?: string }>;
  print(options: { data: string }): Promise<{ ok: boolean; bytes: number; lastError: number }>;
  printText(options: { text: string }): Promise<{ ok: boolean }>;
  feed(): Promise<{ ok: boolean }>;
  setDarkness(options: { level: number }): Promise<{ ok: boolean }>;
}
export declare const FloatPrinter: FloatPrinterPlugin;
