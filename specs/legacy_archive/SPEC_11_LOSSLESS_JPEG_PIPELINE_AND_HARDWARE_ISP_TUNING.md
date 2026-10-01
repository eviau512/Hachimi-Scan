# 技术规格书 11：零损耗 JPEG 编码管线与高通硬件 ISP 细节增强规范 (Lossless JPEG Pipeline & Hardware ISP Detail Enhancement Specification)

> **文档性质**：核心系统架构与图像质量技术规格书 (Technical Specification)  
> **制定目标**：彻底解决当前应用拍摄照片相比 LineageOS 原生相机文件体积缩水（3.97MB vs 6.67MB）、高频微细节（如文字雕刻、栏杆微反差）被多次压缩涂抹的严重画质降级问题。构建 CameraX 硬件直通最高画质、高通 Spectra ISP 边缘锐化与降噪硬件指令注入、彻底根除中间有损重压缩的零损耗编码管线。

---

## 1. 缺陷分析与逆向排查结果 (Forensic Root Cause Analysis)

### 1.1 三次串联有损压缩灾难 (Triple JPEG Re-compression Loss)
排查发现，当前拍摄流程经历整整三次有损编码，严重摧毁高频离散余弦变换（DCT）细节：
1. **第一重**：CameraX 默认未配置 `setJpegQuality(100)`，部分机型默认以 80~85 品质写入临时文件；
2. **第二重**：在 `normalizeExifOrientation` 中，为矫正 90 度旋转，将临时文件解码为 Bitmap，并调用 `Bitmap.compress(JPEG, 95)` 强行在磁盘上重写；
3. **第三重**：送入 C++ 融合后，调用 OpenCV `Imgcodecs.imwrite`，默认以质量 95 及 4:2:0 色度抽样进行第三次压缩。
> **量化表实测对比**：
> Lineage 原生相机亮度量化表为 `[1, 1, 1, 1, 1, 2, 2, 2]`（近乎纯无损）；
> 哈基米相机因连环压缩被退化至 `[2, 1, 1, 2, 2, 4, 5, 6]`，色度通道量化步长恶化到 10，细小文字和栏杆直接产生锯齿与涂抹。

### 1.2 硬件 ISP 细节增强指令缺失
Lineage 原生相机直接对接 Camera2，硬件默认激活了高通 Spectra 旗舰 ISP 的硬件反卷积锐化与 RAW 域降噪。CameraX 若未显式挂载 `Camera2Interop` 参数，硬件将以保守节能模式运行，出图偏软。

---

## 2. 核心架构优化：零损耗高画质直通管线 (Lossless High-Quality Pipeline)

```
[CameraX ImageCapture 硬件采集]
  ├── setJpegQuality(100)
  └── Camera2Interop 注入:
        ├── CaptureRequest.EDGE_MODE = EDGE_MODE_HIGH_QUALITY
        ├── CaptureRequest.NOISE_REDUCTION_MODE = NOISE_REDUCTION_MODE_HIGH_QUALITY
        └── CaptureRequest.HOT_PIXEL_MODE = HOT_PIXEL_MODE_HIGH_QUALITY
                           │
                           ▼
                 [原始单帧 JPEG 100% 极清落盘]
                           │
             ┌─────────────┴─────────────┐
             ▼                           ▼
      【单张拍照模式】              【HDR 多帧连拍模式】
   • 严禁磁盘二次压缩解码！       • OpenCV 直接解码内存 Mat
   • 保留原始 100% 字节流         • 旋转在 Mat 内存无损执行 (Core.rotate)
   • 仅以 EXIF 属性标识朝向       • 融合后以 IMWRITE_JPEG_QUALITY=100 落盘
             │                           │
             └─────────────┬─────────────┘
                           │
                           ▼
           [体积 6MB~7MB，比肩原生相机的超清成片]
```

---

## 3. 详细工程改造要求 (Implementation Requirements)

### 3.1 `CameraScreen.kt`：Camera2 硬件顶级画质指令与 100% JPEG 质量
```kotlin
val imageCaptureBuilder = ImageCapture.Builder()
    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
    .setJpegQuality(100)
    .setResolutionSelector(sensorResolutionSelector)

val camera2Extender = Camera2Interop.Extender(imageCaptureBuilder)
camera2Extender.setCaptureRequestOption(
    CaptureRequest.EDGE_MODE,
    CaptureRequest.EDGE_MODE_HIGH_QUALITY
)
camera2Extender.setCaptureRequestOption(
    CaptureRequest.NOISE_REDUCTION_MODE,
    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
)
camera2Extender.setCaptureRequestOption(
    CaptureRequest.HOT_PIXEL_MODE,
    CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY
)

val imageCapture = imageCaptureBuilder.build()
```

### 3.2 `CameraViewModel.kt`：消灭二次磁盘压缩
1. **重构 `normalizeExifOrientation`**：
   - 彻底删除 `FileOutputStream.use { rotatedBmp.compress(Bitmap.CompressFormat.JPEG, 95, out) }` 逻辑；
   - 仅利用 `BitmapFactory.Options.inJustDecodeBounds = true` 读取照片宽高并结合 EXIF 计算朝向后的 `photoW` / `photoH`；
   - 原始文件保持字节无损，绝不进行重新编码！
2. **多帧融合无损旋转与 100% 质量保存**：
   - 连拍帧读入 OpenCV Mat 后，若需要旋转，直接在内存中执行 `Core.rotate`（无损像素转置），严禁任何磁盘有损中转；
   - 最终融合图像保存时：
     ```kotlin
     val saveParams = MatOfInt(
         Imgcodecs.IMWRITE_JPEG_QUALITY, 100,
         Imgcodecs.IMWRITE_JPEG_OPTIMIZE, 1
     )
     Imgcodecs.imwrite(finalPhotoFile.absolutePath, fusedMat, saveParams)
     ```

---

## 4. 验收标准 (Acceptance Criteria)

1. **量化表与文件大小指标**：
   - 成片文件体积提升至 $6.0\text{ MB} \sim 7.5\text{ MB}$ 范围，彻底告别 3.9MB 缩水；
   - 亮度量化表恢复至低步长高保真水平，高频细线不被粗暴抹除；
2. **细节保真度验证**：
   - 针对远景雕塑金字、建筑走廊栏杆细线进行 400% 放大对比，文字边缘清晰发挺，栏杆直线平滑连贯，无锯齿色块，完全比肩原生相机画质；
3. **零重影保持**：
   - SPEC_10 零重影防抖算法继续有效，强反差树枝与夜景光源无任何浮雕重影。
