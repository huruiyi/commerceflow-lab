import { computed, ref, toValue, watch, type MaybeRefOrGetter } from 'vue';
import type { VxePagerProps } from 'vxe-pc-ui';

const pageSizes = [10, 20, 30, 50, 100, 200];
export const defaultAdminPageSize = 20;

export const adminPagerOptions: Pick<
  VxePagerProps,
  'background' | 'size' | 'pageSizes' | 'pageSizePlacement' | 'pagerCount' | 'layouts'
> = {
  background: true,
  size: 'mini',
  pageSizes,
  pageSizePlacement: 'top',
  pagerCount: 5,
  layouts: ['Total', 'Sizes', 'Home', 'PrevJump', 'PrevPage', 'Number', 'NextPage', 'NextJump', 'End'],
};

export function useAdminPageSize(tableId: MaybeRefOrGetter<string>) {
  const key = computed(() => 'sim-admin-page-size:' + toValue(tableId));
  const size = ref(defaultAdminPageSize);
  watch(key, (storageKey) => {
    try {
      const saved = Number(localStorage.getItem(storageKey));
      size.value = pageSizes.includes(saved) ? saved : defaultAdminPageSize;
    } catch {
      size.value = defaultAdminPageSize;
    }
  }, { immediate: true, flush: 'sync' });

  return computed({
    get: () => size.value,
    set(value: number) {
      if (!pageSizes.includes(value) || size.value === value) return;
      size.value = value;
      try {
        localStorage.setItem(key.value, String(value));
      } catch {
        // The selection stays usable for this session when storage is unavailable.
      }
    },
  });
}
