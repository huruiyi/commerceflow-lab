import { ref } from 'vue';

export const themes = [
  ['default', '默认', 'Default', '#006be6'],
  ['violet', '紫罗兰', 'Violet', '#7361ef'],
  ['pink', '樱花粉', 'Pink', '#ec4770'],
  ['yellow', '柠檬黄', 'Yellow', '#e7b449'],
  ['sky-blue', '天蓝色', 'Sky blue', '#5064ff'],
  ['green', '浅绿色', 'Green', '#00bb86'],
  ['zinc', '锌色灰', 'Zinc', '#3d3d46'],
  ['deep-green', '深绿色', 'Deep green', '#078f92'],
  ['deep-blue', '深蓝色', 'Deep blue', '#0962ac'],
  ['orange', '橙黄色', 'Orange', '#b54209'],
  ['rose', '玫瑰红', 'Rose', '#bf1924'],
  ['neutral', '中性色', 'Neutral', '#414141'],
  ['slate', '石板灰', 'Slate', '#334155'],
  ['gray', '中灰色', 'Gray', '#374151'],
] as const;

export const defaults = {
  theme: 'default',
  radius: 0.5,
  fontSize: 14,
  darkSidebar: false,
  darkHeader: false,
  showSidebar: true,
  sidebarCollapsed: false,
  sidebarPinned: true,
  compact: false,
  searchShortcut: true,
  showTimezone: true,
  showNotifications: true,
};
export type ConsolePreferences = typeof defaults;
const key = 'sim-admin-preferences';

export function readPreferences(): ConsolePreferences {
  try {
    return { ...defaults, ...JSON.parse(localStorage.getItem(key) ?? '{}') };
  } catch {
    return { ...defaults };
  }
}
export function savePreferences(preferences: ConsolePreferences) {
  localStorage.setItem(key, JSON.stringify(preferences));
}

export function readMode(): 'light' | 'dark' | 'system' {
  const mode = localStorage.getItem('sim-admin-theme');
  return mode === 'dark' || mode === 'system' ? mode : 'light';
}

// DEFAULT_TIME_ZONE_OPTIONS from vue-vben-admin (MIT). See LICENSE.vben.
export const timezones = [
  { value: 'America/New_York', label: 'America/New_York(GMT-5)' },
  { value: 'Europe/London', label: 'Europe/London(GMT0)' },
  { value: 'Asia/Shanghai', label: 'Asia/Shanghai(GMT+8)' },
  { value: 'Asia/Tokyo', label: 'Asia/Tokyo(GMT+9)' },
  { value: 'Asia/Seoul', label: 'Asia/Seoul(GMT+9)' },
] as const;

type Timezone = (typeof timezones)[number]['value'];
const savedZone = localStorage.getItem('sim-admin-timezone');
export const timezone = ref<Timezone>(
  timezones.find(({ value }) => value === savedZone)?.value ?? 'Asia/Shanghai',
);

export function setTimezone(value: Timezone) {
  timezone.value = value;
  localStorage.setItem('sim-admin-timezone', value);
}

export function formatAdminDate(value: string, locale = 'zh-CN', date = true) {
  return new Intl.DateTimeFormat(locale, {
    dateStyle: date ? 'medium' : undefined,
    timeStyle: 'medium',
    timeZone: timezone.value,
  }).format(new Date(value));
}
