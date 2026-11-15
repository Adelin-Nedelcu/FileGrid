export interface DownloadProgress {
  loaded: number;
  total: number;
  note: string | null;
}

const STALL_MS = 60_000;
const MAX_BACKOFF_MS = 30_000;
const MAX_ATTEMPTS_WITHOUT_PROGRESS = 6;

class Fatal extends Error {}

type SavePicker = (options: { suggestedName: string }) => Promise<FileSystemFileHandle>;

function savePicker(): SavePicker | undefined {
  return (window as unknown as { showSaveFilePicker?: SavePicker }).showSaveFilePicker;
}

export function canResumeDownloads(): boolean {
  return typeof savePicker() === 'function';
}

export async function resumableDownload(
  url: string,
  name: string,
  size: number,
  report: (progress: DownloadProgress) => void,
): Promise<boolean> {
  let handle: FileSystemFileHandle;
  try {
    handle = await savePicker()!.call(window, { suggestedName: name });
  } catch {
    return false;
  }
  const target = await handle.createWritable();
  let position = 0;
  let stuck = 0;
  const track = (progress: DownloadProgress) => {
    position = progress.loaded;
    report(progress);
  };
  try {
    while (position < size) {
      const before = position;
      try {
        await fetchFrom(url, position, size, target, track);
        if (position < size) throw new Error('Transfer întrerupt');
      } catch (err) {
        if (err instanceof Fatal) throw err;
        stuck = position > before ? 0 : stuck + 1;
        if (stuck >= MAX_ATTEMPTS_WITHOUT_PROGRESS) {
          throw new Fatal('Descărcarea nu mai înaintează. Verificați conexiunea sau integritatea fișierului („Verifică”).');
        }
        const delay = Math.min(MAX_BACKOFF_MS, 1000 * 2 ** stuck);
        report({ loaded: position, total: size, note: `Conexiune instabilă — reiau automat în ${Math.round(delay / 1000)} s` });
        await new Promise<void>((resolve) => {
          const timer = setTimeout(resolve, delay);
          window.addEventListener(
            'online',
            () => {
              clearTimeout(timer);
              resolve();
            },
            { once: true },
          );
        });
      }
    }
    await target.close();
    return true;
  } catch (err) {
    await target.abort().catch(() => undefined);
    throw err;
  }
}

async function fetchFrom(
  url: string,
  start: number,
  size: number,
  target: FileSystemWritableFileStream,
  report: (progress: DownloadProgress) => void,
): Promise<void> {
  const abort = new AbortController();
  let watchdog = setTimeout(() => abort.abort(), STALL_MS);
  try {
    const response = await fetch(url, {
      credentials: 'same-origin',
      signal: abort.signal,
      headers: start > 0 ? { Range: `bytes=${start}-` } : {},
    });
    if (response.status >= 400) {
      if ([408, 429, 502, 503, 504].includes(response.status)) throw new Error(`HTTP ${response.status}`);
      const problem = (await response.json().catch(() => null)) as { title?: string; detail?: string } | null;
      throw new Fatal(problem?.detail ? `${problem.title ?? 'Eroare'}: ${problem.detail}` : `Eroare ${response.status}`);
    }
    if (start > 0 && response.status !== 206) {
      throw new Fatal('Serverul nu a acceptat reluarea descărcării.');
    }
    const reader = response.body!.getReader();
    let position = start;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) return;
      clearTimeout(watchdog);
      watchdog = setTimeout(() => abort.abort(), STALL_MS);
      await target.write(value);
      position += value.length;
      report({ loaded: position, total: size, note: null });
    }
  } finally {
    clearTimeout(watchdog);
  }
}
