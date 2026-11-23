export interface AdminListPage<T> {
  rows: T[];
  total: number;
  page: number;
  size: number;
  truncated?: boolean;
}
