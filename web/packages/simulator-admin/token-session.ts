import { onMounted, ref } from 'vue';

export class AdminRequestError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

export function useAdminTokenSession(
  consoleId: string,
  signIn: (credential: string) => Promise<void>,
) {
  const key = 'sim-admin-session:' + consoleId;
  const token = ref('');
  let saved = '';
  try {
    saved = sessionStorage.getItem(key) ?? '';
  } catch {
    // Sign-in still works in memory when browser storage is unavailable.
  }
  const restoring = ref(Boolean(saved));

  onMounted(async () => {
    const credential = saved;
    saved = '';
    if (!credential) return;
    try {
      await signIn(credential);
    } finally {
      restoring.value = false;
    }
  });

  function remember(credential: string) {
    token.value = credential;
    try {
      sessionStorage.setItem(key, credential);
    } catch {
      // Keep the verified session usable in memory.
    }
  }

  function clear() {
    token.value = '';
    try {
      sessionStorage.removeItem(key);
    } catch {
      // There is no accessible browser storage to clear.
    }
  }

  function discardIfUnauthorized(failure: unknown) {
    if (
      restoring.value &&
      failure instanceof AdminRequestError &&
      failure.status === 401
    )
      clear();
  }

  return { token, restoring, remember, clear, discardIfUnauthorized };
}
