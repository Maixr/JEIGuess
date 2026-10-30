# 纠错逻辑离线测试

用**整合包真实物品名**验证「猜你想搜」的三档逻辑，不需要启动游戏。

## 原理
- `names.txt`：从整合包所有 mod 的 `lang/zh_cn.json` 收集 `item.*` / `block.*` 名称（约 1.5 万条）
- `alias.txt`：由 `jeiguess_aliases.json` 转成 `键=值` 行
- `CorrectionTest`：按 mod 里 `correction()` 的同一套逻辑跑用例

## 步骤
1. 用 PowerShell 从目标整合包 `mods/*.jar` 的 `lang/zh_cn.json` 导出 `names.txt`
2. `alias.txt` 由 `src/main/resources/jeiguess_aliases.json` 生成
3. 编译并运行（需要 JEC 的 `PinIn` 与 `fastutil` 在 classpath 上）：

```
javac -encoding UTF-8 -cp "<jecharacters.jar>;<fastutil.jar>" -d out CorrectionTest.java
java -Dfile.encoding=UTF-8 -cp "out;<jecharacters.jar>;<fastutil.jar>" CorrectionTest <数据目录>
```

## 判定标准
- 正例：土豆/砖石/会议室/鹅卵石/棍子/合成台 都要命中正确物品
- 负例：泥土/钻石/木棍/工作台/石头/铁锭/苹果/玻璃 必须**不提示**（不误报）

## 重要：数据必须包含原版物品名
只收集 mod 的 `zh_cn.json` 会漏掉原版（马铃薯、钻石、圆石、工作台…），
测出来会全盘失败 —— 那是测试数据的问题，不是代码的问题。
正确做法：mod 名 + 官方 `assets/minecraft/lang/zh_cn.json` 合并（约 1.56 万条）。

当前状态：**17/17 通过**（14945 个物品名；含 铁铲→铁锹、钻石铲→钻石锹、铲子→锹）。

## 四档纠错（v0.6.5）
1. 别名表：土豆 → 马铃薯
2. 只差一个字且同音：砖石 → 钻石
3. 拼音首字母全等（≥3 字）：会议室 → 黑曜石
4. **同音字替换**：稿子 → 镐

第 4 档的依据：镐是**多音字**（hao4 / gao3），只取第一读音会漏掉 gao3。
替换后取"能搜到 ≥3 件物品"的最长词 —— 所以 稿子 得到的是「镐」
（游戏里没有"镐子"，但有 86 件"…镐"），点击直接搜出全部镐类。

## 近义字替换（v0.6.7）
铲(chǎn) / 锹(qiāo) 不同音，同音规则抓不到。用一张显式的「近义字」表：
`CHAR_SUB = {铲→锹}`，替换后校验物品名，所以"铁铲→铁锹""钻石铲→钻石锹"能命中，
而本身就带"铲"的真实物品（辛辛那金铲）不会被改写。

**顺序很重要**：这条规则必须排在所有"猜"之前。曾经放在最后，
结果「钻石铲」被首字母档配成「钻石锄」、「铲子」被同音档配成「产」。
