# 技术规格书 13：对标 MotoCam 硬件 OIS 激活、电影级 S 曲线通透感与自适应微反差质感规范 (MotoCam Parity: Hardware OIS Activation, Cinematic S-Curve Tone Mapping & Adaptive Micro-Contrast Specification)

> **文档性质**：核心算法与硬件 ISP 管线升级规格书 (Algorithm & Hardware Pipeline Specification)  
> **制定目标**：全面对标 Motorola Moto G75 5G (Sony LYTIA 600) 的旗舰级成像水准，唤醒 OnePlus Ace 3V 相同的 Sony LYT-600 硬件 OIS 光学防抖能力，通过暗部黑电平压制消除夜空发灰与噪点漂浮，注入自适应保边微反差合成算法彻底消灭树叶与砖石的“水彩涂抹感”，并恢复完整的 EXIF 摄影参数转录。

---

## 1. 现状剖析与对标诊断 (Forensic Benchmark Analysis)

### 1.1 硬件对齐：同门 CMOS 的计算摄影决战
- **Moto G75 5G**: Sony LYTIA 600 (50MP, 1/1.95", f/1.79, 硬件 OIS).
- **OnePlus Ace 3V**: Sony LYTIA 600 / IMX882 (50MP, 1/1.95", f/1.8, 硬件 OIS).
- **基准测试数据**: 暗光夜景 1/12.5s 快门，ISO 1800 左右。
- 两者硬件素质处于完全同一水平线，成像差异完全取决于软件与 ISP 管线。

### 1.2 哈基米 Cam 与 MotoCam 的三大核心差距
1. **暗光微抖模糊（硬件 OIS 未唤醒）**：
   在 `CameraScreen.kt` 中未显式请求 `CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON`。在夜间 1/12 秒的手持长曝光下，没有音圈马达物理防抖补偿，手部微颤直接造成了 1~2 个像素的微位移模糊。
2. **植物与文字的“水彩涂抹感”（单帧降噪与缺乏微反差）**：
   Qualcomm ISP 在 ISO 1800 时，单帧空间双边降噪会粗暴抹除高频纹理。MotoCam 凭借高通 Spectra 多帧平均和边缘局部反差补偿保持了树叶的清晰脉络，而哈基米缺少后处理的高频微反差合成。
3. **夜空发灰与暗部底噪浮起（黑电平未收敛）**：
   MotoCam 采用了电影级 S 曲线，将极暗区域（$Y < 25$）沉稳压至纯黑，使得天空极度纯净深邃，画面对比度和空间立体感极强。哈基米目前暗部平均亮度偏高（RGB 均值 88 vs Moto 的 67），致使夜空发灰并暴露了暗光紫红散粒噪点。
4. **EXIF 拍摄参数丢失**：
   OpenCV 的 `Imgcodecs.imwrite` 输出的 JPEG 文件不包含原始传感器的 EXIF 元数据，导致快门、ISO、焦距等专业摄影信息丢失。

---

## 2. 核心数学模型与管线设计 (Architecture & Mathematical Models)

### 2.1 硬件 ISP 管线唤醒：物理 OIS 与纯净拍照配置
在 Camera2 拍照请求构建器中：
1. **显式开启硬件光学防抖**：
   ```kotlin
   camera2Extender.setCaptureRequestOption(
       CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
       CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
   )
   ```
2. **关闭电子裁切防抖**（静止拍照无需视频 EIS，避免画质劣化与视角裁切）：
   ```kotlin
   camera2Extender.setCaptureRequestOption(
       CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
       CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
   )
   ```
3. **开启全流程高质量色彩与色调管线**：
   ```kotlin
   camera2Extender.setCaptureRequestOption(
       CaptureRequest.TONEMAP_MODE,
       CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
   )
   ```

### 2.2 电影级 S-Curve 暗部黑电平压制 (Cinematic Black-Point Smooth Toe)
在 C++ 原生图像处理层，对融合后的图像进行暗部色调沉降：
定义亮度：
$$Y(x, y) = 0.114 \cdot B + 0.587 \cdot G + 0.299 \cdot R$$

对深暗阴影与夜空区（$Y \le Y_{\text{toe}} = 28.0$），引入平滑底电平压制（Smooth Toe Function）：
$$u = \frac{Y}{Y_{\text{toe}}}$$
$$Y_{\text{tone}} = Y \cdot u^{0.65} = Y_{\text{toe}} \cdot u^{1.65}$$

- **连续性保障**：当 $Y = Y_{\text{toe}}$ 时，$u = 1 \implies Y_{\text{tone}} = Y_{\text{toe}}$，一阶导数连续平滑相切，完全无任何阶梯或断层；
- **纯净黑位收敛**：当 $Y \to 0$ 时，$Y_{\text{tone}}$ 加速逼近 0，彻底消灭夜空紫红底噪，重塑通透深邃的立体质感；
- **色彩等比守恒**：对各颜色通道采用严格亮度比例缩放：
  $$S = \frac{Y_{\text{tone}}}{\max(Y, 0.001f)}$$
  $$C_{\text{tone}} = \text{clamp}(C \cdot S, 0, 255)$$
  严禁改变 RGB 相对比例，确保色彩饱和度与色相 100% 真实。

### 2.3 自适应保边微反差与质感合成 (Adaptive Edge-Preserving Micro-Contrast)
为了击碎树叶与地砖的“涂抹感”，采用带死区核心控制（Coring Threshold）的自适应非锐化微反差增强：
1. **低频背景提取**：
   对图像亮度进行平滑滤波得到低频背景 $Y_{\text{base}}$（采用 $3 \times 3$ 快速可分离核）。
2. **高频微纹理残差**：
   $$D(x, y) = Y(x, y) - Y_{\text{base}}(x, y)$$
3. **死区防噪阈值（Coring）与高频微反差提升**：
   - 死区门限 $\tau_{\text{core}} = 2.0$（平坦区域的微弱波动判定为传感器底噪，增益归零）；
   - 微反差增益 $\beta = 0.55$（增强系数）：
     $$\Delta Y = \begin{cases} 
     0 & \text{if } |D| \le \tau_{\text{core}} \\
     \text{sign}(D) \cdot \min\left((|D| - \tau_{\text{core}}) \cdot \beta, 16.0\right) & \text{if } |D| > \tau_{\text{core}}
     \end{cases}$$
4. **合成回写**：
   $$C_{\text{final}} = \text{clamp}(C_{\text{tone}} + \Delta Y, 0, 255)$$
   树叶、砖缝、文字边缘的微小明暗对比被显著拉开，同时平坦的墙面、皮肤、天空绝不产生白边或噪点颗粒。

### 2.4 EXIF 原生参数完整保留
在 `CameraViewModel.kt` 中：
在 `Imgcodecs.imwrite(finalPhotoFile.absolutePath, ...)` 完成后，使用 `androidx.exifinterface.media.ExifInterface` 将 `tempFiles[0]` 中的核心 EXIF 属性完整克隆至 `finalPhotoFile`：
- `TAG_EXPOSURE_TIME`
- `TAG_PHOTOGRAPHIC_SENSITIVITY`
- `TAG_F_NUMBER`
- `TAG_FOCAL_LENGTH`
- `TAG_WHITE_BALANCE`
- `TAG_DATETIME`
- `TAG_MAKE`、`TAG_MODEL`

---

## 3. 验收标准与交付物清单
1. `specs/SPEC_13_MOTOCAM_PARITY_OIS_TONEMAP_AND_TEXTURE.md` 正式定稿入库。
2. `CameraScreen.kt` 成功配置 `LENS_OPTICAL_STABILIZATION_MODE_ON`、`CONTROL_VIDEO_STABILIZATION_MODE_OFF` 和 `TONEMAP_MODE_HIGH_QUALITY`。
3. `burst_fusion.cpp` 实现暗部平滑 S-Curve 黑电平压制与自适应微反差质感提升。
4. `CameraViewModel.kt` 实现 EXIF 参数无损转录。
5. Gradle 构建 `assembleHachimiRelease` 成功，并通过 Taildrop 传输到 `hyper-obs:`。
6. 代码提交至 Git 仓库，**严禁创建或修改任何 GitHub Release 或 Tag**。
