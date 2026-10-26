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

当前状态：**29/29 通过**（14908 个物品名，单次查询 1–20 微秒）。
