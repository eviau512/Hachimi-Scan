# 技术规格书 16：Full HDR 统一连拍管线规范 (Unified Capture Pipeline Specification)

> **文档性质**：架构整合规格书（Architecture Consolidation Specification）  
> **版本**：v1.0 · 2026-10-01  
> **制定目标**：将"多帧亚像素超分 (1+4 Super-Resolution)"与"多重曝光高动态范围合成 (Multi-EV HDR)"整合为单一 Full HDR 模式开关，同时规范点按聚焦/测光交互行为。

---

## 1. 模式命名约定

| 旧名称 | 新名称 | 说明 |
|:---|:---|:---|
| `50MP Super-Res Burst` | *(已废弃，并入 Full HDR)* | — |
| `Full HDR` | **Full HDR** | 唯一对外模式，整合超分与高动态范围 |
| Normal | Normal | 单帧普通快拍（不进行融合） |

用户在 Settings 中看到且只看到**两种拍摄模式选项**：
1. **Normal**（默认，单张快拍）
2. **Full HDR**（1主3超分 + 1压暗高光帧，共 4 帧）

---

## 2. Full HDR 连拍帧配置规范

### 2.1 帧序列布局

```
帧索引  曝光补偿  用途
  0       EV 0    基准帧（Sub-pixel 超分基准 + 空间基准）
  1    EV -1.5~-2 高光压暗帧（HDR 高光恢复用）
  2       EV 0    亚像素超分辅助帧 1（手持微颤位移）
  3       EV 0    亚像素超分辅助帧 2（手持微颤位移）
```

- 总帧数：**4 帧**，其中 3 帧 EV 0（超分）+ 1 帧 EV 负（HDR 高光）
- EV 负值帧位于第 1 帧（而非尾帧），减少因 AE 重新锁定引入的帧间 ISP 色调偏差
- 帧间 AE 稳定等待：切换曝光后等待 ≤ 50ms 再触发快门

### 2.2 融合引擎参数

| 参数 | Full HDR 模式值 |
|:---|:---|
| `removeGlare` | `true` |
| `isScreenMode` | `true`（启用 HDR 高光接缝 + 暗部 S-Curve） |
| `superResolution` | `true`（2× 画布扩展，输出 ≈ 50MP） |
| 输出 JPEG 质量 | 95% |
| EXIF 模式标记 | `[Full HDR]` |

---

## 3. 普通单帧快拍（Normal）规范

- 单次 `takePicture`，CameraX MINIMIZE_LATENCY
- 直接通过 `normalizeExifOrientation` 旋正后入库
- EXIF 模式标记：`[Normal]`

---

## 4. 点按聚焦交互规范 (Tap-to-Focus / AF+AE)

### 4.1 用户行为触发

用户在取景器任意位置单次点击（单指 tap），触发：
1. **AF 对焦**（AutoFocus）：将对焦测量区域移至点击坐标；
2. **AE 测光**（Auto Exposure）：将曝光测量区域同步移至点击坐标；
3. **聚焦动画**：在点击位置显示一个方形聚焦框，动画 250ms 缩小至目标大小，持续显示 1.5s 后淡出。

### 4.2 技术实现契约

```kotlin
// 在 PreviewView 坐标系内创建测光点
val factory = previewView.meteringPointFactory
val point = factory.createPoint(tapOffset.x, tapOffset.y)
val action = FocusMeteringAction.Builder(point)
    .addPoint(point, FocusMeteringAction.FLAG_AE)
    .setAutoCancelDuration(3, TimeUnit.SECONDS)
    .build()
cameraControl.startFocusAndMetering(action)
```

### 4.3 聚焦框样式规范

- 形状：正方形圆角框（CornerRadius = 4dp），边长 72dp
- 颜色：Pure White，alpha 0.9
- 边线宽度：1.5dp
- 出场动画：scale 1.4→1.0，duration 200ms，FastOutSlowInEasing
- 自动淡出：显示 1.5s 后 150ms 淡出消失
- 聚焦期间内圈保持；AE 完成或 3s 超时后清除

### 4.4 与多框检测的交互

- 点按聚焦触发时，暂不影响正在运行的 FrameAnalyzer 文档检测线程；
- 聚焦期间继续实时显示边框检测叠加层；
- `setTouchPoint` 调用（影响 FrameAnalyzer 触摸感知区域）不再复用 tap-to-focus 手势，两者独立。

---

## 5. Settings UI 规范

### 5.1 变更项

| 旧条目 | 新条目 |
|:---|:---|
| `50MP Super-Res Burst`（独立开关）| **已移除** |
| `Full HDR (Low-Level Camera2)` | **Full HDR**（名称精简，警告文案同步更新） |

### 5.2 Full HDR 选项说明文字

- 标题：`Full HDR`（简短，无括号副标题）
- 摘要（EN）：`Multi-frame 1+3 super-resolution with multi-EV highlight recovery. Outputs ~50MP.`
- 摘要（ZH）：`多帧亚像素超分（1主3辅）+ 多重曝光高光恢复，输出约 50MP 画质。`

---

## 6. EXIF 溯源 (Provenance) 规范变更

| 拍摄模式 | `ImageDescription` | `Software` 后缀 |
|:---|:---|:---|
| 普通模式 | `Capture Mode: Normal` | `[Normal]` |
| Full HDR | `Capture Mode: Full HDR` | `[Full HDR]` |

旧的 `[50MP]`、`[HDR]`、`[50MP HDR]` 标记不再用于新拍摄，保留历史可读性（`extractMode` 继续识别旧标记以兼容已存照片）。

---

## 7. 与其他规格书的关系

| 规格书 | 关系 |
|:---|:---|
| SPEC_04（Burst Fusion Engine） | 超分算法实现来源，本规范定义触发参数 |
| SPEC_08（Burst HDR & Screen Stabilization） | HDR 高光接缝实现来源 |
| SPEC_10（Base-Locked Highlight Grafting） | HDR 暗部 S-Curve 实现来源 |
| SPEC_11（Lossless JPEG Pipeline） | 输出 JPEG 质量与 IMWRITE 参数来源 |
| SPEC_14（EXIF Provenance） | 模式标记字符串规范 |
