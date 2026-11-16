import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window } from '@kit.ArkUI';

const domain: number = 0x9201;
const tag: string = 'EntryAbility';

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(domain, '0', tag, 'onCreate');
  }

  onDestroy(): void {
    hilog.info(domain, '0', tag, 'onDestroy');
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    hilog.info(domain, '0', tag, 'onWindowStageCreate');
    windowStage.loadContent('pages/Index', (err) => {
      if (err.code) {
        hilog.error(domain, '0', tag, 'Failed to load content: %{public}s', err.message ?? '');
        return;
      }
      hilog.info(domain, '0', tag, 'Content loaded');
    });
  }

  onWindowStageDestroy(): void {
    hilog.info(domain, '0', tag, 'onWindowStageDestroy');
  }
}
