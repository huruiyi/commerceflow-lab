export function readPage(value: string | null): number {
  const page = Number(value ?? 0);
  return Number.isInteger(page) && page >= 0 && page <= 2147483647 ? page : 0;
}
