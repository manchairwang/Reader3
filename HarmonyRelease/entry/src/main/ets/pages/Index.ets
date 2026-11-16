@Entry
@Component
struct Index {
  @State message: string = 'Reader3Pic 鸿蒙版';

  build() {
    Column() {
      Text(this.message)
        .fontSize(28)
        .fontWeight(FontWeight.Bold)
        .margin({ top: 120, bottom: 40 })

      Text('HarmonyOS NEXT 工程验证')
        .fontSize(16)
        .fontColor('#666')
    }
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Start)
    .alignItems(HorizontalAlign.Center)
    .backgroundColor('#FFFFFF')
  }
}
