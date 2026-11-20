// Grid defaults adapted from vue-vben-admin (MIT). See LICENSE.vben.
import type { App } from 'vue';
import {
  VxeUI,
  VxeButton,
  VxeCheckbox,
  VxeIcon,
  VxeInput,
  VxeLoading,
  VxeModal,
  VxeNumberInput,
  VxePager,
  VxeSelect,
  VxeTooltip,
} from 'vxe-pc-ui';
import {
  VxeGrid,
  VxeTable,
  VxeToolbar,
  VxeColumn,
  VxeColgroup,
} from 'vxe-table';
import zhCN from 'vxe-pc-ui/lib/language/zh-CN';
import enUS from 'vxe-pc-ui/lib/language/en-US';
import 'vxe-pc-ui/lib/style.css';
import 'vxe-table/lib/style.css';

export function setAdminLocale(locale: 'zh-CN' | 'en-US') {
  VxeUI.setLanguage(locale);
}

export function setAdminTheme(theme: 'light' | 'dark') {
  VxeUI.setTheme(theme);
}

export function installAdminGrid(app: App) {
  for (const component of [
    VxeButton,
    VxeCheckbox,
    VxeIcon,
    VxeInput,
    VxeLoading,
    VxeModal,
    VxeNumberInput,
    VxePager,
    VxeSelect,
    VxeTooltip,
    VxeGrid,
    VxeTable,
    VxeToolbar,
    VxeColumn,
    VxeColgroup,
  ]) {
    VxeUI.component(component);
    app.component(component.name!, component);
  }
  VxeUI.setI18n('zh-CN', zhCN);
  VxeUI.setI18n('en-US', enUS);
  setAdminLocale('zh-CN');
  VxeUI.setConfig({
    table: { round: true },
    grid: {
      align: 'left',
      border: false,
      columnConfig: { resizable: true },
      formConfig: { enabled: false },
      minHeight: 180,
      round: true,
      showOverflow: 'tooltip',
      size: 'small',
    },
  });
}
